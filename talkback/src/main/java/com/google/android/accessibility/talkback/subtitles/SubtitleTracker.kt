package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SubtitleLine(val text: String, val bounds: Rect)

// Do not confuse an OCR result containing unrelated interface text with a subtitle.
internal enum class SubtitleFrameStatus {
  NO_TEXT, NO_SUBTITLE_CANDIDATE, WAITING_FOR_STABILITY, DUPLICATE, READY
}
internal data class SubtitleFrameObservation(
  val text: String?,
  val status: SubtitleFrameStatus,
  val candidateLines: Int = 0,
  val regionReleased: Boolean = false,
)

internal class SubtitleTracker(
  private val automatic: Boolean,
  private val fastOcr: Boolean = false,
) {
  var lockedBounds: Rect? = null
    private set
  var visible = false
    private set
  private var candidateBounds: Rect? = null
  private var candidateFrames = 0
  private var candidateText = ""
  private var textFrames = 0
  private var previousText = ""
  private var previousLines = emptyList<String>()
  private var blankFrames = 0
  private var missingInLockedRegion = 0
  private var unchangedFrames = 0
  private var avoidedBounds: Rect? = null
  private var avoidanceFrames = 0

  fun resize(previous: Rect, next: Rect) {
    lockedBounds = lockedBounds?.let { resizeBounds(it, previous, next) }
    avoidedBounds = avoidedBounds?.let { resizeBounds(it, previous, next) }
    candidateBounds = null
    candidateFrames = 0
  }

  fun reset() {
    lockedBounds = null
    candidateBounds = null
    candidateFrames = 0
    candidateText = ""
    textFrames = 0
    previousText = ""
    previousLines = emptyList()
    blankFrames = 0
    missingInLockedRegion = 0
    unchangedFrames = 0
    avoidedBounds = null
    avoidanceFrames = 0
    visible = false
  }

  fun observe(input: List<SubtitleLine>, viewport: Rect): String? =
    observeFrame(input, viewport).text

  fun observeFrame(
    input: List<SubtitleLine>,
    viewport: Rect,
    fastText: Boolean = fastOcr,
  ): SubtitleFrameObservation {
    if (avoidanceFrames > 0) avoidanceFrames-- else avoidedBounds = null
    var lines = input.filter { it.text.isNotBlank() && !it.bounds.isEmpty }
      .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    val rawTextPresent = lines.isNotEmpty()

    if (automatic && lockedBounds == null) {
      val group = selectCandidate(lines, viewport)
      if (group.isEmpty()) return noCandidate(rawTextPresent)
      val box = union(group)
      val previous = candidateBounds
      candidateFrames = if (previous != null && near(previous, box)) candidateFrames + 1 else 1
      candidateBounds = box
      val strongVideoCandidate = fastOcr &&
        box.centerY() >= viewport.top + viewport.height() * 55 / 100 &&
        group.sumOf { it.text.length } >= 4
      if (candidateFrames < if (strongVideoCandidate) 1 else 2) {
        return SubtitleFrameObservation(null, SubtitleFrameStatus.WAITING_FOR_STABILITY, group.size)
      }
      val height = max(viewport.height() * 18 / 100, box.height() * 3)
      lockedBounds = Rect(
        viewport.left + viewport.width() * 4 / 100,
        max(viewport.top, box.centerY() - height / 2),
        viewport.right - viewport.width() * 4 / 100,
        min(viewport.bottom, box.centerY() + height / 2),
      )
    }

    lockedBounds?.let { box ->
      lines = lines.filter { box.contains(it.bounds.centerX(), it.bounds.centerY()) }
    }
    // Reject noise and oversized interface text even after locking. Short one-character
    // captions are permitted once the subtitle region is established.
    lines = lines.filter { line ->
      val normalized = normalize(line.text)
      normalized.any { it.isLetter() } &&
        line.bounds.height() <= max(1, viewport.height() / 6) &&
        (!automatic || lockedBounds != null || normalized.length >= 2)
    }
    if (lines.isEmpty()) return noCandidate(rawTextPresent)

    blankFrames = 0
    missingInLockedRegion = 0
    visible = true
    val text = lines.joinToString("\n") { it.text.trim() }
    val key = normalize(text)
    if (key.isEmpty()) return noCandidate(rawTextPresent)
    if (similar(candidateText, key)) textFrames++
    else {
      candidateText = key
      textFrames = 1
    }
    if (textFrames < if (fastText) 1 else 2) {
      return SubtitleFrameObservation(null, SubtitleFrameStatus.WAITING_FOR_STABILITY, lines.size)
    }

    if (similar(previousText, key)) {
      unchangedFrames++
      // A static UI label is not a video subtitle stream. Avoid remaining locked on it.
      if (automatic && unchangedFrames >= 14) {
        releaseAutoRegion()
        return SubtitleFrameObservation(null, SubtitleFrameStatus.NO_SUBTITLE_CANDIDATE,
          regionReleased = true)
      }
      return SubtitleFrameObservation(null, SubtitleFrameStatus.DUPLICATE, lines.size)
    }
    unchangedFrames = 0
    val keys = lines.map { normalize(it.text) }
    val fresh = lines.filterIndexed { index, _ -> keys[index] !in previousLines }
    val spoken = if (fresh.isEmpty()) "" else fresh.joinToString(" ") { it.text.trim() }
    val previousWords = previousText.split(' ').filter { it.isNotEmpty() }
    val words = normalize(spoken).split(' ').filter { it.isNotEmpty() }
    val rawWords = spoken.split(Regex("\\s+"))
    var overlap = 0
    if (fresh.size == lines.size) {
      for (count in 2..min(previousWords.size, words.size)) {
        if (previousWords.takeLast(count) == words.take(count)) overlap = count
      }
    }
    previousText = key
    previousLines = keys
    val delivered = (if (overlap > 0 && rawWords.size == words.size)
      rawWords.drop(overlap).joinToString(" ") else spoken).takeIf { it.isNotBlank() }
    return SubtitleFrameObservation(delivered, if (delivered == null)
      SubtitleFrameStatus.DUPLICATE else SubtitleFrameStatus.READY, lines.size)
  }

  private fun noCandidate(rawTextPresent: Boolean): SubtitleFrameObservation {
    visible = false
    candidateText = ""
    textFrames = 0
    candidateFrames = 0
    candidateBounds = null
    unchangedFrames = 0
    if (++blankFrames >= 2) {
      previousText = ""
      previousLines = emptyList()
    }
    if (lockedBounds != null && automatic && ++missingInLockedRegion >= 8) {
      releaseAutoRegion()
      return SubtitleFrameObservation(null,
        if (rawTextPresent) SubtitleFrameStatus.NO_SUBTITLE_CANDIDATE else SubtitleFrameStatus.NO_TEXT,
        regionReleased = true)
    }
    return SubtitleFrameObservation(null,
      if (rawTextPresent) SubtitleFrameStatus.NO_SUBTITLE_CANDIDATE else SubtitleFrameStatus.NO_TEXT)
  }

  private fun releaseAutoRegion() {
    avoidedBounds = lockedBounds?.let(::Rect)
    avoidanceFrames = 12
    lockedBounds = null
    visible = false
    missingInLockedRegion = 0
    unchangedFrames = 0
    candidateBounds = null
    candidateFrames = 0
    candidateText = ""
    textFrames = 0
  }

  private fun selectCandidate(lines: List<SubtitleLine>, screen: Rect): List<SubtitleLine> {
    val eligible = lines.filter { line ->
      val text = normalize(line.text)
      val isAvoided = avoidanceFrames > 0 &&
        avoidedBounds?.contains(line.bounds.centerX(), line.bounds.centerY()) == true
      !isAvoided && text.length >= 2 && text.any { it.isLetter() } &&
        line.bounds.height() <= max(1, screen.height() / 6) &&
        abs(line.bounds.centerX() - screen.centerX()) < screen.width() * 42 / 100
    }
    val groups = mutableListOf<MutableList<SubtitleLine>>()
    for (line in eligible) {
      val group = groups.lastOrNull()
      val last = group?.lastOrNull()
      if (group != null && last != null &&
        line.bounds.top - last.bounds.bottom <= max(line.bounds.height(), last.bounds.height()) &&
        union(group).height() + line.bounds.height() <= screen.height() / 4) {
        group.add(line)
      } else {
        groups.add(mutableListOf(line))
      }
    }
    return groups.maxByOrNull { group ->
      val box = union(group)
      val lower = if (box.centerY() > screen.top + screen.height() * 55 / 100) 40 else 0
      lower + min(40, group.sumOf { it.text.length }) -
        abs(box.centerX() - screen.centerX()) * 20 / max(1, screen.width())
    } ?: emptyList()
  }

  private fun union(lines: List<SubtitleLine>): Rect =
    Rect(lines.first().bounds).also { box -> lines.drop(1).forEach { box.union(it.bounds) } }

  private fun near(a: Rect, b: Rect): Boolean =
    abs(a.centerY() - b.centerY()) <= max(a.height(), b.height()) &&
      abs(a.centerX() - b.centerX()) <= max(a.width(), b.width()) / 2

  companion object {
    internal fun resizeBounds(box: Rect, old: Rect, next: Rect): Rect = Rect(
      next.left + (box.left - old.left) * next.width() / max(1, old.width()),
      next.top + (box.top - old.top) * next.height() / max(1, old.height()),
      next.left + (box.right - old.left) * next.width() / max(1, old.width()),
      next.top + (box.bottom - old.top) * next.height() / max(1, old.height()))

    internal fun normalize(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal fun similar(a: String, b: String): Boolean {
      if (a == b) return true
      if (a.length < 24 || b.length < 24 || abs(a.length - b.length) > 1) return false
      var left = 0
      var right = 0
      var errors = 0
      while (left < a.length && right < b.length) {
        if (a[left] == b[right]) { left++; right++; continue }
        if (++errors > 1) return false
        if (a.length >= b.length) left++
        if (b.length >= a.length) right++
      }
      return errors + (a.length - left) + (b.length - right) <= 1
    }
  }
}
