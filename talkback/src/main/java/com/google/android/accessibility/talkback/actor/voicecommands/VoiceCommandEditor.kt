/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.Context
import com.google.android.accessibility.talkback.R

/**
 * Reads the spoken commands that drive the text editor while its screen is in front of the
 * user: select this sentence, delete the passage, read again, read everything, undo, redo,
 * and find.
 *
 * Their words overlap the fixed commands' — "tìm" searches the screen as a whole and "xóa"
 * deletes in a text field — so these are matched only while the editor is on screen, and
 * ahead of the fixed commands. Matching is exact on the normalized, diacritic-free words:
 * a sentence that is none of these is left for the other commands, so a mis-hearing does
 * nothing rather than running the wrong edit.
 */
object VoiceCommandEditor {

  /** The editor's keyword lists of every language listened for, normalized without diacritics. */
  class Keywords(
    select: List<String>,
    delete: List<String>,
    read: List<String>,
    readAll: List<String>,
    undo: List<String>,
    redo: List<String>,
    find: List<String>,
  ) {
    val selectTokenSets: List<List<String>> = tokenSets(select)
    val deleteTokenSets: List<List<String>> = tokenSets(delete)
    val readTokenSets: List<List<String>> = tokenSets(read)
    val readAllTokenSets: List<List<String>> = tokenSets(readAll)
    val undoTokenSets: List<List<String>> = tokenSets(undo)
    val redoTokenSets: List<List<String>> = tokenSets(redo)
    val findTokenSets: List<List<String>> = tokenSets(find)

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords =
        Keywords(
          phrases(context, R.string.voice_editor_select),
          phrases(context, R.string.voice_editor_delete),
          phrases(context, R.string.voice_editor_read),
          phrases(context, R.string.voice_editor_read_all),
          phrases(context, R.string.voice_editor_undo),
          phrases(context, R.string.voice_editor_redo),
          phrases(context, R.string.voice_editor_find),
        )

      /** The resource's keyword list, or the user's own phrasings when they replaced it. */
      private fun phrases(context: Context, resId: Int): List<String> =
        VoiceCommandSpeechOverrides.forResource(context, resId)
          ?: VoiceCommandBilingual.list(context, resId)
    }
  }

  private fun tokenSets(phrases: List<String>): List<List<String>> =
    phrases.map { tokens(it) }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.size }

  /** One thing the editor is told to do by voice. */
  sealed class Command {
    object SelectCurrent : Command()

    object DeleteSelection : Command()

    object ReadCurrent : Command()

    object ReadAll : Command()

    object Undo : Command()

    object Redo : Command()

    /** Find [query] in the text; a null query repeats the search before it. */
    class Find(val query: String?) : Command()
  }

  /**
   * The command [command] asks the editor for, or null when it is none of them. Find is the
   * only command taking an argument: everything after its keyword is the search text, taken
   * from the sentence as spoken so its diacritics survive.
   */
  @JvmStatic
  fun parse(command: String, keywords: Keywords): Command? {
    val words = tokens(command)
    if (words.isEmpty()) return null
    val exact =
      listOf(
        keywords.selectTokenSets to Command.SelectCurrent,
        keywords.deleteTokenSets to Command.DeleteSelection,
        keywords.readAllTokenSets to Command.ReadAll,
        keywords.readTokenSets to Command.ReadCurrent,
        keywords.undoTokenSets to Command.Undo,
        keywords.redoTokenSets to Command.Redo,
      )
    for ((sets, result) in exact) {
      if (sets.any { it == words }) return result
    }
    val prefix = keywords.findTokenSets.firstOrNull { words.startsWith(it) } ?: return null
    return Command.Find(remainder(command, prefix.size))
  }

  /** The words of [command] after its first [count], as spoken, or null when there are none. */
  private fun remainder(command: String, count: Int): String? {
    val parts = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.size <= count) return null
    return parts
      .drop(count)
      .joinToString(" ")
      .trim()
      .trimStart(':', ',')
      .trim()
      .takeIf { it.isNotEmpty() }
  }

  private fun plain(text: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(text))

  private fun tokens(text: String): List<String> =
    plain(text).split(' ').filter { it.isNotEmpty() }

  private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix
}
