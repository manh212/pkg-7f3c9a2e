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

import android.content.Context
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.keyboard.ArrowKeyNav
import com.google.android.accessibility.talkback.keyboard.ArrowKeyNavCommand
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.talkback.preference.base.KeyboardShortcutDialogPreference
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * One command: the gestures, arrow keys and keyboard shortcut that run it, to change or add to.
 */
class CommandFragment : TalkbackBaseFragment() {

  private val prefs by lazy { SharedPreferencesUtils.getSharedPreferences(requireContext()) }

  private val command: Commands.Command? by lazy {
    Commands.find(requireContext(), arguments?.getString(ARG_COMMAND))
  }

  override fun getTitle(): CharSequence = command?.label ?: getString(R.string.title_pref_commands)

  /** The gestures, then the arrow keys of arrow key navigation: each can run one command. */
  private class Section(
    val name: String,
    @StringRes val title: Int,
    @StringRes val none: Int,
    @StringRes val add: Int,
    @StringRes val choose: Int,
    @StringRes val remove: Int,
    val slots: List<CommandBindings.GestureSlot>,
  )

  private var sections: List<Section> = emptyList()

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    sections = buildSections()
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  /**
   * Reads the current gesture and arrow-key slots. Rebuilt on every fill: arrow-key
   * slots cache their value at construction, so a reassignment must rebuild them.
   */
  private fun buildSections(): List<Section> {
    val gestures = readGestures(this)
    return listOf(
      Section(
        "gestures",
        R.string.command_gestures,
        R.string.command_no_gestures,
        R.string.command_add_gesture,
        R.string.command_choose_gesture,
        R.string.command_remove_gesture,
        gestures,
      ),
      Section(
        "arrow_keys",
        R.string.keycombo_menu_category_arrow_key_nav,
        R.string.command_no_arrow_keys,
        R.string.command_add_arrow_keys,
        R.string.command_choose_arrow_keys,
        R.string.command_remove_arrow_keys,
        CommandBindings.arrowKeys(requireContext()),
      ),
    )
  }

  override fun onResume() {
    super.onResume()
    fill()
  }

  private fun fill() {
    val context = requireContext()
    sections = buildSections()
    val screen = preferenceScreen
    screen.removeAll()
    val command = command ?: return

    for (section in sections) {
      addSection(section, command)
    }

    val manager = CommandBindings.keyComboManager(context)
    val model = manager.keyComboModel ?: return
    val keyboardCategory =
      AccessibilitySuitePreferenceCategory(context).apply {
        key = "pref_command_keyboard"
        title = getString(R.string.command_keyboard_shortcut)
        isIconSpaceReserved = false
      }
    screen.addPreference(keyboardCategory)
    val key = CommandBindings.keyboardKey(context, model, command)
    keyboardCategory.addPreference(
      KeyboardShortcutDialogPreference(context, key, command.label).apply {
        isIconSpaceReserved = false
        // Recording a shortcut needs the screen reader's key events.
        isEnabled = TalkBackService.isServiceActive() && CommandBindings.isCustomizable(context, key)
      }
    )
  }

  /** Adds [section]: what of it runs [command], or a line saying nothing does, then Add. */
  private fun addSection(section: Section, command: Commands.Command) {
    val context = requireContext()
    val category =
      AccessibilitySuitePreferenceCategory(context).apply {
        key = "pref_command_${section.name}"
        title = getString(section.title)
        isIconSpaceReserved = false
      }
    preferenceScreen.addPreference(category)
    val assigned = section.slots.filter { it.value(context, prefs) == command.value }
    if (assigned.isEmpty()) {
      category.addPreference(
        AccessibilitySuitePreference(context).apply {
          key = "pref_command_no_${section.name}"
          isPersistent = false
          isIconSpaceReserved = false
          isSelectable = false
          title = getString(section.none)
        }
      )
    }
    for (slot in assigned) {
      category.addPreference(
        AccessibilitySuitePreference(context).apply {
          key = "pref_command_slot_${slot.key}"
          isPersistent = false
          isIconSpaceReserved = false
          title = slot.name
          setOnPreferenceClickListener {
            confirmRemove(slot, section.remove)
            true
          }
        }
      )
    }
    category.addPreference(
      AccessibilitySuitePreference(context).apply {
        key = "pref_command_add_${section.name}"
        com.google.android.accessibility.talkback.preference.base.CreateNewShortcut.mark(this)
        isPersistent = false
        isIconSpaceReserved = false
        title = getString(section.add)
        setOnPreferenceClickListener {
          chooseGesture(command, section.slots, section.choose)
          true
        }
      }
    )
  }

  private fun confirmRemove(gesture: CommandBindings.GestureSlot, @StringRes remove: Int) {
    AlertDialog.Builder(requireContext())
      .setTitle(gesture.name)
      .setPositiveButton(remove) { _, _ ->
        assign(gesture, getString(R.string.shortcut_value_unassigned))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun chooseGesture(
    command: Commands.Command,
    gestures: List<CommandBindings.GestureSlot>,
    @StringRes chooseTitle: Int,
  ) {
    val context = requireContext()
    val unassigned = getString(R.string.shortcut_value_unassigned)
    val choices = gestures.filter { it.value(context, prefs) != command.value }
    val names =
      choices.map { gesture ->
        val current = gesture.value(context, prefs)
        if (current == unassigned) {
          gesture.name
        } else {
          "${gesture.name}: ${Commands.label(context, current) ?: current}"
        }
      }
    AlertDialog.Builder(context)
      .setTitle(chooseTitle)
      .setItems(names.toTypedArray()) { _, which ->
        val gesture = choices[which]
        val current = gesture.value(context, prefs)
        if (current == unassigned) {
          assign(gesture, command.value)
        } else {
          confirmReplace(gesture, current, command)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmReplace(
    gesture: CommandBindings.GestureSlot,
    current: String,
    command: Commands.Command,
  ) {
    val context = requireContext()
    AlertDialog.Builder(context)
      .setMessage(
        getString(
          R.string.command_replace_gesture,
          gesture.name,
          Commands.label(context, current) ?: current,
          command.label,
        )
      )
      .setPositiveButton(R.string.command_replace) { _, _ -> assign(gesture, command.value) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun assign(gesture: CommandBindings.GestureSlot, value: String) {
    if (!assignArrowSlot(gesture, value)) {
      prefs.edit().putString(gesture.key, value).apply()
    }
    fill()
    val context = requireContext()
    PreferencesActivityUtils.announceText(
      getString(
        R.string.command_gesture_assigned,
        gesture.name,
        Commands.label(context, value) ?: getString(R.string.keycombo_unassigned),
      ),
      context,
    )
  }

  /**
   * Assigns [value] to an arrow-key slot, routing through [ArrowKeyNav]: arrow-key
   * assignments are long prefs (`arrow_key_nav_keys_<name>`), not the slot's synthetic
   * string key, so a plain `putString` would land nowhere. Returns true when
   * [gesture] is an arrow-key slot and the write was handled; false otherwise, so the
   * caller writes the string pref as usual. Removing (value == unassigned) frees the
   * slot's keys; assigning moves the slot's keys to the command [value] runs.
   */
  private fun assignArrowSlot(gesture: CommandBindings.GestureSlot, value: String): Boolean {
    val context = requireContext()
    val slotCommand = arrowCommandForSlot(gesture) ?: return false
    val unassigned = getString(R.string.shortcut_value_unassigned)
    if (value == unassigned) {
      ArrowKeyNav.save(context, slotCommand, ArrowKeyNav.UNASSIGNED)
      return true
    }
    val target =
      ArrowKeyNavCommand.entries.firstOrNull { arrowKeyCommandValue(context, it) == value }
        ?: return false
    if (target == slotCommand) return true
    val keys = ArrowKeyNav.keysFor(context, slotCommand)
    ArrowKeyNav.save(context, slotCommand, ArrowKeyNav.UNASSIGNED)
    ArrowKeyNav.save(context, target, keys)
    return true
  }

  /** The arrow-key command a slot stands for, from its synthetic key (see CommandBindings.arrowKeys). */
  private fun arrowCommandForSlot(gesture: CommandBindings.GestureSlot): ArrowKeyNavCommand? {
    val name = gesture.key.removePrefix(ARROW_SLOT_KEY_PREFIX)
    if (name == gesture.key) return null
    return ArrowKeyNavCommand.entries.firstOrNull { it.name.lowercase() == name }
  }

  /** The command value [command] runs, or null when it runs no command. */
  private fun arrowKeyCommandValue(context: Context, command: ArrowKeyNavCommand): String? {
    command.action?.let {
      return Commands.valueOfKeyboardAction(context, it)
    }
    return if (command == ArrowKeyNavCommand.TOGGLE) {
      context.getString(R.string.shortcut_value_toggle_arrow_key_nav)
    } else {
      null
    }
  }

  companion object {
    const val ARG_COMMAND = "command"

    /** Prefix of the synthetic keys CommandBindings.arrowKeys() gives its slots. */
    private const val ARROW_SLOT_KEY_PREFIX = "arrow_key_nav_slot_"
  }
}
