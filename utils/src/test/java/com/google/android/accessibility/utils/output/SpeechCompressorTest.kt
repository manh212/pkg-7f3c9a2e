/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils.output

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCompressorTest {
  private val rate = 48_000

  @Test
  fun damagedSettingsAreTheDefaults() {
    assertEquals(SpeechCompressor.defaults(), SpeechCompressor.parse(null))
    assertEquals(SpeechCompressor.defaults(), SpeechCompressor.parse(""))
    assertEquals(SpeechCompressor.defaults(), SpeechCompressor.parse("1,2,3"))
    assertEquals(
      SpeechCompressor.defaults(),
      SpeechCompressor.parse("0,0,0,0,0,20,20,20,20,20,10,200,x"),
    )
  }

  @Test
  fun valuesAreKeptInRange() {
    val settings = SpeechCompressor.parse("-99,0,0,0,0,999,1,20,20,20,0,9999,-5")
    assertEquals(SpeechCompressor.MIN_THRESHOLD_DB, settings.thresholds[0])
    assertEquals(SpeechCompressor.MAX_RATIO_X10, settings.ratios[0])
    assertEquals(SpeechCompressor.MIN_RATIO_X10, settings.ratios[1])
    assertEquals(SpeechCompressor.MIN_ATTACK_MS, settings.attackMs)
    assertEquals(SpeechCompressor.MAX_RELEASE_MS, settings.releaseMs)
    assertEquals(0, settings.makeupDb)
  }

  @Test
  fun formatParsesBack() {
    for (preset in SpeechCompressor.Preset.entries) {
      val settings = preset.settings()
      assertEquals(settings, SpeechCompressor.parse(SpeechCompressor.format(settings)))
    }
  }

  @Test
  fun presetsAreFound() {
    for (preset in SpeechCompressor.Preset.entries) {
      assertEquals(preset, SpeechCompressor.presetOf(preset.settings()))
      assertEquals(preset, SpeechCompressor.presetFor(preset.value))
    }
    val custom = SpeechCompressor.defaults().also { it.thresholds[2] = -20 }
    assertNull(SpeechCompressor.presetOf(custom))
    assertTrue(SpeechCompressor.defaults().isNoOp())
    assertFalse(SpeechCompressor.Preset.GENTLE.settings().isNoOp())
  }

  @Test
  fun keys() {
    assertTrue(SpeechCompressor.isKey(SpeechCompressor.PREF_DEFAULT))
    assertTrue(SpeechCompressor.isKey(SpeechCompressor.PREF_DEFAULT_ON))
    assertTrue(SpeechCompressor.isKey(SpeechCompressor.key("3")))
    assertTrue(SpeechCompressor.isKey(SpeechCompressor.onKey("3")))
    assertTrue(VoiceProfiles.isProfileKey(SpeechCompressor.key("3")))
    assertFalse(SpeechCompressor.isKey(VoiceProfiles.key("3", VoiceProfiles.RATE)))
    assertFalse(SpeechCompressor.isKey(SpeechEqualizer.key("3")))
    assertFalse(SpeechCompressor.isKey(null))
  }

  @Test
  fun nothingCompressedPassesSpeechUnchanged() {
    for (frequency in doubleArrayOf(100.0, 1000.0, 5000.0)) {
      val samples = sine(frequency, amplitude = 0.1f, frames = rate * 2, atRate = rate)
      val input = rms(samples, samples.size / 2)
      SpeechCompressor.Filter(SpeechCompressor.defaults(), rate, 1).process(samples)
      assertEquals("at $frequency Hz", 0.0, 20 * log10(rms(samples, samples.size / 2) / input), 0.05)
    }
  }

  @Test
  fun bandCompressesItsOwnFrequencies() {
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(0, 0, -24, 0, 0), intArrayOf(20, 20, 40, 20, 20), 5, 150, 0)
    val measured = changeDb(settings, 1500.0, amplitude = 0.5f, atRate = rate)
    assertTrue("was $measured dB", measured < -4.0 && measured > -9.0)
  }

  @Test
  fun bandLeavesOtherFrequenciesAlone() {
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(0, 0, -24, 0, 0), intArrayOf(20, 20, 40, 20, 20), 5, 150, 0)
    assertEquals(0.0, changeDb(settings, 100.0, amplitude = 0.5f, atRate = rate), 0.3)
    assertEquals(0.0, changeDb(settings, 400.0, amplitude = 0.5f, atRate = rate), 0.3)
  }

  @Test
  fun lowestBandCompresses() {
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(-24, 0, 0, 0, 0), intArrayOf(40, 20, 20, 20, 20), 5, 150, 0)
    val measured = changeDb(settings, 100.0, amplitude = 0.5f, atRate = rate)
    assertTrue("was $measured dB", measured < -10.0 && measured > -14.0)
  }

  @Test
  fun topBandStillCompressesAtALowRate() {
    // At 8 kHz the 6 kHz crossover cannot be built, so everything above 2.5 kHz is the
    // fourth band's, and that band must still compress it.
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(0, 0, 0, -24, 0), intArrayOf(20, 20, 20, 40, 20), 5, 150, 0)
    val measured = changeDb(settings, 3000.0, amplitude = 0.5f, atRate = 8_000)
    assertTrue("was $measured dB", measured < -5.0)
  }

  @Test
  fun makeupIsLimited() {
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(0, 0, 0, 0, 0), intArrayOf(20, 20, 20, 20, 20), 10, 200, 6)
    val samples = sine(500.0, amplitude = 0.9f, frames = rate, atRate = rate)
    SpeechCompressor.Filter(settings, rate, 1).process(samples)
    assertTrue(samples.all { abs(it) <= 0.98f + 1e-4f })
  }

  @Test
  fun silenceStaysSilent() {
    val samples = FloatArray(rate)
    SpeechCompressor.Filter(SpeechCompressor.Preset.EVEN.settings(), rate, 1).process(samples)
    assertTrue(samples.all { it == 0f })
  }

  @Test
  fun silentChannelStaysSilent() {
    val settings =
      SpeechCompressor.Settings(
        intArrayOf(0, 0, -24, 0, 0), intArrayOf(20, 20, 40, 20, 20), 5, 150, 0)
    val left = sine(1500.0, amplitude = 0.5f, frames = rate, atRate = rate)
    val stereo = FloatArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else 0f }
    SpeechCompressor.Filter(settings, rate, 2).process(stereo)
    assertTrue((0 until rate).all { stereo[it * 2 + 1] == 0f })
  }

  private fun changeDb(
    settings: SpeechCompressor.Settings,
    frequency: Double,
    amplitude: Float,
    atRate: Int,
  ): Double {
    val samples = sine(frequency, amplitude, frames = atRate * 2, atRate = atRate)
    val input = rms(samples, samples.size / 2)
    SpeechCompressor.Filter(settings, atRate, 1).process(samples)
    return 20 * log10(rms(samples, samples.size / 2) / input)
  }

  private fun sine(frequency: Double, amplitude: Float, frames: Int, atRate: Int): FloatArray =
    FloatArray(frames) { (amplitude * sin(2 * PI * frequency * it / atRate)).toFloat() }

  private fun rms(samples: FloatArray, from: Int): Double {
    var sum = 0.0
    for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / (samples.size - from))
  }
}
