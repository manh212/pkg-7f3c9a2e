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

package com.google.android.accessibility.talkback.backup

import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/**
 * One thing the Backup screen can back up and restore on its own. These are the
 * pieces the full backup already holds: the preference files, and one section each
 * for the editor documents, custom labels, clipboard history, favorite texts,
 * timer sounds, key sounds, sound themes and scripts. The order follows the full
 * backup's, so [SettingsBackup.export] stays byte-identical.
 *
 * Gesture profiles, voice profiles, the speech dictionary, custom voice-command
 * phrasings and keymaps are not items of their own: each lives as keys in the
 * preference files, so they ride inside [SETTINGS]. Splitting the preference
 * files further would need a key-to-feature map that does not exist, and a wrong
 * split would silently drop keys.
 */
enum class BackupItem(
  /** The row's preference key, as a string resource like the other backup rows. */
  @StringRes val keyRes: Int,
  /** The row's title, and the item's name in the done and failed messages. */
  @StringRes val titleRes: Int,
  /** The file name the system file picker offers for this item's backup. */
  val fileName: String,
) {
  SETTINGS(
    R.string.pref_backup_item_settings_key,
    R.string.backup_item_settings,
    "meridian-settings-only.json",
  ),
  EDITOR_DOCUMENTS(
    R.string.pref_backup_item_editor_documents_key,
    R.string.backup_item_editor_documents,
    "meridian-editor-documents.json",
  ),
  CUSTOM_LABELS(
    R.string.pref_backup_item_custom_labels_key,
    R.string.backup_item_custom_labels,
    "meridian-custom-labels.json",
  ),
  CLIPBOARD_HISTORY(
    R.string.pref_backup_item_clipboard_history_key,
    R.string.backup_item_clipboard_history,
    "meridian-clipboard-history.json",
  ),
  FAVORITE_TEXTS(
    R.string.pref_backup_item_favorite_texts_key,
    R.string.backup_item_favorite_texts,
    "meridian-favorite-texts.json",
  ),
  TIMER_SOUNDS(
    R.string.pref_backup_item_timer_sounds_key,
    R.string.backup_item_timer_sounds,
    "meridian-timer-sounds.json",
  ),
  KEY_SOUNDS(
    R.string.pref_backup_item_key_sounds_key,
    R.string.backup_item_key_sounds,
    "meridian-key-sounds.json",
  ),
  SOUND_THEMES(
    R.string.pref_backup_item_sound_themes_key,
    R.string.backup_item_sound_themes,
    "meridian-sound-themes.json",
  ),
  SCRIPTS(
    R.string.pref_backup_item_scripts_key,
    R.string.backup_item_scripts,
    "meridian-scripts.json",
  ),
}

/**
 * The per-item rows of the Backup screen. Each row asks whether to back up just
 * that item to its own file or restore just that item from a file: the file holds
 * only that item, in the full backup's own format, so the full Import row can
 * read it too, and restoring it touches nothing else.
 *
 * This is not SandFly's per-screen Export to file / Import from file: there is
 * no separate "sandfly-items" format and no per-screen export elsewhere, only
 * these rows inside the Backup system.
 */
object BackupItems {
  /** Sets up one row per [BackupItem] in the Backup screen. */
  @JvmStatic
  fun setUpPreferences(fragment: PreferenceFragmentCompat) {
    // Watches have no file picker.
    val wear = FormFactorUtils.isAndroidWear()
    for (item in BackupItem.entries) {
      val row =
        fragment.findPreference<Preference>(fragment.getString(item.keyRes)) ?: continue
      if (wear) {
        row.parent?.removePreference(row)
        continue
      }
      val create =
        fragment.registerForActivityResult(
          ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
          uri?.let {
            SettingsBackup.writeItem(fragment.requireContext().applicationContext, it, item)
          }
        }
      val open =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
          uri?.let {
            SettingsBackup.readItem(fragment.requireContext().applicationContext, it, item)
          }
        }
      row.setOnPreferenceClickListener {
        val context = fragment.requireContext()
        A11yAlertDialogWrapper.materialDialogBuilder(context, fragment.parentFragmentManager)
          .setTitle(item.titleRes)
          .setMessage(R.string.backup_item_dialog_message)
          .setPositiveButton(R.string.backup_item_export_action) { _, _ ->
            create.launch(item.fileName)
          }
          .setNeutralButton(R.string.backup_item_import_action) { _, _ ->
            open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
          }
          .setNegativeButton(android.R.string.cancel, null)
          .create()
          .show()
        true
      }
    }
  }
}
