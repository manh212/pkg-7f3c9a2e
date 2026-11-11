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

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * A multiband compressor for speech: the speech is split into five bands, and in each band the
 * parts louder than the band's threshold are turned down by the band's ratio, so the loudness of
 * each part of the voice stays more even. Its settings are kept per voice profile, like the
 * speech equalizer's, and its [Filter] runs on the speech after the equalizer's.
 */
object SpeechCompressor {

  const val PREF_DEFAULT = "pref_speech_compressor"

  const val PREF_DEFAULT_ON = "pref_speech_compressor_on"

  /** The frequencies in Hz where one band ends and the next begins. */
  @JvmField val CROSSOVERS = intArrayOf(250, 1000, 2500, 6000)

  @JvmField val BANDS = CROSSOVERS.size + 1

  const val MIN_THRESHOLD_DB = -48
  const val MAX_RATIO_X10 = 100
  const val MIN_RATIO_X10 = 10
  const val MIN_ATTACK_MS = 1
  const val MAX_ATTACK_MS = 100
  const val MIN_RELEASE_MS = 50
  const val MAX_RELEASE_MS = 500
  const val MAX_MAKEUP_DB = 12

  /** Compressor settings: a threshold and a ratio for each band, and the shared controls. */
  class Settings(
    @JvmField val thresholds: IntArray,
    /** The ratios multiplied by ten, so 25 is a ratio of 2.5 to 1. */
    @JvmField val ratios: IntArray,
    @JvmField val attackMs: Int,
    @JvmField val releaseMs: Int,
    @JvmField val makeupDb: Int,
  ) {
    fun copy(): Settings =
      Settings(thresholds.copyOf(), ratios.copyOf(), attackMs, releaseMs, makeupDb)

    /** Whether nothing is compressed and nothing is made up, so speech passes unchanged. */
    fun isNoOp(): Boolean = thresholds.all { it >= 0 } && makeupDb == 0

    override fun equals(other: Any?): Boolean =
      other is Settings &&
        thresholds.contentEquals(other.thresholds) &&
        ratios.contentEquals(other.ratios) &&
        attackMs == other.attackMs &&
        releaseMs == other.releaseMs &&
        makeupDb == other.makeupDb

    override fun hashCode(): Int {
      var result = thresholds.contentHashCode()
      result = 31 * result + ratios.contentHashCode()
      result = 31 * result + attackMs
      result = 31 * result + releaseMs
      result = 31 * result + makeupDb
      return result
    }
  }

  /** The settings that compress nothing: every threshold at the top, no makeup gain. */
  @JvmStatic
  fun defaults(): Settings =
    Settings(IntArray(BANDS), IntArray(BANDS) { 20 }, 10, 200, 0)

  enum class Preset(private val settings: Settings) {
    NONE(defaults()),
    GENTLE(Settings(intArrayOf(-18, -18, -18, -18, -18), intArrayOf(15, 15, 15, 15, 15), 10, 250, 2)),
    CLARITY(
      Settings(intArrayOf(-15, -21, -27, -27, -21), intArrayOf(15, 20, 25, 30, 30), 5, 150, 3)
    ),
    EVEN(Settings(intArrayOf(-24, -27, -30, -30, -24), intArrayOf(25, 30, 35, 35, 30), 10, 300, 4));

    fun settings(): Settings = settings.copy()

    val value: String
      get() = name.lowercase()
  }

  @JvmStatic
  fun key(profileId: String): String =
    if (profileId.isEmpty()) PREF_DEFAULT else VoiceProfiles.key(profileId, VoiceProfiles.COMPRESSOR)

  @JvmStatic
  fun onKey(profileId: String): String =
    if (profileId.isEmpty()) PREF_DEFAULT_ON
    else VoiceProfiles.key(profileId, VoiceProfiles.COMPRESSOR_ON)

  @JvmStatic
  fun isKey(key: String?): Boolean =
    key != null &&
      (key == PREF_DEFAULT ||
        key == PREF_DEFAULT_ON ||
        (key.startsWith(VoiceProfiles.PREF_PREFIX + "_") &&
          (key.endsWith("_" + VoiceProfiles.COMPRESSOR) ||
            key.endsWith("_" + VoiceProfiles.COMPRESSOR_ON)) &&
          VoiceProfiles.isProfileKey(key)))

  /** Whether the compressor is switched on for [profileId]. It starts off, unlike the equalizer. */
  @JvmStatic
  fun isOn(prefs: SharedPreferences, profileId: String): Boolean =
    try {
      prefs.getBoolean(onKey(profileId), false)
    } catch (e: ClassCastException) {
      false
    }

  @JvmStatic
  fun setOn(prefs: SharedPreferences, profileId: String, on: Boolean) {
    prefs.edit { putBoolean(onKey(profileId), on) }
  }

  @JvmStatic
  fun read(prefs: SharedPreferences, profileId: String): Settings =
    parse(
      try {
        prefs.getString(key(profileId), null)
      } catch (e: ClassCastException) {
        null
      }
    )

  @JvmStatic
  fun save(prefs: SharedPreferences, profileId: String, settings: Settings) {
    prefs.edit { putString(key(profileId), format(settings)) }
  }

  /** The settings in use, for the voice profile in use, or null when it changes nothing. */
  @JvmStatic
  fun inUse(prefs: SharedPreferences): Settings? {
    val id = VoiceProfiles.activeId(prefs)
    if (!isOn(prefs, id)) return null
    val settings = read(prefs, id)
    return if (settings.isNoOp()) null else settings
  }

  /**
   * Reads settings saved by [format]: five thresholds, five ratios multiplied by ten, the attack
   * and release in milliseconds, and the makeup gain. Damaged values give the default settings,
   * and every number is kept to the range the settings screen offers.
   */
  @JvmStatic
  fun parse(value: String?): Settings {
    if (value.isNullOrEmpty()) return defaults()
    val parts = value.split(",")
    if (parts.size != BANDS * 2 + 3) return defaults()
    val numbers = IntArray(parts.size)
    for (i in parts.indices) {
      numbers[i] = parts[i].trim().toIntOrNull() ?: return defaults()
    }
    return Settings(
      IntArray(BANDS) { numbers[it].coerceIn(MIN_THRESHOLD_DB, 0) },
      IntArray(BANDS) { numbers[BANDS + it].coerceIn(MIN_RATIO_X10, MAX_RATIO_X10) },
      numbers[BANDS * 2].coerceIn(MIN_ATTACK_MS, MAX_ATTACK_MS),
      numbers[BANDS * 2 + 1].coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS),
      numbers[BANDS * 2 + 2].coerceIn(0, MAX_MAKEUP_DB),
    )
  }

  @JvmStatic
  fun format(settings: Settings): String =
    (settings.thresholds.asIterable() +
        settings.ratios.asIterable() +
        listOf(settings.attackMs, settings.releaseMs, settings.makeupDb))
      .joinToString(",")

  @JvmStatic
  fun presetOf(settings: Settings): Preset? = Preset.entries.firstOrNull { it.settings() == settings }

  @JvmStatic fun presetFor(value: String?): Preset? = Preset.entries.firstOrNull { it.value == value }

  /**
   * Splits speech into the bands with Linkwitz-Riley crossovers, compresses each band, and adds
   * the bands back together. Each crossover's two sides add up to an all-pass of what it split,
   * so a band is also passed through the all-passes of the crossovers below its own; with every
   * gain at one the bands then add back to the speech itself, unchanged. The level of a band is
   * followed across all channels together, so the balance between channels never moves.
   *
   * One filter serves one stream, so it starts unsettled: the crossovers need a few tens of
   * milliseconds before their bands add back up to the speech. To keep the start of every
   * utterance clean, the processed speech is blended in from the speech itself over the first
   * [START_FADE_MS] milliseconds while the crossovers settle underneath it.
   */
  class Filter(settings: Settings, rate: Int, private val channels: Int) {
    private val splits: Int
    private val lowPass: Array<Cascade>
    private val highPass: Array<Cascade>
    private val compensation: Array<Array<AllPass?>>
    private val thresholds = settings.thresholds
    private val ratios = FloatArray(BANDS) { settings.ratios[it] / 10f }
    private val attack = (1.0 - exp(-1.0 / (settings.attackMs / 1000.0 * rate))).toFloat()
    private val release = (1.0 - exp(-1.0 / (settings.releaseMs / 1000.0 * rate))).toFloat()
    private val makeup = 10.0.pow(settings.makeupDb / 20.0).toFloat()
    private val limit = settings.makeupDb > 0
    private val limiterRelease = exp(-1.0 / (LIMITER_RELEASE_S * rate)).toFloat()
    private var limiterGain = 1f
    private val envelopes = FloatArray(BANDS)
    private val bandSamples = FloatArray(BANDS * channels)
    private val loudest = FloatArray(BANDS)
    private val gains = FloatArray(BANDS)
    private val raw = FloatArray(BANDS)
    private val fadeFrames = (rate * START_FADE_MS / 1000).coerceAtLeast(1)
    private var framesSeen = 0
    private val dry = FloatArray(channels)
    private val wet = FloatArray(channels)

    init {
      var count = 0
      while (count < CROSSOVERS.size && CROSSOVERS[count] < rate * MAX_FREQUENCY_OF_RATE) count++
      splits = count
      // Two cascaded Butterworth sections per side make a fourth-order Linkwitz-Riley crossover.
      lowPass =
        Array(splits) { Cascade(butterworth(CROSSOVERS[it].toDouble(), rate.toDouble(), true), channels) }
      highPass =
        Array(splits) { Cascade(butterworth(CROSSOVERS[it].toDouble(), rate.toDouble(), false), channels) }
      compensation =
        Array(BANDS) { band ->
          Array<AllPass?>(splits) { split ->
            if (split > band) {
              AllPass(
                Cascade(butterworth(CROSSOVERS[split].toDouble(), rate.toDouble(), true), channels),
                Cascade(butterworth(CROSSOVERS[split].toDouble(), rate.toDouble(), false), channels),
              )
            } else {
              null
            }
          }
        }
    }

    fun process(samples: FloatArray): FloatArray {
      val frames = samples.size / channels
      for (frame in 0 until frames) {
        for (channel in 0 until channels) dry[channel] = samples[frame * channels + channel]
        // Split every channel into its bands, remembering each band's loudest channel sample.
        loudest.fill(0f)
        for (channel in 0 until channels) {
          var rest = dry[channel]
          for (split in 0 until splits) {
            raw[split] = lowPass[split].run(channel, rest)
            rest = highPass[split].run(channel, rest)
          }
          raw[splits] = rest
          for (band in 0..splits) {
            var sample = raw[band]
            for (split in band + 1 until splits) {
              sample = compensation[band][split]!!.run(channel, sample)
            }
            bandSamples[band * channels + channel] = sample
            loudest[band] = maxOf(loudest[band], abs(sample))
          }
          for (band in splits + 1 until BANDS) bandSamples[band * channels + channel] = 0f
        }
        // Follow each band's level and work out how much to turn it down by.
        for (band in 0 until BANDS) {
          val envelope = envelopes[band]
          val coefficient = if (loudest[band] > envelope) attack else release
          val followed = envelope + coefficient * (loudest[band] - envelope)
          envelopes[band] = followed
          gains[band] = gainFor(band, followed)
        }
        var peak = 0f
        for (channel in 0 until channels) {
          var out = 0f
          for (band in 0 until BANDS) out += bandSamples[band * channels + channel] * gains[band]
          out *= makeup
          wet[channel] = out
          peak = maxOf(peak, abs(out))
        }
        if (limit) {
          limiterGain = 1f - (1f - limiterGain) * limiterRelease
          if (peak * limiterGain > LIMITER_CEILING) limiterGain = LIMITER_CEILING / peak
          for (channel in 0 until channels) wet[channel] *= limiterGain
        }
        if (framesSeen < fadeFrames) {
          // Blend the processed speech in from the speech itself while the crossovers settle.
          val step = framesSeen.toFloat() / fadeFrames
          val weight = step * step * (3f - 2f * step)
          for (channel in 0 until channels) {
            samples[frame * channels + channel] = dry[channel] + (wet[channel] - dry[channel]) * weight
          }
        } else {
          for (channel in 0 until channels) samples[frame * channels + channel] = wet[channel]
        }
        framesSeen++
      }
      for (i in envelopes.indices) if (envelopes[i] < DENORMAL) envelopes[i] = 0f
      return samples
    }

    /** Two Butterworth sections in a row, keeping their state apart for each channel. */
    private class Cascade(private val coefficients: DoubleArray, channels: Int) {
      private val state = FloatArray(STAGES * channels * 4)
      private val stride = channels * 4

      fun run(channel: Int, input: Float): Float {
        var sample = input
        for (stage in 0 until STAGES) {
          val at = stage * stride + channel * 4
          val out =
            (coefficients[0] * sample +
              coefficients[1] * state[at] +
              coefficients[2] * state[at + 1] -
              coefficients[3] * state[at + 2] -
              coefficients[4] * state[at + 3])
              .toFloat()
          state[at + 1] = state[at]
          state[at] = sample
          state[at + 3] = state[at + 2]
          state[at + 2] = out
          sample = out
        }
        return sample
      }
    }

    /** A crossover's two sides run side by side and added, which passes every frequency. */
    private class AllPass(private val low: Cascade, private val high: Cascade) {
      fun run(channel: Int, input: Float): Float = low.run(channel, input) + high.run(channel, input)
    }

    /** The gain for a band whose followed level is [envelope], from its threshold and ratio. */
    private fun gainFor(band: Int, envelope: Float): Float {
      val threshold = thresholds[band]
      if (threshold >= 0 || envelope <= 0f) return 1f
      val levelDb = 20 * log10(envelope.toDouble())
      val slope = 1.0 - 1.0 / ratios[band]
      val reduction =
        when {
          levelDb <= threshold - KNEE_DB / 2 -> 0.0
          levelDb >= threshold + KNEE_DB / 2 -> (levelDb - threshold) * slope
          else -> {
            val over = levelDb - threshold + KNEE_DB / 2
            slope * over * over / (2 * KNEE_DB)
          }
        }
      return 10.0.pow(-reduction / 20.0).toFloat()
    }
  }

  /** The coefficients of a second-order Butterworth section, low-pass or high-pass. */
  private fun butterworth(frequency: Double, rate: Double, lowPass: Boolean): DoubleArray {
    val w0 = 2 * PI * frequency / rate
    val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
    val cosW0 = cos(w0)
    val a0 = 1 + alpha
    return if (lowPass) {
      doubleArrayOf(
        ((1 - cosW0) / 2) / a0,
        (1 - cosW0) / a0,
        ((1 - cosW0) / 2) / a0,
        -2 * cosW0 / a0,
        (1 - alpha) / a0,
      )
    } else {
      doubleArrayOf(
        ((1 + cosW0) / 2) / a0,
        -(1 + cosW0) / a0,
        ((1 + cosW0) / 2) / a0,
        -2 * cosW0 / a0,
        (1 - alpha) / a0,
      )
    }
  }

  private const val STAGES = 2
  private const val BUTTERWORTH_Q = 0.7071067811865476
  private const val KNEE_DB = 6.0
  private const val MAX_FREQUENCY_OF_RATE = 0.45
  private const val LIMITER_CEILING = 0.98f
  private const val LIMITER_RELEASE_S = 0.08
  private const val START_FADE_MS = 60
  private const val DENORMAL = 1e-20f
}
