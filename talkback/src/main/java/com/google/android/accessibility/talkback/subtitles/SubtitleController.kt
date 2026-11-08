package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.liveaudio.InternalLiveRecognitionActivity
import com.google.android.accessibility.talkback.liveaudio.InternalLiveRecognitionService
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

internal interface SubtitleSpeaker {
  fun isBusy(): Boolean
  fun speak(text: String, interrupt: Boolean)
  fun stop()
}

class SubtitleController internal constructor(
  private val service: AccessibilityService,
  private val announce: (String) -> Unit,
  private val speaker: SubtitleSpeaker,
  private val canUseFeature: (String) -> Boolean = { feature ->
    com.google.android.accessibility.talkback.licensing.DeviceLicenseManager.allows(service, feature)
  },
) {
  constructor(service: TalkBackService, announce: (String) -> Unit) : this(service, announce, object : SubtitleSpeaker {
    override fun isBusy(): Boolean = service.speechController.isSpeakingOrSpeechQueued
    override fun stop() = service.speechController.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES)
    override fun speak(text: String, interrupt: Boolean) {
      var flags = FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE or
        FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP
      if (interrupt) flags = flags or FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP
      service.speechController.speak(text, Performance.EVENT_ID_UNTRACKED,
        SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
          .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES).setFlags(flags))
    }
  })
  private val handler = Handler(Looper.getMainLooper())
  private val prefs = SharedPreferencesUtils.getSharedPreferences(service)
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "subtitle-ocr").apply { isDaemon = true }
  }
  private var recognizer: TextRecognizer? = null
  private var active = false
  private var generation = 0
  private var owner = ""
  private var viewport = Rect()
  private var ocrViewport = Rect()
  private var lastVideoViewportCheck = 0L
  private var focus: Rect? = null
  private var tracker = SubtitleTracker(true)
  private var accessibilityTracker = SubtitleTracker(true)
  private val overlay = SubtitleOverlay(service)
  private var capturePending = false
  private var failures = 0
  private var emptyFrames = 0
  private var noTextAnnounced = false
  private var captureWasDark = false
  private var nearDarkFrames = 0
  private var missingWindowSince = 0L
  private var lastDelivered = ""
  private var lastDeliveredAt = 0L
  private var status = ""
  private val captureTimeout = Runnable {
    if (active && capturePending) {
      LogUtils.w(TAG, "Screenshot/OCR deadline exceeded")
      generation++
      capturePending = false
      overlay.restoreAfterCapture()
      captureFailed("timeout")
    }
  }
  private var pendingText: String? = null
  private var pendingAt = 0L
  private var quietUntil = 0L
  private var receiverRegistered = false
  private var closed = false
  private val capture = Runnable { captureFrame() }
  private companion object {
    const val TAG = "SubtitleController"
    const val CAPTURE_TIMEOUT_MS = 8_000L
  }
  private val speakPending = object : Runnable {
    override fun run() {
      if (!active || pendingText == null) return
      val now = SystemClock.uptimeMillis()
      if (now - pendingAt > 5000L || (!tracker.visible && !accessibilityTracker.visible)) {
        pendingText = null
        return
      }
      if (now < quietUntil || speaker.isBusy()) {
        handler.postDelayed(this, 100L)
        return
      }
      deliverPending()
    }
  }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_SCREEN_OFF) stop(false)
    }
  }

  fun toggle() {
    // Both the gesture and the context-menu subtitle command land here.
    if (InternalLiveRecognitionService.running) {
      service.startService(Intent(service, InternalLiveRecognitionService::class.java)
        .setAction(InternalLiveRecognitionService.ACTION_STOP))
      speaker.stop()
      announce(service.getString(R.string.subtitle_stopped))
      return
    }
    if (active) { stop(true); return }
    if (!canUseFeature("ocr")) {
      announce("Giấy phép hiện tại không có tính năng nhận dạng chữ OCR.")
      return
    }
    if (closed) return
    if (SubtitleSettings.engine(prefs) == SubtitleSettings.LIVE_ENGINE) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        announce("Dịch âm thanh nội bộ cần Android 10 trở lên.")
        return
      }
      if (GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY).isEmpty()) {
        announce("Hãy nhập khóa Gemini API trong cài đặt Nhận dạng phụ đề.")
        return
      }
      // Android's required projection/recording permission prompts are the only UI here.
      service.startActivity(Intent(service, InternalLiveRecognitionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return
    }
    // The special permission powers the visible overlay, NOT screenshot OCR.
    if (service is TalkBackService && !Settings.canDrawOverlays(service)) {
      announce("Hãy cấp quyền Hiển thị trên các ứng dụng khác cho lớp phủ trạng thái phụ đề, rồi bật lại tính năng.")
      try {
        service.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:" + service.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot launch overlay permission page: %s", e.message)
      }
      return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      announce(service.getString(R.string.subtitle_requires_android))
      return
    }
    if (SubtitleSettings.engine(prefs) == "tesseract" && !TesseractOcr.isDataDownloaded(service)) {
      announce(service.getString(R.string.subtitle_model_missing))
      return
    }
    val root = foregroundRoot()
    if (root == null) { announce(service.getString(R.string.subtitle_no_window)); return }
    try {
      owner = root.packageName?.toString().orEmpty()
      root.getBoundsInScreen(viewport)
      focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
        try { Rect().also { node.getBoundsInScreen(it) } } finally { node.recycle() }
      }
      ocrViewport = if (SubtitleSettings.region(prefs) == "auto")
        SubtitleVideoViewport.find(root, viewport) ?: Rect(viewport) else Rect(viewport)
      lastVideoViewportCheck = SystemClock.uptimeMillis()
    } finally { root.recycle() }
    if (owner.isEmpty() || viewport.isEmpty) {
      announce(service.getString(R.string.subtitle_no_window))
      return
    }
    if (SubtitleSettings.bounds(prefs, viewport, focus) == null) {
      announce(service.getString(R.string.subtitle_no_focus))
      return
    }
    tracker = SubtitleTracker(SubtitleSettings.region(prefs) == "auto", fastOcr = true)
    accessibilityTracker = SubtitleTracker(SubtitleSettings.region(prefs) == "auto")
    emptyFrames = 0
    noTextAnnounced = false
    nearDarkFrames = 0
    captureWasDark = false
    missingWindowSince = 0L
    lastDelivered = ""
    active = true
    LogUtils.i(TAG, "Started owner=%s engine=%s region=%s interval=%d ocrViewport=%s",
      owner, SubtitleSettings.engine(prefs), SubtitleSettings.region(prefs),
      SubtitleSettings.interval(prefs), ocrViewport)
    setStatus("Đang tìm phụ đề")
    generation++
    failures = 0
    quietUntil = SystemClock.uptimeMillis() + 1000L
    ContextCompat.registerReceiver(service, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
    announce(service.getString(R.string.subtitle_started))
    schedule()
  }

  fun findAgain() {
    if (InternalLiveRecognitionService.running) {
      announce("Gemini đang dịch âm thanh trực tuyến; chế độ này không quét vùng ảnh phụ đề.")
      return
    }
    if (!active) { announce(service.getString(R.string.subtitle_not_running)); return }
    generation++
    tracker.reset()
    accessibilityTracker.reset()
    pendingText = null
    handler.removeCallbacks(speakPending)
    speaker.stop()
    announce(service.getString(R.string.subtitle_searching))
    schedule()
  }

  fun stop(speak: Boolean) {
    val wasActive = active
    active = false
    generation++
    handler.removeCallbacks(capture)
    handler.removeCallbacks(captureTimeout)
    handler.removeCallbacks(speakPending)
    capturePending = false
    pendingText = null
    tracker.reset()
    accessibilityTracker.reset()
    overlay.dismiss()
    status = ""
    if (wasActive) LogUtils.i(TAG, "Stopped subtitle reading")
    if (receiverRegistered) {
      service.unregisterReceiver(screenReceiver)
      receiverRegistered = false
    }
    if (wasActive) {
      speaker.stop()
      if (speak) announce(service.getString(R.string.subtitle_stopped))
    }
  }

  fun shutdown() {
    if (closed) return
    if (InternalLiveRecognitionService.running) {
      service.startService(Intent(service, InternalLiveRecognitionService::class.java)
        .setAction(InternalLiveRecognitionService.ACTION_STOP))
    }
    stop(false)
    closed = true
    worker.execute { recognizer?.close(); recognizer = null }
    worker.shutdown()
  }

  fun onUserInput() {
    if (!active) return
    quietUntil = SystemClock.uptimeMillis() + 1200L
    pendingText = null
    handler.removeCallbacks(speakPending)
    speaker.stop()
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!active) return
    if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) onUserInput()
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      if (!sameApplication()) {
        LogUtils.i(TAG, "Stopped: foreground application changed from %s", owner)
        stop(false)
        return
      }
    }
    // Ignore focus navigation to avoid reading controls as subtitles.
    if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) &&
      event.packageName?.toString() == owner) {
      refreshVideoViewport()
      readAccessibleSubtitle(event)
    }
  }

  private fun foregroundRoot(): AccessibilityNodeInfo? {
    val activeRoot = service.rootInActiveWindow
    if (activeRoot != null && activeRoot.packageName?.toString() != service.packageName) return activeRoot
    for (window in service.windows) {
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
      val candidate = window.root ?: continue
      if (candidate.packageName?.toString() != service.packageName) {
        activeRoot?.recycle()
        return candidate
      }
      candidate.recycle()
    }
    return activeRoot
  }

  private fun refreshVideoViewport() {
    if (!active || SubtitleSettings.region(prefs) != "auto") return
    val now = SystemClock.uptimeMillis()
    if (now - lastVideoViewportCheck < 3000L) return
    lastVideoViewportCheck = now
    val root = foregroundRoot() ?: return
    try {
      if (root.packageName?.toString() != owner) return
      val next = SubtitleVideoViewport.find(root, viewport) ?: Rect(viewport)
      if (next == ocrViewport) return
      LogUtils.i(TAG, "Video viewport changed: %s -> %s", ocrViewport, next)
      ocrViewport = next
      generation++
      capturePending = false
      handler.removeCallbacks(captureTimeout)
      overlay.restoreAfterCapture()
      tracker.reset()
      emptyFrames = 0
      noTextAnnounced = false
      schedule()
    } finally { root.recycle() }
  }

  private fun sameApplication(): Boolean {
    val root = foregroundRoot()
    if (root == null) {
      if (missingWindowSince == 0L) missingWindowSince = SystemClock.uptimeMillis()
      return SystemClock.uptimeMillis() - missingWindowSince < 2500L
    }
    return try {
      val pkg = root.packageName?.toString().orEmpty()
      if (pkg != owner) {
        if (pkg != service.packageName) return false
        if (missingWindowSince == 0L) missingWindowSince = SystemClock.uptimeMillis()
        return SystemClock.uptimeMillis() - missingWindowSince < 2500L
      }
      missingWindowSince = 0L
      val bounds = Rect().also { root.getBoundsInScreen(it) }
      if (!bounds.isEmpty && bounds != viewport) {
        LogUtils.d(TAG, "Viewport changed; invalidating old screenshot")
        generation++
        capturePending = false
        handler.removeCallbacks(captureTimeout)
        overlay.restoreAfterCapture()
        val previousOcrViewport = Rect(ocrViewport)
        ocrViewport = SubtitleTracker.resizeBounds(ocrViewport, viewport, bounds)
        tracker.resize(previousOcrViewport, ocrViewport)
        accessibilityTracker.resize(viewport, bounds)
        focus = focus?.let { SubtitleTracker.resizeBounds(it, viewport, bounds) }
        viewport = bounds
        schedule()
      }
      true
    } finally { root.recycle() }
  }

  private fun schedule() {
    handler.removeCallbacks(capture)
    if (active && !capturePending) handler.postDelayed(capture, SubtitleSettings.interval(prefs))
  }

  private fun captureFrame() {
    if (!canUseFeature("ocr")) { stop(false); return }
    if (!active || closed || capturePending) return
    if (service.getSystemService(PowerManager::class.java)?.isInteractive != true || !sameApplication()) {
      LogUtils.i(TAG, "Stopped: screen off or app changed")
      stop(false)
      return
    }
    val request = generation
    capturePending = true
    captureWasDark = false
    handler.removeCallbacks(captureTimeout)
    handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
    overlay.hideForCapture()
    handler.postDelayed({
      if (!active || request != generation || !capturePending) return@postDelayed
      try {
        ScreenshotCapture.takeScreenshot(service, object : CaptureListener {
          override fun onScreenCaptureFinished(bitmap: Bitmap?, isFormatSupported: Boolean) {
            if (!active || request != generation) { bitmap?.recycle(); return }
            overlay.restoreAfterCapture()
            if (!sameApplication()) { bitmap?.recycle(); stop(false); return }
            if (request != generation) { bitmap?.recycle(); return }
            if (bitmap == null || !isFormatSupported) {
              bitmap?.recycle()
              LogUtils.w(TAG, "Screenshot failed or unsupported format")
              complete(request, null)
              return
            }
            val region = Rect(tracker.lockedBounds ?: if (SubtitleSettings.region(prefs) == "auto")
              ocrViewport else (SubtitleSettings.bounds(prefs, viewport, focus) ?: viewport))
            if (!region.intersect(0, 0, bitmap.width, bitmap.height) || region.isEmpty) {
              LogUtils.w(TAG, "Invalid screenshot crop: bitmap=%dx%d", bitmap.width, bitmap.height)
              bitmap.recycle()
              complete(request, null)
              return
            }
            val cropped = try {
              Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            } catch (e: RuntimeException) {
              bitmap.recycle()
              LogUtils.w(TAG, "Screenshot crop error: %s", e.message)
              complete(request, null)
              return
            }
            if (cropped !== bitmap) bitmap.recycle()
            val stats = SubtitleImageProcessor.inspect(cropped)
            captureWasDark = stats.nearlyUniformDark
            val controls = if (SubtitleSettings.region(prefs) == "auto" && tracker.lockedBounds == null) controlBounds() else emptyList()
            // Rotate enhancement and OCR engines only after repeated frames with NO caption candidates.
            // A screenshot containing unrelated interface text must not disable this fallback.
            val enhance = emptyFrames >= 3 && emptyFrames % 2 == 1
            val preferredEngine = SubtitleSettings.engine(prefs)
            val fallbackEngine = if (preferredEngine == "tesseract") "mlkit" else "tesseract"
            val useFallback = (failures > 0 && failures % 2 == 1 ||
              emptyFrames >= 6 && emptyFrames % 4 == 2) &&
              (fallbackEngine != "tesseract" || TesseractOcr.isDataDownloaded(service))
            val engine = if (useFallback) fallbackEngine else preferredEngine
            LogUtils.d(TAG, "Screenshot ready: %dx%d crop=%s dark=%d%% contrast=%d engine=%s enhanced=%s",
              cropped.width, cropped.height, region, stats.darkPercent, stats.contrastRange, engine, enhance)
            try {
              worker.execute { recognize(cropped, region, controls, request, engine, enhance) }
            } catch (e: RuntimeException) {
              cropped.recycle()
              LogUtils.w(TAG, "OCR worker unavailable: %s", e.message)
              complete(request, null)
            }
          }
        })
      } catch (e: RuntimeException) {
        overlay.restoreAfterCapture()
        LogUtils.w(TAG, "Screenshot request error: %s", e.message)
        complete(request, null)
      }
    }, 80L)
  }

  private fun recognize(
    bitmap: Bitmap, region: Rect, controls: List<Rect>, request: Int,
    engineName: String, enhanced: Boolean,
  ) {
    val prepared = SubtitleImageProcessor.prepare(bitmap, enhanced)
    val image = prepared.bitmap
    if (engineName == "tesseract") {
      val lines = try {
        TesseractOcr.recognizeSegments(service, image)?.map {
          SubtitleLine(it.text, prepared.screenBounds(it.bounds, region))
        }
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Tesseract failure: %s", e.message)
        null
      } finally { image.recycle() }
      handler.post {
        complete(request, lines?.filterNot { line ->
          controls.any { it.contains(line.bounds.centerX(), line.bounds.centerY()) }
        })
      }
    } else {
      try {
        val engine = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
          .also { recognizer = it }
        engine.process(InputImage.fromBitmap(image, 0))
          .addOnSuccessListener { text ->
            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
              line.boundingBox?.let {
                SubtitleLine(line.text, prepared.screenBounds(it, region))
              }
            }.filterNot { line ->
              controls.any { it.contains(line.bounds.centerX(), line.bounds.centerY()) }
            }
            complete(request, lines)
          }
          .addOnFailureListener { error ->
            LogUtils.w(TAG, "ML Kit subtitle recognition failed: %s", error.message)
            complete(request, null)
          }
          .addOnCompleteListener { image.recycle() }
      } catch (e: RuntimeException) {
        image.recycle()
        LogUtils.w(TAG, "ML Kit invocation failed: %s", e.message)
        handler.post { complete(request, null) }
      }
    }
  }

  private fun captureFailed(reason: String) {
    failures++
    LogUtils.w(TAG, "OCR failure=%s count=%d", reason, failures)
    if (failures >= 3) {
      stop(false)
      announce(service.getString(R.string.subtitle_failed))
    } else {
      setStatus("Đang thử nhận dạng lại")
      schedule()
    }
  }

  private fun complete(request: Int, lines: List<SubtitleLine>?) {
    if (!active || request != generation) return
    handler.removeCallbacks(captureTimeout)
    capturePending = false
    overlay.restoreAfterCapture()
    if (!sameApplication()) { stop(false); return }
    if (request != generation) { schedule(); return }
    if (lines == null) { captureFailed("screenshot/recognizer"); return }
    failures = 0
    val result = tracker.observeFrame(lines, ocrViewport)
    val hasCandidate = result.candidateLines > 0
    LogUtils.d(TAG, "OCR frame: rawLines=%d candidateLines=%d state=%s locked=%s released=%s",
      lines.size, result.candidateLines, result.status, tracker.lockedBounds != null, result.regionReleased)
    // Important: unrelated OCR text must NOT reset the missing-caption watchdog.
    if (result.status == SubtitleFrameStatus.NO_TEXT ||
        result.status == SubtitleFrameStatus.NO_SUBTITLE_CANDIDATE) {
      emptyFrames++
      nearDarkFrames = if (captureWasDark) nearDarkFrames + 1 else 0
    } else {
      emptyFrames = 0
      nearDarkFrames = 0
      noTextAnnounced = false
    }
    if (result.regionReleased) {
      LogUtils.i(TAG, "Automatically reacquiring subtitle region after irrelevant or stale text")
      setStatus("Đang tìm lại vùng phụ đề")
    }
    if (emptyFrames >= 8 && !noTextAnnounced &&
        SystemClock.uptimeMillis() - lastDeliveredAt > 5000L) {
      noTextAnnounced = true
      val reason = when {
        nearDarkFrames >= 4 -> "Vùng ảnh chụp gần như tối. Hãy kiểm tra video có hiển thị trong ảnh chụp hay không."
        lines.isNotEmpty() -> "OCR chỉ tìm được chữ ngoài vùng phụ đề. Meridian đang thử tìm lại vùng và đổi cách nhận dạng."
        else -> "OCR không thấy chữ trong ảnh video. Hãy kiểm tra phụ đề đang hiển thị."
      }
      LogUtils.i(TAG, "Subtitle candidates missing for %d frames; rawLines=%d darkFrames=%d",
        emptyFrames, lines.size, nearDarkFrames)
      setStatus("Chưa tìm thấy chữ phụ đề")
      announce("Chưa tìm thấy phụ đề trên màn hình. $reason")
    }
    if (result.status == SubtitleFrameStatus.WAITING_FOR_STABILITY) {
      setStatus(if (tracker.lockedBounds == null) "Đang xác định vùng phụ đề"
        else "Đang xác nhận dòng phụ đề")
    }
    if (!tracker.visible && !accessibilityTracker.visible) {
      pendingText = null
      if (SubtitleSettings.interrupt(prefs)) speaker.stop()
    }
    result.text?.let { queueSubtitle(it, "ocr") }
    schedule()
  }

  private fun readAccessibleSubtitle(event: AccessibilityEvent) {
    val node = event.source ?: return
    try {
      if (!node.isVisibleToUser || node.isClickable || node.isEditable) return
      val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
      if (text.length !in 5..160 || !text.any { it.isLetter() }) return
      val box = Rect().also { node.getBoundsInScreen(it) }
      if (box.isEmpty || !Rect.intersects(box, viewport)) return
      val region = SubtitleSettings.bounds(prefs, viewport, focus) ?: return
      if (!region.contains(box.centerX(), box.centerY())) return
      if (SubtitleSettings.region(prefs) == "auto" &&
          (kotlin.math.abs(box.centerX() - viewport.centerX()) > viewport.width() * 35 / 100 ||
           box.centerY() < viewport.top + viewport.height() * 35 / 100)) return
      accessibilityTracker.observe(listOf(SubtitleLine(text, box)), viewport)?.let {
        queueSubtitle(it, "accessibility")
      }
    } finally { node.recycle() }
  }

  private fun queueSubtitle(text: String, source: String) {
    val key = SubtitleTracker.normalize(text)
    val now = SystemClock.uptimeMillis()
    if (key.isEmpty() || (key == lastDelivered && now - lastDeliveredAt < 10_000L)) return
    LogUtils.d(TAG, "Subtitle queued source=%s length=%d", source, text.length)
    pendingText = text
    pendingAt = now
    handler.removeCallbacks(speakPending)
    setStatus("Đang đọc phụ đề")
    if (SubtitleSettings.interrupt(prefs) && now >= quietUntil) deliverPending()
    else handler.post(speakPending)
  }

  private fun deliverPending() {
    if (!canUseFeature("ocr")) { stop(false); return }
    val text = pendingText ?: return
    pendingText = null
    if (!active || !sameApplication()) { stop(false); return }
    lastDelivered = SubtitleTracker.normalize(text)
    lastDeliveredAt = SystemClock.uptimeMillis()
    speaker.speak(text, SubtitleSettings.interrupt(prefs))
  }

  private fun setStatus(message: String) {
    if (status == message) return
    status = message
    LogUtils.i(TAG, "State: %s", message)
    if (Settings.canDrawOverlays(service)) overlay.show(message)
  }

  private fun controlBounds(): List<Rect> {
    val root = foregroundRoot() ?: return emptyList()
    val boxes = mutableListOf<Rect>()
    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    val deadline = SystemClock.uptimeMillis() + 30L
    try {
      while (queue.isNotEmpty() && visited++ < 250 && SystemClock.uptimeMillis() < deadline) {
        val node = queue.removeFirst()
        try {
          if (node.isVisibleToUser && (node.isClickable || node.isEditable)) {
            val box = Rect().also { node.getBoundsInScreen(it) }
            if (box.width().toLong() * box.height() < viewport.width().toLong() * viewport.height() / 10) boxes.add(box)
          }
          for (index in 0 until minOf(node.childCount, 250 - visited - queue.size)) node.getChild(index)?.let { queue.add(it) }
        } finally { node.recycle() }
      }
    } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    return boxes
  }
}
