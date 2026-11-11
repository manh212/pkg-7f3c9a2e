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
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * The voice command phrases the user taught Meridian: a spoken phrase of their own for one of
 * the assignable actions. They are stored as one value of the main preferences, so the
 * settings backup carries them to a new phone like other settings, and they are matched
 * before the catalog of action names, so a taught phrase wins over a built-in name.
 */
object VoiceCommandPhrases {

  /**
   * A taught phrase, as the user said it, and the catalog values of its actions, in the
   * order they run. One action is a plain phrase; several make a macro.
   */
  class Phrase(val phrase: String, val values: List<String>)

  const val PREF_KEY = "pref_voice_command_phrases"

  private const val FIELD_SEPARATOR = "\t"
  private const val ENTRY_SEPARATOR = "\n"

  /** Joins the action values of one phrase inside its stored form and matcher entries. */
  const val VALUE_SEPARATOR = "\u0002"

  /** The matcher-entry form of a phrase's action values. */
  @JvmStatic
  fun joinValues(values: List<String>): String = values.joinToString(VALUE_SEPARATOR)

  /** The action values held in a stored field or a matcher entry's value. */
  @JvmStatic
  fun splitValues(joined: String): List<String> =
    joined.split(VALUE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  /** The words that drive the phrase dialogue, already normalized without diacritics. */
  class Keywords(
    createOpeners: List<String>,
    setPrefixes: List<String>,
    separators: List<String>,
    listCommands: List<String>,
    deletePrefixes: List<String>,
    cancelWords: List<String>,
    finishWords: List<String>,
  ) {
    val createOpeners: Set<String> = createOpeners.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val setPrefixTokens: List<List<String>> =
      setPrefixes.map { tokens(it) }.filter { it.isNotEmpty() }
    val separatorTokens: Set<String> =
      separators.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val listCommands: Set<String> = listCommands.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val deletePrefixTokens: List<List<String>> =
      deletePrefixes.map { tokens(it) }.filter { it.isNotEmpty() }
    val cancelWords: Set<String> = cancelWords.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val finishWords: Set<String> = finishWords.map { plain(it) }.filter { it.isNotEmpty() }.toSet()

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords =
        Keywords(
          VoiceCommandBilingual.list(context, R.string.voice_phrase_create_openers),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_set_prefixes),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_separator),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_list_commands),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_delete_prefixes),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_cancel_words),
          VoiceCommandBilingual.list(context, R.string.voice_phrase_finish_words),
        )
    }
  }

  /** The normalized form two phrases are compared by, ignoring case and diacritics. */
  @JvmStatic
  fun identity(phrase: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(phrase))

  private fun plain(text: String): String = identity(text)

  private fun tokens(text: String): List<String> =
    plain(text).split(' ').filter { it.isNotEmpty() }

  /** Reads the stored phrases in the order they were taught. */
  @JvmStatic
  fun load(context: Context): List<Phrase> =
    parse(SharedPreferencesUtils.getSharedPreferences(context).getString(PREF_KEY, null))

  /** The stored form of [phrases]; empty phrases are dropped and whitespace is collapsed. */
  @JvmStatic
  fun serialize(phrases: List<Phrase>): String =
    phrases
      .map { Phrase(clean(it.phrase), it.values.map { value -> clean(value) }) }
      .filter { it.phrase.isNotEmpty() && it.values.isNotEmpty() && it.values.all { value -> value.isNotEmpty() } }
      .joinToString(ENTRY_SEPARATOR) { it.phrase + FIELD_SEPARATOR + joinValues(it.values) }

  /** The phrases held in the stored form [stored], in their stored order. */
  @JvmStatic
  fun parse(stored: String?): List<Phrase> {
    if (stored.isNullOrEmpty()) return emptyList()
    return stored
      .split(ENTRY_SEPARATOR)
      .mapNotNull { line ->
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 2) return@mapNotNull null
        val phrase = clean(fields[0])
        val values = splitValues(fields[1])
        if (phrase.isEmpty() || values.isEmpty()) null else Phrase(phrase, values)
      }
  }

  private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

  private fun save(context: Context, phrases: List<Phrase>) {
    SharedPreferencesUtils.getSharedPreferences(context)
      .edit()
      .putString(PREF_KEY, serialize(phrases))
      .apply()
  }

  /**
   * Stores [phrase] for the actions [values], in order; a phrase the user already
   * taught, compared without case or diacritics, is retargeted instead of duplicated.
   */
  @JvmStatic
  fun add(context: Context, phrase: String, values: List<String>) {
    val phrases = load(context).toMutableList()
    val key = identity(phrase)
    phrases.removeAll { identity(it.phrase) == key }
    phrases.add(Phrase(clean(phrase), values))
    save(context, phrases)
  }

  /** Removes the taught [phrase], compared without case or diacritics. */
  @JvmStatic
  fun remove(context: Context, phrase: String) {
    val key = identity(phrase)
    save(context, load(context).filter { identity(it.phrase) != key })
  }

  /** The taught phrases as matcher entries, in the order they were taught. */
  @JvmStatic
  fun entries(phrases: List<Phrase>): List<VoiceCommandMatcher.Entry> =
    phrases.map { VoiceCommandMatcher.Entry(it.phrase, joinValues(it.values)) }

  /** Whether [command] ends the list of actions being taught for one phrase. */
  @JvmStatic
  fun isFinish(command: String, keywords: Keywords): Boolean =
    plain(command) in keywords.finishWords

  /** Whether [command] is one of the spoken phrases that open the guided creation dialogue. */
  @JvmStatic
  fun isCreateOpener(command: String, keywords: Keywords): Boolean =
    plain(command) in keywords.createOpeners

  /** Whether [command] asks for the list of taught phrases. */
  @JvmStatic
  fun isListCommand(command: String, keywords: Keywords): Boolean =
    plain(command) in keywords.listCommands

  /** Whether [command] abandons the creation dialogue at a step that takes free text. */
  @JvmStatic
  fun isCancel(command: String, keywords: Keywords): Boolean =
    plain(command) in keywords.cancelWords

  /**
   * The phrase and the action words of a spoken assignment "[prefix] [phrase] [separator]
   * [action]", split at each separator word from the first one on, so the caller tries the
   * longest action name first — a taught phrase may itself contain the separator word.
   * Null when [command] has no assignment prefix or no separator.
   */
  @JvmStatic
  fun splitAssignment(command: String, keywords: Keywords): List<Pair<String, String>>? {
    val words = command.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }
    val plainWords = words.map { VoiceCommandMatcher.stripDiacritics(it.lowercase()) }
    val prefix =
      keywords.setPrefixTokens.firstOrNull { prefix ->
        plainWords.size > prefix.size && plainWords.subList(0, prefix.size) == prefix
      }
        ?: return null
    val rest = words.drop(prefix.size)
    val restPlain = plainWords.drop(prefix.size)
    val splits = mutableListOf<Pair<String, String>>()
    for (index in restPlain.indices) {
      if (restPlain[index] in keywords.separatorTokens && index > 0 && index < rest.size - 1) {
        splits.add(
          rest.subList(0, index).joinToString(" ") to rest.subList(index + 1, rest.size).joinToString(" ")
        )
      }
    }
    return splits.ifEmpty { null }
  }

  /**
   * The phrase a spoken deletion "[prefix] [phrase]" names, or null when [command] starts
   * with no deletion prefix or names nothing.
   */
  @JvmStatic
  fun deleteTarget(command: String, keywords: Keywords): String? {
    val words = tokens(command)
    val prefix =
      keywords.deletePrefixTokens.firstOrNull { prefix ->
        words.size > prefix.size && words.subList(0, prefix.size) == prefix
      }
        ?: return null
    val target = words.drop(prefix.size).joinToString(" ")
    return target.ifEmpty { null }
  }
}
