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
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/**
 * Base for the two settings pages that list ways of saying voice commands. Each row shows an
 * item's current phrasings and opens an editor; a phrasing the user saves replaces the
 * item's built-in phrasings entirely, and every page ends with a restore-all row.
 */
abstract class VoiceCommandSpeechListFragment : TalkbackBaseFragment() {

  protected abstract val items: List<VoiceCommandSpeechOverrides.Item>

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    buildRows()
  }

  override fun onResume() {
    super.onResume()
    buildRows()
  }

  private fun buildRows() {
    val screen = preferenceScreen ?: return
    screen.removeAll()
    val context = requireContext()
    for (item in items) {
      val row = AccessibilitySuitePreference(context)
      row.title = getString(item.titleRes)
      row.summary = summaryFor(item)
      row.isPersistent = false
      row.isIconSpaceReserved = false
      row.setOnPreferenceClickListener {
        showEditor(item)
        true
      }
      screen.addPreference(row)
    }
    val restoreAll = AccessibilitySuitePreference(context)
    restoreAll.title = getString(R.string.voice_speech_restore_all)
    restoreAll.isPersistent = false
    restoreAll.isIconSpaceReserved = false
    restoreAll.setOnPreferenceClickListener {
      confirmRestoreAll()
      true
    }
    screen.addPreference(restoreAll)
  }

  private fun summaryFor(item: VoiceCommandSpeechOverrides.Item): String {
    val context = requireContext()
    val text = VoiceCommandSpeechOverrides.phrases(context, item).joinToString(", ")
    return if (VoiceCommandSpeechOverrides.isOverridden(context, item)) {
      text + " " + getString(R.string.voice_speech_overridden_suffix)
    } else {
      text
    }
  }

  private fun showEditor(item: VoiceCommandSpeechOverrides.Item) {
    val context = requireContext()
    val input = EditText(context)
    input.setText(VoiceCommandSpeechOverrides.phrases(context, item).joinToString(", "))
    input.setSelection(input.text.length)
    var message = getString(R.string.voice_speech_edit_message)
    if (item.hintRes != 0) {
      message += "\n" + getString(item.hintRes)
    }
    val builder =
        AlertDialog.Builder(context)
            .setTitle(item.titleRes)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.voice_speech_save, null)
            .setNegativeButton(android.R.string.cancel, null)
    if (VoiceCommandSpeechOverrides.isOverridden(context, item)) {
      builder.setNeutralButton(R.string.voice_speech_restore_default) { _, _ ->
        VoiceCommandSpeechOverrides.setPhrases(context, item, null)
        buildRows()
      }
    }
    val dialog = builder.create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val parsed = VoiceCommandSpeechOverrides.sanitize(input.text.toString().split(","))
        if (parsed.isEmpty()) {
          // An item with no way of saying it could never be spoken again, so an
          // empty edit is refused instead of saved.
          input.error = getString(R.string.voice_speech_empty_error)
        } else {
          VoiceCommandSpeechOverrides.setPhrases(context, item, parsed)
          dialog.dismiss()
          buildRows()
        }
      }
    }
    dialog.show()
  }

  private fun confirmRestoreAll() {
    val context = requireContext()
    AlertDialog.Builder(context)
        .setTitle(R.string.voice_speech_restore_all)
        .setMessage(R.string.voice_speech_restore_all_message)
        .setPositiveButton(R.string.voice_speech_restore) { _, _ ->
          VoiceCommandSpeechOverrides.restoreAll(context, items)
          buildRows()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }
}

/** The keyword groups a voice command is built from. */
class VoiceCommandKeywordListFragment : VoiceCommandSpeechListFragment() {

  override val items: List<VoiceCommandSpeechOverrides.Item>
    get() = VoiceCommandSpeechOverrides.KEYWORD_ITEMS

  override fun getTitle(): CharSequence = getText(R.string.title_voice_command_keywords)
}

/** The fixed commands and their phrasings. */
class VoiceCommandFixedListFragment : VoiceCommandSpeechListFragment() {

  override val items: List<VoiceCommandSpeechOverrides.Item>
    get() = VoiceCommandSpeechOverrides.COMMAND_ITEMS

  override fun getTitle(): CharSequence = getText(R.string.title_voice_command_fixed_commands)
}
