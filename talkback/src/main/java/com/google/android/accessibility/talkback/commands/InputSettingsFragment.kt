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

package com.google.android.accessibility.talkback.commands

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.talkback.training.TutorialInitiator

/**
 * Input Settings: houses all non-binding behavior controls (gesture options, edge gesture geometry,
 * back-tap sensitivity, volume key behaviors, keyboard keymap/modifier) plus container managers
 * (gesture profiles, per-app gestures, custom menus, and quick settings panel).
 */
class InputSettingsFragment : TalkBackPreferenceFragment(R.xml.input_settings_preferences) {

  override fun getTitle(): CharSequence = getString(R.string.title_pref_input_settings)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val context = requireContext()
    findPreference<Preference>(getString(R.string.pref_practice_gestures_entry_point_key))
      ?.setOnPreferenceClickListener {
        startActivity(TutorialInitiator.createPracticeGesturesIntent(context))
        true
      }
    updateTriggerModifierVisibility()
  }

  override fun syncDependentPreferencesVisibility() {
    super.syncDependentPreferencesVisibility()
    updateTriggerModifierVisibility()
  }

  private fun updateTriggerModifierVisibility() {
    val keymap = findPreference<ListPreference>(getString(R.string.pref_select_keymap_key))
    val modifier = findPreference<Preference>(getString(R.string.pref_default_keymap_trigger_modifier_key))
    if (keymap != null && modifier != null) {
      val classicValue = getString(R.string.default_keymap_entry_value)
      val showModifier = keymap.value == classicValue
      if (modifier.isVisible != showModifier) {
        modifier.isVisible = showModifier
      }
    }
  }
}
