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

/**
 * Reads the phone number spoken inside a call voice command: "gọi đến số" and the dialling
 * words like it, then the number itself, digit by digit.
 *
 * A phone number is not a quantity, so it is never read through [VietnameseNumbers]: the
 * digits are taken one by one, as raw digits or as the ten single digit words, in the order
 * they are said. A word that is no digit — "trăm" in "một trăm" is the everyday case —
 * breaks the run, and words left over after the run mean the tail is no number at all.
 * A leading spoken "cộng" becomes the "+" of an international number.
 */
object VoiceCommandPhoneNumbers {

  /** The call phrases of every language listened for, normalized without diacritics. */
  class Keywords(callPhrases: List<String>) {
    val callTokenSets: List<List<String>> =
      callPhrases.map { tokens(it) }.filter { it.isNotEmpty() }.sortedByDescending { it.size }

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords = Keywords(phrases(context, R.string.voice_param_call))

      /** The resource's keyword list, or the user's own phrasings when they replaced it. */
      private fun phrases(context: Context, resId: Int): List<String> =
          VoiceCommandSpeechOverrides.forResource(context, resId)
              ?: VoiceCommandBilingual.list(context, resId)
    }
  }

  /** What a sentence asks about calling, once its call phrases are taken off. */
  sealed class Request {
    /** The call phrases alone: the number still has to be asked for. */
    object AskNumber : Request()

    /** Words followed the call phrases, but they form no usable number. */
    object InvalidNumber : Request()

    /** A whole phone number was dictated after the call phrases. */
    class Number(val number: String) : Request()
  }

  private const val MIN_DIGITS = 3
  private const val MAX_DIGITS = 15
  private const val PLUS_WORD = "cong"

  private val digitWords =
    mapOf(
      "khong" to '0',
      "mot" to '1',
      "hai" to '2',
      "ba" to '3',
      "bon" to '4',
      "nam" to '5',
      "sau" to '6',
      "bay" to '7',
      "tam" to '8',
      "chin" to '9',
    )

  private fun plain(text: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(text))

  private fun tokens(text: String): List<String> =
    plain(text).split(' ').filter { it.isNotEmpty() }

  /**
   * What [command] asks about calling, or null when it starts with no call phrase. The
   * number is the whole rest of the sentence: anything said after it makes it invalid.
   */
  @JvmStatic
  fun parseCommand(command: String, keywords: Keywords): Request? {
    val words = tokens(command)
    val prefix =
      keywords.callTokenSets.firstOrNull { words.startsWith(it) } ?: return null
    val tail = words.drop(prefix.size)
    if (tail.isEmpty()) {
      return Request.AskNumber
    }
    return numberFromTokens(tail)?.let { Request.Number(it) } ?: Request.InvalidNumber
  }

  /**
   * The phone number dictated in [text] on its own — the answer to the call command's
   * question — or null when the text is no usable number.
   */
  @JvmStatic
  fun parseNumber(text: String): String? {
    val trimmed = text.trim()
    var plus = false
    var rest = trimmed
    if (rest.startsWith("+")) {
      plus = true
      rest = rest.substring(1)
    }
    return numberFromTokens(tokens(rest), plus)
  }

  /** The number held in [words]: digit words and digit groups, in order, nothing else. */
  private fun numberFromTokens(words: List<String>, plus: Boolean = false): String? {
    var index = 0
    var international = plus
    if (words.getOrNull(index) == PLUS_WORD) {
      international = true
      index++
    }
    val digits = StringBuilder()
    while (index < words.size) {
      val word = words[index]
      val digit = digitWords[word]
      when {
        digit != null -> digits.append(digit)
        word.isNotEmpty() && word.all { it.isDigit() } -> digits.append(word)
        // A word that is no digit breaks the run: what remains is no phone number.
        else -> return null
      }
      index++
    }
    if (digits.length !in MIN_DIGITS..MAX_DIGITS) {
      return null
    }
    return if (international) "+$digits" else digits.toString()
  }

  /** [number] with its digits grouped for reading: threes, a lone last digit merged in. */
  @JvmStatic
  fun grouped(number: String): String {
    val plus = number.startsWith("+")
    val digits = if (plus) number.substring(1) else number
    val groups = mutableListOf<String>()
    var index = 0
    while (index < digits.length) {
      var size = minOf(3, digits.length - index)
      if (digits.length - index - size == 1) {
        size++
      }
      groups.add(digits.substring(index, index + size))
      index += size
    }
    val joined = groups.joinToString(" ")
    return if (plus) "+$joined" else joined
  }

  private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix
}
