package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
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
  private var active = false
  private var generation = 0
  private var owner = ""
  private var ownerWindowId = -1
  private var viewport = Rect()
  private var ocrViewport = Rect()
  private var playerKey: String? = null
  private var playerAvailable = false
  private var focus: Rect? = null
  private var tracker = SubtitleTracker(true)
  private val accessibilityTracker = SubtitleTracker(false)
  private var captionBranch: SubtitleAccessibility.Branch? = null
  private var captionSamples = emptyMap<SubtitleAccessibility.Branch, CaptionSample>()
  private var readPosted = false
  private var ocrEnabled = false
  private var ocrUnavailableAnnounced = false
  private var ocrRetryAfter = 0L
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
  private var lastDeliveredSource: Source? = null
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
  private var pendingSource: Source? = null
  private var pendingAt = 0L
  private var quietUntil = 0L
  private var receiverRegistered = false
  private var closed = false
  private val capture = Runnable { captureFrame() }
  private val readAccessibility = Runnable {
    readPosted = false
    if (active && sameApplication()) readAccessibleWindow()
  }

  private enum class Source { ACCESSIBILITY, OCR }

  private data class CaptionSample(
    val text: String,
    val startedAt: Long,
    val lastSeenAt: Long,
  )

  private companion object {
    const val TAG = "SubtitleController"
    const val CAPTURE_TIMEOUT_MS = 8_000L
    const val ACCESSIBILITY_EVENT_INTERVAL_MS = 200L
    const val CAPTION_CONFIRMATION_MS = 3_000L
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
    if (InternalLiveRecognitionService.running ||
        InternalLiveRecognitionService.hasPendingStart()) {
      InternalLiveRecognitionService.clearPendingStart()
      if (InternalLiveRecognitionService.running) service.startService(Intent(service, InternalLiveRecognitionService::class.java)
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
        announce("Hãy thiết lập khóa truy cập trong cài đặt nhận dạng phụ đề.")
        return
      }
      // Android's required projection/recording permission prompts are the only UI here.
      InternalLiveRecognitionService.markPendingStart()
      try {
        service.startActivity(Intent(service, InternalLiveRecognitionActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (e: RuntimeException) {
        InternalLiveRecognitionService.clearPendingStart()
        LogUtils.w(TAG, "Consent Activity failed: %s", e.message)
        announce("Không mở được yêu cầu cấp quyền đọc phụ đề.")
      }
      return
    }
    ocrEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && TesseractOcr.isDataDownloaded(service)
    ocrUnavailableAnnounced = false
    val root = foregroundRoot()
    if (root == null) { announce(service.getString(R.string.subtitle_no_window)); return }
    try {
      owner = root.packageName?.toString().orEmpty()
      ownerWindowId = root.windowId
      root.getBoundsInScreen(viewport)
      focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
        try { Rect().also { node.getBoundsInScreen(it) } } finally { node.recycle() }
      }
      ocrViewport = Rect(viewport)
      playerKey = null
      playerAvailable = false
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
    accessibilityTracker.reset()
    captionBranch = null
    captionSamples = emptyMap()
    emptyFrames = 0
    noTextAnnounced = false
    nearDarkFrames = 0
    captureWasDark = false
    missingWindowSince = 0L
    lastDelivered = ""
    lastDeliveredAt = 0L
    lastDeliveredSource = null
    active = true
    LogUtils.i(TAG, "Subtitle reading started; interval=%d", SubtitleSettings.interval(prefs))
    setStatus("Đang tìm phụ đề")
    generation++
    failures = 0
    ocrRetryAfter = 0L
    quietUntil = SystemClock.uptimeMillis() + 1000L
    ContextCompat.registerReceiver(service, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
    announce(service.getString(R.string.subtitle_started))
    schedule()
  }

  fun findAgain() {
    if (InternalLiveRecognitionService.running ||
        InternalLiveRecognitionService.hasPendingStart()) {
      announce("Đang đọc phụ đề; không có vùng ảnh cần tìm lại.")
      return
    }
    if (!active) { announce(service.getString(R.string.subtitle_not_running)); return }
    generation++
    capturePending = false
    handler.removeCallbacks(captureTimeout)
    overlay.restoreAfterCapture()
    tracker.reset()
    accessibilityTracker.reset()
    captionSamples = emptyMap()
    pendingText = null
    pendingSource = null
    handler.removeCallbacks(speakPending)
    handler.removeCallbacks(readAccessibility)
    readPosted = false
    speaker.stop()
    announce(service.getString(R.string.subtitle_searching))
    if (sameApplication()) readAccessibleWindow()
    schedule()
  }

  fun stop(speak: Boolean) {
    // GestureController calls stop(false) when TalkBack is paused. It must also
    // stop the selected Gemini subtitle engine, not only the OCR capture loop.
    val liveWasActive = InternalLiveRecognitionService.running ||
      InternalLiveRecognitionService.hasPendingStart()
    if (liveWasActive) InternalLiveRecognitionService.clearPendingStart()
    if (InternalLiveRecognitionService.running) {
      service.startService(Intent(service, InternalLiveRecognitionService::class.java)
        .setAction(InternalLiveRecognitionService.ACTION_STOP))
    }
    val wasActive = active || liveWasActive
    active = false
    generation++
    handler.removeCallbacks(capture)
    handler.removeCallbacks(captureTimeout)
    handler.removeCallbacks(speakPending)
    handler.removeCallbacks(readAccessibility)
    readPosted = false
    capturePending = false
    pendingText = null
    pendingSource = null
    tracker.reset()
    accessibilityTracker.reset()
    captionBranch = null
    captionSamples = emptyMap()
    playerKey = null
    playerAvailable = false
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
    stop(false)
    closed = true
    worker.shutdown()
  }

  fun onUserInput() {
    if (!active) return
    quietUntil = SystemClock.uptimeMillis() + 1200L
    pendingText = null
    pendingSource = null
    handler.removeCallbacks(speakPending)
    speaker.stop()
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!active) return
    if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) onUserInput()
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      if (!sameApplication()) {
        LogUtils.i(TAG, "Stopped: foreground application changed")
        stop(false)
        return
      }
    }
    // Ignore focus navigation to avoid reading controls as subtitles.
    if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) &&
      event.packageName?.toString() == owner &&
      (ownerWindowId < 0 || event.windowId < 0 || ownerWindowId == event.windowId) &&
      !readPosted) {
      readPosted = true
      handler.postDelayed(readAccessibility, ACCESSIBILITY_EVENT_INTERVAL_MS)
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

  private fun invalidateCapture() {
    generation++
    capturePending = false
    handler.removeCallbacks(captureTimeout)
    overlay.restoreAfterCapture()
    schedule()
  }

  private fun resetScope() {
    invalidateCapture()
    handler.removeCallbacks(speakPending)
    tracker.reset()
    accessibilityTracker.reset()
    captionBranch = null
    captionSamples = emptyMap()
    pendingText = null
    pendingSource = null
    if (lastDeliveredSource != null) speaker.stop()
    lastDelivered = ""
    lastDeliveredAt = 0L
    lastDeliveredSource = null
    emptyFrames = 0
    noTextAnnounced = false
    failures = 0
    ocrRetryAfter = 0L
  }

  private fun updatePlayer(player: SubtitleVideoViewport.Player?) {
    val wasAvailable = playerAvailable
    playerAvailable = player != null
    if (player == null) {
      if (wasAvailable && SubtitleSettings.region(prefs) == "auto") invalidateCapture()
      return
    }
    val key = player.key
    if (key != playerKey) {
      resetScope()
      playerKey = key
    } else if (player.bounds != ocrViewport) {
      invalidateCapture()
      tracker.resize(ocrViewport, player.bounds)
    }
    ocrViewport = player.bounds
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
      if (root.windowId >= 0 && root.windowId != ownerWindowId) {
        resetScope()
        ownerWindowId = root.windowId
        playerKey = null
        playerAvailable = false
        focus = null
        if (!bounds.isEmpty) viewport = bounds
        ocrViewport = Rect(viewport)
        schedule()
      } else if (!bounds.isEmpty && bounds != viewport) {
        LogUtils.d(TAG, "Viewport changed; invalidating old screenshot")
        invalidateCapture()
        val previousOcrViewport = Rect(ocrViewport)
        ocrViewport = SubtitleTracker.resizeBounds(ocrViewport, viewport, bounds)
        tracker.resize(previousOcrViewport, ocrViewport)
        accessibilityTracker.resize(previousOcrViewport, ocrViewport)
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
    if (readAccessibleWindow()) {
      schedule()
      return
    }
    if (!ocrEnabled) {
      if (!ocrUnavailableAnnounced) {
        ocrUnavailableAnnounced = true
        val message = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
          R.string.subtitle_requires_android else R.string.subtitle_model_missing
        setStatus(service.getString(message))
      }
      schedule()
      return
    }
    if (SystemClock.uptimeMillis() < ocrRetryAfter) {
      schedule()
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
              ocrViewport else (SubtitleSettings.bounds(prefs, viewport, focus) ?: run {
                bitmap.recycle()
                complete(request, emptyList())
                return
              }))
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
            val controls = controlBounds()
            val enhance = emptyFrames >= 3 && emptyFrames % 2 == 1
            LogUtils.d(TAG, "Screenshot ready: %dx%d crop=%s dark=%d%% contrast=%d enhanced=%s",
              cropped.width, cropped.height, region, stats.darkPercent, stats.contrastRange, enhance)
            try {
              worker.execute { recognize(cropped, region, controls, request, enhance) }
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
    bitmap: Bitmap, region: Rect, controls: List<Rect>, request: Int, enhanced: Boolean,
  ) {
    val prepared = SubtitleImageProcessor.prepare(bitmap, enhanced)
    val image = prepared.bitmap
    val lines = try {
      TesseractOcr.recognizeSegments(service, image)?.map {
        SubtitleLine(it.text, prepared.screenBounds(it.bounds, region), it.confidence)
      }
    } finally {
      image.recycle()
    }
    handler.post {
      complete(request, lines?.filterNot { line ->
        controls.any { it.contains(line.bounds.centerX(), line.bounds.centerY()) }
      })
    }
  }

  private fun captureFailed(reason: String) {
    failures++
    LogUtils.w(TAG, "OCR failure=%s count=%d", reason, failures)
    if (failures >= 3) ocrRetryAfter = SystemClock.uptimeMillis() + 5_000L
    setStatus("Đang thử nhận dạng lại")
    schedule()
  }

  private fun complete(request: Int, lines: List<SubtitleLine>?) {
    if (!active || request != generation) return
    handler.removeCallbacks(captureTimeout)
    capturePending = false
    overlay.restoreAfterCapture()
    if (!sameApplication()) { stop(false); return }
    if (request != generation) { schedule(); return }
    val automatic = SubtitleSettings.region(prefs) == "auto"
    if (captionBranch != null || (automatic && !playerAvailable)) {
      schedule()
      return
    }
    if (lines == null) { captureFailed("screenshot/recognizer"); return }
    failures = 0
    val result = tracker.observeFrame(lines, if (automatic) ocrViewport else viewport)
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
      // No spoken warning when there are no subtitles: silence is the expected state
      // whenever the video itself has nothing to read.
      noTextAnnounced = true
      LogUtils.i(TAG, "Subtitle candidates missing for %d frames; rawLines=%d darkFrames=%d",
        emptyFrames, lines.size, nearDarkFrames)
      setStatus("Chưa tìm thấy chữ phụ đề")
    }
    if (result.status == SubtitleFrameStatus.WAITING_FOR_STABILITY) {
      setStatus(if (tracker.lockedBounds == null) "Đang xác định vùng phụ đề"
        else "Đang xác nhận dòng phụ đề")
    }
    if (!tracker.visible && !accessibilityTracker.visible) {
      pendingText = null
      if (SubtitleSettings.interrupt(prefs)) speaker.stop()
    }
    result.text?.let { queueSubtitle(it, Source.OCR) }
    schedule()
  }

  private fun readAccessibleWindow(): Boolean {
    val root = foregroundRoot() ?: return true
    return try {
      if (root.packageName?.toString() != owner ||
        (ownerWindowId >= 0 && root.windowId >= 0 && root.windowId != ownerWindowId)) return true
      readAccessibleSubtitle(root)
    } finally { root.recycle() }
  }

  private fun readAccessibleSubtitle(root: AccessibilityNodeInfo): Boolean {
    val mode = SubtitleSettings.region(prefs)
    val player = SubtitleVideoViewport.findPlayer(root, viewport)
    try {
      updatePlayer(player)
      if (player == null && mode == "auto") {
        if (captionBranch != null) observeAccessible(emptyList())
        return true
      }
      val region = SubtitleSettings.bounds(prefs, viewport, focus) ?: return true
      val playerGroups = player?.let { SubtitleAccessibility.findPlayerGroups(it.node, it.bounds, region) }.orEmpty()
      val groups = if (playerGroups.isEmpty() && mode != "auto" && mode != "full" &&
        (captionBranch == null || captionBranch == SubtitleAccessibility.MANUAL_BRANCH)) {
        SubtitleAccessibility.findPlayerGroups(root, viewport, region, manual = true)
      } else playerGroups
      val selected = captionBranch
      if (selected != null) {
        val current = groups.firstOrNull { it.branch == selected }
        if (current != null) {
          observeAccessible(current.lines)
        } else {
          val replacement = confirmCaption(groups)
          if (replacement != null) selectCaption(replacement) else observeAccessible(emptyList())
        }
        return true
      }
      val confirmed = confirmCaption(groups)
      if (confirmed != null) {
        selectCaption(confirmed)
        return true
      }
      val now = SystemClock.uptimeMillis()
      return captionSamples.values.any { now - it.startedAt <= CAPTION_CONFIRMATION_MS }
    } finally { player?.node?.recycle() }
  }

  private fun confirmCaption(groups: List<SubtitleAccessibility.Group>): SubtitleAccessibility.Group? {
    groups.firstOrNull { it.branch.marked }?.let { return it }
    val now = SystemClock.uptimeMillis()
    val samples = captionSamples.filterValues { now - it.lastSeenAt <= 10_000L }.toMutableMap()
    val changed = mutableListOf<SubtitleAccessibility.Group>()
    for (group in groups) {
      val text = SubtitleTracker.normalize(group.lines.joinToString(" ") { it.text })
      if (text.isEmpty()) continue
      val previous = samples[group.branch]
      if (previous != null && !SubtitleTracker.similar(previous.text, text)) changed.add(group)
      samples[group.branch] = CaptionSample(text, previous?.startedAt ?: now, now)
    }
    captionSamples = samples
    return changed.singleOrNull()
  }

  private fun selectCaption(group: SubtitleAccessibility.Group) {
    captionBranch = group.branch
    captionSamples = emptyMap()
    if (pendingSource == Source.OCR) {
      pendingText = null
      pendingSource = null
      handler.removeCallbacks(speakPending)
    }
    if (lastDeliveredSource == Source.OCR &&
      !matchesRecentDelivery(group.lines.joinToString(" ") { it.text })) speaker.stop()
    failures = 0
    emptyFrames = 0
    nearDarkFrames = 0
    noTextAnnounced = false
    observeAccessible(group.lines)
  }

  private fun observeAccessible(lines: List<SubtitleLine>) {
    val frame = accessibilityTracker.observeFrame(lines, viewport, fastText = true)
    if (lines.isEmpty() && pendingSource == Source.ACCESSIBILITY) {
      pendingText = null
      pendingSource = null
    }
    frame.text?.let { queueSubtitle(it, Source.ACCESSIBILITY) }
  }

  private fun matchesRecentDelivery(text: String): Boolean =
    SystemClock.uptimeMillis() - lastDeliveredAt < 1500L &&
      SubtitleTracker.similar(SubtitleTracker.normalize(text), lastDelivered)

  private fun queueSubtitle(text: String, source: Source) {
    val now = SystemClock.uptimeMillis()
    if (SubtitleTracker.normalize(text).isEmpty() ||
      (lastDeliveredSource != source && matchesRecentDelivery(text))) return
    LogUtils.d(TAG, "Subtitle queued source=%s length=%d", source.name.lowercase(), text.length)
    pendingText = text
    pendingSource = source
    pendingAt = now
    handler.removeCallbacks(speakPending)
    setStatus("Đang đọc phụ đề")
    if (SubtitleSettings.interrupt(prefs) && now >= quietUntil) deliverPending()
    else handler.post(speakPending)
  }

  private fun deliverPending() {
    if (!canUseFeature("ocr")) { stop(false); return }
    val text = pendingText ?: return
    val source = pendingSource ?: return
    val request = generation
    pendingText = null
    pendingSource = null
    if (!active || !sameApplication()) { stop(false); return }
    if (request != generation || (source == Source.OCR && captionBranch != null)) return
    lastDelivered = SubtitleTracker.normalize(text)
    lastDeliveredAt = SystemClock.uptimeMillis()
    lastDeliveredSource = source
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
          if (node.isVisibleToUser && (node.isClickable || node.isEditable || node.rangeInfo != null)) {
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
