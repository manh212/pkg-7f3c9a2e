package com.google.android.accessibility.talkback.recognition

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

internal class CameraTextTracker(private val requireConfirmation: Boolean = true) {
  private var previous = ""
  private var previousAt = Long.MIN_VALUE

  fun observe(text: String, nowMs: Long): String? {
    val normalized = normalize(text)
    if (normalized.isEmpty()) {
      reset()
      return null
    }
    val consistent = previous.isNotEmpty() && previousAt != Long.MIN_VALUE &&
      nowMs - previousAt in 0L..60_000L && similar(previous, normalized)
    previous = normalized
    previousAt = nowMs
    return if (!requireConfirmation || consistent) text else null
  }

  fun reset() {
    previous = ""
    previousAt = Long.MIN_VALUE
  }

  companion object {
    private fun normalize(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal fun similar(first: String, second: String): Boolean {
      if (first == second) return true
      if (minOf(first.length, second.length) < 20) return false
      val numbers = Regex("[0-9]+")
      val firstNumbers = numbers.findAll(first).map { it.value }.toList()
      if (firstNumbers != numbers.findAll(second).map { it.value }.toList()) return false
      val left = first.split(' ').filter(String::isNotEmpty)
      val right = second.split(' ').filter(String::isNotEmpty)
      var row = IntArray(right.size + 1)
      for (word in left) {
        val next = IntArray(right.size + 1)
        for (j in right.indices) next[j + 1] =
          if (word == right[j]) row[j] + 1 else max(next[j], row[j + 1])
        row = next
      }
      return row.last().toDouble() / max(left.size, right.size) >= 0.9
    }
  }
}
