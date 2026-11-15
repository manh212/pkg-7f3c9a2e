package com.google.android.accessibility.talkback.compositor

import android.app.Notification
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.Role
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import java.util.ArrayDeque
import java.util.function.Supplier
import kotlin.math.pow
import kotlin.math.roundToInt

class ProgressBarAnnouncer(
  private val service: TalkBackService,
  private val root: Supplier<AccessibilityNodeInfo?>,
) {
  private enum class Mode(val tone: Boolean, val speech: Boolean) {
    SOUND(true, false), BOTH(true, true), SPEECH(false, true), OFF(false, false),
  }

  private data class Source(val window: Int?, val id: String)

  private val prefs by lazy { SharedPreferencesUtils.getSharedPreferences(service) }
  private val handler = Handler(Looper.getMainLooper())
  private val previous = linkedMapOf<Source, ProgressReading>()
  private val tones = linkedMapOf<Source, ProgressReading>()
  private val phrases = linkedMapOf<Source, Pair<ProgressReading, Long>>()
  private var nextTone = 0L
  private var nextSpeech = 0L
  private var nextScan = 0L
  private var scanScheduled = false
  private var activeWindow: Int? = null
  private val scan = Runnable {
    scanScheduled = false
    nextScan = SystemClock.uptimeMillis() + SCAN_INTERVAL_MS
    scanWindow()
  }
  private val output = Runnable { report() }

  fun onEvent(event: AccessibilityEvent) {
    val type = event.eventType
    if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
      type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
      type != AccessibilityEvent.TYPE_VIEW_SELECTED &&
      type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
    if (!enabled(prefs) || !canReport()) return
    when (type) {
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
        if (NotificationListService.instance != null) return
        val notification = event.parcelableData as? Notification ?: return
        val reading = ProgressReading.fromNotification(notification) ?: return
        record(Source(null, "${event.packageName}|${notification.channelId}|${reading.label}"), reading)
      }
      AccessibilityEvent.TYPE_VIEW_SELECTED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        val changeTypes = event.contentChangeTypes
        val direct = type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
          event.className?.endsWith("ProgressBar") == true ||
          changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0
        if (direct) {
          val source = event.source
          if (source != null) {
            try {
              observe(source, event)
            } finally {
              source.recycle()
            }
          } else if (event.className?.endsWith("ProgressBar") == true) {
            ProgressReading.fromEvent(event)?.let {
              record(Source(event.windowId, "${event.packageName}|${it.label}"), it)
            }
          }
        }
        scheduleScan()
      }
      else -> scheduleScan()
    }
  }

  fun onNotificationPosted(notification: StatusBarNotification) {
    if (!enabled(prefs) || !canReport()) return
    val key = Source(null, notification.key)
    val reading = ProgressReading.fromNotification(notification.notification)
    if (reading == null) onNotificationRemoved(notification.key) else record(key, reading)
  }

  fun onNotificationRemoved(key: String) {
    val source = Source(null, key)
    if (previous[source]?.percent == 100) previous.remove(source) else forget(source)
  }

  fun onModeChanged() {
    stop()
    if (TalkBackService.isServiceActive()) {
      service.speechController.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_PROGRESS_BAR_PROGRESS)
      if (enabled(prefs)) {
        scheduleScan()
        NotificationListService.instance?.publishProgress()
        NotificationListService.onSandFlyStarted(service)
      }
    }
  }

  fun stop() {
    handler.removeCallbacksAndMessages(null)
    previous.clear()
    phrases.clear()
    tones.clear()
    scanScheduled = false
    activeWindow = null
    nextTone = 0L
    nextSpeech = 0L
    nextScan = 0L
  }

  private fun canReport() = TalkBackService.isServiceActive() && !PauseController.isPaused() &&
    service.getSystemService(PowerManager::class.java)?.isInteractive == true

  private fun scheduleScan() {
    if (scanScheduled) return
    scanScheduled = true
    handler.postAtTime(scan, maxOf(nextScan, SystemClock.uptimeMillis() + 120L))
  }

  private fun scanWindow() {
    if (!canReport() || !enabled(prefs)) return
    val first = root.get() ?: return
    val window = first.windowId
    if (activeWindow != window) {
      previous.keys.filter { it.window != null && it.window != window }.forEach(::forget)
      activeWindow = window
    }
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(first)
    val deadline = SystemClock.uptimeMillis() + 25L
    var visited = 0
    try {
      while (queue.isNotEmpty() && visited < MAX_NODES && SystemClock.uptimeMillis() < deadline) {
        val node = queue.removeFirst()
        visited++
        try {
          observe(node)
          val children = minOf(node.childCount, MAX_NODES - visited - queue.size)
          for (index in 0 until children) {
            if (SystemClock.uptimeMillis() >= deadline) break
            node.getChild(index)?.let(queue::addLast)
          }
        } finally {
          node.recycle()
        }
      }
    } finally {
      queue.forEach { it.recycle() }
    }
  }

  private fun observe(node: AccessibilityNodeInfo, event: AccessibilityEvent? = null) {
    if (!node.isVisibleToUser || Role.getRole(node) != Role.ROLE_PROGRESS_BAR) return
    val reading = ProgressReading.fromNode(node, event)
    val key = Source(node.windowId, node.hashCode().toString())
    val last = previous[key]
    if (event == null && last?.percent != null && reading.percent == null) return
    record(key, reading)
  }

  private fun record(key: Source, reading: ProgressReading) {
    val before = previous.put(key, reading)
    if (before == reading) return
    if (previous.size > MAX_SOURCES) forget(previous.keys.first())
    val mode = mode(prefs)
    if (mode.tone && (before == null || before.percent != reading.percent)) tones[key] = reading
    if (mode.speech) phrases[key] = reading to SystemClock.uptimeMillis()
    handler.removeCallbacks(output)
    handler.post(output)
  }

  private fun forget(key: Source) {
    previous.remove(key)
    tones.remove(key)
    phrases.remove(key)
  }

  private fun report() {
    if (!canReport() || !enabled(prefs)) {
      stop()
      return
    }
    val now = SystemClock.uptimeMillis()
    if (now >= nextTone && tones.isNotEmpty()) {
      val key = tones.keys.first()
      val progress = tones.remove(key)?.percent
      val rate = if (progress == null) 1f else 2.0.pow(progress / 50.0 - 1).toFloat()
      service.feedbackController.playAuditoryWithoutHaptic(R.raw.scroll_tone, rate, 0.5f, EVENT_ID_UNTRACKED)
      nextTone = now + TONE_INTERVAL_MS
    }
    if (now >= nextSpeech && phrases.isNotEmpty()) {
      val speech = service.speechController
      if (speech.isMute) phrases.clear()
      phrases.entries.removeAll { now - it.value.second > MAX_SPEECH_AGE_MS }
      if (phrases.isNotEmpty() && !speech.isSpeakingOrSpeechQueued) {
        val batch = phrases.keys.take(3).mapNotNull { phrases.remove(it)?.first }
        speech.speak(batch.joinToString(". ") { phrase(it) }, EVENT_ID_UNTRACKED,
          SpeechController.SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
            .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_PROGRESS_BAR_PROGRESS)
            .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE))
        nextSpeech = now + SPEECH_INTERVAL_MS
      } else {
        nextSpeech = now + 500L
      }
    }
    if (tones.isNotEmpty() || phrases.isNotEmpty()) {
      val toneAt = if (tones.isEmpty()) Long.MAX_VALUE else nextTone
      val speechAt = if (phrases.isEmpty()) Long.MAX_VALUE else nextSpeech
      handler.postAtTime(output, minOf(toneAt, speechAt))
    }
  }

  private fun phrase(reading: ProgressReading): String {
    val status = when (val percent = reading.percent) {
      null -> service.getString(R.string.progress_bar_processing)
      100 -> service.getString(R.string.progress_bar_complete)
      else -> service.getString(R.string.progress_bar_remaining, 100 - percent)
    }
    return if (reading.label.isEmpty()) status else "${reading.label}, $status"
  }

  companion object {
    const val KEY = "pref_progress_bar_feedback"
    private const val MAX_NODES = 256
    private const val MAX_SOURCES = 48
    private const val SCAN_INTERVAL_MS = 900L
    private const val TONE_INTERVAL_MS = 800L
    private const val SPEECH_INTERVAL_MS = 4_000L
    private const val MAX_SPEECH_AGE_MS = 20_000L

    private fun mode(prefs: SharedPreferences) = when (prefs.getString(KEY, "off")) {
      "sound" -> Mode.SOUND
      "both" -> Mode.BOTH
      "speech" -> Mode.SPEECH
      else -> Mode.OFF
    }

    fun enabled(prefs: SharedPreferences) = mode(prefs) != Mode.OFF
  }
}

internal data class ProgressReading(val percent: Int?, val label: String) {
  companion object {
    private val percentage = Regex("""(?<![\p{Nd}.,])([\p{Nd}]{1,3}(?:[.,][\p{Nd}]+)?)\s*[%٪％]""")

    fun fromNode(node: AccessibilityNodeInfo, event: AccessibilityEvent?): ProgressReading {
      val range = node.rangeInfo
      val state = AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString().orEmpty()
      val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
      if (range != null && range.type !in RangeInfo.RANGE_TYPE_INT..RangeInfo.RANGE_TYPE_PERCENT) {
        return ProgressReading(null, cleanLabel(label))
      }
      val value = range?.let { ratio(it.min.toDouble(), it.max.toDouble(), it.current.toDouble()) }
        ?: percentIn(state) ?: percentIn(label) ?: event?.let(::eventPercent)
      return ProgressReading(value, cleanLabel(label))
    }

    fun fromEvent(event: AccessibilityEvent): ProgressReading? {
      val label = (event.contentDescription ?: event.text.firstOrNull())?.toString().orEmpty()
      val value = percentIn(label) ?: eventPercent(event) ?: return null
      return ProgressReading(value, cleanLabel(label))
    }

    fun fromNotification(notification: Notification): ProgressReading? {
      val extras = notification.extras ?: return null
      val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
      val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
      if (max <= 0 && !indeterminate && notification.category != Notification.CATEGORY_PROGRESS) return null
      val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
      val current = extras.getInt(Notification.EXTRA_PROGRESS, -1)
      val value = if (indeterminate) null else {
        ratio(0.0, max.toDouble(), current.toDouble()) ?: percentIn(text) ?: percentIn(title)
      }
      return ProgressReading(value, cleanLabel(title))
    }

    private fun eventPercent(event: AccessibilityEvent): Int? =
      ratio(0.0, event.itemCount.toDouble(), event.currentItemIndex.toDouble())

    private fun ratio(min: Double, max: Double, value: Double): Int? =
      if (min.isFinite() && max.isFinite() && max > min && value in min..max) {
        ((value - min) * 100 / (max - min)).roundToInt().coerceAtMost(if (value == max) 100 else 99)
      } else null

    private fun percentIn(text: String): Int? {
      val number = percentage.findAll(text).singleOrNull()?.groupValues?.get(1) ?: return null
      return number.map { if (it.isDigit()) Character.digit(it, 10).toString() else it.toString() }
        .joinToString("").replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.roundToInt()
    }

    private fun cleanLabel(text: String): String =
      percentage.replace(text, "").trim { it.isWhitespace() || it in ":,;-(–)—)" }.take(80)
  }
}
