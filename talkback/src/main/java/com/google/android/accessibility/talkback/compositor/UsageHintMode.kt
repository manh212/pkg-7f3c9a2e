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

import android.content.SharedPreferences
import android.content.res.Resources
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.utils.VerbosityPreferences

/**
 * When usage hints are spoken. This replaced an on/off setting, whose on is [ALL]. The value is
 * read through the verbosity preset, like the other verbosity preferences.
 */
object UsageHintMode {
  /** Every usage hint is spoken. */
  const val ALL = "all"

  /** Only the hint that the focused item has actions is spoken. */
  const val ACTIONS_ONLY = "actions"

  /** No usage hint is spoken. */
  const val OFF = "off"

  /** Reads the mode for the current verbosity preset. */
  @JvmStatic
  fun read(prefs: SharedPreferences, res: Resources): String {
    val value =
      try {
        VerbosityPreferences.getPreferenceValueString(
          prefs,
          res,
          res.getString(R.string.pref_a11y_hints_key),
          res.getString(R.string.pref_a11y_hints_default),
        )
      } catch (e: ClassCastException) {
        // A value stored by the old on/off setting, not yet migrated.
        null
      }
    return fromValue(value)
  }

  /** Maps a stored value to a mode; unknown or missing values mean actions only. */
  @JvmStatic
  fun fromValue(value: String?): String =
    when (value) {
      ALL, ACTIONS_ONLY, OFF -> value
      else -> ACTIONS_ONLY
    }

  /**
   * Turns each copy of the old on/off setting into the matching mode, in place: on becomes
   * [ALL], off becomes [OFF]. There is a copy for each verbosity preset, and one with no preset
   * from before presets. Copies that already hold a mode are left alone.
   */
  @JvmStatic
  fun migrate(prefs: SharedPreferences, res: Resources) {
    val key = res.getString(R.string.pref_a11y_hints_key)
    val editor = prefs.edit()
    var changed = false
    fun keyFor(preset: String?) =
      if (preset == null) key else VerbosityPreferences.toVerbosityPrefKey(preset, key)
    for (preset in listOf(null) + res.getStringArray(R.array.pref_verbosity_preset_values)) {
      val stored = prefs.all[keyFor(preset)]
      if (stored is Boolean) {
        editor.putString(keyFor(preset), if (stored) ALL else OFF)
        changed = true
      }
    }
    if (changed) {
      editor.apply()
    }
  }
}
