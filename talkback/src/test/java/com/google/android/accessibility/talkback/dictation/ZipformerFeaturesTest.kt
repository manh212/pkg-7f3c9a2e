package com.google.android.accessibility.talkback.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerFeaturesTest {
  @Test fun emptySamplesHaveNoFrames() {
    assertEquals(0, ZipformerFeatures.compute(FloatArray(0)).size)
  }

  @Test fun oneSecondHasAHundredFramesOfEightyBins() {
    val features = ZipformerFeatures.compute(FloatArray(16_000))
    assertEquals(100, features.size)
    assertTrue(features.all { it.size == 80 })
  }

  @Test fun frameCountFollowsTheTenMillisecondShift() {
    assertEquals(10, ZipformerFeatures.compute(FloatArray(1_600)).size)
  }

  @Test fun silenceSitsAtTheLogFloor() {
    val features = ZipformerFeatures.compute(FloatArray(16_000))
    assertTrue(features.all { row -> row.all { it > -16.0f && it < -15.9f } })
  }
}
