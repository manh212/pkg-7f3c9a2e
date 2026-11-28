package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class PhotoBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width get() = right - left
  val height get() = bottom - top
  val cx get() = (left + right) / 2
  val cy get() = (top + bottom) / 2
  val valid get() = listOf(left, top, right, bottom).all { it.isFinite() } && width > 0 && height > 0
  val centerDistance get() = (cx - .5f) * (cx - .5f) + (cy - .5f) * (cy - .5f)

  fun overlaps(other: PhotoBox): Float {
    val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
      max(0f, min(bottom, other.bottom) - max(top, other.top))
    return intersection / max(.00001f, width * height + other.width * other.height - intersection)
  }

  fun stableAgainst(other: PhotoBox): Boolean =
    abs(cx - other.cx) <= .035f && abs(cy - other.cy) <= .035f &&
      abs(width - other.width) <= .06f && abs(height - other.height) <= .06f
}

internal enum class PhotoSubjectKind { FACE, DOCUMENT, OBJECT }

internal data class PhotoSubject(
  val key: String,
  val label: String,
  val box: PhotoBox,
  val kind: PhotoSubjectKind,
  val confidence: Float = 1f,
  val documentState: DocumentAlignmentState? = null,
  val facingCamera: Boolean = true,
)

internal data class PhotoObservation(
  val subjects: List<PhotoSubject>,
  val brightness: Float,
  val sharpness: Float,
  val movement: Float,
  val focusReady: Boolean,
)

internal enum class PhotoGuidance(val message: Int) {
  SEARCH(R.string.guided_photo_search),
  LOW_LIGHT(R.string.guided_photo_low_light),
  BLUR(R.string.guided_photo_blur),
  STEADY(R.string.guided_photo_steady),
  FOCUS(R.string.guided_photo_focus),
  CLOSER(R.string.guided_photo_closer),
  AWAY(R.string.guided_photo_away),
  LEFT(R.string.guided_photo_left),
  RIGHT(R.string.guided_photo_right),
  UP(R.string.guided_photo_up),
  DOWN(R.string.guided_photo_down),
  FLAT(R.string.guided_photo_flat),
  FACE_FORWARD(R.string.guided_photo_face_forward),
}

internal class GuidedPhotoController {
  sealed class Signal {
    data class Say(val ticket: Int, val message: Int, val argument: String? = null) : Signal()
    data class Guide(val guidance: PhotoGuidance) : Signal()
    data object CancelSpeech : Signal()
    data object CancelCountdown : Signal()
    data object Capture : Signal()
  }

  private enum class Speech { SUBJECT, TWO, ONE }
  private var subject: PhotoSubject? = null
  private var seen = 0
  private var subjectTicket: Int? = null
  private var subjectSpoken = false
  private var serial = 0
  private val tickets = mutableMapOf<Int, Speech>()
  private var lastFrame = Long.MIN_VALUE
  private var anchor: PhotoBox? = null
  private var stableSince: Long? = null
  private var countdownSince: Long? = null
  private var phase = 0
  private var twoSpoken = false
  private var oneSpoken = false
  private var lastGuide: PhotoGuidance? = null
  private var lastGuideAt = Long.MIN_VALUE
  private var captured = false

  fun spoken(ticket: Int, completed: Boolean) {
    val kind = tickets.remove(ticket) ?: return
    when (kind) {
      Speech.SUBJECT -> {
        subjectTicket = null
        subjectSpoken = completed
      }
      Speech.TWO -> twoSpoken = completed
      Speech.ONE -> oneSpoken = completed
    }
    if (!completed) {
      countdownSince = null
      phase = 0
      twoSpoken = false
      oneSpoken = false
      stableSince = null
    }
  }

  fun observe(observation: PhotoObservation, now: Long, frontCamera: Boolean = false): List<Signal> {
    if (captured || (lastFrame != Long.MIN_VALUE && now <= lastFrame)) return emptyList()
    val signals = mutableListOf<Signal>()
    if (lastFrame != Long.MIN_VALUE && now - lastFrame > MAX_FRAME_GAP_MS) cancel(signals)
    lastFrame = now
    val next = select(observation.subjects)
    val previous = subject
    val same = next != null && previous != null && next.key == previous.key &&
      next.box.overlaps(previous.box) >= .3f
    if (!same) {
      cancel(signals)
      if (subjectTicket != null || subjectSpoken) signals.add(Signal.CancelSpeech)
      tickets.clear()
      subjectTicket = null
      subjectSpoken = false
      seen = 0
      subject = next
      anchor = next?.box
    }
    if (next == null) {
      guide(if (observation.brightness < MIN_BRIGHTNESS) PhotoGuidance.LOW_LIGHT else PhotoGuidance.SEARCH,
        now, signals)
      return signals
    }
    subject = next
    seen++
    if (seen < 2) return signals
    if (!subjectSpoken && subjectTicket == null) {
      val ticket = ++serial
      subjectTicket = ticket
      tickets[ticket] = Speech.SUBJECT
      signals.add(Signal.Say(ticket, R.string.guided_photo_found, next.label))
    }
    val needed = guidance(next, observation, frontCamera)
    if (needed != null) {
      cancel(signals)
      anchor = next.box
      if (subjectSpoken) guide(needed, now, signals)
      return signals
    }
    if (!subjectSpoken) return signals
    if (anchor?.stableAgainst(next.box) != true) {
      cancel(signals)
      anchor = next.box
      guide(PhotoGuidance.STEADY, now, signals)
      return signals
    }
    if (stableSince == null) stableSince = now
    if (now - stableSince!! < SETTLE_MS) return signals
    val start = countdownSince
    if (start == null) {
      countdownSince = now
      phase = 2
      say(Speech.TWO, R.string.guided_photo_two, signals)
    } else if (phase == 2 && twoSpoken && now - start >= 1_000L) {
      phase = 1
      say(Speech.ONE, R.string.guided_photo_one, signals)
    } else if (phase == 1 && oneSpoken && now - start >= COUNTDOWN_MS) {
      captured = true
      tickets.clear()
      signals.add(Signal.Capture)
    }
    return signals
  }

  fun reset() {
    subject = null
    seen = 0
    subjectTicket = null
    subjectSpoken = false
    tickets.clear()
    lastFrame = Long.MIN_VALUE
    anchor = null
    stableSince = null
    countdownSince = null
    phase = 0
    twoSpoken = false
    oneSpoken = false
    lastGuide = null
    lastGuideAt = Long.MIN_VALUE
    captured = false
  }

  private fun say(kind: Speech, message: Int, signals: MutableList<Signal>) {
    val ticket = ++serial
    tickets[ticket] = kind
    signals.add(Signal.Say(ticket, message))
  }

  private fun cancel(signals: MutableList<Signal>) {
    if (countdownSince != null) signals.add(Signal.CancelCountdown)
    countdownSince = null
    stableSince = null
    phase = 0
    twoSpoken = false
    oneSpoken = false
    tickets.entries.removeAll { it.value != Speech.SUBJECT }
  }

  private fun guide(value: PhotoGuidance, now: Long, signals: MutableList<Signal>) {
    if (value != lastGuide || lastGuideAt == Long.MIN_VALUE || now - lastGuideAt >= 3_500L) {
      lastGuide = value
      lastGuideAt = now
      signals.add(Signal.Guide(value))
    }
  }

  companion object {
    const val COUNTDOWN_MS = 2_000L
    const val MAX_FRAME_GAP_MS = 900L
    const val MIN_BRIGHTNESS = 42f
    const val MIN_SHARPNESS = 35f
    private const val SETTLE_MS = 300L

    fun select(subjects: List<PhotoSubject>): PhotoSubject? = subjects
      .filter { it.box.valid && it.confidence.isFinite() && it.confidence >= .55f }
      .minWithOrNull(compareBy<PhotoSubject> { it.box.centerDistance }
        .thenBy { if (it.kind == PhotoSubjectKind.FACE) 0 else 1 }
        .thenByDescending { it.confidence })

    fun guidance(subject: PhotoSubject, frame: PhotoObservation, frontCamera: Boolean): PhotoGuidance? {
      if (!frame.brightness.isFinite() || frame.brightness < MIN_BRIGHTNESS) return PhotoGuidance.LOW_LIGHT
      val box = subject.box
      if (box.width > .9f || box.height > .9f) return PhotoGuidance.AWAY
      if (box.width < .16f || box.height < .18f) return PhotoGuidance.CLOSER
      if (box.cx < .39f || box.left < .025f) return if (frontCamera) PhotoGuidance.RIGHT else PhotoGuidance.LEFT
      if (box.cx > .61f || box.right > .975f) return if (frontCamera) PhotoGuidance.LEFT else PhotoGuidance.RIGHT
      if (box.cy < .39f || box.top < .025f) return PhotoGuidance.UP
      if (box.cy > .61f || box.bottom > .975f) return PhotoGuidance.DOWN
      when (subject.documentState) {
        DocumentAlignmentState.HOLD_FLAT -> return PhotoGuidance.FLAT
        DocumentAlignmentState.MOVE_CLOSER -> return PhotoGuidance.CLOSER
        DocumentAlignmentState.MOVE_AWAY -> return PhotoGuidance.AWAY
        DocumentAlignmentState.MOVE_LEFT -> return if (frontCamera) PhotoGuidance.RIGHT else PhotoGuidance.LEFT
        DocumentAlignmentState.MOVE_RIGHT -> return if (frontCamera) PhotoGuidance.LEFT else PhotoGuidance.RIGHT
        DocumentAlignmentState.MOVE_UP -> return PhotoGuidance.UP
        DocumentAlignmentState.MOVE_DOWN -> return PhotoGuidance.DOWN
        DocumentAlignmentState.BLURRY -> return PhotoGuidance.BLUR
        DocumentAlignmentState.LOW_LIGHT -> return PhotoGuidance.LOW_LIGHT
        DocumentAlignmentState.NO_PAPER -> return PhotoGuidance.SEARCH
        else -> Unit
      }
      if (!subject.facingCamera) return PhotoGuidance.FACE_FORWARD
      if (!frame.sharpness.isFinite() || frame.sharpness < MIN_SHARPNESS) return PhotoGuidance.BLUR
      if (!frame.movement.isFinite() || frame.movement > 12f) return PhotoGuidance.STEADY
      if (!frame.focusReady) return PhotoGuidance.FOCUS
      return null
    }
  }
}
