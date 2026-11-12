/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.compositor

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import com.google.android.accessibility.talkback.utils.VerbosityPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageHintModeTest {
  private val prefs = FakeSharedPreferences()
  private lateinit var res: Resources
  private lateinit var key: String
  private lateinit var custom: String
  private lateinit var realPrefs: SharedPreferences

  @Before
  fun setUp() {
    res = RuntimeEnvironment.getApplication().resources
    key = res.getString(R.string.pref_a11y_hints_key)
    custom = res.getString(R.string.pref_verbosity_preset_value_custom)
    realPrefs =
      RuntimeEnvironment.getApplication()
        .getSharedPreferences("usage_hint_mode_test", Context.MODE_PRIVATE)
    realPrefs.edit().clear().commit()
  }

  private fun setPreset(preset: String) {
    prefs.edit().putString(res.getString(R.string.pref_verbosity_preset_key), preset).apply()
  }

  @Test
  fun defaultIsActionsOnly() {
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.read(prefs, res))
  }

  @Test
  fun storedModesReadBack() {
    for (mode in listOf(UsageHintMode.ALL, UsageHintMode.ACTIONS_ONLY, UsageHintMode.OFF)) {
      prefs.edit().putString(key, mode).apply()
      assertEquals(mode, UsageHintMode.read(prefs, res))
    }
  }

  @Test
  fun unknownValueFallsBackToActionsOnly() {
    prefs.edit().putString(key, "banana").apply()
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.read(prefs, res))
  }

  @Test
  fun highAndLowPresetsHaveFixedModes() {
    setPreset(res.getString(R.string.pref_verbosity_preset_value_high))
    assertEquals(UsageHintMode.ALL, UsageHintMode.read(prefs, res))
    setPreset(res.getString(R.string.pref_verbosity_preset_value_low))
    assertEquals(UsageHintMode.OFF, UsageHintMode.read(prefs, res))
  }

  @Test
  fun customPresetReadsItsOwnMode() {
    setPreset(custom)
    prefs
      .edit()
      .putString(
        VerbosityPreferences.toVerbosityPrefKey(custom, key), UsageHintMode.OFF)
      .apply()
    assertEquals(UsageHintMode.OFF, UsageHintMode.read(prefs, res))
  }

  @Test
  fun legacyBoolFallsBackToActionsOnly() {
    realPrefs.edit().putBoolean(key, true).commit()
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.read(realPrefs, res))
  }

  @Test
  fun migrateTurnsOnAndOffIntoModes() {
    prefs
      .edit()
      .putBoolean(VerbosityPreferences.toVerbosityPrefKey(custom, key), false)
      .putBoolean(key, true)
      .apply()
    UsageHintMode.migrate(prefs, res)
    setPreset(custom)
    assertEquals(UsageHintMode.OFF, UsageHintMode.read(prefs, res))
    assertEquals(UsageHintMode.ALL, prefs.getString(key, null))
  }

  @Test
  fun migrateKeepsAModeAlreadyChosen() {
    prefs
      .edit()
      .putString(VerbosityPreferences.toVerbosityPrefKey(custom, key), UsageHintMode.OFF)
      .putBoolean(key, false)
      .apply()
    UsageHintMode.migrate(prefs, res)
    assertEquals(
      UsageHintMode.OFF,
      prefs.getString(VerbosityPreferences.toVerbosityPrefKey(custom, key), null))
    assertEquals(UsageHintMode.OFF, prefs.getString(key, null))
  }

  @Test
  fun migrateLeavesNewUsersAlone() {
    UsageHintMode.migrate(prefs, res)
    assertFalse(prefs.contains(key))
    assertFalse(prefs.contains(VerbosityPreferences.toVerbosityPrefKey(custom, key)))
  }

  @Test
  fun fromValueMapsKnownModesAndFallsBack() {
    assertEquals(UsageHintMode.ALL, UsageHintMode.fromValue(UsageHintMode.ALL))
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.fromValue(UsageHintMode.ACTIONS_ONLY))
    assertEquals(UsageHintMode.OFF, UsageHintMode.fromValue(UsageHintMode.OFF))
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.fromValue(null))
    assertEquals(UsageHintMode.ACTIONS_ONLY, UsageHintMode.fromValue("banana"))
  }
}
