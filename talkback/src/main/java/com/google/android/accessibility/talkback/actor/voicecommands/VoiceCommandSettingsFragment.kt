/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
 * applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import android.os.Bundle
import androidx.preference.Preference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/** The voice command settings page: the phrases the user taught, and how they are matched. */
class VoiceCommandSettingsFragment : TalkbackBaseFragment() {

  private var phrasesRow: Preference? = null

  override fun getTitle(): CharSequence = getText(R.string.title_voice_commands_settings)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    val screen = preferenceManager.createPreferenceScreen(context)
    preferenceScreen = screen

    val row = AccessibilitySuitePreference(context)
    row.title = getString(R.string.title_voice_command_phrases)
    row.isPersistent = false
    row.isIconSpaceReserved = false
    row.fragment = VoiceCommandPhraseListFragment::class.java.name
    screen.addPreference(row)
    phrasesRow = row
  }

  override fun onResume() {
    super.onResume()
    val count = VoiceCommandPhrases.load(requireContext()).size
    phrasesRow?.summary =
      if (count == 0) {
        getString(R.string.voice_phrase_list_empty)
      } else {
        getString(R.string.voice_command_phrases_count, count)
      }
  }
}
