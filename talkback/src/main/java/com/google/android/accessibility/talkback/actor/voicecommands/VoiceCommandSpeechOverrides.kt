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

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.google.android.accessibility.talkback.R

/**
 * The ways of saying voice commands that the user has replaced with their own.
 *
 * Every built-in keyword list and fixed command is phrased by string resources. In Settings,
 * the user can replace the phrasings of a whole item — a keyword group, or one fixed command
 * with all of its resource variants — and from then on only their phrasings are heard for
 * that item; the built-in ones no longer match. An item they never touched keeps its
 * defaults. Overrides are stored per string resource, keyed by the resource's entry name,
 * in one default-preferences value, so the settings backup carries them like the taught
 * phrases.
 */
object VoiceCommandSpeechOverrides {

  const val PREF_KEY = "pref_voice_command_speech_overrides"

  /** The select-all command matches an Android string, which has no entry name here. */
  private const val SELECT_ALL_KEY = "!select_all"
  private const val LINE_SEPARATOR = "\n"
  private const val FIELD_SEPARATOR = "\t"
  private const val PHRASE_SEPARATOR = "\u0002"
  private val WHITESPACE = Regex("\\s+")

  /** One editable row: a keyword group, or a fixed command and its resource variants. */
  class Item(
      val id: String,
      @StringRes val titleRes: Int,
      val resIds: List<Int>,
      val keyword: Boolean,
      @StringRes val hintRes: Int = 0,
  )

  val KEYWORD_ITEMS: List<Item> =
      listOf(
          Item("volume", R.string.voice_word_volume, listOf(R.string.voice_param_volume), true),
          Item(
              "media_volume",
              R.string.voice_word_media_volume,
              listOf(R.string.voice_param_media_volume),
              true),
          Item(
              "speech_rate",
              R.string.voice_word_speech_rate,
              listOf(R.string.voice_param_speech_rate),
              true),
          Item("percent", R.string.voice_word_percent, listOf(R.string.voice_param_percent), true),
          Item("alarm", R.string.voice_word_alarm, listOf(R.string.voice_param_alarm), true),
          Item(
              "countdown",
              R.string.voice_word_countdown,
              listOf(R.string.voice_param_countdown),
              true),
          Item("hours", R.string.voice_word_hours, listOf(R.string.voice_param_hours), true),
          Item("minutes", R.string.voice_word_minutes, listOf(R.string.voice_param_minutes), true),
          Item("seconds", R.string.voice_word_seconds, listOf(R.string.voice_param_seconds), true),
          Item("times", R.string.voice_word_times, listOf(R.string.voice_param_times), true),
          Item(
              "increase",
              R.string.voice_word_increase,
              listOf(R.string.voice_param_increase),
              true),
          Item(
              "decrease",
              R.string.voice_word_decrease,
              listOf(R.string.voice_param_decrease),
              true),
          Item("at", R.string.voice_word_at, listOf(R.string.voice_param_at), true),
          Item(
              "day_parts",
              R.string.voice_word_day_parts,
              listOf(R.string.voice_param_day_parts),
              true),
          Item(
              "item_goto",
              R.string.voice_word_item_goto,
              listOf(R.string.voice_param_item_goto),
              true),
          Item("item", R.string.voice_word_item, listOf(R.string.voice_param_item), true),
          Item(
              "number_word",
              R.string.voice_word_number_word,
              listOf(R.string.voice_param_number_word),
              true),
          Item(
              "call_number",
              R.string.voice_word_call,
              listOf(R.string.voice_param_call),
              true),
          Item(
              "yes_answers",
              R.string.voice_word_yes_answers,
              listOf(R.string.voice_commands_yes_answers),
              true),
          Item(
              "create_openers",
              R.string.voice_word_create_openers,
              listOf(R.string.voice_phrase_create_openers),
              true),
          Item(
              "set_prefixes",
              R.string.voice_word_set_prefixes,
              listOf(R.string.voice_phrase_set_prefixes),
              true),
          Item(
              "separator",
              R.string.voice_word_separator,
              listOf(R.string.voice_phrase_separator),
              true),
          Item(
              "list_commands",
              R.string.voice_word_list_commands,
              listOf(R.string.voice_phrase_list_commands),
              true),
          Item(
              "delete_prefixes",
              R.string.voice_word_delete_prefixes,
              listOf(R.string.voice_phrase_delete_prefixes),
              true),
          Item(
              "cancel_words",
              R.string.voice_word_cancel_words,
              listOf(R.string.voice_phrase_cancel_words),
              true),
          Item(
              "finish_words",
              R.string.voice_word_finish_words,
              listOf(R.string.voice_phrase_finish_words),
              true),
          Item(
              "chain_connectors",
              R.string.voice_word_chain_connectors,
              listOf(R.string.voice_chain_connectors),
              true),
          Item(
              "continuous_start",
              R.string.voice_word_continuous_start,
              listOf(R.string.voice_continuous_start_commands),
              true),
          Item(
              "continuous_stop",
              R.string.voice_word_continuous_stop,
              listOf(R.string.voice_continuous_stop_words),
              true),
          Item(
              "help_words",
              R.string.voice_word_help_words,
              listOf(R.string.voice_commands_what, R.string.voice_commands_say),
              true),
          Item(
              "editor_select",
              R.string.voice_word_editor_select,
              listOf(R.string.voice_editor_select),
              true),
          Item(
              "editor_delete",
              R.string.voice_word_editor_delete,
              listOf(R.string.voice_editor_delete),
              true),
          Item(
              "editor_read",
              R.string.voice_word_editor_read,
              listOf(R.string.voice_editor_read),
              true),
          Item(
              "editor_read_all",
              R.string.voice_word_editor_read_all,
              listOf(R.string.voice_editor_read_all),
              true),
          Item(
              "editor_undo",
              R.string.voice_word_editor_undo,
              listOf(R.string.voice_editor_undo),
              true),
          Item(
              "editor_redo",
              R.string.voice_word_editor_redo,
              listOf(R.string.voice_editor_redo),
              true),
          Item(
              "editor_find",
              R.string.voice_word_editor_find,
              listOf(R.string.voice_editor_find),
              true),
      )

  val COMMAND_ITEMS: List<Item> =
      listOf(
          Item(
              "read_current",
              R.string.voice_fixed_read_current,
              listOf(R.string.shortcut_read_from_current, R.string.voice_commands_read_from_next),
              false),
          Item(
              "read_top",
              R.string.voice_fixed_read_top,
              listOf(R.string.title_read_from_top),
              false),
          Item(
              "first",
              R.string.voice_fixed_first,
              listOf(R.string.voice_commands_first, R.string.voice_commands_top),
              false),
          Item(
              "last",
              R.string.voice_fixed_last,
              listOf(R.string.voice_commands_last, R.string.voice_commands_bottom),
              false),
          Item(
              "next_heading",
              R.string.voice_fixed_next_heading,
              listOf(R.string.voice_commands_next_heading),
              false),
          Item(
              "next_control",
              R.string.voice_fixed_next_control,
              listOf(R.string.voice_commands_next_control),
              false),
          Item(
              "next_link",
              R.string.voice_fixed_next_link,
              listOf(R.string.voice_commands_next_link),
              false),
          Item(
              "next_landmark",
              R.string.voice_fixed_next_landmark,
              listOf(R.string.voice_commands_next_landmark),
              false),
          Item(
              "verbosity",
              R.string.voice_fixed_verbosity,
              listOf(
                  R.string.voice_commands_verbosity_parameter,
                  R.string.voice_commands_parameter_verbosity,
                  R.string.voice_commands_change_verbosity_to_parameter),
              false,
              hintRes = R.string.voice_speech_format_hint),
          Item(
              "granularity",
              R.string.voice_fixed_granularity,
              listOf(
                  R.string.voice_commands_navigation_by_parameter,
                  R.string.voice_commands_parameter_navigation,
                  R.string.voice_commands_parameter_granularity,
                  R.string.voice_commands_read_by_parameter),
              false,
              hintRes = R.string.voice_speech_format_hint),
          Item(
              "find",
              R.string.voice_fixed_find,
              listOf(
                  R.string.voice_commands_find,
                  R.string.voice_commands_search_for,
                  R.string.voice_commands_search),
              false),
          Item(
              "screen_search",
              R.string.voice_fixed_screen_search,
              listOf(
                  R.string.voice_commands_screen_search,
                  R.string.voice_commands_search_on_screen),
              false),
          Item(
              "faster",
              R.string.voice_fixed_faster,
              listOf(
                  R.string.voice_commands_faster, R.string.voice_commands_increase_speech_rate),
              false),
          Item(
              "slower",
              R.string.voice_fixed_slower,
              listOf(
                  R.string.voice_commands_slower, R.string.voice_commands_decrease_speech_rate),
              false),
          Item(
              "language",
              R.string.voice_fixed_language,
              listOf(R.string.voice_commands_language, R.string.voice_commands_languages),
              false),
          Item(
              "select_all",
              R.string.voice_fixed_select_all,
              listOf(android.R.string.selectAll),
              false),
          Item(
              "select_start",
              R.string.voice_fixed_select_start,
              listOf(
                  R.string.voice_commands_select,
                  R.string.voice_commands_start_select,
                  R.string.voice_commands_start_selection,
                  R.string.voice_commands_start_selection_mode),
              false),
          Item(
              "select_finish",
              R.string.voice_fixed_select_finish,
              listOf(
                  R.string.voice_commands_finish_select,
                  R.string.voice_commands_finish_selection_mode,
                  R.string.voice_commands_finish_selection,
                  R.string.voice_commands_end_select),
              false),
          Item("copy", R.string.voice_fixed_copy, listOf(R.string.voice_commands_copy), false),
          Item("cut", R.string.voice_fixed_cut, listOf(R.string.voice_commands_cut), false),
          Item("paste", R.string.voice_fixed_paste, listOf(R.string.voice_commands_paste), false),
          Item(
              "delete", R.string.voice_fixed_delete, listOf(R.string.voice_commands_delete), false),
          Item(
              "type",
              R.string.voice_fixed_type,
              listOf(
                  R.string.voice_commands_type,
                  R.string.voice_commands_input,
                  R.string.voice_commands_dictate,
                  R.string.voice_commands_write,
                  R.string.voice_commands_Spell),
              false),
          Item(
              "back",
              R.string.voice_fixed_back,
              listOf(R.string.voice_commands_back, R.string.voice_commands_go_back),
              false),
          Item(
              "home",
              R.string.voice_fixed_home,
              listOf(R.string.voice_commands_home, R.string.voice_commands_desktop),
              false),
          Item(
              "recent_apps",
              R.string.voice_fixed_recent_apps,
              listOf(
                  R.string.voice_commands_overview,
                  R.string.voice_commands_recent_apps,
                  R.string.voice_commands_recent,
                  R.string.voice_commands_recents),
              false),
          Item(
              "all_apps",
              R.string.voice_fixed_all_apps,
              listOf(R.string.voice_commands_all_apps, R.string.voice_commands_apps),
              false),
          Item(
              "notifications",
              R.string.voice_fixed_notifications,
              listOf(R.string.voice_commands_notification, R.string.voice_commands_notifications),
              false),
          Item(
              "quick_settings",
              R.string.voice_fixed_quick_settings,
              listOf(R.string.voice_commands_quick_settings, R.string.voice_commands_quick_setting),
              false),
          Item(
              "assistant",
              R.string.voice_fixed_assistant,
              listOf(R.string.voice_commands_assistant),
              false),
          Item(
              "dim_screen",
              R.string.voice_fixed_dim_screen,
              listOf(R.string.voice_commands_dim, R.string.voice_commands_darken),
              false),
          Item(
              "show_screen",
              R.string.voice_fixed_show_screen,
              listOf(
                  R.string.shortcut_disable_dimming,
                  R.string.voice_commands_brighten_screen,
                  R.string.voice_commands_restore_screen,
                  R.string.voice_commands_cancel_hide_screen),
              false),
          Item(
              "custom_actions",
              R.string.voice_fixed_custom_actions,
              listOf(
                  R.string.title_custom_action,
                  R.string.voice_commands_custom_actions,
                  R.string.voice_commands_action),
              false),
          Item(
              "label", R.string.voice_fixed_label, listOf(R.string.voice_commands_label), false),
          Item(
              "talkback_settings",
              R.string.voice_fixed_talkback_settings,
              listOf(
                  R.string.voice_commands_talkback_settings,
                  R.string.voice_commands_talkback_setting),
              false),
          Item(
              "stop",
              R.string.voice_fixed_stop,
              listOf(
                  R.string.voice_commands_stop,
                  R.string.voice_commands_quit,
                  R.string.voice_commands_quiet,
                  R.string.voice_commands_pause,
                  R.string.voice_commands_nevermind,
                  R.string.voice_commands_shut_up),
              false),
          Item("help", R.string.voice_fixed_help, listOf(R.string.title_pref_help), false),
      )

  /** All overrides, keyed by storage key. */
  fun load(context: Context): Map<String, List<String>> =
      parse(
          PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null))

  internal fun parse(stored: String?): Map<String, List<String>> {
    if (stored.isNullOrEmpty()) {
      return emptyMap()
    }
    val result = LinkedHashMap<String, List<String>>()
    for (line in stored.split(LINE_SEPARATOR)) {
      val parts = line.split(FIELD_SEPARATOR)
      if (parts.size != 2) {
        continue
      }
      val phrases = parts[1].split(PHRASE_SEPARATOR).filter { it.isNotEmpty() }
      if (parts[0].isNotEmpty() && phrases.isNotEmpty()) {
        result[parts[0]] = phrases
      }
    }
    return result
  }

  internal fun serialize(overrides: Map<String, List<String>>): String =
      overrides.entries.joinToString(LINE_SEPARATOR) { (key, phrases) ->
        key + FIELD_SEPARATOR + phrases.joinToString(PHRASE_SEPARATOR)
      }

  /** Trims a phrase list typed by the user: no empty or duplicate ways of saying. */
  internal fun sanitize(raw: List<String>): List<String> =
      raw.map { it.replace(WHITESPACE, " ").trim() }.filter { it.isNotEmpty() }.distinct()

  /** The phrasings an item has without any override. */
  fun defaults(context: Context, item: Item): List<String> =
      if (item.keyword) {
        item.resIds.flatMap { VoiceCommandBilingual.list(context, it) }
      } else {
        item.resIds.map { context.getString(it) }
      }

  /** The phrasings currently in effect for an item: the user's, or the defaults. */
  fun phrases(context: Context, item: Item): List<String> {
    val overrides = load(context)
    for (resId in item.resIds) {
      val key = keyFor(context, resId) ?: continue
      overrides[key]?.let {
        return it
      }
    }
    return defaults(context, item)
  }

  fun isOverridden(context: Context, item: Item): Boolean {
    val overrides = load(context)
    return item.resIds.any { resId ->
      val key = keyFor(context, resId)
      key != null && overrides.containsKey(key)
    }
  }

  /** Sets an item's own phrasings, or restores its defaults when [phrases] is null. */
  fun setPhrases(context: Context, item: Item, phrases: List<String>?) {
    val overrides = load(context).toMutableMap()
    applyTo(overrides, context, item, phrases)
    save(context, overrides)
  }

  /** Restores the defaults of every item on one settings page. */
  fun restoreAll(context: Context, items: List<Item>) {
    val overrides = load(context).toMutableMap()
    for (item in items) {
      applyTo(overrides, context, item, null)
    }
    save(context, overrides)
  }

  /**
   * The user's phrasings for one string resource, or null when it is not overridden. This is
   * the one lookup every matcher uses, so an override applies everywhere the resource is
   * matched: keyword lists, fixed commands, chain connectors and listening words alike.
   */
  @JvmStatic
  fun forResource(context: Context, @StringRes resId: Int): List<String>? {
    val key = keyFor(context, resId) ?: return null
    return load(context)[key]
  }

  private fun applyTo(
      overrides: MutableMap<String, List<String>>,
      context: Context,
      item: Item,
      phrases: List<String>?,
  ) {
    val cleaned = phrases?.let { sanitize(it) }
    for (resId in item.resIds) {
      val key = keyFor(context, resId) ?: continue
      if (cleaned.isNullOrEmpty()) {
        overrides.remove(key)
      } else {
        overrides[key] = cleaned
      }
    }
  }

  private fun save(context: Context, overrides: Map<String, List<String>>) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putString(PREF_KEY, serialize(overrides))
        .apply()
  }

  private fun keyFor(context: Context, @StringRes resId: Int): String? {
    if (resId == android.R.string.selectAll) {
      return SELECT_ALL_KEY
    }
    return try {
      context.resources.getResourceEntryName(resId)
    } catch (e: Resources.NotFoundException) {
      null
    }
  }
}
