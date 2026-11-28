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
    val area = max(0f, min(right, other.right) - max(left, other.left)) *
      max(0f, min(bottom, other.bottom) - max(top, other.top))
    return area / max(.00001f, width * height + other.width * other.height - area)
  }
  fun stableAgainst(other: PhotoBox): Boolean =
    abs(cx - other.cx) <= .1f && abs(cy - other.cy) <= .1f &&
      abs(width - other.width) <= .18f && abs(height - other.height) <= .18f
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
  val members: List<PhotoSubject> = emptyList(),
) {
  val regions get() = members.ifEmpty { listOf(this) }
  val faceCount get() = if (kind == PhotoSubjectKind.FACE) regions.size else 0
  val focusBox get() = regions.minBy { it.box.centerDistance }.box
}

internal data class PhotoRegionQuality(
  val box: PhotoBox,
  val brightness: Float,
  val sharpness: Float,
  val movement: Float,
  val pixelWidth: Int,
  val pixelHeight: Int,
)

internal data class PhotoObservation(
  val subjects: List<PhotoSubject>,
  val brightness: Float,
  val sharpness: Float,
  val movement: Float,
  val focusReady: Boolean,
  val regions: List<PhotoRegionQuality> = emptyList(),
  val imageWidth: Int = 0,
  val imageHeight: Int = 0,
  val ageMs: Long = 0,
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
  PROCESSING(R.string.guided_photo_processing),
}

internal class GuidedPhotoController {
  sealed class Signal {
    data class Say(val ticket: Int, val message: Int, val argument: String? = null, val number: Int? = null) : Signal()
    data class Guide(val guidance: PhotoGuidance) : Signal()
    data object CancelSpeech : Signal()
    data object CancelCountdown : Signal()
    data object Capture : Signal()
  }
  private enum class Speech { SUBJECT, TWO, ONE }
  private var subject: PhotoSubject? = null
  private var candidate: PhotoSubject? = null
  private var candidateSince = 0L
  private var candidateFrames = 0
  private var missingSince: Long? = null
  private var subjectTicket: Int? = null
  private var subjectSpoken = false
  private var serial = 0
  private val tickets = mutableMapOf<Int, Speech>()
  private var lastFrame = Long.MIN_VALUE
  private var stableSince: Long? = null
  private var countdownSince: Long? = null
  private var phase = 0
  private var twoSpoken = false
  private var oneSpoken = false
  private var problemSince: Long? = null
  private var pendingGuide: PhotoGuidance? = null
  private var pendingGuideSince = 0L
  private var lastGuide: PhotoGuidance? = null
  private var lastGuideAt = Long.MIN_VALUE
  private var captured = false
  var reason: PhotoGuidance? = PhotoGuidance.SEARCH
    private set

  fun started(ticket: Int, now: Long) {
    if (tickets[ticket] == Speech.TWO && phase == 2 && countdownSince == null) countdownSince = now
  }

  fun spoken(ticket: Int, completed: Boolean) {
    val kind = tickets.remove(ticket) ?: return
    when (kind) {
      Speech.SUBJECT -> { subjectTicket = null; subjectSpoken = completed }
      Speech.TWO -> twoSpoken = completed
      Speech.ONE -> oneSpoken = completed
    }
    if (!completed) clearCountdown()
  }

  fun observe(frame: PhotoObservation, now: Long, frontCamera: Boolean = false): List<Signal> {
    if (captured || (lastFrame != Long.MIN_VALUE && now <= lastFrame)) return emptyList()
    val signals = mutableListOf<Signal>()
    if (lastFrame != Long.MIN_VALUE && now - lastFrame > MAX_FRAME_GAP_MS) cancel(signals)
    lastFrame = now
    if (frame.ageMs > MAX_ANALYSIS_AGE_MS) {
      problem(PhotoGuidance.PROCESSING, now, signals)
      return signals
    }
    val next = select(frame.subjects, subject)
    val current = subject
    if (current == null || next == null || !sameTarget(current, next)) {
      if (missingSince == null) missingSince = now
      if (next != null) {
        if (candidate == null || !sameTarget(candidate!!, next)) {
          candidate = next
          candidateSince = now
          candidateFrames = 1
        } else {
          candidate = next
          candidateFrames++
        }
        val confirmed = candidateFrames >= 2 && now - candidateSince >= CONFIRM_MS
        if (confirmed && (current == null || now - missingSince!! >= LOST_GRACE_MS)) {
          cancel(signals)
          if (subjectTicket != null || subjectSpoken) signals.add(Signal.CancelSpeech)
          tickets.clear()
          subjectTicket = null
          subjectSpoken = false
          subject = next
          candidate = null
          candidateFrames = 0
          missingSince = null
          stableSince = null
          problemSince = null
          pendingGuide = null
        } else {
          if (current != null && now - missingSince!! >= LOST_GRACE_MS) cancel(signals)
          reason = PhotoGuidance.SEARCH
          return signals
        }
      } else {
        reason = PhotoGuidance.SEARCH
        if (now - missingSince!! >= LOST_GRACE_MS) {
          cancel(signals)
          guide(PhotoGuidance.SEARCH, now, signals)
        }
        return signals
      }
    } else {
      subject = next
      missingSince = null
      candidate = null
      candidateFrames = 0
    }
    val selected = subject ?: return signals
    if (!subjectSpoken && subjectTicket == null) {
      val ticket = ++serial
      tickets[ticket] = Speech.SUBJECT
      subjectTicket = ticket
      signals.add(if (selected.faceCount > 1)
        Signal.Say(ticket, R.string.guided_photo_found_faces, number = selected.faceCount)
        else Signal.Say(ticket, R.string.guided_photo_found, selected.label))
    }
    val needed = guidance(selected, frame, frontCamera)
    if (needed != null) {
      problem(needed, now, signals)
      return signals
    }
    reason = null
    problemSince = null
    pendingGuide = null
    if (!subjectSpoken) return signals
    if (stableSince == null) stableSince = now
    if (now - stableSince!! < SETTLE_MS) return signals
    if (phase == 0) {
      phase = 2
      signals.add(Signal.CancelSpeech)
      say(Speech.TWO, R.string.guided_photo_two, signals)
      return signals
    }
    val start = countdownSince ?: return signals
    if (phase == 2 && twoSpoken && now - start >= 1_000L) {
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
    candidate = null
    candidateFrames = 0
    missingSince = null
    subjectTicket = null
    subjectSpoken = false
    tickets.clear()
    lastFrame = Long.MIN_VALUE
    stableSince = null
    problemSince = null
    pendingGuide = null
    lastGuide = null
    lastGuideAt = Long.MIN_VALUE
    captured = false
    reason = PhotoGuidance.SEARCH
    clearCountdown()
  }

  private fun say(kind: Speech, message: Int, signals: MutableList<Signal>) {
    val ticket = ++serial
    tickets[ticket] = kind
    signals.add(Signal.Say(ticket, message))
  }

  private fun clearCountdown() {
    countdownSince = null
    stableSince = null
    phase = 0
    twoSpoken = false
    oneSpoken = false
    tickets.entries.removeAll { it.value != Speech.SUBJECT }
  }

  private fun cancel(signals: MutableList<Signal>) {
    if (phase != 0) signals.add(Signal.CancelCountdown)
    clearCountdown()
  }

  private fun problem(value: PhotoGuidance, now: Long, signals: MutableList<Signal>) {
    reason = value
    if (problemSince == null) problemSince = now
    if (now - problemSince!! >= BAD_GRACE_MS) cancel(signals)
    if (subjectSpoken || value == PhotoGuidance.PROCESSING) guide(value, now, signals)
  }

  private fun guide(value: PhotoGuidance, now: Long, signals: MutableList<Signal>) {
    if (pendingGuide != value) {
      pendingGuide = value
      pendingGuideSince = now
    }
    if (now - pendingGuideSince < GUIDE_SETTLE_MS) return
    val interval = if (value == lastGuide) 5_000L else 1_800L
    if (lastGuideAt == Long.MIN_VALUE || now - lastGuideAt >= interval) {
      lastGuide = value
      lastGuideAt = now
      signals.add(Signal.Guide(value))
    }
  }

  companion object {
    const val COUNTDOWN_MS = 2_000L
    const val MAX_FRAME_GAP_MS = 3_000L
    const val MAX_ANALYSIS_AGE_MS = 2_000L
    const val MIN_BRIGHTNESS = 15f
    const val MIN_SHARPNESS = 6f
    private const val CONFIRM_MS = 250L
    private const val SETTLE_MS = 250L
    private const val LOST_GRACE_MS = 650L
    private const val BAD_GRACE_MS = 450L
    private const val GUIDE_SETTLE_MS = 450L

    fun select(subjects: List<PhotoSubject>, previous: PhotoSubject? = null): PhotoSubject? {
      val valid = subjects.filter { it.box.valid && it.confidence.isFinite() && it.confidence >= .5f }
      val faces = valid.filter { it.kind == PhotoSubjectKind.FACE }
      if (faces.isNotEmpty()) {
        return faces.first().copy(key = "faces", box = PhotoBox(
          faces.minOf { it.box.left }, faces.minOf { it.box.top },
          faces.maxOf { it.box.right }, faces.maxOf { it.box.bottom }),
          members = faces.sortedBy { it.box.cx })
      }
      previous?.takeIf { it.kind != PhotoSubjectKind.FACE }?.let { old ->
        valid.filter { it.key == old.key && it.box.overlaps(old.box) >= .2f }
          .maxByOrNull { it.box.overlaps(old.box) }?.let { return it }
      }
      return valid.minWithOrNull(compareBy<PhotoSubject> { if (it.kind == PhotoSubjectKind.DOCUMENT) 1 else 0 }
        .thenBy { it.box.centerDistance }.thenByDescending { it.confidence })
    }

    private fun sameTarget(a: PhotoSubject, b: PhotoSubject): Boolean {
      if (a.kind != b.kind) return false
      if (a.kind != PhotoSubjectKind.FACE) return a.key == b.key && a.box.overlaps(b.box) >= .2f
      if (a.faceCount != b.faceCount) return false
      val remaining = b.regions.toMutableList()
      return a.regions.all { face ->
        val match = remaining.maxByOrNull { it.box.overlaps(face.box) } ?: return@all false
        if (match.box.overlaps(face.box) < .2f) false else { remaining.remove(match); true }
      }
    }

    fun guidance(subject: PhotoSubject, frame: PhotoObservation, frontCamera: Boolean): PhotoGuidance? {
      val box = subject.box
      val left = box.left < .008f
      val right = box.right > .992f
      val up = box.top < .008f
      val down = box.bottom > .992f
      if ((left && right) || (up && down)) return PhotoGuidance.AWAY
      if (left) return if (frontCamera) PhotoGuidance.RIGHT else PhotoGuidance.LEFT
      if (right) return if (frontCamera) PhotoGuidance.LEFT else PhotoGuidance.RIGHT
      if (up) return PhotoGuidance.UP
      if (down) return PhotoGuidance.DOWN
      val qualities = subject.regions.mapNotNull { member ->
        frame.regions.filter { it.box.overlaps(member.box) >= .5f }
          .maxByOrNull { it.box.overlaps(member.box) }
      }
      if (frame.regions.isNotEmpty() && qualities.size != subject.regions.size) return PhotoGuidance.PROCESSING
      val dark = if (qualities.isEmpty()) frame.brightness < MIN_BRIGHTNESS
        else qualities.any { !it.brightness.isFinite() || it.brightness < MIN_BRIGHTNESS }
      if (dark || !frame.brightness.isFinite()) return PhotoGuidance.LOW_LIGHT
      if (qualities.any { it.pixelWidth < 24 || it.pixelHeight < 24 }) return PhotoGuidance.CLOSER
      val blur = if (qualities.isEmpty()) frame.sharpness < MIN_SHARPNESS
        else qualities.any { !it.sharpness.isFinite() || it.sharpness < MIN_SHARPNESS }
      if (blur || !frame.sharpness.isFinite()) return PhotoGuidance.BLUR
      val motion = if (qualities.isEmpty()) frame.movement > 28f
        else qualities.any { !it.movement.isFinite() || it.movement > 28f }
      if (motion || !frame.movement.isFinite()) return PhotoGuidance.STEADY
      return null
    }
  }
}
