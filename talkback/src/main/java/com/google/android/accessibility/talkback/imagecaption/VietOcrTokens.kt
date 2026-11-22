package com.google.android.accessibility.talkback.imagecaption

import kotlin.math.exp

internal class VietOcrTokens(private val vocabulary: String, private val offset: Int, private val eos: Int) {
  data class Text(val value: String, val confidence: Double)
  private val text = StringBuilder()
  private var logConfidence = 0.0
  private var ended = false
  private var invalid = false
  var token = 1
    private set

  fun accept(logProbabilities: FloatArray): Boolean {
    if (ended) return false
    val next = logProbabilities.indices.maxByOrNull { logProbabilities[it] } ?: return false
    val score = logProbabilities[next]
    if (!score.isFinite() || score > 0.001f) {
      invalid = true
      ended = true
      return false
    }
    token = next
    if (next == eos) {
      ended = true
      return false
    }
    if (next - offset !in vocabulary.indices) {
      invalid = true
      ended = true
      return false
    }
    text.append(vocabulary[next - offset])
    logConfidence += score
    return true
  }

  fun result(): Text? {
    if (!ended || invalid || text.none { it.isLetterOrDigit() }) return null
    return Text(text.toString().trim(), exp(logConfidence / text.length).coerceIn(0.0, 1.0))
  }

  companion object {
    fun inputWidth(width: Int, height: Int): Int {
      require(width > 0 && height > 0)
      val scaled = (32L * width / height).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      return (((scaled.toLong() + 9) / 10) * 10).coerceIn(32, 512).toInt()
    }
  }
}
