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
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/**
 * Lists the phrases the user taught for voice commands, each with its action's name;
 * tapping one offers to delete it. Phrases are taught by voice, never typed here.
 */
class VoiceCommandPhraseListFragment : TalkbackBaseFragment() {

  override fun getTitle(): CharSequence = getText(R.string.title_voice_command_phrases)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    rebuild()
  }

  override fun onResume() {
    super.onResume()
    rebuild()
  }

  private fun rebuild() {
    val context = requireContext()
    val screen = preferenceManager.createPreferenceScreen(context)
    preferenceScreen = screen
    val phrases = VoiceCommandPhrases.load(context)
    if (phrases.isEmpty()) {
      val empty = AccessibilitySuitePreference(context)
      empty.title = getString(R.string.voice_command_phrases_empty)
      empty.isPersistent = false
      empty.isIconSpaceReserved = false
      empty.isSelectable = false
      screen.addPreference(empty)
      return
    }
    val labels = VoiceCommandCatalog.entries(context).associate { it.value to it.phrase }
    for (phrase in phrases) {
      val label = labels[phrase.value] ?: phrase.value
      val row = AccessibilitySuitePreference(context)
      row.title = phrase.phrase
      row.summary = label
      row.isPersistent = false
      row.isIconSpaceReserved = false
      row.setOnPreferenceClickListener {
        confirmDelete(phrase, label)
        true
      }
      screen.addPreference(row)
    }
  }

  private fun confirmDelete(phrase: VoiceCommandPhrases.Phrase, label: String) {
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.voice_command_phrase_delete_title)
      .setMessage(
        getString(R.string.voice_command_phrase_delete_message, phrase.phrase, label)
      )
      .setPositiveButton(android.R.string.ok) { _, _ ->
        VoiceCommandPhrases.remove(requireContext(), phrase.phrase)
        rebuild()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
