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
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.BindingEntry
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.ResetScope
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.TargetKind
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.talkback.gesture.CustomMenus
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.talkback.gesture.TapTextActions
import com.google.android.accessibility.talkback.gesture.TapTextEditor
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture
import com.google.android.accessibility.talkback.keyboard.KeyComboManager
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.talkback.preference.base.TalkBackGestureShortcutPreferenceFragment
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Unified Commands List: single entry point to search, browse, assign, edit, remove, and reset
 * every command across all 10 input and menu surfaces.
 */
class CommandsFragment : TalkbackBaseFragment() {

  private var allGestureSlots: List<CommandBindings.GestureSlot> = emptyList()
  private var showAssignedOnly: Boolean = false
  private var searchQuery: String = ""

  override fun getTitle(): CharSequence = getString(R.string.title_pref_commands)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    allGestureSlots = readGestures(this) + CommandBindings.arrowKeys(requireContext())
    searchQuery =
      arguments?.getString(ARG_SEARCH_QUERY)
        ?: arguments?.getString(TalkBackGestureShortcutPreferenceFragment.ACTION_SEARCH_QUERY)
        ?: ""
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  override fun onResume() {
    super.onResume()
    rebuild()
  }

  private fun rebuild() {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val keyComboManager = CommandBindings.keyComboManager(context)
    val categories = Commands.categories(context)
    val staticCommands = categories.flatMap { it.commands }
    val bindingIndex =
      UnifiedCommandBindings.buildBindingIndex(
        context,
        prefs,
        allGestureSlots,
        keyComboManager,
        staticCommands,
      )
    val tapTextCommands = UnifiedCommandBindings.collectTapTextCommands(context, bindingIndex)
    val allCommands = staticCommands + tapTextCommands

    val screen = preferenceScreen
    screen.removeAll()

    // Top controls category
    val controls =
      AccessibilitySuitePreferenceCategory(context).apply {
        key = "pref_commands_controls"
        title = getString(R.string.commands_controls_header)
        isIconSpaceReserved = false
      }
    screen.addPreference(controls)

    controls.addPreference(
      AccessibilitySuitePreference(context).apply {
        key = "pref_commands_search"
        isPersistent = false
        isIconSpaceReserved = false
        title = getString(R.string.commands_search_title)
        summary =
          if (searchQuery.isNotBlank()) {
            getString(R.string.commands_search_active_summary, searchQuery)
          } else {
            getString(R.string.commands_search_hint)
          }
        setOnPreferenceClickListener {
          showSearchDialog()
          true
        }
      }
    )

    controls.addPreference(
      SwitchPreferenceCompat(context).apply {
        key = "pref_commands_filter_assigned"
        isPersistent = false
        isIconSpaceReserved = false
        title = getString(R.string.commands_filter_assigned_only)
        isChecked = showAssignedOnly
        setOnPreferenceChangeListener { _, newValue ->
          showAssignedOnly = newValue as Boolean
          rebuild()
          true
        }
      }
    )

    controls.addPreference(
      AccessibilitySuitePreference(context).apply {
        key = "pref_commands_add_tap_text"
        isPersistent = false
        isIconSpaceReserved = false
        title = getString(R.string.tap_text_add)
        setOnPreferenceClickListener {
          TapTextEditor.show(context, null) { composedValue ->
            val label = TapTextActions.label(context, composedValue) ?: composedValue
            val cmd = Commands.Command(composedValue, label, keyboardAction = null)
            UnifiedCommandDialogs.showChooseTargetDialog(
              fragment = this@CommandsFragment,
              context = context,
              prefs = prefs,
              allGestureSlots = allGestureSlots,
              keyComboManager = keyComboManager,
              command = cmd,
              onChanged = { rebuild() },
            )
          }
          true
        }
      }
    )

    controls.addPreference(
      AccessibilitySuitePreference(context).apply {
        key = "pref_commands_reset"
        isPersistent = false
        isIconSpaceReserved = false
        title = getString(R.string.commands_reset_defaults)
        setOnPreferenceClickListener {
          UnifiedCommandDialogs.showResetDialog(
            context = context,
            prefs = prefs,
            allGestureSlots = allGestureSlots,
            keyComboManager = keyComboManager,
            onChanged = { rebuild() },
          )
          true
        }
      }
    )

    // Flat filtered list when searching or filtering assigned-only
    if (searchQuery.isNotBlank() || showAssignedOnly) {
      val q = searchQuery.trim().lowercase()
      val matched =
        allCommands.filter { cmd ->
          val bindings = bindingIndex[cmd.value].orEmpty()
          val matchesFilter = !showAssignedOnly || bindings.isNotEmpty()
          val matchesQuery =
            q.isEmpty() ||
              cmd.label.lowercase().contains(q) ||
              bindings.any { it.displayLabel.lowercase().contains(q) }
          matchesFilter && matchesQuery
        }
      val resultsCat =
        AccessibilitySuitePreferenceCategory(context).apply {
          key = "pref_commands_results"
          title = getString(R.string.commands_results_header, matched.size)
          isIconSpaceReserved = false
        }
      screen.addPreference(resultsCat)
      if (matched.isEmpty()) {
        resultsCat.addPreference(
          AccessibilitySuitePreference(context).apply {
            key = "pref_commands_no_results"
            isPersistent = false
            isIconSpaceReserved = false
            isSelectable = false
            title = getString(R.string.commands_no_results)
          }
        )
      } else {
        for (cmd in matched) {
          addCommandRow(resultsCat, cmd, bindingIndex[cmd.value].orEmpty(), prefs, keyComboManager)
        }
      }
      return
    }

    // Category list
    val groupsCat =
      AccessibilitySuitePreferenceCategory(context).apply {
        key = "pref_commands_groups"
        title = getString(R.string.commands_categories_header)
        isIconSpaceReserved = false
      }
    screen.addPreference(groupsCat)

    if (tapTextCommands.isNotEmpty()) {
      val assignedCount = tapTextCommands.count { !bindingIndex[it.value].isNullOrEmpty() }
      groupsCat.addPreference(
        AccessibilitySuitePreference(context).apply {
          key = "pref_commands_tap_text_category"
          isPersistent = false
          isIconSpaceReserved = false
          title = getString(R.string.tap_text_title)
          summary = getString(R.string.commands_category_summary, assignedCount, tapTextCommands.size)
          fragment = CommandCategoryFragment::class.java.name
          extras.putInt(CommandCategoryFragment.ARG_CATEGORY, CommandCategoryFragment.CATEGORY_TAP_TEXT)
        }
      )
    }

    for (category in categories) {
      val assignedCount = category.commands.count { !bindingIndex[it.value].isNullOrEmpty() }
      groupsCat.addPreference(
        AccessibilitySuitePreference(context).apply {
          key = "pref_commands_${category.titleRes}"
          isPersistent = false
          isIconSpaceReserved = false
          title = getString(category.titleRes)
          summary =
            getString(R.string.commands_category_summary, assignedCount, category.commands.size)
          fragment = CommandCategoryFragment::class.java.name
          extras.putInt(CommandCategoryFragment.ARG_CATEGORY, category.titleRes)
        }
      )
    }
  }

  private fun addCommandRow(
    parent: PreferenceCategory,
    command: Commands.Command,
    bindings: List<BindingEntry>,
    prefs: SharedPreferences,
    keyComboManager: KeyComboManager,
  ) {
    val context = requireContext()
    parent.addPreference(
      AccessibilitySuitePreference(context).apply {
        key = "pref_cmd_row_${command.value}"
        isPersistent = false
        isIconSpaceReserved = false
        title = command.label
        summary =
          bindings.joinToString(", ") { it.displayLabel }.ifEmpty {
            getString(R.string.commands_unassigned_summary)
          }
        setOnPreferenceClickListener {
          UnifiedCommandDialogs.onCommandClicked(
            fragment = this@CommandsFragment,
            context = context,
            prefs = prefs,
            allGestureSlots = allGestureSlots,
            keyComboManager = keyComboManager,
            command = command,
            bindings = bindings,
            onChanged = { rebuild() },
          )
          true
        }
      }
    )
  }

  private fun showSearchDialog() {
    val context = requireContext()
    val input =
      EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        hint = getString(R.string.commands_search_hint)
        setText(searchQuery)
        setSelection(text.length)
      }
    AlertDialog.Builder(context)
      .setTitle(R.string.commands_search_title)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        searchQuery = input.text?.toString()?.trim().orEmpty()
        rebuild()
      }
      .setNeutralButton(R.string.commands_search_clear) { _, _ ->
        searchQuery = ""
        rebuild()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  companion object {
    const val ARG_SEARCH_QUERY = "commands_search_query"
  }
}

/**
 * The commands of one category, each displaying all assigned inputs/menus and supporting direct
 * assign (if unassigned) or add/edit/remove (if already assigned).
 */
class CommandCategoryFragment : TalkbackBaseFragment() {

  private val categoryTitle: Int
    get() = arguments?.getInt(ARG_CATEGORY) ?: 0

  override fun getTitle(): CharSequence =
    when (categoryTitle) {
      CATEGORY_TAP_TEXT -> getString(R.string.tap_text_title)
      0 -> getString(R.string.title_pref_commands)
      else -> getString(categoryTitle)
    }

  private var allGestureSlots: List<CommandBindings.GestureSlot> = emptyList()

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    allGestureSlots = readGestures(this) + CommandBindings.arrowKeys(requireContext())
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  override fun onResume() {
    super.onResume()
    fill()
  }

  private fun fill() {
    val context = requireContext()
    val screen = preferenceScreen
    screen.removeAll()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manager = CommandBindings.keyComboManager(context)
    val categories = Commands.categories(context)
    val staticCommands = categories.flatMap { it.commands }
    val bindingIndex =
      UnifiedCommandBindings.buildBindingIndex(context, prefs, allGestureSlots, manager, staticCommands)

    val commandsToShow: List<Commands.Command> =
      if (categoryTitle == CATEGORY_TAP_TEXT) {
        UnifiedCommandBindings.collectTapTextCommands(context, bindingIndex)
      } else {
        categories.firstOrNull { it.titleRes == categoryTitle }?.commands.orEmpty()
      }

    for (command in commandsToShow) {
      val bindings = bindingIndex[command.value].orEmpty()
      val summaryText =
        bindings.joinToString(", ") { it.displayLabel }.ifEmpty {
          getString(R.string.commands_unassigned_summary)
        }
      screen.addPreference(
        AccessibilitySuitePreference(context).apply {
          key = "pref_command_${command.value}"
          isPersistent = false
          isIconSpaceReserved = false
          title = command.label
          summary = summaryText
          setOnPreferenceClickListener {
            UnifiedCommandDialogs.onCommandClicked(
              fragment = this@CommandCategoryFragment,
              context = context,
              prefs = prefs,
              allGestureSlots = allGestureSlots,
              keyComboManager = manager,
              command = command,
              bindings = bindings,
              onChanged = { fill() },
            )
            true
          }
        }
      )
    }
  }

  companion object {
    const val ARG_CATEGORY = "category"
    const val CATEGORY_TAP_TEXT = -101
  }
}

/** Shared assignment, edit, remove, and reset dialogs for [CommandsFragment] and [CommandCategoryFragment]. */
internal object UnifiedCommandDialogs {

  fun onCommandClicked(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    bindings: List<BindingEntry>,
    onChanged: () -> Unit,
  ) {
    if (bindings.isEmpty()) {
      showChooseTargetDialog(
        fragment,
        context,
        prefs,
        allGestureSlots,
        keyComboManager,
        command,
        onChanged,
      )
      return
    }
    val labels = mutableListOf<String>()
    labels.add(context.getString(R.string.commands_add_binding))
    for (b in bindings) {
      labels.add(b.displayLabel)
    }
    AlertDialog.Builder(context)
      .setTitle(command.label)
      .setItems(labels.toTypedArray()) { _, which ->
        if (which == 0) {
          showChooseTargetDialog(
            fragment,
            context,
            prefs,
            allGestureSlots,
            keyComboManager,
            command,
            onChanged,
          )
        } else {
          val selected = bindings[which - 1]
          showManageSingleBindingDialog(
            fragment,
            context,
            prefs,
            allGestureSlots,
            keyComboManager,
            command,
            selected,
            onChanged,
          )
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showManageSingleBindingDialog(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    entry: BindingEntry,
    onChanged: () -> Unit,
  ) {
    val canEditSlot =
      entry.kind != TargetKind.MERIDIAN_MENU && entry.kind != TargetKind.CUSTOM_MENU
    val actions =
      if (canEditSlot) {
        arrayOf(
          context.getString(R.string.commands_edit_binding),
          context.getString(R.string.commands_remove_binding),
        )
      } else {
        arrayOf(context.getString(R.string.commands_remove_binding))
      }
    AlertDialog.Builder(context)
      .setTitle(entry.displayLabel)
      .setItems(actions) { _, which ->
        if (canEditSlot && which == 0) {
          // Edit: pick a new slot of the same kind, then clear the old one if different
          if (entry.kind == TargetKind.KEYBOARD_SHORTCUT) {
            openKeyboardShortcutScreen(fragment, command)
          } else {
            UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
            dispatchTargetAssignment(
              fragment,
              context,
              prefs,
              allGestureSlots,
              keyComboManager,
              command,
              entry.kind,
              onChanged,
            )
          }
        } else {
          UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
          onChanged()
          PreferencesActivityUtils.announceText(
            context.getString(R.string.commands_binding_removed, entry.displayLabel),
            context,
          )
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  fun showChooseTargetDialog(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    onChanged: () -> Unit,
  ) {
    val options = mutableListOf<Pair<TargetKind, String>>()
    options.add(TargetKind.TOUCH_GESTURE to context.getString(R.string.commands_target_touch_gesture))
    if (UnifiedCommandBindings.supportsFingerprintGestures(context)) {
      options.add(
        TargetKind.FINGERPRINT_GESTURE to
          context.getString(R.string.commands_target_fingerprint_gesture)
      )
    }
    options.add(TargetKind.EDGE_GESTURE to context.getString(R.string.commands_target_edge_gesture))
    options.add(TargetKind.BACK_TAP to context.getString(R.string.commands_target_back_tap))
    options.add(
      TargetKind.KEYBOARD_SHORTCUT to context.getString(R.string.commands_target_keyboard_shortcut)
    )
    options.add(TargetKind.VOLUME_KEY to context.getString(R.string.commands_target_volume_key))
    options.add(TargetKind.MERIDIAN_MENU to context.getString(R.string.commands_target_meridian_menu))
    options.add(TargetKind.CUSTOM_MENU to context.getString(R.string.commands_target_custom_menu))
    options.add(
      TargetKind.GESTURE_PROFILE to context.getString(R.string.commands_target_gesture_profile)
    )
    options.add(TargetKind.APP_GESTURE to context.getString(R.string.commands_target_app_gesture))

    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.commands_choose_target_title, command.label))
      .setItems(options.map { it.second }.toTypedArray()) { _, which ->
        dispatchTargetAssignment(
          fragment,
          context,
          prefs,
          allGestureSlots,
          keyComboManager,
          command,
          options[which].first,
          onChanged,
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun dispatchTargetAssignment(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    kind: TargetKind,
    onChanged: () -> Unit,
  ) {
    when (kind) {
      TargetKind.TOUCH_GESTURE -> {
        val slots =
          allGestureSlots.filter {
            !UnifiedCommandBindings.isBackTapKey(context, it.key) &&
              !UnifiedCommandBindings.isFingerprintKey(context, it.key)
          }
        pickGestureSlot(context, prefs, slots, command, R.string.command_choose_gesture) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.FINGERPRINT_GESTURE -> {
        val slots =
          allGestureSlots.filter { UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        pickGestureSlot(
          context,
          prefs,
          slots,
          command,
          R.string.commands_target_fingerprint_gesture,
        ) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.BACK_TAP -> {
        val slots = allGestureSlots.filter { UnifiedCommandBindings.isBackTapKey(context, it.key) }
        pickGestureSlot(context, prefs, slots, command, R.string.commands_target_back_tap) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.EDGE_GESTURE -> {
        val unassigned = context.getString(R.string.shortcut_value_unassigned)
        val edges = EdgeGesture.values().toList()
        val labels =
          edges.map { edge ->
            val current = edge.action(prefs, unassigned)
            val name = UnifiedCommandBindings.edgeSlotLabel(context, edge)
            if (current.isNullOrEmpty() || current == unassigned) name
            else "$name: ${Commands.label(context, current) ?: current}"
          }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_edge_gesture)
          .setItems(labels.toTypedArray()) { _, which ->
            val edge = edges[which]
            UnifiedCommandBindings.assignEdgeGesture(prefs, edge, command.value)
            onChanged()
            announceAssigned(context, UnifiedCommandBindings.edgeSlotLabel(context, edge), command.label)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.VOLUME_KEY -> {
        val unassigned = context.getString(R.string.shortcut_value_unassigned)
        val slots = UnifiedCommandBindings.volumeSlots(context)
        val labels =
          slots.map { slot ->
            val current = prefs.getString(slot.key, unassigned) ?: unassigned
            if (current.isEmpty() || current == unassigned) slot.label
            else "${slot.label}: ${Commands.label(context, current) ?: current}"
          }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_volume_key)
          .setItems(labels.toTypedArray()) { _, which ->
            val slot = slots[which]
            UnifiedCommandBindings.assignVolumeSlot(prefs, slot.key, command.value)
            onChanged()
            announceAssigned(context, slot.label, command.label)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.KEYBOARD_SHORTCUT -> openKeyboardShortcutScreen(fragment, command)
      TargetKind.MERIDIAN_MENU -> {
        val currentlyIn = UnifiedCommandBindings.isInMeridianMenu(context, prefs, command.value)
        UnifiedCommandBindings.setInMeridianMenu(context, prefs, command.value, !currentlyIn)
        onChanged()
        PreferencesActivityUtils.announceText(
          context.getString(
            if (!currentlyIn) R.string.commands_added_to_meridian_menu
            else R.string.commands_removed_from_meridian_menu,
            command.label,
          ),
          context,
        )
      }
      TargetKind.CUSTOM_MENU -> {
        val menus = CustomMenus.menus(context)
        if (menus.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_custom_menu)
            .setMessage(R.string.commands_no_custom_menus_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_custom_menu)
          .setItems(menus.map { it.name }.toTypedArray()) { _, which ->
            val menu = menus[which]
            CustomMenus.addItem(context, menu.id, command.label, command.value)
            onChanged()
            announceAssigned(context, menu.name, command.label)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.GESTURE_PROFILE -> {
        val profiles = GestureProfiles.read(prefs)
        if (profiles.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_gesture_profile)
            .setMessage(R.string.commands_no_gesture_profiles_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        val touchSlots =
          allGestureSlots.filter { !UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_gesture_profile)
          .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
            val profile = profiles[which]
            val slotLabels =
              touchSlots.map { slot ->
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                val existing = profile.gestures[baseKey]
                if (existing.isNullOrEmpty()) slot.name.toString()
                else "${slot.name}: ${Commands.label(context, existing) ?: existing}"
              }
            AlertDialog.Builder(context)
              .setTitle(profile.name)
              .setItems(slotLabels.toTypedArray()) { _, slotIdx ->
                val slot = touchSlots[slotIdx]
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                UnifiedCommandBindings.assignToProfile(prefs, profile.id, baseKey, command.value)
                onChanged()
                announceAssigned(context, "${profile.name}: ${slot.name}", command.label)
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.APP_GESTURE -> {
        val appsMap = AppGestures.read(prefs)
        if (appsMap.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_app_gesture)
            .setMessage(R.string.commands_no_app_gestures_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        val entries = appsMap.entries.toList()
        val touchSlots =
          allGestureSlots.filter { !UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_app_gesture)
          .setItems(entries.map { it.value.name }.toTypedArray()) { _, which ->
            val (pkg, app) = entries[which]
            val slotLabels =
              touchSlots.map { slot ->
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                val existing = app.gestures[baseKey]
                if (existing.isNullOrEmpty()) slot.name.toString()
                else "${slot.name}: ${Commands.label(context, existing) ?: existing}"
              }
            AlertDialog.Builder(context)
              .setTitle(app.name)
              .setItems(slotLabels.toTypedArray()) { _, slotIdx ->
                val slot = touchSlots[slotIdx]
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                UnifiedCommandBindings.assignToApp(prefs, pkg, app.name, baseKey, command.value)
                onChanged()
                announceAssigned(context, "${app.name}: ${slot.name}", command.label)
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
  }

  private fun pickGestureSlot(
    context: Context,
    prefs: SharedPreferences,
    slots: List<CommandBindings.GestureSlot>,
    command: Commands.Command,
    titleRes: Int,
    onPicked: (CommandBindings.GestureSlot) -> Unit,
  ) {
    val unassigned = context.getString(R.string.shortcut_value_unassigned)
    val labels =
      slots.map { slot ->
        val current = slot.value(context, prefs)
        if (current == unassigned) slot.name.toString()
        else "${slot.name}: ${Commands.label(context, current) ?: current}"
      }
    AlertDialog.Builder(context)
      .setTitle(titleRes)
      .setItems(labels.toTypedArray()) { _, which ->
        val slot = slots[which]
        val current = slot.value(context, prefs)
        if (current == unassigned || current == command.value) {
          onPicked(slot)
        } else {
          AlertDialog.Builder(context)
            .setMessage(
              context.getString(
                R.string.command_replace_gesture,
                slot.name,
                Commands.label(context, current) ?: current,
                command.label,
              )
            )
            .setPositiveButton(R.string.command_replace) { _, _ -> onPicked(slot) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun openKeyboardShortcutScreen(
    fragment: TalkbackBaseFragment,
    command: Commands.Command,
  ) {
    val target = CommandFragment().apply {
      arguments = Bundle().apply { putString(CommandFragment.ARG_COMMAND, command.value) }
    }
    fragment.parentFragmentManager
      .beginTransaction()
      .replace(fragment.id, target)
      .addToBackStack(null)
      .commit()
  }

  fun showResetDialog(
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    onChanged: () -> Unit,
  ) {
    val items =
      listOf(
        ResetScope.TOUCH_AND_FINGERPRINT to context.getString(R.string.commands_reset_gestures),
        ResetScope.EDGE_GESTURES to context.getString(R.string.commands_reset_edge),
        ResetScope.BACK_TAP to context.getString(R.string.commands_reset_back_tap),
        ResetScope.KEYBOARD_SHORTCUTS to context.getString(R.string.commands_reset_keyboard),
        ResetScope.VOLUME_KEYS to context.getString(R.string.commands_reset_volume),
        ResetScope.MERIDIAN_MENU to context.getString(R.string.commands_reset_meridian_menu),
        ResetScope.ALL to context.getString(R.string.commands_reset_all),
      )
    AlertDialog.Builder(context)
      .setTitle(R.string.commands_reset_defaults)
      .setItems(items.map { it.second }.toTypedArray()) { _, which ->
        val (scope, label) = items[which]
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_reset_defaults)
          .setMessage(context.getString(R.string.commands_reset_confirm, label))
          .setPositiveButton(android.R.string.ok) { _, _ ->
            UnifiedCommandBindings.resetBindings(context, prefs, allGestureSlots, keyComboManager, scope)
            onChanged()
            PreferencesActivityUtils.announceText(
              context.getString(R.string.commands_reset_done, label),
              context,
            )
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun announceAssigned(context: Context, slotLabel: String, commandLabel: String) {
    PreferencesActivityUtils.announceText(
      context.getString(R.string.command_gesture_assigned, slotLabel, commandLabel),
      context,
    )
  }
}

/**
 * Returns the gestures that can be assigned, read from the Gestures screen, which [fragment] shows
 * only until it sets its own screen.
 */
internal fun readGestures(fragment: TalkbackBaseFragment): List<CommandBindings.GestureSlot> {
  fragment.addPreferencesFromResource(R.xml.gesture_preferences)
  return CommandBindings.gestures(fragment.requireContext(), fragment.preferenceScreen)
}

internal fun readGestureCategories(fragment: TalkbackBaseFragment): List<CommandBindings.GestureCategory> {
  fragment.addPreferencesFromResource(R.xml.gesture_preferences)
  return CommandBindings.gestureCategories(fragment.requireContext(), fragment.preferenceScreen)
}
