/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.recognition

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Observer
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LiveTextRecognitionDialog(
  private val activity: ComponentActivity,
  private val prefs: SharedPreferences,
  private val speak: (String, Boolean) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val alignmentTracker = DocumentAlignmentTracker()
  private val textTracker = CameraTextTracker(requireConfirmation = true)

  private var dialog: AlertDialog? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  private var imageCapture: ImageCapture? = null
  private var previewView: PreviewView? = null
  private var resultText: TextView? = null
  private var liveButton: Button? = null
  private var captureButton: Button? = null
  private var flashBox: CheckBox? = null

  private var useVietOcr: Boolean =
    prefs.getString(PREF_TEXT_ENGINE, ENGINE_TESSERACT) == ENGINE_VIETOCR
  private var upscaleSmallText: Boolean = prefs.getBoolean(PREF_TEXT_UPSCALE, true)
  private var flashOn = false
  private var liveRunning = false
  private var captureInFlight = false
  private var waitingGroup: OfflineModels.Group? = null
  private var downloadBucket = 0
  private var dismissed = false
  @Volatile private var sessionToken = 0

  private val modelObserver = Observer<Map<OfflineModels.Group, OfflineModels.Status>> { states ->
    val group = waitingGroup ?: return@Observer
    if (dismissed) return@Observer
    val state = states[group]
    if (OfflineModels.isReady(activity, group)) {
      waitingGroup = null
      downloadBucket = 0
      updateResult(activity.getString(R.string.live_recognition_model_download_ready), speakInterrupt = false)
      if (liveRunning) bindCamera()
    } else if (state?.failed == true) {
      waitingGroup = null
      liveRunning = false
      updateButtons()
      updateResult(activity.getString(R.string.live_recognition_model_download_failed), speakInterrupt = true)
    } else if (state?.progress != null) {
      val bucket = state.progress / 25
      if (bucket > downloadBucket) {
        downloadBucket = bucket
        updateResult(
          activity.getString(R.string.live_recognition_model_download_progress, bucket * 25),
          speakInterrupt = false,
        )
      }
    }
  }

  fun show() {
    val density = activity.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val column = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    column.addView(TextView(activity).apply {
      text = activity.getString(R.string.live_recognition_text_dialog_hint)
      textSize = 16f
    })

    val pv = PreviewView(activity).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    previewView = pv
    column.addView(pv, LinearLayout.LayoutParams(-1, (140 * density).toInt()))

    val tesseractRadio = RadioButton(activity).apply {
      id = View.generateViewId()
      text = activity.getString(R.string.live_recognition_text_engine_tesseract)
      isChecked = !useVietOcr
    }
    val vietOcrRadio = RadioButton(activity).apply {
      id = View.generateViewId()
      text = activity.getString(R.string.live_recognition_text_engine_vietocr)
      isChecked = useVietOcr
    }
    val engineGroup = RadioGroup(activity).apply {
      addView(tesseractRadio)
      addView(vietOcrRadio)
      setOnCheckedChangeListener { _, checkedId ->
        val nextViet = checkedId == vietOcrRadio.id
        if (nextViet != useVietOcr) {
          useVietOcr = nextViet
          prefs.edit {
            putString(PREF_TEXT_ENGINE, if (useVietOcr) ENGINE_VIETOCR else ENGINE_TESSERACT)
          }
          textTracker.reset()
          alignmentTracker.reset()
          if (liveRunning) ensureModelReady(startAfter = true)
        }
      }
    }
    column.addView(engineGroup)

    column.addView(CheckBox(activity).apply {
      text = activity.getString(R.string.live_recognition_text_upscale)
      isChecked = upscaleSmallText
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        upscaleSmallText = checked
        prefs.edit { putBoolean(PREF_TEXT_UPSCALE, checked) }
      }
    })

    val flash = CheckBox(activity).apply {
      text = activity.getString(R.string.live_recognition_flash)
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        flashOn = checked
        applyTorch()
      }
    }
    flashBox = flash
    column.addView(flash)

    val liveBtn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_text_start_live)
      setOnClickListener { toggleLiveReading() }
    }
    liveButton = liveBtn
    column.addView(liveBtn)

    val capBtn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_text_capture)
      setOnClickListener { captureSingleFrame() }
    }
    captureButton = capBtn
    column.addView(capBtn)

    val result = TextView(activity).apply {
      textSize = 17f
      setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
    }
    resultText = result
    val resultScroll = ScrollView(activity).apply {
      addView(result)
    }
    column.addView(resultScroll, LinearLayout.LayoutParams(-1, (160 * density).toInt()))

    val outerScroll = ScrollView(activity).apply { addView(column) }

    OfflineModels.status.observe(activity, modelObserver)
    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.live_recognition_text_dialog_title)
      .setView(outerScroll)
      .setNegativeButton(R.string.live_recognition_close, null)
      .setOnDismissListener { cleanup() }
      .create()
      .also { it.show() }

    bindCamera()
  }

  fun dismiss() {
    dialog?.dismiss()
  }

  private fun selectedGroup(): OfflineModels.Group =
    if (useVietOcr) OfflineModels.Group.CAMERA_VIETOCR else OfflineModels.Group.CAMERA_OCR

  private fun ensureModelReady(startAfter: Boolean): Boolean {
    val group = selectedGroup()
    if (OfflineModels.isReady(activity, group)) {
      waitingGroup = null
      return true
    }
    waitingGroup = group
    downloadBucket = 0
    OfflineModels.request(activity, group)
    updateResult(activity.getString(R.string.live_recognition_model_download_started), speakInterrupt = true)
    if (!startAfter) liveRunning = false
    updateButtons()
    return false
  }

  private fun toggleLiveReading() {
    if (liveRunning) {
      liveRunning = false
      sessionToken++
      alignmentTracker.reset()
      textTracker.reset()
      updateButtons()
      bindCamera()
      speak(activity.getString(R.string.live_recognition_text_stop_live), true)
      return
    }
    if (!ensureModelReady(startAfter = true)) {
      liveRunning = true
      updateButtons()
      return
    }
    liveRunning = true
    alignmentTracker.reset()
    textTracker.reset()
    updateButtons()
    bindCamera()
    speak(activity.getString(R.string.live_recognition_text_confirming), true)
  }

  private fun captureSingleFrame() {
    if (captureInFlight) return
    if (!ensureModelReady(startAfter = false)) return
    val capture = imageCapture
    if (capture == null) {
      updateResult(activity.getString(R.string.live_recognition_camera_error), speakInterrupt = true)
      return
    }
    if (liveRunning) {
      liveRunning = false
      updateButtons()
    }
    val token = ++sessionToken
    captureInFlight = true
    updateButtons()
    updateResult(activity.getString(R.string.live_recognition_please_wait), speakInterrupt = true)
    capture.takePicture(
      worker,
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          val bitmap = try {
            imageToRotatedBitmap(image)
          } catch (e: Throwable) {
            LogUtils.e(TAG, "Text capture decode failed: %s", e.javaClass.simpleName)
            null
          } finally {
            image.close()
          }
          if (bitmap == null) {
            handler.post {
              if (!dismissed && sessionToken == token) {
                captureInFlight = false
                updateButtons()
                updateResult(activity.getString(R.string.live_recognition_camera_error), speakInterrupt = true)
              }
            }
            return
          }
          try {
            val text = runOcrPipeline(bitmap)
            handler.post {
              if (!dismissed && sessionToken == token) {
                captureInFlight = false
                updateButtons()
                if (text == null) {
                  updateResult(activity.getString(R.string.live_recognition_ocr_error), speakInterrupt = true)
                } else if (text.isBlank()) {
                  updateResult(activity.getString(R.string.live_recognition_text_not_found), speakInterrupt = true)
                } else {
                  updateResult(text.take(MAX_TEXT_LENGTH), speakInterrupt = true)
                }
              }
            }
          } finally {
            bitmap.recycle()
          }
        }

        override fun onError(exception: ImageCaptureException) {
          LogUtils.e(TAG, "Text capture error: %s", exception.message ?: exception.javaClass.simpleName)
          handler.post {
            if (!dismissed && sessionToken == token) {
              captureInFlight = false
              updateButtons()
              updateResult(activity.getString(R.string.live_recognition_camera_error), speakInterrupt = true)
            }
          }
        }
      },
    )
  }

  private fun runOcrPipeline(bitmap: Bitmap): String? {
    val alignment = try {
      DocumentAlignmentScanner.analyze(bitmap)
    } catch (_: RuntimeException) {
      null
    }
    val dewarped = alignment?.corners?.let { DocumentAlignmentScanner.dewarp(bitmap, it) }
    val source = dewarped ?: bitmap
    val prepared = DocumentAlignmentScanner.prepareForCameraOcr(source, upscaleSmallText)
    return try {
      TesseractOcr.recognizeCameraText(activity, prepared, useVietOcr)
    } finally {
      if (prepared !== source) prepared.recycle()
      dewarped?.recycle()
    }
  }

  private fun bindCamera() {
    if (dismissed) return
    val pv = previewView ?: return
    val token = ++sessionToken
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
      if (dismissed || sessionToken != token) return@addListener
      try {
        val provider = future.get()
        cameraProvider = provider
        unbindCamera()
        val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        val newCapture = ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .build()
        preview = newPreview
        imageCapture = newCapture

        if (liveRunning && OfflineModels.isReady(activity, selectedGroup())) {
          val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
              ResolutionSelector.Builder()
                .setResolutionStrategy(
                  ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                  )
                )
                .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          var lastFrameMs = 0L
          newAnalysis.setAnalyzer(worker) { frame ->
            val now = SystemClock.elapsedRealtime()
            if (dismissed || sessionToken != token || !liveRunning ||
                now - lastFrameMs < LIVE_FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
              frame.close()
              return@setAnalyzer
            }
            lastFrameMs = now
            val bitmap = try {
              imageToRotatedBitmap(frame)
            } catch (_: Throwable) {
              null
            } finally {
              frame.close()
            }
            if (bitmap == null) {
              processing.set(false)
              return@setAnalyzer
            }
            try {
              handleLiveFrame(bitmap, token, now)
            } finally {
              bitmap.recycle()
              processing.set(false)
            }
          }
          analysis = newAnalysis
          boundCamera = provider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            newPreview,
            newCapture,
            newAnalysis,
          )
        } else {
          analysis = null
          boundCamera = provider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            newPreview,
            newCapture,
          )
        }
        applyTorch()
      } catch (e: Exception) {
        LogUtils.e(TAG, "Text dialog camera failed: %s", e.javaClass.simpleName)
        updateResult(activity.getString(R.string.live_recognition_camera_error), speakInterrupt = true)
      }
    }, ContextCompat.getMainExecutor(activity))
  }

  private fun handleLiveFrame(bitmap: Bitmap, token: Int, nowMs: Long) {
    val alignment = try {
      DocumentAlignmentScanner.analyze(bitmap)
    } catch (_: RuntimeException) {
      DocumentAlignmentResult(DocumentAlignmentState.READY, "")
    }
    when (val decision = alignmentTracker.observe(alignment, nowMs)) {
      is DocumentAlignmentTracker.Decision.Guide -> {
        handler.post {
          if (!dismissed && sessionToken == token && liveRunning) {
            updateResult(decision.message, speakInterrupt = false)
          }
        }
      }
      is DocumentAlignmentTracker.Decision.Capture -> {
        val dewarped = decision.corners?.let { DocumentAlignmentScanner.dewarp(bitmap, it) }
        val source = dewarped ?: bitmap
        val prepared = DocumentAlignmentScanner.prepareForCameraOcr(source, upscaleSmallText)
        val raw = try {
          TesseractOcr.recognizeCameraText(activity, prepared, useVietOcr)
        } finally {
          if (prepared !== source) prepared.recycle()
          dewarped?.recycle()
        }
        val confirmed = raw?.let { textTracker.observe(it.take(MAX_TEXT_LENGTH), SystemClock.elapsedRealtime()) }
        handler.post {
          if (dismissed || sessionToken != token || !liveRunning) return@post
          if (raw == null) {
            liveRunning = false
            updateButtons()
            updateResult(activity.getString(R.string.live_recognition_ocr_error), speakInterrupt = true)
          } else if (confirmed != null) {
            updateResult(confirmed, speakInterrupt = false)
          } else if (raw.isBlank()) {
            updateResult(activity.getString(R.string.live_recognition_text_not_found), speakInterrupt = false)
          } else {
            updateResult(activity.getString(R.string.live_recognition_text_confirming), speakInterrupt = false)
          }
        }
      }
      DocumentAlignmentTracker.Decision.Wait -> Unit
    }
  }

  private fun applyTorch() {
    val camera = boundCamera ?: return
    if (flashOn && !camera.cameraInfo.hasFlashUnit()) {
      flashOn = false
      flashBox?.isChecked = false
      updateResult(activity.getString(R.string.live_recognition_flash_unavailable), speakInterrupt = true)
      return
    }
    camera.cameraControl.enableTorch(flashOn)
  }

  private fun updateButtons() {
    liveButton?.text = activity.getString(
      if (liveRunning) R.string.live_recognition_text_stop_live else R.string.live_recognition_text_start_live
    )
    captureButton?.isEnabled = !captureInFlight
  }

  private fun updateResult(message: String, speakInterrupt: Boolean) {
    resultText?.text = message
    speak(message, speakInterrupt)
  }

  private fun unbindCamera() {
    boundCamera?.cameraControl?.enableTorch(false)
    boundCamera = null
    analysis?.clearAnalyzer()
    val bound = listOfNotNull(preview, analysis, imageCapture).toTypedArray()
    if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
    preview = null
    analysis = null
    imageCapture = null
  }

  private fun cleanup() {
    if (dismissed) return
    dismissed = true
    sessionToken++
    liveRunning = false
    captureInFlight = false
    waitingGroup = null
    OfflineModels.status.removeObserver(modelObserver)
    unbindCamera()
    worker.shutdownNow()
    onClosed()
  }

  companion object {
    private const val TAG = "LiveTextDialog"
    private const val PREF_TEXT_ENGINE = "pref_live_recognition_text_engine"
    private const val PREF_TEXT_UPSCALE = "pref_live_recognition_text_upscale"
    private const val ENGINE_TESSERACT = "tesseract"
    private const val ENGINE_VIETOCR = "vietocr"
    private const val LIVE_FRAME_INTERVAL_MS = 900L
    private const val MAX_TEXT_LENGTH = 1_200

    internal fun imageToRotatedBitmap(image: ImageProxy): Bitmap {
      val raw = image.toBitmap()
      val rotation = image.imageInfo.rotationDegrees
      if (rotation == 0) return raw
      val rotated = Bitmap.createBitmap(
        raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true
      )
      if (rotated !== raw) raw.recycle()
      return rotated
    }
  }
}
