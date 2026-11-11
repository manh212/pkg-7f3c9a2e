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

package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.talkback.gesture.OpenAppActions
import java.text.Normalizer
import java.util.Locale

/**
 * Matches a recognized sentence against the names of the actions a gesture or a shortcut can run,
 * so any of those actions can also be run by speaking its name.
 *
 * The match is scored, not exact: the sentence and the names are normalized (case, punctuation
 * and spacing), compared as token sets, and the best name wins only when it scores at least
 * [MIN_SCORE] and clearly beats the runner-up, so a mis-hearing does nothing rather than running
 * the wrong action. Because speech recognizers do not always return Vietnamese diacritics, every
 * comparison also runs on a diacritic-free form of both sides.
 */
object VoiceCommandMatcher {

  /** One spoken phrase and the action value it runs. */
  class Entry(val phrase: String, val value: String)

  /** The winning entry of a [match], with the phrase that won and the score it won by. */
  class Match(val phrase: String, val value: String, val score: Double)

  private const val MIN_SCORE = 0.85
  private const val MIN_MARGIN = 0.1

  /** The score given when one side's words are all in the other and cover most of it. */
  private const val CONTAINMENT_SCORE = 0.92

  /** The smallest share of the longer side a containment match must cover. */
  private const val CONTAINMENT_COVERAGE = 0.6

  /** Returns the best entry for [spoken], or null when nothing matches well enough. */
  fun match(spoken: String, entries: List<Entry>): Match? {
    val spokenForms = forms(spoken)
    if (spokenForms.isEmpty()) return null
    var best: Match? = null
    var runnerUp = 0.0
    for (entry in entries) {
      var score = 0.0
      for (form in forms(entry.phrase)) {
        for (spokenForm in spokenForms) {
          score = maxOf(score, score(spokenForm, form))
        }
      }
      val current = best
      if (current == null || score > current.score) {
        if (current != null && current.value != entry.value) runnerUp = maxOf(runnerUp, current.score)
        best = Match(entry.phrase, entry.value, score)
      } else if (entry.value != current.value) {
        runnerUp = maxOf(runnerUp, score)
      }
    }
    val winner = best ?: return null
    if (winner.score < MIN_SCORE) return null
    // An exact match needs no margin; anything else must clearly beat the runner-up.
    if (winner.score < 1.0 && winner.score - runnerUp < MIN_MARGIN) return null
    return winner
  }

  /** The score of [spoken] against one [phrase], both already normalized. */
  private fun score(spoken: String, phrase: String): Double {
    if (spoken == phrase) return 1.0
    val spokenTokens = spoken.split(' ').filter { it.isNotEmpty() }
    val phraseTokens = phrase.split(' ').filter { it.isNotEmpty() }
    if (spokenTokens.isEmpty() || phraseTokens.isEmpty()) return 0.0
    val shared = spokenTokens.toSet().intersect(phraseTokens.toSet()).size
    if (shared == 0) return 0.0
    var result = 2.0 * shared / (spokenTokens.size + phraseTokens.size)
    val smaller = minOf(spokenTokens.size, phraseTokens.size)
    val larger = maxOf(spokenTokens.size, phraseTokens.size)
    if (shared == smaller && smaller >= 2 && smaller.toDouble() / larger >= CONTAINMENT_COVERAGE) {
      result = maxOf(result, CONTAINMENT_SCORE)
    }
    return result
  }

  /** The normalized form of [text], plus its diacritic-free form when that differs. */
  private fun forms(text: String): List<String> {
    val normalized = normalize(text)
    if (normalized.isEmpty()) return emptyList()
    val stripped = stripDiacritics(normalized)
    return if (stripped == normalized) listOf(normalized) else listOf(normalized, stripped)
  }

  /** Lowercases [text], drops punctuation, and collapses spacing. */
  fun normalize(text: String): String {
    val builder = StringBuilder()
    for (char in text.lowercase(Locale.ROOT)) {
      builder.append(if (char.isLetterOrDigit()) char else ' ')
    }
    return builder.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
  }

  /** Removes the diacritics of a normalized [text], for recognizers that do not return them. */
  fun stripDiacritics(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace('đ', 'd')
}

/**
 * Builds the phrases [VoiceCommandMatcher] can match: the name of every command in the catalog
 * of actions a gesture or a shortcut can run, apart from the commands only the keyboard code
 * performs (they have no gesture action to run), the voice commands themselves, switching the
 * screen reader off, and the unassigned entry.
 */
object VoiceCommandCatalog {

  @JvmStatic
  fun entries(context: Context): List<VoiceCommandMatcher.Entry> {
    val excluded =
      setOf(
        context.getString(R.string.shortcut_value_voice_commands),
        context.getString(R.string.shortcut_value_turn_off_sandfly),
        context.getString(R.string.shortcut_value_unassigned),
      )
    val entries = mutableListOf<VoiceCommandMatcher.Entry>()
    val seen = mutableSetOf<String>()
    for (category in Commands.categories(context)) {
      for (command in category.commands) {
        val value = command.value
        if (value.startsWith(Commands.KEYBOARD_PREFIX) || value in excluded || !seen.add(value)) {
          continue
        }
        entries.add(VoiceCommandMatcher.Entry(command.label, value))
        if (OpenAppActions.isAction(value)) {
          // An app is in the catalog under its bare name, but nobody says the name
          // alone: the spoken form is "Mở" and the name, so register that too.
          OpenAppActions.label(context, value)?.let {
            entries.add(VoiceCommandMatcher.Entry(it, value))
          }
        }
      }
    }
    return entries
  }
}
