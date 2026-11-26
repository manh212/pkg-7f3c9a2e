/*
 * Copyright 2026 Backtalk contributors
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

import android.media.AudioAttributes
import android.media.AudioManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLatencyAudioDeclickTest {
  @Test
  fun abruptStop_rampsSmoothlyToZeroWithoutLargeStep() {
    val sampleRate = 48_000
    val frames = 256
    val declicker = LowLatencyAudio.SpeechDeclicker(sampleRate)
    val sourceA = Any()

    // Burst 1: zero-mean speech-like sine wave (~468.75 Hz, 2.5 cycles across 256 frames -> ends near peak).
    val burst1 = FloatArray(frames * 2)
    for (i in 0 until frames) {
      val s = (0.6 * sin(2.0 * PI * 2.25 * (i + 1) / frames)).toFloat()
      burst1[i * 2] = s
      burst1[i * 2 + 1] = s
    }
    declicker.processBurst(burst1, frames, sourceA, frames, sourceEnded = false)
    val lastBurst1L = burst1[(frames - 1) * 2]
    assertTrue("Expected burst1 to end near peak, got $lastBurst1L", abs(lastBurst1L) > 0.4f)

    // Burst 2: stream abruptly stopped (source = null, speechWritten = 0).
    val burst2 = FloatArray(frames * 2)
    declicker.processBurst(burst2, frames, source = null, speechWritten = 0, sourceEnded = false)

    // Verify no abrupt step discontinuity between burst1's end and burst2's start, or within burst2.
    var prev = lastBurst1L
    var maxDelta = 0f
    for (i in 0 until frames) {
      val cur = burst2[i * 2]
      maxDelta = maxOf(maxDelta, abs(cur - prev))
      prev = cur
    }
    assertTrue("Expected smooth decay without click step, maxDelta=$maxDelta", maxDelta < 0.02f)
    // After a few silent bursts for the 20 Hz DC blocker to settle, output returns near 0.
    var tailBurst = burst2
    repeat(6) {
      val next = FloatArray(frames * 2)
      declicker.processBurst(next, frames, source = null, speechWritten = 0, sourceEnded = false)
      tailBurst = next
    }
    assertTrue("Expected tail near zero, got ${tailBurst[(frames - 1) * 2]}", abs(tailBurst[(frames - 1) * 2]) < 0.02f)
  }

  @Test
  fun midBurstEnd_decaysAtExactCutoffFrame() {
    val sampleRate = 48_000
    val frames = 256
    val declicker = LowLatencyAudio.SpeechDeclicker(sampleRate)
    val sourceA = Any()

    // Stream writes only 40 frames at 0.5f and ends mid-burst.
    val burst = FloatArray(frames * 2)
    for (i in 0 until 40) {
      burst[i * 2] = 0.5f
      burst[i * 2 + 1] = -0.5f
    }
    declicker.processBurst(burst, frames, sourceA, speechWritten = 40, sourceEnded = true)

    // Frame 40 should continue smoothly from frame 39 rather than dropping straight to 0.
    val stepAtCutoff = abs(burst[40 * 2] - burst[39 * 2])
    assertTrue("Expected smooth transition at mid-burst cutoff, step=$stepAtCutoff", stepAtCutoff < 0.02f)
  }

  @Test
  fun dcBlocker_attenuatesSteadyDcOffsetAndSettlesToZeroWhenIdle() {
    val sampleRate = 48_000
    val frames = 256
    val declicker = LowLatencyAudio.SpeechDeclicker(sampleRate)
    val source = Any()

    // Feed 50 bursts (~266 ms) of constant DC offset (+0.5f).
    var lastBurst = FloatArray(frames * 2)
    repeat(50) {
      val burst = FloatArray(frames * 2) { 0.5f }
      declicker.processBurst(burst, frames, source, frames, sourceEnded = false)
      lastBurst = burst
    }
    assertTrue(
      "Expected DC blocker to attenuate steady DC offset, got ${lastBurst[(frames - 1) * 2]}",
      abs(lastBurst[(frames - 1) * 2]) < 0.05f,
    )

    // After reset and idle bursts, output must stay exact 0f.
    declicker.reset()
    val idle = FloatArray(frames * 2)
    declicker.processBurst(idle, frames, source = null, speechWritten = 0, sourceEnded = false)
    assertEquals(0f, idle[0], 0f)
    assertEquals(0f, idle[(frames - 1) * 2 + 1], 0f)
  }

  @Test
  fun smoothstep_clampsAndIsMonotonic() {
    assertEquals(0f, LowLatencyAudio.smoothstep(-0.5f), 0f)
    assertEquals(0f, LowLatencyAudio.smoothstep(0f), 0f)
    assertEquals(0.5f, LowLatencyAudio.smoothstep(0.5f), 1e-6f)
    assertEquals(1f, LowLatencyAudio.smoothstep(1f), 0f)
    assertEquals(1f, LowLatencyAudio.smoothstep(2f), 0f)
  }

  @Test
  fun hasExternalPlayback_detectsRingtoneMediaAndIgnoresAccessibility() {
    // Accessibility and sonification alone do not trigger AAudio yield.
    org.junit.Assert.assertFalse(
      LowLatencyAudio.hasExternalPlayback(
        activeUsages = listOf(
          AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
          AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ),
        ownPlayingUsages = emptyList(),
        audioMode = AudioManager.MODE_NORMAL,
      )
    )
    // Ringtone preview (e.g. SoundPicker) triggers AAudio yield.
    assertTrue(
      LowLatencyAudio.hasExternalPlayback(
        activeUsages = listOf(
          AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
          AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        ),
        ownPlayingUsages = emptyList(),
        audioMode = AudioManager.MODE_NORMAL,
      )
    )
    // Own media track alone does not trigger yield, but a second external media track does.
    org.junit.Assert.assertFalse(
      LowLatencyAudio.hasExternalPlayback(
        activeUsages = listOf(AudioAttributes.USAGE_MEDIA),
        ownPlayingUsages = listOf(AudioAttributes.USAGE_MEDIA),
        audioMode = AudioManager.MODE_NORMAL,
      )
    )
    assertTrue(
      LowLatencyAudio.hasExternalPlayback(
        activeUsages = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_MEDIA),
        ownPlayingUsages = listOf(AudioAttributes.USAGE_MEDIA),
        audioMode = AudioManager.MODE_NORMAL,
      )
    )
    // Ringtone or call audio mode always counts as external playback.
    assertTrue(
      LowLatencyAudio.hasExternalPlayback(
        activeUsages = emptyList(),
        ownPlayingUsages = emptyList(),
        audioMode = AudioManager.MODE_RINGTONE,
      )
    )
  }
}
