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

/**
 * The initial setup's performance configuration: speed keeps every Developer performance option
 * as it is, energy turns them all off. Speed writes only the marker, so it never touches a
 * value the user set; energy writes every key, so switching to energy leaves no stale value
 * behind. The marker records which profile was picked.
 */
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
  private const val EVENT_PROCESSING_LEVEL_LOW = "low"

  /** The stored profile, or null when none was ever picked, the stored value is unknown, or it was recorded before explicit choice was required. */
  fun storedProfile(preferences: SharedPreferences): String? =
    preferences.getString(KEY, null)?.takeIf { it == SPEED || it == ENERGY }
      ?.takeIf { preferences.getInt(CHOICE_VERSION_KEY, 0) == CHOICE_VERSION }

  /** Writes the profile's values in one edit. Anything other than [ENERGY] applies speed. */
  fun apply(preferences: SharedPreferences, profile: String) {
    if (profile != ENERGY) {
      preferences.edit().apply {
        putString(KEY, SPEED)
        putInt(CHOICE_VERSION_KEY, CHOICE_VERSION)
      }.apply()
      return
    }
    preferences.edit().apply {
      putBoolean(DISABLE_LATENCY_EXTRAS_KEY, true)
      putBoolean(DISABLE_LATENCY_PINNED_KEY, true)
      putBoolean(PREPARE_ADJACENT_KEY, false)
      putBoolean(PREPARE_SECOND_AHEAD_KEY, false)
      putBoolean(ORDER_CACHE_KEY, false)
      putBoolean(SPLIT_CONTROL_SPEECH_KEY, false)
      for (feature in LatencyExperiments.Feature.values()) {
        putBoolean(feature.key, false)
      }
      putString(EVENT_PROCESSING_LEVEL_KEY, EVENT_PROCESSING_LEVEL_LOW)
      putInt(
        LatencyExperiments.STATIONARY_TOUCH_DELAY_KEY,
        LatencyExperiments.defaultStationaryTouchDelayMs(),
      )
      putString(KEY, ENERGY)
      putInt(CHOICE_VERSION_KEY, CHOICE_VERSION)
    }.apply()
  }
}
