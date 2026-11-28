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

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.AiService
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.talkback.licensing.DeviceLicenseManager
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.monitor.RecentUserInput
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.libraries.accessibility.utils.log.LogUtils
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** The Live recognition screen. The camera runs only while this screen is in the foreground. */
class LiveRecognitionActivity : ComponentActivity() {
  private val enabled = mutableSetOf<RecognitionMode>()
  private val switches = mutableMapOf<RecognitionMode, CheckBox>()
  private val handler = Handler(Looper.getMainLooper())
  private val loadingFeedback = LiveRecognitionLoadingFeedback(
    handler,
    onSound = {
      if (resumed && enabled.isNotEmpty() && TalkBackService.isServiceActive() && !PauseController.isPaused()) {
        TalkBackService.getInstance()?.feedbackController?.playAuditoryWithoutHaptic(
          R.raw.loading, Performance.EVENT_ID_UNTRACKED)
      }
    },
    onVibration = {
      if (resumed && enabled.isNotEmpty() && TalkBackService.isServiceActive() && !PauseController.isPaused()) {
        TalkBackService.getInstance()?.feedbackController?.playHaptic(
          R.array.loading_pattern, Performance.EVENT_ID_UNTRACKED)
      }
    },
  )
  private val worker = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val documentTextTracker = CameraTextTracker()
  private val waitingModels = mutableSetOf<OfflineModels.Group>()
  private var modelProgressBucket = 0
  private var qrOcrRequested = false
  private var qrOcrFailed = false
  private val qrRecognizer by lazy { QrBarcodeRecognizer() }
  private val qrTracker = QrBarcodeTracker()
  private val colorTracker = ColorTracker()
  private val documentTracker = DocumentAlignmentTracker()
  private val announcements = RecognitionAnnouncements()
  private var activeTextDialog: LiveTextRecognitionDialog? = null
  private var activeLotteryDialog: LiveLotteryDialog? = null
  private var activeCurrencyDialog: LiveCurrencyDialog? = null
  private var activePointDialog: LivePointAndSpeakDialog? = null
  private var activeLightDialog: LiveLightDialog? = null
  private var activeObjectDialog: LiveFindObjectDialog? = null
  private var activePhotoDialog: GuidedPhotoDialog? = null
  private var pendingPhotoStorage = false
  private val pendingResults = linkedMapOf<String, Pair<String, Long>>()
  private val prefs by lazy { SharedPreferencesUtils.getSharedPreferences(this) }
  // Read by the camera thread, to encode a frame only when it can be described.
  @Volatile private var describer: FrameDescriber? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var analysis: ImageAnalysis? = null
  private var preview: Preview? = null
  private var cameraState: LiveData<CameraState>? = null
  private lateinit var previewView: PreviewView
  private lateinit var status: TextView
  private lateinit var flashSwitch: CheckBox
  private var flashOn = false
  private var pendingFlashPermission = false
  private var flashAfterResume = false
  private var resumed = false
  private var dialogOpen = false
  private var pendingPermission: RecognitionMode? = null
  private var initialMode: RecognitionMode? = null
  @Volatile private var generation = 0
  private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val mode = pendingPermission
    val flashRequested = pendingFlashPermission
    pendingPermission = null
    pendingFlashPermission = false
    if (granted && mode != null) {
      if (resumed) toggle(mode) else initialMode = mode
    } else if (granted && flashRequested) {
      if (resumed) toggleFlash() else flashAfterResume = true
    } else if (!granted) {
      report(R.string.live_recognition_camera_permission)
    }
  }

  private val photoStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    pendingPhotoStorage = false
    if (granted) {
      if (resumed) toggle(RecognitionMode.GUIDED_PHOTO) else initialMode = RecognitionMode.GUIDED_PHOTO
    } else report(R.string.guided_photo_storage_permission)
  }

  /** While a mode is on: speaks a result that waited for speech or navigation to end. */
  private val runningCheck = object : Runnable {
    override fun run() {
      if (!resumed || enabled.isEmpty()) return
      if (!TalkBackService.isServiceActive() || PauseController.isPaused()) {
        stopRecognition()
        return
      }
      speakPendingResult()
      handler.postDelayed(this, CHECK_INTERVAL_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = getString(R.string.live_recognition_title)
    initialMode = if (savedInstanceState == null) parseMode(intent) else null
    val density = resources.displayMetrics.density
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val pad = (16 * density).toInt()
      setPadding(pad, pad, pad, pad)
    }
    column.addView(TextView(this).apply {
      text = getString(R.string.live_recognition_instructions)
      textSize = 18f
    })
    previewView = PreviewView(this).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    column.addView(previewView, LinearLayout.LayoutParams(-1, (180 * density).toInt()))
    status = TextView(this).apply {
      text = getString(R.string.live_recognition_camera_off)
      textSize = 18f
    }
    val statusContainer = ScrollView(this).apply { addView(status) }
    column.addView(statusContainer, LinearLayout.LayoutParams(-1, (96 * density).toInt()))
    flashSwitch = CheckBox(this).apply {
      text = getString(R.string.live_recognition_flash)
      textSize = 20f
      minHeight = (48 * density).toInt()
      setOnClickListener { toggleFlash() }
    }
    column.addView(flashSwitch)
    RecognitionMode.entries.forEach { mode ->
      if (mode in DIALOG_MODES) {
        val actionButton = Button(this).apply {
          text = getString(mode.label)
          textSize = 20f
          minHeight = (48 * density).toInt()
          setOnClickListener { toggle(mode) }
        }
        column.addView(actionButton)
        return@forEach
      }
      val button = CheckBox(this).apply {
        text = getString(mode.label)
        textSize = 20f
        setOnClickListener { toggle(mode) }
      }
      switches[mode] = button
      column.addView(button)
    }
    setContentView(ScrollView(this).apply { addView(column); fitsSystemWindows = true })
    OfflineModels.status.observe(this) { states ->
      if (resumed && waitingModels.isNotEmpty()) {
        val finished = waitingModels.filter { group ->
          states[group]?.progress == null &&
            (OfflineModels.isReady(this, group) || states[group]?.failed == true)
        }
        if (finished.isNotEmpty()) {
          val failed = finished.filter { states[it]?.failed == true }.toSet()
          if (OfflineModels.Group.CAMERA_OCR in failed && qrOcrRequested) qrOcrFailed = true
          enabled.removeAll { modelGroup(it) in failed }
          waitingModels.removeAll(finished.toSet())
          reconcile()
          if (failed.isNotEmpty()) report(R.string.live_recognition_model_download_failed)
          else if (waitingModels.isEmpty()) {
            speak(getString(R.string.live_recognition_model_download_ready), false)
          }
        } else showModelProgress()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val mode = parseMode(intent) ?: return
    if (resumed) toggle(mode) else initialMode = mode
  }

  private fun parseMode(intent: Intent): RecognitionMode? =
    RecognitionMode.entries.find { it.name == intent.getStringExtra(EXTRA_MODE) }

  override fun onResume() {
    super.onResume()
    resumed = true
    LiveRecognition.attach(this)
    initialMode?.let { initialMode = null; toggle(it) }
    if (flashAfterResume) {
      flashAfterResume = false
      toggleFlash()
    }
  }

  override fun onPause() {
    resumed = false
    dismissAllDialogs()
    flashOn = false
    updateFlashSwitch()
    boundCamera?.cameraControl?.enableTorch(false)
    stopRecognition()
    LiveRecognition.detach(this)
    super.onPause()
  }

  override fun onDestroy() {
    loadingFeedback.stop()
    describer?.close()
    worker.shutdown()
    super.onDestroy()
  }

  internal fun canUseRotor(): Boolean = resumed && !dialogOpen && pendingPermission == null &&
    !pendingFlashPermission && !pendingPhotoStorage

  internal fun isEnabled(mode: RecognitionMode): Boolean = when (mode) {
    RecognitionMode.TEXT -> activeTextDialog != null
    RecognitionMode.LOTTERY_TICKET -> activeLotteryDialog != null
    RecognitionMode.CURRENCY -> activeCurrencyDialog != null
    RecognitionMode.POINT_AND_SPEAK -> activePointDialog != null
    RecognitionMode.LIGHT -> activeLightDialog != null
    RecognitionMode.FIND_OBJECT -> activeObjectDialog != null
    RecognitionMode.GUIDED_PHOTO -> activePhotoDialog != null
    else -> mode in enabled
  }

  internal fun isFlashEnabled(): Boolean = flashOn

  private fun dismissAllDialogs() {
    activeTextDialog?.dismiss()
    activeTextDialog = null
    activeLotteryDialog?.dismiss()
    activeLotteryDialog = null
    activeCurrencyDialog?.dismiss()
    activeCurrencyDialog = null
    activePointDialog?.dismiss()
    activePointDialog = null
    activeLightDialog?.dismiss()
    activeLightDialog = null
    activeObjectDialog?.dismiss()
    activeObjectDialog = null
    activePhotoDialog?.dismiss()
    activePhotoDialog = null
    dialogOpen = false
  }

  private fun prepareForDialog() {
    stopRecognition()
    flashOn = false
    updateFlashSwitch()
    dialogOpen = true
  }

  /** A flashlight toggle independent of recognition modes and their AI settings. */
  internal fun toggleFlash() {
    if (!canUseRotor()) { updateFlashSwitch(); return }
    if (flashOn) {
      flashOn = false
      updateFlashSwitch()
      boundCamera?.cameraControl?.enableTorch(false)
      if (enabled.none { it.usesCamera }) reconcile()
      return
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      pendingFlashPermission = true
      updateFlashSwitch()
      cameraPermission.launch(Manifest.permission.CAMERA)
      return
    }
    flashOn = true
    updateFlashSwitch()
    if (boundCamera == null && enabled.none { it.usesCamera }) reconcile() else applyTorch()
  }

  private fun updateFlashSwitch() { flashSwitch.isChecked = flashOn }

  /** Torch is owned by CameraX and remains on when users switch recognition modes. */
  private fun applyTorch() {
    val camera = boundCamera ?: return
    if (flashOn && !camera.cameraInfo.hasFlashUnit()) {
      flashOn = false
      updateFlashSwitch()
      report(R.string.live_recognition_flash_unavailable)
      if (enabled.none { it.usesCamera }) reconcile()
      return
    }
    val operation = camera.cameraControl.enableTorch(flashOn)
    if (flashOn) {
      operation.addListener({
        try {
          operation.get()
        } catch (error: Exception) {
          if (!resumed || boundCamera !== camera || !flashOn) return@addListener
          LogUtils.e(TAG, "Flash failed: %s", error.javaClass.simpleName)
          flashOn = false
          updateFlashSwitch()
          report(R.string.live_recognition_flash_unavailable)
          if (enabled.none { it.usesCamera }) reconcile()
        }
      }, ContextCompat.getMainExecutor(this))
    }
  }

  internal fun toggle(mode: RecognitionMode) {
    if (!canUseRotor()) { updateSwitches(); return }
    when (mode) {
      RecognitionMode.TEXT -> { openTextDialog(); return }
      RecognitionMode.LOTTERY_TICKET -> { openLotteryDialog(); return }
      RecognitionMode.CURRENCY -> { openCurrencyDialog(); return }
      RecognitionMode.POINT_AND_SPEAK -> { openPointDialog(); return }
      RecognitionMode.LIGHT -> { openLightDialog(); return }
      RecognitionMode.FIND_OBJECT -> { openObjectDialog(); return }
      RecognitionMode.GUIDED_PHOTO -> { openPhotoDialog(); return }
      else -> Unit
    }
    if (mode in enabled) {
      enabled.remove(mode)
      if (mode == RecognitionMode.QR_BARCODE) {
        qrOcrRequested = false
        qrOcrFailed = false
      }
      reconcile()
      speakMode(mode)
      return
    }
    if (mode.usesCamera &&
        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      pendingPermission = mode
      updateSwitches()
      cameraPermission.launch(Manifest.permission.CAMERA)
      return
    }
    if (mode.usesAi && !canUseAi(mode)) {
      updateSwitches()
      return
    }
    if (mode in OCR_MODES && !DeviceLicenseManager.allows(this, "ocr")) {
      updateSwitches()
      report(R.string.live_recognition_ocr_unlicensed)
      return
    }
    enabled.add(mode)
    reconcile()
    if (mode in enabled && modelGroup(mode) !in waitingModels) speakMode(mode)
  }

  private fun requireCameraPermission(mode: RecognitionMode): Boolean {
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
    pendingPermission = mode
    cameraPermission.launch(Manifest.permission.CAMERA)
    return false
  }

  private fun openTextDialog() {
    if (!requireCameraPermission(RecognitionMode.TEXT)) return
    if (!DeviceLicenseManager.allows(this, "ocr")) {
      report(R.string.live_recognition_ocr_unlicensed)
      return
    }
    prepareForDialog()
    activeTextDialog = LiveTextRecognitionDialog(this, prefs, ::speak) {
      dialogOpen = false
      activeTextDialog = null
    }.also { it.show() }
  }

  private fun openLotteryDialog() {
    if (!requireCameraPermission(RecognitionMode.LOTTERY_TICKET)) return
    val provider = AiProvider.active(prefs)
    if (provider == AiProvider.NONE || !provider.isReady(prefs)) {
      report(R.string.live_recognition_lottery_online_required)
      return
    }
    prepareForDialog()
    activeLotteryDialog = LiveLotteryDialog(this, ::speak) {
      dialogOpen = false
      activeLotteryDialog = null
    }.also { it.show() }
  }

  private fun openCurrencyDialog() {
    if (!requireCameraPermission(RecognitionMode.CURRENCY)) return
    prepareForDialog()
    activeCurrencyDialog = LiveCurrencyDialog(this, ::speak) {
      dialogOpen = false
      activeCurrencyDialog = null
    }.also { it.show() }
  }

  private fun openPointDialog() {
    if (!requireCameraPermission(RecognitionMode.POINT_AND_SPEAK)) return
    if (!canUseAi(RecognitionMode.POINT_AND_SPEAK)) return
    prepareForDialog()
    activePointDialog = LivePointAndSpeakDialog(this, prefs, ::speak) {
      dialogOpen = false
      activePointDialog = null
    }.also { it.show() }
  }

  private fun openLightDialog() {
    if (LiveRecognition.lightSensor(this) == null) {
      report(R.string.live_recognition_light_unavailable)
      return
    }
    prepareForDialog()
    activeLightDialog = LiveLightDialog(this, ::speak) {
      dialogOpen = false
      activeLightDialog = null
    }.also { it.show() }
  }

  private fun openObjectDialog() {
    if (!requireCameraPermission(RecognitionMode.FIND_OBJECT)) return
    prepareForDialog()
    activeObjectDialog = LiveFindObjectDialog(this, ::speak) {
      dialogOpen = false
      activeObjectDialog = null
    }.also { it.show() }
  }

  private fun openPhotoDialog() {
    if (!requireCameraPermission(RecognitionMode.GUIDED_PHOTO)) return
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
      checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      pendingPhotoStorage = true
      photoStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      return
    }
    prepareForDialog()
    activePhotoDialog = GuidedPhotoDialog(this) {
      dialogOpen = false
      activePhotoDialog = null
    }.also { it.show() }
  }

  /**
   * Whether the modes that use AI can be turned on: an AI service is in use, and the user agreed to
   * images being sent to it, which this asks once. When it asks, [mode] is turned on after Send.
   */
  private fun canUseAi(mode: RecognitionMode): Boolean {
    val backend = AiBackend.of(this, prefs)
    if (backend == AiBackend.NONE) {
      report(R.string.ai_service_not_set_up)
      return false
    }
    if (backend == AiBackend.GEMINI && GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY).isBlank()) {
      report(R.string.gemini_model_needs_key)
      return false
    }
    // Nothing leaves the phone with on-device AI.
    if (backend == AiBackend.ON_DEVICE) return true
    val host = if (backend == AiBackend.SERVICE) AiService.host(prefs) else null
    val consent = if (host == null) CLOUD_CONSENT_PREF else SERVICE_CONSENT_PREF + host
    if (prefs.getBoolean(consent, false)) return true
    dialogOpen = true
    AlertDialog.Builder(this)
      .setTitle(if (host == null) getString(R.string.live_recognition_cloud_title)
        else getString(R.string.live_recognition_service_title, host))
      .setMessage(if (host == null) getString(R.string.live_recognition_cloud_message)
        else getString(R.string.live_recognition_service_message, host))
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.live_recognition_send) { _, _ ->
        prefs.edit { putBoolean(consent, true) }
        dialogOpen = false
        if (resumed) toggle(mode)
      }.setOnDismissListener { dialogOpen = false }.show()
    return false
  }

  private fun updateSwitches() { switches.forEach { (mode, view) -> view.isChecked = mode in enabled } }
  private fun speakMode(mode: RecognitionMode) = speak(getString(R.string.live_recognition_mode_state,
    getString(mode.label), getString(if (mode in enabled) R.string.value_on else R.string.value_off)), true)

  internal fun stopAndFinish() { stopRecognition(); finish() }

  internal fun skipResult() {
    TalkBackService.getInstance()?.speechController?.interrupt(false)
    speakPendingResult(ignoreRecentInput = true)
  }

  private fun stopRecognition() {
    enabled.clear()
    qrOcrRequested = false
    qrOcrFailed = false
    reconcile()
  }

  /** Invalidates old OCR/network callbacks before replacing the enabled modes or camera. */
  private fun reconcile() {
    val token = ++generation
    LogUtils.i(TAG, "Modes: %s", enabled)
    TalkBackService.getInstance()?.speechController?.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_LIVE_RECOGNITION)
    describer?.close()
    describer = null
    cameraState?.removeObservers(this)
    cameraState = null
    boundCamera = null
    analysis?.clearAnalyzer()
    val bound = listOfNotNull(preview, analysis).toTypedArray()
    if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
    analysis = null
    preview = null
    announcements.clear()
    pendingResults.clear()
    worker.execute {
      qrTracker.reset()
      colorTracker.reset()
      documentTracker.reset()
      documentTextTracker.reset()
    }
    updateSwitches()
    handler.removeCallbacks(runningCheck)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (!resumed || (enabled.isEmpty() && !flashOn)) {
      waitingModels.clear()
      modelProgressBucket = 0
      loadingFeedback.stop()
      status.setText(R.string.live_recognition_camera_off)
      return
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (enabled.isNotEmpty()) handler.post(runningCheck)
    val required = enabled.mapNotNull(::modelGroup).toMutableSet()
    if (RecognitionMode.QR_BARCODE in enabled && qrOcrRequested && !qrOcrFailed) {
      required += OfflineModels.Group.CAMERA_OCR
    }
    val missing = required.filterNot { OfflineModels.isReady(this, it) }.toSet()
    val added = missing - waitingModels
    waitingModels.retainAll(missing)
    waitingModels.addAll(missing)
    added.forEach { OfflineModels.request(this, it) }
    if (added.isNotEmpty()) {
      modelProgressBucket = 0
      status.setText(R.string.live_recognition_model_download_started)
      speak(getString(R.string.live_recognition_model_download_started), false)
    }
    val cameraRecognition = enabled.any { it.usesCamera && modeReady(it) }
    if (!cameraRecognition && waitingModels.isNotEmpty() && !flashOn) {
      loadingFeedback.start()
      showModelProgress()
      return
    }
    if (cameraRecognition) {
      loadingFeedback.start()
      status.setText(R.string.live_recognition_camera_on)
      if (enabled.any { it.usesAi }) describer = createDescriber(token)
    } else {
      loadingFeedback.stop()
      status.setText(R.string.live_recognition_flash_only)
    }
    startCamera(token, cameraRecognition)
    if (waitingModels.isNotEmpty()) showModelProgress()
  }

  private fun modelGroup(mode: RecognitionMode): OfflineModels.Group? = when (mode) {
    RecognitionMode.DOCUMENT_LABEL -> OfflineModels.Group.CAMERA_OCR
    else -> null
  }

  private fun modeReady(mode: RecognitionMode): Boolean =
    modelGroup(mode)?.let { OfflineModels.isReady(this, it) } ?: true

  private fun showModelProgress() {
    if (waitingModels.isEmpty()) return
    val groups = enabled.mapNotNull(::modelGroup).toMutableSet()
    if (RecognitionMode.QR_BARCODE in enabled && qrOcrRequested && !qrOcrFailed) {
      groups += OfflineModels.Group.CAMERA_OCR
    }
    val total = groups.sumOf { OfflineModels.models(it).sumOf { model -> model.size } }
    val completed = groups.sumOf { group ->
      val size = OfflineModels.models(group).sumOf { it.size }
      if (OfflineModels.isReady(this, group)) size
      else size * (OfflineModels.state(group).progress ?: 0) / 100
    }
    val progress = if (total == 0L) 0 else (completed * 100 / total).toInt()
    status.text = getString(R.string.live_recognition_model_download_progress, progress)
    val bucket = progress / 25
    if (bucket > modelProgressBucket) {
      modelProgressBucket = bucket
      announceResult(SOURCE_MODELS, getString(R.string.live_recognition_model_download_progress, bucket * 25))
    }
  }

  /** Describes frames with what Describe image uses: see [AiBackend]. */
  private fun createDescriber(token: Int): FrameDescriber? {
    val language = Locale.getDefault().toLanguageTag()
    val onResult = { text: String -> if (generation == token) announceResult(SOURCE_AI, text) }
    fun stopAi(message: String?) {
      if (generation != token) return
      enabled.removeAll { it.usesAi }
      reconcile()
      if (message != null) report(message)
    }
    return when (val backend = AiBackend.of(this, prefs)) {
      AiBackend.GEMINI -> {
        status.setText(R.string.live_recognition_connecting)
        GeminiLiveSession(
          GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY),
          language,
          onReady = { if (generation == token) status.setText(R.string.live_recognition_camera_on) },
          onResult = onResult,
          onFailure = { stopAi(getString(it)) },
        )
      }
      AiBackend.ON_DEVICE, AiBackend.SERVICE -> {
        val questions = LiveRecognition.imageQuestions(this)
        val plain = backend == AiBackend.ON_DEVICE && AiBackend.usesPlainPrompts(this)
        RequestDescriber(
          prompt = { modes, above ->
            if (plain) GeminiLiveProtocol.plainPrompt(modes, above, language)
            else GeminiLiveProtocol.imagePrompt(modes, above, language)
          },
          ask = questions::ask,
          cancel = questions::cancel,
          minIntervalMs = if (backend == AiBackend.ON_DEVICE) 0 else GeminiLiveSession.REQUEST_INTERVAL_MS,
          maxResultAgeMs = REQUEST_MAX_RESULT_AGE_MS,
          onResult = onResult,
          onFailure = { stopAi(it?.userMessage(this)) },
        )
      }
      AiBackend.NONE -> {
        enabled.removeAll { it.usesAi }
        updateSwitches()
        report(R.string.ai_service_not_set_up)
        null
      }
    }
  }

  private fun startCamera(token: Int, cameraRecognition: Boolean) {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      if (generation != token || !resumed) return@addListener
      try {
        val provider = future.get()
        cameraProvider = provider
        val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val highDetail = enabled.any { it in OCR_MODES || it == RecognitionMode.QR_BARCODE }
        val frameSize = if (highDetail) Size(1920, 1080) else Size(1280, 720)
        val newAnalysis = ImageAnalysis.Builder()
          .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(frameSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val qr = RecognitionMode.QR_BARCODE in enabled
        val color = RecognitionMode.COLOR in enabled
        val document = RecognitionMode.DOCUMENT_LABEL in enabled && modeReady(RecognitionMode.DOCUMENT_LABEL)
        val fastLocalOnly = (qr || color) && !(document || enabled.any { it.usesAi })
        val captureInterval = if (fastLocalOnly) FAST_FRAME_INTERVAL_MS else FRAME_INTERVAL_MS
        var lastFrame = -FRAME_INTERVAL_MS
        newAnalysis.setAnalyzer(worker) { frame ->
          val now = SystemClock.elapsedRealtime()
          val ai = describer?.canRequest() == true
          val localNeed = qr || color || document
          if (generation != token || now - lastFrame < captureInterval ||
              !(localNeed || ai) || !processing.compareAndSet(false, true)) {
            frame.close()
            return@setAnalyzer
          }
          lastFrame = now
          try {
            val raw = frame.toBitmap()
            val rotation = frame.imageInfo.rotationDegrees
            val bitmap = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
              Matrix().apply { postRotate(rotation.toFloat()) }, true).also { raw.recycle() }
            val jpeg = if (!ai) null else ByteArrayOutputStream().use {
              bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
              Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
            }
            handler.post { processFrame(bitmap, jpeg, token) }
          } catch (e: Throwable) {
            LogUtils.e(TAG, "Frame failed: %s", e.javaClass.simpleName)
            processing.set(false)
            handler.post { if (generation == token) cameraFailed() }
          } finally { frame.close() }
        }
        preview = newPreview
        analysis = if (cameraRecognition) newAnalysis else null
        val camera = if (!cameraRecognition) {
          provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, newPreview)
        } else {
          provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, newPreview, newAnalysis)
        }
        boundCamera = camera
        if (flashOn) applyTorch()
        if (generation != token) return@addListener
        cameraState = camera.cameraInfo.cameraState
        cameraState?.observe(this) { state ->
          if (generation == token && state.error != null) {
            LogUtils.e(TAG, "Camera error %d", state.error!!.code)
            cameraFailed()
          }
        }
      } catch (e: Exception) {
        LogUtils.e(TAG, "Camera not started: %s", e.javaClass.simpleName)
        if (generation == token) cameraFailed()
      }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun cameraFailed() {
    flashOn = false
    updateFlashSwitch()
    enabled.removeAll { it.usesCamera }
    reconcile()
    report(R.string.live_recognition_camera_error)
  }

  private fun processFrame(bitmap: Bitmap, jpeg: String?, token: Int) {
    fun release() { bitmap.recycle(); processing.set(false) }
    if (generation != token || !resumed) { release(); return }
    if (jpeg != null) describer?.describe(jpeg, enabled.toSet(), true)
    val runQr = RecognitionMode.QR_BARCODE in enabled
    val runColor = RecognitionMode.COLOR in enabled
    val runDoc = RecognitionMode.DOCUMENT_LABEL in enabled && modeReady(RecognitionMode.DOCUMENT_LABEL)

    if (!(runQr || runColor || runDoc)) { release(); return }
    try {
      worker.execute {
        try {
          if (generation != token) return@execute
          val nowMs = SystemClock.elapsedRealtime()
          val ocrReady = OfflineModels.isReady(this, OfflineModels.Group.CAMERA_OCR)
          if (runDoc && !ocrReady) handler.post { if (generation == token && resumed) reconcile() }
          if (runColor) {
            val sample = ColorRecognizer.detect(this, bitmap)
            val spoken = colorTracker.observe(sample, nowMs)
            if (spoken != null) {
              handler.post {
                if (generation == token && resumed && RecognitionMode.COLOR in enabled) {
                  announceResult(SOURCE_COLOR, spoken)
                }
              }
            }
          }
          val alignment = if (ocrReady && runDoc) {
            try {
              DocumentAlignmentScanner.analyze(bitmap)
            } catch (error: RuntimeException) {
              LogUtils.e(TAG, "Document alignment failed: %s", error.javaClass.simpleName)
              DocumentAlignmentResult(DocumentAlignmentState.READY, "")
            }
          } else null
          var originalTextRead = false
          var originalText: String? = null
          var framedTextRead = false
          var framedText: String? = null
          fun readText(corners: Array<Point>? = null): String? {
            fun recognize(source: Bitmap): String? {
              val prepared = DocumentAlignmentScanner.prepareForCameraOcr(source, upscaleSmallText = false)
              return try {
                TesseractOcr.recognizeCameraText(this, prepared)
              } finally {
                if (prepared !== source) prepared.recycle()
              }
            }
            if (corners != null && framedTextRead) return framedText
            val dewarped = corners?.let { DocumentAlignmentScanner.dewarp(bitmap, it) }
            if (dewarped != null) {
              framedTextRead = true
              framedText = try {
                recognize(dewarped)
              } finally {
                dewarped.recycle()
              }
              return framedText
            }
            if (!originalTextRead) {
              originalText = recognize(bitmap)
              originalTextRead = true
            }
            return originalText
          }
          if (runQr) {
            val scanned = qrRecognizer.decode(bitmap)
            if (scanned != null && QrContentFormatter.needsIdentityText(scanned) && !ocrReady &&
                !qrOcrRequested && !qrOcrFailed && scanned.rawValue.length in listOf(9, 12) &&
                DeviceLicenseManager.allows(this, "ocr")) {
              handler.post {
                if (generation == token && resumed && RecognitionMode.QR_BARCODE in enabled) {
                  qrOcrRequested = true
                  reconcile()
                }
              }
            }
            val decoded = if (scanned != null && QrContentFormatter.needsIdentityText(scanned) && ocrReady) {
              val cardText = readText()
              val identity = cardText?.let { QrContentFormatter.formatIdentityText(scanned, it) }
              if (identity != null) scanned.copy(spokenText = identity) else scanned
            } else scanned
            val spoken = qrTracker.observe(decoded, nowMs)
            if (spoken != null) {
              handler.post {
                if (generation == token && resumed && RecognitionMode.QR_BARCODE in enabled) {
                  announceResult(SOURCE_QR, spoken)
                }
              }
            }
          }
          if (runDoc && alignment != null) {
            when (val decision = documentTracker.observe(alignment, nowMs)) {
              is DocumentAlignmentTracker.Decision.Guide -> {
                handler.post {
                  if (generation == token && resumed && RecognitionMode.DOCUMENT_LABEL in enabled) {
                    announceResult(SOURCE_DOCUMENT, decision.message)
                  }
                }
              }
              is DocumentAlignmentTracker.Decision.Capture -> {
                val rawDocText = readText(decision.corners)
                val confirmedDoc = rawDocText?.let {
                  documentTextTracker.observe(it.take(MAX_TEXT_LENGTH), SystemClock.elapsedRealtime())
                }
                handler.post {
                  if (generation != token || !resumed || RecognitionMode.DOCUMENT_LABEL !in enabled) return@post
                  if (rawDocText == null) {
                    enabled.remove(RecognitionMode.DOCUMENT_LABEL)
                    reconcile()
                    report(R.string.live_recognition_ocr_error)
                  } else if (confirmedDoc != null) {
                    announceResult(SOURCE_DOCUMENT, ExpiryDateExtractor.formatDocumentSpeech(confirmedDoc, MAX_TEXT_LENGTH))
                  } else {
                    pendingResults.remove(SOURCE_DOCUMENT)
                    status.setText(R.string.live_recognition_text_confirming)
                  }
                }
              }
              DocumentAlignmentTracker.Decision.Wait -> Unit
            }
          }
        } catch (error: RuntimeException) {
          LogUtils.e(TAG, "Local recognition frame failed: %s", error.javaClass.simpleName)
          if (runDoc) handler.post {
            if (generation == token && resumed) {
              enabled.removeAll(OCR_MODES)
              reconcile()
              report(R.string.live_recognition_ocr_error)
            }
          }
        } catch (error: OutOfMemoryError) {
          LogUtils.e(TAG, "Local recognition ran out of memory")
          handler.post { if (generation == token && resumed) cameraFailed() }
        } finally {
          release()
        }
      }
    } catch (_: RejectedExecutionException) {
      release()
    }
  }

  private fun announceResult(source: String, text: String) {
    if (!resumed || enabled.isEmpty() || PauseController.isPaused()) return
    pendingResults[source] = text to SystemClock.elapsedRealtime()
    speakPendingResult()
  }

  private fun speakPendingResult(ignoreRecentInput: Boolean = false) {
    if (!resumed || enabled.isEmpty() || PauseController.isPaused() || pendingResults.isEmpty()) return
    val speech = TalkBackService.getInstance()?.speechController ?: return
    if (speech.isSpeaking || (!ignoreRecentInput && RecentUserInput.isRecent())) {
      LogUtils.v(TAG, "Result waits: speaking=%s", speech.isSpeaking)
      return
    }
    val now = SystemClock.elapsedRealtime()
    val iterator = pendingResults.iterator()
    while (iterator.hasNext()) {
      val (source, result) = iterator.next()
      iterator.remove()
      val age = now - result.second
      if (age <= GeminiLiveSession.MAX_RESULT_AGE_MS &&
          announcements.shouldSpeak(source, result.first, now, RecognitionAnnouncements.REPEAT_INTERVAL_MS)) {
        LogUtils.d(TAG, "Speaking %s result, %d ms after it came", source, age)
        status.text = result.first
        speak(result.first, false)
        return
      }
      LogUtils.d(TAG, "Dropped %s result, %d characters, %d ms old", source, result.first.length, age)
    }
  }

  private fun report(message: Int) = report(getString(message))
  private fun report(message: String) { status.text = message; speak(message, true) }
  private fun speak(text: String, interrupt: Boolean) = LiveRecognition.speak(text, interrupt)

  companion object {
    const val EXTRA_MODE = "recognition_mode"
    /** Whether the user agreed to camera images being sent to Gemini. */
    private const val CLOUD_CONSENT_PREF = "pref_live_recognition_cloud_consent"
    private const val SOURCE_MODELS = "models"
    private const val SOURCE_AI = "ai"
    private const val SOURCE_QR = "qr"
    private const val SOURCE_COLOR = "color"
    private const val SOURCE_DOCUMENT = "document"
    private val OCR_MODES = setOf(RecognitionMode.DOCUMENT_LABEL)
    private val DIALOG_MODES = setOf(
      RecognitionMode.TEXT,
      RecognitionMode.POINT_AND_SPEAK,
      RecognitionMode.CURRENCY,
      RecognitionMode.LOTTERY_TICKET,
      RecognitionMode.FIND_OBJECT,
      RecognitionMode.LIGHT,
      RecognitionMode.GUIDED_PHOTO,
    )
    /** The first part of the key that says the user agreed to images going to an AI service. */
    private const val SERVICE_CONSENT_PREF = "pref_live_recognition_consent_"
    /** How old an answer from the on-device model or an AI service can be when it comes. */
    private const val REQUEST_MAX_RESULT_AGE_MS = 30_000L
    private const val FRAME_INTERVAL_MS = 1_000L
    private const val FAST_FRAME_INTERVAL_MS = 450L
    private const val CHECK_INTERVAL_MS = 500L
    private const val JPEG_QUALITY = 70
    private const val MAX_TEXT_LENGTH = 1_200
    private const val TAG = "LiveRecognition"
  }
}

internal class LiveRecognitionLoadingFeedback(
  private val handler: Handler,
  private val onSound: () -> Unit,
  private val onVibration: () -> Unit,
) {
  private var active = false
  private val repeatSound = object : Runnable {
    override fun run() {
      if (!active) return
      onSound()
      if (active) handler.postDelayed(this, REPEAT_INTERVAL_MS)
    }
  }

  fun start() {
    if (active) return
    active = true
    onVibration()
    handler.post(repeatSound)
  }

  fun stop() {
    active = false
    handler.removeCallbacks(repeatSound)
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 1_440L
  }
}
