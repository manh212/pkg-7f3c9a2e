package com.google.android.accessibility.talkback.recognition

import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class GuidedPhotoDialog(
  private val activity: ComponentActivity,
  private val serviceReady: () -> Boolean = {
    TalkBackService.isServiceActive() && !PauseController.isPaused()
  },
  private val requestProvider: () -> ListenableFuture<ProcessCameraProvider> = {
    ProcessCameraProvider.getInstance(activity)
  },
  private val onClosed: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val mainExecutor = ContextCompat.getMainExecutor(activity)
  private val processing = AtomicBoolean(false)
  private val controller = GuidedPhotoController()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(activity)
  private var front = prefs.getBoolean(FRONT_CAMERA, false)
  private var flash = false
  @Volatile private var dismissed = false
  @Volatile private var finished = false
  @Volatile private var capturing = false
  private var guidanceEnabled = false
  private var guidanceEpoch = 0
  private var focusReady = false
  private var focusKey: String? = null
  private var focusBox: PhotoBox? = null
  private var focusAttemptAt = Long.MIN_VALUE
  private var focusRequest = 0
  private var lastModelBucket = -1
  @Volatile private var generation = 0
  private var dialog: AlertDialog? = null
  private var provider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var binding = false
  private var cameraState: LiveData<CameraState>? = null
  private val cameraObserver = Observer<CameraState> { state ->
    if (!dismissed && !finished && state.error != null) fail(R.string.live_recognition_camera_error)
  }
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  private var capture: ImageCapture? = null
  private var detector: GuidedPhotoDetector? = null
  private var output: GuidedPhotoOutput? = null
  private lateinit var previewView: PreviewView
  private lateinit var status: TextView
  private lateinit var flashButton: CheckBox
  private lateinit var cameraButton: Button
  private lateinit var againButton: Button
  private lateinit var captureButton: Button

  private val modelObserver = Observer<Map<OfflineModels.Group, OfflineModels.Status>> { states ->
    if (!dismissed && !finished && camera == null) {
      if (OfflineModels.isReady(activity, OfflineModels.Group.OBJECTS)) {
        bindCamera()
      } else {
        val state = states[OfflineModels.Group.OBJECTS]
        if (state?.failed == true) fail(R.string.live_recognition_model_download_failed)
        else state?.progress?.let { progress ->
          val bucket = progress / 25
          if (bucket > lastModelBucket) {
            lastModelBucket = bucket
            message(activity.getString(R.string.live_recognition_model_download_progress, bucket * 25))
          }
        }
      }
    }
  }

  private val activeCheck = object : Runnable {
    override fun run() {
      if (dismissed) return
      if (!allowed()) {
        status.setText(R.string.guided_photo_paused)
        dismiss()
        return
      }
      if (!finished && camera == null && !binding &&
        OfflineModels.isReady(activity, OfflineModels.Group.OBJECTS)) bindCamera()
      handler.postDelayed(this, 250L)
    }
  }

  fun show() {
    val density = activity.resources.displayMetrics.density
    val column = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      val pad = (16 * density).toInt()
      setPadding(pad, pad, pad, pad)
    }
    column.addView(TextView(activity).apply { setText(R.string.guided_photo_instructions); textSize = 18f })
    previewView = PreviewView(activity).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FIT_CENTER
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    column.addView(previewView, LinearLayout.LayoutParams(-1, (180 * density).toInt()))
    status = TextView(activity).apply {
      setText(R.string.guided_photo_search)
      textSize = 20f
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }
    column.addView(status)
    fun button(label: Int, action: () -> Unit): Button = Button(activity).apply {
      setText(label)
      minHeight = (48 * density).toInt()
      setOnClickListener { action() }
      column.addView(this)
    }
    button(R.string.guided_photo_listen) { beginSpeech(R.string.guided_photo_instructions) }
    cameraButton = button(if (front) R.string.guided_photo_back else R.string.guided_photo_front) { switchCamera() }
    flashButton = CheckBox(activity).apply {
      setText(R.string.live_recognition_flash)
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        flash = checked
        resetGuidance()
        applyFlash()
      }
    }
    column.addView(flashButton)
    captureButton = button(R.string.guided_photo_now) { takePhoto() }.apply { isEnabled = false }
    againButton = button(R.string.guided_photo_again) { restart() }.apply { isEnabled = false }
    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.guided_photo_title)
      .setView(ScrollView(activity).apply { addView(column) })
      .setNegativeButton(R.string.live_recognition_close, null)
      .setOnDismissListener { cleanup() }
      .create().also { it.show() }
    handler.post(activeCheck)
    beginSpeech(R.string.guided_photo_instructions)
    OfflineModels.status.observe(activity, modelObserver)
    if (!OfflineModels.isReady(activity, OfflineModels.Group.OBJECTS)) {
      status.setText(R.string.live_recognition_model_download_started)
      OfflineModels.request(activity, OfflineModels.Group.OBJECTS)
    } else bindCamera()
  }

  fun dismiss() {
    if (dialog?.isShowing == true) dialog?.dismiss() else cleanup()
  }

  private fun allowed(): Boolean = !dismissed && !activity.isFinishing &&
    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
    serviceReady() &&
    activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

  private fun beginSpeech(resource: Int) {
    resetGuidance()
    guidanceEnabled = false
    val token = guidanceEpoch
    message(activity.getString(resource)) {
      if (!dismissed && guidanceEpoch == token) guidanceEnabled = true
    }
  }

  private fun resetGuidance() {
    guidanceEpoch++
    guidanceEnabled = true
    controller.reset()
    focusReady = false
    focusKey = null
    focusBox = null
    focusRequest++
    TalkBackService.getInstance()?.speechController
      ?.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_LIVE_RECOGNITION)
  }

  private fun restart() {
    if (!allowed() || capturing) return
    finished = false
    againButton.isEnabled = false
    resetGuidance()
    guidanceEnabled = true
    captureButton.isEnabled = capture != null
    flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
    if (OfflineModels.isReady(activity, OfflineModels.Group.OBJECTS)) bindCamera()
    else {
      lastModelBucket = -1
      OfflineModels.request(activity, OfflineModels.Group.OBJECTS)
    }
  }

  private fun switchCamera() {
    if (!allowed() || capturing) return
    val next = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    try {
      if (provider?.hasCamera(next) == false) {
        message(activity.getString(R.string.guided_photo_camera_missing))
        return
      }
    } catch (_: Exception) {
      message(activity.getString(R.string.guided_photo_camera_missing))
      return
    }
    front = !front
    prefs.edit().putBoolean(FRONT_CAMERA, front).apply()
    cameraButton.setText(if (front) R.string.guided_photo_back else R.string.guided_photo_front)
    flash = false
    flashButton.isChecked = false
    finished = false
    againButton.isEnabled = false
    unbindCamera()
    resetGuidance()
    guidanceEnabled = false
    val token = guidanceEpoch
    message(activity.getString(if (front) R.string.guided_photo_front_active else R.string.guided_photo_back_active)) {
      if (!dismissed && guidanceEpoch == token) guidanceEnabled = true
    }
    if (OfflineModels.isReady(activity, OfflineModels.Group.OBJECTS)) bindCamera()
  }

  private fun bindCamera() {
    if (!allowed() || finished || camera != null || binding) return
    binding = true
    val token = ++generation
    val future = try {
      requestProvider()
    } catch (error: RuntimeException) {
      LogUtils.w(TAG, "Camera provider failed (%s)", error.javaClass.simpleName)
      fail(R.string.live_recognition_camera_error)
      return
    } catch (error: LinkageError) {
      LogUtils.w(TAG, "Camera provider library unavailable (%s)", error.javaClass.simpleName)
      fail(R.string.live_recognition_camera_error)
      return
    }
    future.addListener({
      if (!allowed() || token != generation || finished) {
        if (token == generation) binding = false
        return@addListener
      }
      binding = false
      try {
        val p = future.get()
        provider = p
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!p.hasCamera(selector)) {
          fail(R.string.guided_photo_camera_missing)
          return@addListener
        }
        val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
        pv.setSurfaceProvider(previewView.surfaceProvider)
        val rotation = previewView.display?.rotation ?: 0
        val photo = ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
          .setTargetAspectRatio(AspectRatio.RATIO_4_3)
          .setTargetRotation(rotation)
          .build()
        val frames = ImageAnalysis.Builder()
          .setTargetResolution(Size(640, 480))
          .setTargetRotation(rotation)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
        var lastFrame = Long.MIN_VALUE
        frames.setAnalyzer(worker) { image ->
          val now = SystemClock.elapsedRealtime()
          if (dismissed || token != generation || finished || capturing ||
              (lastFrame != Long.MIN_VALUE && now - lastFrame < 200L) ||
              !processing.compareAndSet(false, true)) {
            image.close()
            return@setAnalyzer
          }
          lastFrame = now
          val bitmap = try { LiveTextRecognitionDialog.imageToRotatedBitmap(image) }
            catch (_: RuntimeException) { null }
            finally { image.close() }
          if (bitmap == null) {
            processing.set(false)
            return@setAnalyzer
          }
          try {
            val engine = detector ?: GuidedPhotoDetector(activity.applicationContext).also { detector = it }
            val result = engine.analyze(bitmap)
            handler.post {
              if (allowed() && token == generation && !finished && !capturing && guidanceEnabled) {
                if (SystemClock.elapsedRealtime() - now > GuidedPhotoController.MAX_FRAME_GAP_MS) {
                  resetGuidance()
                } else {
                  try {
                    process(result, now)
                  } catch (error: RuntimeException) {
                    LogUtils.w(TAG, "Camera guidance failed (%s)", error.javaClass.simpleName)
                    fail(R.string.live_recognition_camera_error)
                  }
                }
              }
            }
          } catch (error: Exception) {
            LogUtils.w(TAG, "Subject detection failed (%s)", error.javaClass.simpleName)
            handler.post {
              if (!dismissed && !finished && !capturing && token == generation) {
                fail(R.string.guided_photo_detector_error)
              }
            }
          } catch (error: LinkageError) {
            LogUtils.w(TAG, "Subject detection library unavailable (%s)", error.javaClass.simpleName)
            handler.post {
              if (!dismissed && !finished && !capturing && token == generation) {
                fail(R.string.guided_photo_detector_error)
              }
            }
          } finally {
            bitmap.recycle()
            processing.set(false)
          }
        }
        preview = pv
        analysis = frames
        capture = photo
        camera = p.bindToLifecycle(activity, selector, pv, frames, photo)
        cameraState = camera?.cameraInfo?.cameraState
        cameraState?.observe(activity, cameraObserver)
        if (finished || camera == null) return@addListener
        captureButton.isEnabled = true
        flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
        applyFlash()
      } catch (error: Exception) {
        LogUtils.w(TAG, "Guided camera failed (%s)", error.javaClass.simpleName)
        fail(R.string.live_recognition_camera_error)
      } catch (error: LinkageError) {
        LogUtils.w(TAG, "Guided camera library unavailable (%s)", error.javaClass.simpleName)
        fail(R.string.live_recognition_camera_error)
      }
    }, mainExecutor)
  }

  private fun process(frame: PhotoObservation, now: Long) {
    val subject = GuidedPhotoController.select(frame.subjects)
    updateFocus(subject, now)
    var cancelled = false
    var cancellationSpoken = false
    for (signal in controller.observe(frame.copy(focusReady = focusReady), now, front)) {
      when (signal) {
        is GuidedPhotoController.Signal.Say -> {
          if (signal.message == R.string.guided_photo_two || signal.message == R.string.guided_photo_one) {
            haptic(R.array.guided_photo_count_pattern)
          }
          var text = if (signal.argument == null) activity.getString(signal.message)
            else activity.getString(signal.message, signal.argument)
          if (cancelled) {
            text = activity.getString(R.string.guided_photo_cancelled) + " " + text
            cancellationSpoken = true
          }
          val token = generation
          message(text) { completed ->
            if (!dismissed && generation == token) controller.spoken(signal.ticket, completed)
          }
        }
        is GuidedPhotoController.Signal.Guide -> {
          stopSpeech()
          val prefix = if (cancelled) activity.getString(R.string.guided_photo_cancelled) + " " else ""
          message(prefix + activity.getString(signal.guidance.message))
          cancellationSpoken = cancelled
        }
        GuidedPhotoController.Signal.CancelSpeech -> stopSpeech()
        GuidedPhotoController.Signal.CancelCountdown -> {
          stopSpeech()
          cancelled = true
          haptic(R.array.guided_photo_count_pattern)
          status.setText(R.string.guided_photo_cancelled)
        }
        GuidedPhotoController.Signal.Capture -> takePhoto()
      }
    }
    if (cancelled && !cancellationSpoken) message(activity.getString(R.string.guided_photo_cancelled))
  }

  private fun updateFocus(subject: PhotoSubject?, now: Long) {
    if (subject == null) {
      focusReady = false
      focusKey = null
      return
    }
    val changed = focusKey != subject.key || focusBox?.let { !it.stableAgainst(subject.box) } != false
    if (!changed && (focusReady || now - focusAttemptAt < 2_500L)) return
    val bound = camera ?: return
    focusReady = false
    focusKey = subject.key
    focusBox = subject.box
    focusAttemptAt = now
    val request = ++focusRequest
    val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
    val autofocus = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
    if (!bound.cameraInfo.isFocusMeteringSupported(autofocus)) {
      focusReady = true
      return
    }
    val token = generation
    val focus = try { bound.cameraControl.startFocusAndMetering(action) }
      catch (_: RuntimeException) { return }
    focus.addListener({
      if (!dismissed && token == generation && request == focusRequest) {
        focusReady = runCatching { focus.get().isFocusSuccessful }.getOrDefault(false)
      }
    }, mainExecutor)
  }

  private fun takePhoto() {
    val shutter = capture ?: return
    if (!allowed() || finished || capturing) return
    capturing = true
    stopSpeech()
    status.setText(R.string.guided_photo_capturing)
    captureButton.isEnabled = false
    cameraButton.isEnabled = false
    flashButton.isEnabled = false
    val token = generation
    val destination = try { GuidedPhotoOutput.prepare(activity) }
      catch (_: Exception) { captureFailed(); return }
    output = destination
    haptic(R.array.guided_photo_capture_pattern)
    TalkBackService.getInstance()?.feedbackController?.playAuditoryWithoutHaptic(
      R.raw.lib_new_camera_click, Performance.EVENT_ID_UNTRACKED)
    try {
      shutter.takePicture(destination.options(front), mainExecutor, object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
          if (!allowed() || generation != token) {
            destination.discard()
            return
          }
          try {
            destination.complete()
            output = null
            capturing = false
            finished = true
            cameraButton.isEnabled = true
            againButton.isEnabled = true
            message(activity.getString(R.string.guided_photo_saved))
          } catch (_: Exception) {
            destination.discard()
            captureFailed()
          }
        }
        override fun onError(error: ImageCaptureException) {
          destination.discard()
          if (!dismissed && token == generation) captureFailed()
        }
      })
    } catch (_: RuntimeException) {
      destination.discard()
      captureFailed()
    }
  }

  private fun captureFailed() {
    output?.discard()
    output = null
    capturing = false
    finished = true
    cameraButton.isEnabled = true
    againButton.isEnabled = true
    message(activity.getString(R.string.guided_photo_save_failed))
  }

  private fun applyFlash() {
    camera?.cameraControl?.enableTorch(flash && camera?.cameraInfo?.hasFlashUnit() == true)
  }

  private fun haptic(pattern: Int) {
    TalkBackService.getInstance()?.feedbackController?.playHaptic(pattern, Performance.EVENT_ID_UNTRACKED)
  }

  private fun message(text: String, completed: ((Boolean) -> Unit)? = null) {
    if (dismissed) return
    status.text = text
    val speech = TalkBackService.getInstance()?.speechController ?: return
    speech.speak(text, Performance.EVENT_ID_UNTRACKED, SpeechController.SpeakOptions.create()
      .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
      .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_LIVE_RECOGNITION)
      .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
      .setCompletedAction(SpeechController.UtteranceCompleteRunnable { result ->
        handler.post { if (!dismissed) completed?.invoke(result == SpeechController.STATUS_SPOKEN) }
      }))
  }

  private fun stopSpeech() {
    TalkBackService.getInstance()?.speechController
      ?.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_LIVE_RECOGNITION)
  }

  private fun fail(resource: Int) {
    finished = true
    capturing = false
    unbindCamera()
    stopSpeech()
    captureButton.isEnabled = false
    againButton.isEnabled = true
    message(activity.getString(resource))
  }

  private fun unbindCamera() {
    generation++
    binding = false
    focusRequest++
    cameraState?.removeObserver(cameraObserver)
    cameraState = null
    analysis?.clearAnalyzer()
    camera?.cameraControl?.enableTorch(false)
    val cases = listOfNotNull(preview, analysis, capture).toTypedArray()
    if (cases.isNotEmpty()) provider?.unbind(*cases)
    preview = null
    analysis = null
    capture = null
    camera = null
  }

  private fun cleanup() {
    if (dismissed) return
    dismissed = true
    OfflineModels.status.removeObserver(modelObserver)
    handler.removeCallbacks(activeCheck)
    controller.reset()
    stopSpeech()
    unbindCamera()
    output?.discard()
    output = null
    worker.execute {
      try {
        detector?.close()
      } catch (error: Exception) {
        LogUtils.w(TAG, "Detector cleanup failed (%s)", error.javaClass.simpleName)
      } catch (error: LinkageError) {
        LogUtils.w(TAG, "Detector cleanup library unavailable (%s)", error.javaClass.simpleName)
      } finally {
        detector = null
      }
    }
    worker.shutdown()
    onClosed()
  }

  companion object {
    private const val TAG = "GuidedPhoto"
    private const val FRONT_CAMERA = "pref_guided_photo_front_camera"
  }
}
