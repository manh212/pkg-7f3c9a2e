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

package com.google.android.accessibility.talkback.notifications

import android.content.SharedPreferences
import com.google.android.accessibility.utils.LatencyExperiments

object PerformanceProfile {
  const val KEY = "pref_performance_profile"
  const val SPEED = "speed"
  const val ENERGY = "energy"

  private const val CHOICE_VERSION_KEY = "pref_performance_profile_version"
  private const val CHOICE_VERSION = 1

  private const val DISABLE_LATENCY_EXTRAS_KEY = "pref_disable_latency_extras"
  private const val DISABLE_LATENCY_PINNED_KEY = "pref_disable_latency_pinned"
  private const val PREPARE_ADJACENT_KEY = "pref_prepare_adjacent_speech"
  private const val PREPARE_SECOND_AHEAD_KEY = "pref_prepare_second_ahead_speech"
  private const val ORDER_CACHE_KEY = "pref_order_cache"
  private const val SPLIT_CONTROL_SPEECH_KEY = "pref_split_control_speech"
  private const val EVENT_PROCESSING_LEVEL_KEY = "pref_event_processing_level"
  private const val SPEED_EVENT_PROCESSING_LEVEL = "low"
  private const val ENERGY_EVENT_PROCESSING_LEVEL = "very_low"

  /** The stored profile, or null when none was ever picked, the stored value is unknown, or it was recorded before explicit choice was required. */
  fun storedProfile(preferences: SharedPreferences): String? =
    preferences.getString(KEY, null)?.takeIf { it == SPEED || it == ENERGY }
      ?.takeIf { preferences.getInt(CHOICE_VERSION_KEY, 0) == CHOICE_VERSION }

  /** Writes the profile's values in one edit. Anything other than [ENERGY] applies speed. */
  fun apply(preferences: SharedPreferences, profile: String) {
    val speed = profile != ENERGY
    preferences.edit().apply {
      putBoolean(DISABLE_LATENCY_EXTRAS_KEY, !speed)
      putBoolean(DISABLE_LATENCY_PINNED_KEY, !speed)
      putBoolean(PREPARE_ADJACENT_KEY, speed)
      putBoolean(PREPARE_SECOND_AHEAD_KEY, speed)
      putBoolean(ORDER_CACHE_KEY, speed)
      putBoolean(SPLIT_CONTROL_SPEECH_KEY, speed)
      for (feature in LatencyExperiments.Feature.values()) {
        putBoolean(feature.key, speed && feature.defaultEnabled)
      }
      putString(
        EVENT_PROCESSING_LEVEL_KEY,
        if (speed) SPEED_EVENT_PROCESSING_LEVEL else ENERGY_EVENT_PROCESSING_LEVEL,
      )
      putInt(
        LatencyExperiments.STATIONARY_TOUCH_DELAY_KEY,
        LatencyExperiments.defaultStationaryTouchDelayMs(),
      )
      putString(KEY, if (speed) SPEED else ENERGY)
      putInt(CHOICE_VERSION_KEY, CHOICE_VERSION)
    }.apply()
  }
}
