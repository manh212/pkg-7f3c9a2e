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

package com.google.android.accessibility.talkback.focusmanagement

import android.content.Context
import androidx.core.content.edit
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Detailed focus mode ("Chế độ tiêu điểm chi tiết"): while it is on, swiping and touch
 * exploration focus each text and state inside a focusable item separately instead of the
 * whole item, and activating focused text activates its item.
 *
 * This is a toggle command, not a setting: there is no Navigation switch and no settings
 * key. The state lives in SharedPreferences
 * under a plain literal key, like [com.google.android.accessibility.talkback.keyboard.ArrowKeyNav]'s
 * PREF_ON, so it survives a service restart; TalkBackService applies it in
 * reloadPreferences(), and [toggle] applies it right away. Off by default.
 */
object FocusTextInsideItems {
  private const val PREF_ON = "pref_detailed_focus_mode_on"

  @JvmStatic
  fun isOn(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getBoolean(PREF_ON, false)

  @JvmStatic
  fun setOn(context: Context, on: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(context).edit { putBoolean(PREF_ON, on) }
    AccessibilityNodeInfoUtils.setFocusTextInsideItems(on)
  }

  /** Flips the mode, applies it, and returns the new state. */
  @JvmStatic
  fun toggle(context: Context): Boolean {
    val on = !isOn(context)
    setOn(context, on)
    return on
  }
}
