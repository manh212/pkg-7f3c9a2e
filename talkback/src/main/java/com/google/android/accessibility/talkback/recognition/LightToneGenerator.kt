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

package com.google.android.accessibility.talkback.recognition

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

internal class LightToneGenerator {
  private var lastToneAtMs = -MIN_PULSE_INTERVAL_MS
  private var activeTrack: AudioTrack? = null

  fun playForLux(lux: Float, nowMs: Long) {
    if (nowMs - lastToneAtMs < MIN_PULSE_INTERVAL_MS) return
    lastToneAtMs = nowMs
    val freqHz = frequencyForLux(lux)
    val sampleCount = (SAMPLE_RATE * PULSE_MS) / 1000
    val pcm = ShortArray(sampleCount)
    val fadeSamples = (SAMPLE_RATE * 10) / 1000
    for (i in 0 until sampleCount) {
      val env = when {
        i < fadeSamples -> i.toDouble() / fadeSamples
        i > sampleCount - fadeSamples -> (sampleCount - i).toDouble() / fadeSamples
        else -> 1.0
      }
      val sample = sin(2.0 * PI * freqHz * i / SAMPLE_RATE) * env * AMPLITUDE
      pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    releaseTrack()
    try {
      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
      activeTrack = track
      if (track.write(pcm, 0, pcm.size) != pcm.size ||
          track.setNotificationMarkerPosition(pcm.size) != AudioTrack.SUCCESS) {
        releaseTrack()
        return
      }
      track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(t: AudioTrack) {
          if (activeTrack === t) releaseTrack()
        }
        override fun onPeriodicNotification(t: AudioTrack) {}
      })
      track.play()
    } catch (_: RuntimeException) {
      releaseTrack()
    }
  }

  fun reset() {
    lastToneAtMs = -MIN_PULSE_INTERVAL_MS
    releaseTrack()
  }

  private fun releaseTrack() {
    val track = activeTrack
    activeTrack = null
    track?.release()
  }

  companion object {
    private const val SAMPLE_RATE = 24_000
    private const val PULSE_MS = 75
    private const val AMPLITUDE = 0.22
    private const val MIN_PULSE_INTERVAL_MS = 350L
    private const val MIN_FREQ_HZ = 260.0
    private const val MAX_FREQ_HZ = 1320.0

    fun frequencyForLux(lux: Float): Double {
      val clamped = lux.coerceIn(0f, 1000f).toDouble()
      val ratio = ln(1.0 + clamped) / ln(1001.0)
      return MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(ratio)
    }
  }
}
