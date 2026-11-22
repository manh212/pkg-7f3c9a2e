package com.google.android.accessibility.talkback.imagecaption

import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class VietOcrTokensTest {
  private fun scores(token: Int, confidence: Double = 0.95) = FloatArray(7) { -20f }.apply {
    this[token] = ln(confidence).toFloat()
  }

  @Test fun vietnameseAccentsAndConfidenceSurviveDecoding() {
    val tokens = VietOcrTokens("áộ1", 4, 2)
    assertTrue(tokens.accept(scores(4)))
    assertTrue(tokens.accept(scores(5)))
    assertFalse(tokens.accept(scores(2)))
    assertEquals("áộ", tokens.result()!!.value)
    assertEquals(0.95, tokens.result()!!.confidence, 0.001)
  }

  @Test fun unfinishedUnknownAndNonFiniteOutputsAreRejected() {
    val unfinished = VietOcrTokens("áộ1", 4, 2)
    unfinished.accept(scores(4))
    assertNull(unfinished.result())
    val unknown = VietOcrTokens("áộ1", 4, 2)
    assertFalse(unknown.accept(scores(3)))
    assertNull(unknown.result())
    val invalid = VietOcrTokens("áộ1", 4, 2)
    assertFalse(invalid.accept(FloatArray(7) { Float.NaN }))
    assertNull(invalid.result())
  }

  @Test fun lineResizePreservesTheModelWidthContract() {
    assertEquals(32, VietOcrTokens.inputWidth(10, 32))
    assertEquals(100, VietOcrTokens.inputWidth(100, 32))
    assertEquals(110, VietOcrTokens.inputWidth(101, 32))
    assertEquals(512, VietOcrTokens.inputWidth(2000, 32))
  }
}
