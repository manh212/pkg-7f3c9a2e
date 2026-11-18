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

package com.google.android.accessibility.talkback.volume

import android.os.Bundle
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.GestureListPreference

/**
 * Volume down key settings: a press, pressing the key 2, 3 or 4 times quickly, a short hold and
 * a long hold, separately for when the screen is on, when the screen is locked, and when the
 * screen is off.
 */
class VolumeKeyDownSettingsFragment : VolumeKeySubScreenFragment(R.xml.volume_key_down_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.volume_key_down_category)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    // The lock-screen and screen-off rows only offer actions that make sense while the
    // phone is locked.
    VolumeKeyManager.lockScreenRestrictedKeys(false).forEach { key ->
      findPreference<GestureListPreference>(key)?.setLockScreenActionsOnly()
    }
  }
}
