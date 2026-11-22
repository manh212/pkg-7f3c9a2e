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
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.talkback.gesture.CustomMenus
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.talkback.gesture.TapTextActions
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture
import com.google.android.accessibility.talkback.keyboard.KeyComboManager
import com.google.android.accessibility.talkback.keyboard.KeyComboModel
import com.google.android.accessibility.talkback.volume.VolumeKeyManager
import com.google.android.accessibility.utils.FeatureSupport
import java.util.LinkedHashSet

/**
 * Unified index and mutation layer for every assignable input and menu surface in Meridian:
 * screen gestures, fingerprint gestures, edge gestures, back-tap, hardware keyboard shortcuts,
 * volume keys, Meridian menu, custom menus, gesture profiles, and per-app gestures.
 */
object UnifiedCommandBindings {

  const val PREF_PINNED_MERIDIAN_MENU_COMMANDS = "pref_context_menu_pinned_commands"

  enum class TargetKind {
    TOUCH_GESTURE,
    FINGERPRINT_GESTURE,
    EDGE_GESTURE,
    BACK_TAP,
    KEYBOARD_SHORTCUT,
    VOLUME_KEY,
    MERIDIAN_MENU,
    CUSTOM_MENU,
    GESTURE_PROFILE,
    APP_GESTURE,
  }

  enum class ResetScope {
    TOUCH_AND_FINGERPRINT,
    EDGE_GESTURES,
    BACK_TAP,
    KEYBOARD_SHORTCUTS,
    VOLUME_KEYS,
    MERIDIAN_MENU,
    ALL,
  }

  data class BindingEntry(
    val kind: TargetKind,
    val displayLabel: String,
    val slotKey: String,
    val extraId: String = "",
    val extraIndex: Int = -1,
  )

  data class VolumeSlot(val key: String, val label: String)

  private data class BuiltinMenuMapping(
    val shortcutValueRes: Int,
    val menuPrefKeyRes: Int,
    val defaultBoolRes: Int,
  )

  private val BUILTIN_MERIDIAN_MENU_MAPPINGS =
    listOf(
      BuiltinMenuMapping(
        R.string.shortcut_value_show_custom_actions,
        R.string.pref_show_context_menu_custom_action_setting_key,
        R.bool.pref_show_context_menu_custom_action_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_describe_text_formatting,
        R.string.pref_show_context_menu_text_formatting_setting_key,
        R.bool.pref_show_context_menu_text_formatting_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_describe_image,
        R.string.pref_show_context_menu_image_caption_setting_key,
        R.bool.pref_show_context_menu_image_caption_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_screen_overview,
        R.string.pref_show_context_menu_summarize_view_setting_key,
        R.bool.pref_show_context_menu_summarize_view_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_read_from_top,
        R.string.pref_show_context_menu_read_from_top_setting_key,
        R.bool.pref_show_context_menu_read_from_top_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_read_from_current,
        R.string.pref_show_context_menu_read_from_current_setting_key,
        R.bool.pref_show_context_menu_read_from_current_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_copy_last_spoken_phrase,
        R.string.pref_show_context_menu_copy_last_spoken_phrase_setting_key,
        R.bool.pref_show_context_menu_copy_last_spoken_phrase_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_spell_last_spoken_phrase,
        R.string.pref_show_context_menu_spell_last_spoken_phrase_setting_key,
        R.bool.pref_show_context_menu_spell_last_spoken_phrase_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_repeat_last_spoken_phrase,
        R.string.pref_show_context_menu_repeat_last_spoken_phrase_setting_key,
        R.bool.pref_show_context_menu_repeat_last_spoken_phrase_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_translate_last_spoken_phrase,
        R.string.pref_show_context_menu_translate_last_spoken_phrase_setting_key,
        R.bool.pref_show_context_menu_translate_last_spoken_phrase_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_show_language_options,
        R.string.pref_show_context_menu_language_setting_key,
        R.bool.pref_show_context_menu_language_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_voice_profiles,
        R.string.pref_show_context_menu_voice_profile_setting_key,
        R.bool.pref_show_context_menu_voice_profile_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_screen_search,
        R.string.pref_show_context_menu_find_on_screen_setting_key,
        R.bool.pref_show_context_menu_find_on_screen_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_show_hide_screen,
        R.string.pref_show_context_menu_dim_or_brighten_setting_key,
        R.bool.pref_show_context_menu_dim_or_brighten_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_pause_backtalk,
        R.string.pref_show_context_menu_pause_backtalk_setting_key,
        R.bool.pref_show_context_menu_pause_backtalk_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_turn_off_sandfly,
        R.string.pref_show_context_menu_turn_off_setting_key,
        R.bool.pref_show_context_menu_turn_off_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_inspect_item,
        R.string.pref_show_context_menu_inspect_item_setting_key,
        R.bool.pref_show_context_menu_inspect_item_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_copy_item_details,
        R.string.pref_show_context_menu_copy_item_details_setting_key,
        R.bool.pref_show_context_menu_copy_item_details_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_copy_screen_tree,
        R.string.pref_show_context_menu_copy_screen_tree_setting_key,
        R.bool.pref_show_context_menu_copy_screen_tree_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_voice_commands,
        R.string.pref_show_context_menu_voice_commands_setting_key,
        R.bool.pref_show_context_menu_voice_commands_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_clipboard_history,
        R.string.pref_show_context_menu_clipboard_history_setting_key,
        R.bool.pref_show_context_menu_clipboard_history_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_lock_screen,
        R.string.pref_show_context_menu_lock_screen_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_copy_focused,
        R.string.pref_show_context_menu_copy_focused_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_direct_tap,
        R.string.pref_show_context_menu_direct_tap_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_monitor_focus,
        R.string.pref_show_context_menu_monitor_focus_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_copy_full_screen,
        R.string.pref_show_context_menu_copy_full_screen_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_take_focus_screenshot,
        R.string.pref_show_context_menu_focus_screenshot_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
      BuiltinMenuMapping(
        R.string.shortcut_value_subtitle_find_again,
        R.string.pref_show_context_menu_subtitle_find_again_key,
        R.bool.pref_show_context_menu_extra_hidden_default,
      ),
    )

  @JvmStatic
  fun pinnedMeridianMenuCommands(prefs: SharedPreferences): Set<String> =
    prefs.getStringSet(PREF_PINNED_MERIDIAN_MENU_COMMANDS, emptySet()).orEmpty()

  fun isInMeridianMenu(context: Context, prefs: SharedPreferences, commandValue: String): Boolean {
    val builtin = findBuiltinMenuMapping(context, commandValue)
    if (builtin != null) {
      return prefs.getBoolean(
        context.getString(builtin.menuPrefKeyRes),
        context.resources.getBoolean(builtin.defaultBoolRes),
      )
    }
    return commandValue in pinnedMeridianMenuCommands(prefs)
  }

  fun setInMeridianMenu(
    context: Context,
    prefs: SharedPreferences,
    commandValue: String,
    enabled: Boolean,
  ) {
    val builtin = findBuiltinMenuMapping(context, commandValue)
    if (builtin != null) {
      prefs.edit().putBoolean(context.getString(builtin.menuPrefKeyRes), enabled).apply()
      return
    }
    val next = LinkedHashSet(pinnedMeridianMenuCommands(prefs))
    if (enabled) next.add(commandValue) else next.remove(commandValue)
    prefs.edit().putStringSet(PREF_PINNED_MERIDIAN_MENU_COMMANDS, next).apply()
  }

  private fun findBuiltinMenuMapping(context: Context, commandValue: String): BuiltinMenuMapping? =
    BUILTIN_MERIDIAN_MENU_MAPPINGS.firstOrNull { context.getString(it.shortcutValueRes) == commandValue }

  fun isBackTapKey(context: Context, key: String): Boolean {
    val dbl = context.getString(R.string.pref_shortcut_back_tap_double_key)
    val trp = context.getString(R.string.pref_shortcut_back_tap_triple_key)
    return key == dbl || key == trp || key.startsWith("$dbl-") || key.startsWith("$trp-")
  }

  fun isFingerprintKey(context: Context, key: String): Boolean {
    return key.startsWith("pref_shortcut_fingerprint_")
  }

  fun volumeSlots(context: Context): List<VolumeSlot> {
    val list = mutableListOf<VolumeSlot>()
    val sides = listOf("up" to "Tăng âm lượng", "down" to "Giảm âm lượng")
    val states =
      listOf(
        "" to "Màn hình bật",
        "_locked" to "Màn hình khóa",
        "_screen_off" to "Màn hình tắt",
      )
    for ((sideKey, sideLabel) in sides) {
      for ((stateSuffix, stateLabel) in states) {
        val actions =
          listOf(
            "pref_volume_${sideKey}_press${stateSuffix}_action" to "Nhấn 1 lần",
            "pref_volume_${sideKey}_2_press${stateSuffix}_action" to "Nhấn 2 lần",
            "pref_volume_${sideKey}_3_press${stateSuffix}_action" to "Nhấn 3 lần",
            "pref_volume_${sideKey}_4_press${stateSuffix}_action" to "Nhấn 4 lần",
            "pref_volume_${sideKey}_hold${stateSuffix}_action" to "Giữ ngắn",
            "pref_volume_${sideKey}_hold_long${stateSuffix}_action" to "Giữ dài",
          )
        for ((prefKey, actionLabel) in actions) {
          list.add(VolumeSlot(prefKey, "$sideLabel · $actionLabel ($stateLabel)"))
        }
      }
    }
    list.add(VolumeSlot(VolumeKeyManager.KEY_BOTH_ACTION, "Nhấn đồng thời hai phím âm lượng"))
    return list
  }

  fun edgeSlotLabel(context: Context, gesture: EdgeGesture): String =
    "${context.getString(gesture.edge.title)}: ${gesture.title(context)}"

  fun assignEdgeGesture(prefs: SharedPreferences, gesture: EdgeGesture, commandValue: String) {
    prefs.edit()
      .putBoolean(EdgeGesture.ENABLED, true)
      .putString(gesture.key, commandValue)
      .apply()
  }

  fun clearEdgeGesture(context: Context, prefs: SharedPreferences, gestureKey: String) {
    prefs.edit().putString(gestureKey, context.getString(R.string.shortcut_value_unassigned)).apply()
  }

  fun assignVolumeSlot(prefs: SharedPreferences, slotKey: String, commandValue: String) {
    prefs.edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(slotKey, commandValue)
      .apply()
  }

  fun clearVolumeSlot(context: Context, prefs: SharedPreferences, slotKey: String) {
    prefs.edit().putString(slotKey, context.getString(R.string.shortcut_value_unassigned)).apply()
  }

  fun assignToProfile(
    prefs: SharedPreferences,
    profileId: String,
    gestureBaseKey: String,
    commandValue: String,
  ) {
    val profiles = GestureProfiles.read(prefs).toMutableList()
    val idx = profiles.indexOfFirst { it.id == profileId }
    if (idx < 0) return
    val p = profiles[idx]
    val nextGestures = p.gestures.toMutableMap()
    nextGestures[gestureBaseKey] = commandValue
    profiles[idx] = p.copy(gestures = nextGestures)
    GestureProfiles.write(prefs, profiles)
  }

  fun removeFromProfile(prefs: SharedPreferences, profileId: String, gestureBaseKey: String) {
    val profiles = GestureProfiles.read(prefs).toMutableList()
    val idx = profiles.indexOfFirst { it.id == profileId }
    if (idx < 0) return
    val p = profiles[idx]
    val nextGestures = p.gestures.toMutableMap()
    nextGestures.remove(gestureBaseKey)
    profiles[idx] = p.copy(gestures = nextGestures)
    GestureProfiles.write(prefs, profiles)
  }

  fun assignToApp(
    prefs: SharedPreferences,
    packageName: String,
    appName: String,
    gestureBaseKey: String,
    commandValue: String,
  ) {
    val apps = AppGestures.read(prefs).toMutableMap()
    val existing = apps[packageName] ?: AppGestures.App(appName, emptyMap())
    val nextGestures = existing.gestures.toMutableMap()
    nextGestures[gestureBaseKey] = commandValue
    apps[packageName] = existing.copy(gestures = nextGestures)
    AppGestures.write(prefs, apps)
  }

  fun removeFromApp(prefs: SharedPreferences, packageName: String, gestureBaseKey: String) {
    val apps = AppGestures.read(prefs).toMutableMap()
    val existing = apps[packageName] ?: return
    val nextGestures = existing.gestures.toMutableMap()
    nextGestures.remove(gestureBaseKey)
    if (nextGestures.isEmpty() && existing.keys.isEmpty()) {
      apps.remove(packageName)
    } else {
      apps[packageName] = existing.copy(gestures = nextGestures)
    }
    AppGestures.write(prefs, apps)
  }

  /**
   * Builds an in-memory map from `commandValue` to all active [BindingEntry] records across all
   * 10 supported surfaces so the Commands List renders and filters instantaneously.
   */
  fun buildBindingIndex(
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    allCommands: List<Commands.Command>,
  ): Map<String, List<BindingEntry>> {
    val index = LinkedHashMap<String, MutableList<BindingEntry>>()
    val unassigned = context.getString(R.string.shortcut_value_unassigned)

    fun put(cmdValue: String, entry: BindingEntry) {
      if (cmdValue.isEmpty() || cmdValue == unassigned) return
      index.getOrPut(cmdValue) { mutableListOf() }.add(entry)
    }

    // 1. Touch, fingerprint & back-tap slots from gesture_preferences
    val baseKeyToName = HashMap<String, String>()
    for (slot in allGestureSlots) {
      val baseKey = stripGestureSetSuffix(slot.key)
      baseKeyToName[baseKey] = slot.name.toString()
      val value = slot.value(context, prefs)
      val kind =
        when {
          isBackTapKey(context, slot.key) -> TargetKind.BACK_TAP
          isFingerprintKey(context, slot.key) -> TargetKind.FINGERPRINT_GESTURE
          else -> TargetKind.TOUCH_GESTURE
        }
      put(value, BindingEntry(kind, slot.name.toString(), slot.key))
    }

    // 2. Edge gestures
    for (edge in EdgeGesture.values()) {
      val value = edge.action(prefs, unassigned)
      if (!value.isNullOrEmpty() && value != unassigned) {
        put(value, BindingEntry(TargetKind.EDGE_GESTURE, edgeSlotLabel(context, edge), edge.key))
      }
    }

    // 3. Volume keys
    for (slot in volumeSlots(context)) {
      val value = prefs.getString(slot.key, unassigned) ?: unassigned
      if (value.isNotEmpty() && value != unassigned) {
        put(value, BindingEntry(TargetKind.VOLUME_KEY, slot.label, slot.key))
      }
    }

    // 4. Hardware keyboard shortcuts
    val model = keyComboManager.keyComboModel
    if (model != null) {
      for (cmd in allCommands) {
        val kbKey = CommandBindings.keyboardKey(context, model, cmd)
        val text = CommandBindings.keyboardShortcutText(keyComboManager, kbKey)
        if (!text.isNullOrEmpty()) {
          put(cmd.value, BindingEntry(TargetKind.KEYBOARD_SHORTCUT, "Phím tắt: $text", kbKey))
        }
      }
    }

    // 5. Meridian Menu (built-in + dynamic pinned)
    for (mapping in BUILTIN_MERIDIAN_MENU_MAPPINGS) {
      val cmdValue = context.getString(mapping.shortcutValueRes)
      if (isInMeridianMenu(context, prefs, cmdValue)) {
        put(
          cmdValue,
          BindingEntry(
            TargetKind.MERIDIAN_MENU,
            "Menu Meridian",
            context.getString(mapping.menuPrefKeyRes),
          ),
        )
      }
    }
    for (pinned in pinnedMeridianMenuCommands(prefs)) {
      if (findBuiltinMenuMapping(context, pinned) == null) {
        put(
          pinned,
          BindingEntry(TargetKind.MERIDIAN_MENU, "Menu Meridian", PREF_PINNED_MERIDIAN_MENU_COMMANDS),
        )
      }
    }

    // 6. Custom menus
    for (menu in CustomMenus.menus(context)) {
      menu.items.forEachIndexed { itemIdx, item ->
        put(
          item.value,
          BindingEntry(
            TargetKind.CUSTOM_MENU,
            "Menu ${menu.name}",
            CustomMenus.PREF_KEY,
            extraId = menu.id,
            extraIndex = itemIdx,
          ),
        )
      }
    }

    // 7. Gesture profiles
    for (profile in GestureProfiles.read(prefs)) {
      for ((gestureBaseKey, value) in profile.gestures) {
        val gName = baseKeyToName[gestureBaseKey] ?: gestureBaseKey
        put(
          value,
          BindingEntry(
            TargetKind.GESTURE_PROFILE,
            "Hồ sơ ${profile.name}: $gName",
            gestureBaseKey,
            extraId = profile.id,
          ),
        )
      }
    }

    // 8. Per-app gestures
    for ((pkg, app) in AppGestures.read(prefs)) {
      for ((gestureBaseKey, value) in app.gestures) {
        val gName = baseKeyToName[gestureBaseKey] ?: gestureBaseKey
        put(
          value,
          BindingEntry(
            TargetKind.APP_GESTURE,
            "${app.name}: $gName",
            gestureBaseKey,
            extraId = pkg,
          ),
        )
      }
    }

    return index
  }

  /** Collects every existing TapText (`TAP_TEXT:...`) command across all bindings. */
  fun collectTapTextCommands(
    context: Context,
    bindingIndex: Map<String, List<BindingEntry>>,
  ): List<Commands.Command> {
    val result = mutableListOf<Commands.Command>()
    for (cmdValue in bindingIndex.keys) {
      if (TapTextActions.isAction(cmdValue)) {
        val label = TapTextActions.label(context, cmdValue) ?: cmdValue
        result.add(Commands.Command(cmdValue, label, keyboardAction = null))
      }
    }
    return result
  }

  fun removeBinding(
    context: Context,
    prefs: SharedPreferences,
    keyComboManager: KeyComboManager,
    commandValue: String,
    entry: BindingEntry,
  ) {
    val unassigned = context.getString(R.string.shortcut_value_unassigned)
    when (entry.kind) {
      TargetKind.TOUCH_GESTURE,
      TargetKind.FINGERPRINT_GESTURE,
      TargetKind.BACK_TAP -> prefs.edit().putString(entry.slotKey, unassigned).apply()
      TargetKind.EDGE_GESTURE -> clearEdgeGesture(context, prefs, entry.slotKey)
      TargetKind.VOLUME_KEY -> clearVolumeSlot(context, prefs, entry.slotKey)
      TargetKind.KEYBOARD_SHORTCUT -> {
        keyComboManager.keyComboModel?.saveKeyComboCode(
          entry.slotKey,
          KeyComboModel.KEY_COMBO_CODE_UNASSIGNED.toLong(),
        )
      }
      TargetKind.MERIDIAN_MENU -> setInMeridianMenu(context, prefs, commandValue, enabled = false)
      TargetKind.CUSTOM_MENU -> {
        if (entry.extraId.isNotEmpty() && entry.extraIndex >= 0) {
          CustomMenus.removeItem(context, entry.extraId, entry.extraIndex)
        }
      }
      TargetKind.GESTURE_PROFILE -> removeFromProfile(prefs, entry.extraId, entry.slotKey)
      TargetKind.APP_GESTURE -> removeFromApp(prefs, entry.extraId, entry.slotKey)
    }
  }

  fun resetBindings(
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    scope: ResetScope,
  ) {
    val editor = prefs.edit()
    if (scope == ResetScope.TOUCH_AND_FINGERPRINT || scope == ResetScope.ALL) {
      for (slot in allGestureSlots) {
        if (!isBackTapKey(context, slot.key)) {
          editor.remove(slot.key)
        }
      }
    }
    if (scope == ResetScope.BACK_TAP || scope == ResetScope.ALL) {
      for (slot in allGestureSlots) {
        if (isBackTapKey(context, slot.key)) {
          editor.remove(slot.key)
        }
      }
    }
    if (scope == ResetScope.EDGE_GESTURES || scope == ResetScope.ALL) {
      for (edge in EdgeGesture.values()) {
        editor.remove(edge.key)
      }
    }
    if (scope == ResetScope.VOLUME_KEYS || scope == ResetScope.ALL) {
      for (slot in volumeSlots(context)) {
        editor.remove(slot.key)
      }
    }
    if (scope == ResetScope.MERIDIAN_MENU || scope == ResetScope.ALL) {
      for (mapping in BUILTIN_MERIDIAN_MENU_MAPPINGS) {
        editor.remove(context.getString(mapping.menuPrefKeyRes))
      }
      editor.remove(PREF_PINNED_MERIDIAN_MENU_COMMANDS)
    }
    editor.apply()

    if (scope == ResetScope.KEYBOARD_SHORTCUTS || scope == ResetScope.ALL) {
      val model = keyComboManager.keyComboModel
      if (model != null) {
        for (key in model.keyComboMap.keys.toList()) {
          if (key.startsWith(Commands.SHORTCUT_KEY_PREFIX)) {
            model.saveKeyComboCode(key, KeyComboModel.KEY_COMBO_CODE_UNASSIGNED.toLong())
          } else {
            model.saveKeyComboCode(key, model.getDefaultKeyComboCode(key))
          }
        }
      }
    }
  }

  fun supportsFingerprintGestures(context: Context): Boolean =
    FeatureSupport.isFingerprintGestureSupported(context)

  fun stripGestureSetSuffix(slotKey: String): String {
    val dash = slotKey.lastIndexOf('-')
    if (dash > 0 && slotKey.substring(dash + 1).all { it.isDigit() }) {
      return slotKey.substring(0, dash)
    }
    return slotKey
  }
}
