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

import android.content.Context
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The performance profiles: energy turns every Developer performance option off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceProfileTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun clearPreferences() {
    prefs.edit().clear().apply()
  }

  @Test
  fun energy_turnsEverythingOff() {
    PerformanceProfile.apply(prefs, PerformanceProfile.ENERGY)

    assertTrue(prefs.getBoolean("pref_disable_latency_extras", false))
    assertTrue(prefs.getBoolean("pref_disable_latency_pinned", false))
    assertFalse(prefs.getBoolean("pref_prepare_adjacent_speech", true))
    assertFalse(prefs.getBoolean("pref_prepare_second_ahead_speech", true))
    assertFalse(prefs.getBoolean("pref_order_cache", true))
    assertFalse(prefs.getBoolean("pref_split_control_speech", true))
    for (feature in LatencyExperiments.Feature.values()) {
      assertFalse(feature.key, prefs.getBoolean(feature.key, true))
    }
    assertEquals("low", prefs.getString("pref_event_processing_level", null))
    assertEquals(
      100,
      prefs.getInt(LatencyExperiments.STATIONARY_TOUCH_DELAY_KEY, 0),
    )
    assertEquals(PerformanceProfile.ENERGY, PerformanceProfile.storedProfile(prefs))
  }

  @Test
  fun speed_keepsTheCurrentValues() {
    prefs.edit().apply {
      putBoolean("pref_prepare_adjacent_speech", false)
      putBoolean("pref_disable_latency_extras", true)
      putString("pref_event_processing_level", "medium")
    }.apply()
    PerformanceProfile.apply(prefs, PerformanceProfile.SPEED)

    assertFalse(prefs.getBoolean("pref_prepare_adjacent_speech", true))
    assertTrue(prefs.getBoolean("pref_disable_latency_extras", false))
    assertEquals("medium", prefs.getString("pref_event_processing_level", null))
    for (feature in LatencyExperiments.Feature.values()) {
      assertFalse(feature.key, prefs.contains(feature.key))
    }
    assertFalse(prefs.contains("pref_split_control_speech"))
    assertEquals(PerformanceProfile.SPEED, PerformanceProfile.storedProfile(prefs))
  }

  @Test
  fun switchingToSpeed_keepsTheEnergyValues() {
    PerformanceProfile.apply(prefs, PerformanceProfile.ENERGY)
    PerformanceProfile.apply(prefs, PerformanceProfile.SPEED)

    assertFalse(prefs.getBoolean("pref_prepare_adjacent_speech", true))
    for (feature in LatencyExperiments.Feature.values()) {
      assertFalse(feature.key, prefs.getBoolean(feature.key, true))
    }
    assertEquals("low", prefs.getString("pref_event_processing_level", null))
    assertEquals(PerformanceProfile.SPEED, PerformanceProfile.storedProfile(prefs))

    PerformanceProfile.apply(prefs, PerformanceProfile.ENERGY)

    assertFalse(prefs.getBoolean("pref_prepare_adjacent_speech", true))
    assertEquals(PerformanceProfile.ENERGY, PerformanceProfile.storedProfile(prefs))
  }

  @Test
  fun storedProfile_isNullUntilAPickedProfileIsStored() {
    assertNull(PerformanceProfile.storedProfile(prefs))

    prefs.edit().putString(PerformanceProfile.KEY, "unknown").apply()
    assertNull(PerformanceProfile.storedProfile(prefs))

    PerformanceProfile.apply(prefs, PerformanceProfile.ENERGY)
    assertEquals(PerformanceProfile.ENERGY, PerformanceProfile.storedProfile(prefs))
  }

  @Test
  fun storedProfile_ignoresMarkerRecordedBeforeExplicitChoice() {
    prefs.edit().putString(PerformanceProfile.KEY, PerformanceProfile.SPEED).apply()
    assertNull(PerformanceProfile.storedProfile(prefs))

    PerformanceProfile.apply(prefs, PerformanceProfile.SPEED)
    assertEquals(PerformanceProfile.SPEED, PerformanceProfile.storedProfile(prefs))
  }

  @Test
  fun unknownProfile_appliesSpeed() {
    prefs.edit().putBoolean("pref_prepare_adjacent_speech", false).apply()
    PerformanceProfile.apply(prefs, "unknown")

    assertFalse(prefs.getBoolean("pref_prepare_adjacent_speech", true))
    assertFalse(prefs.contains("pref_disable_latency_extras"))
    assertEquals(PerformanceProfile.SPEED, PerformanceProfile.storedProfile(prefs))
  }
}
