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

/**
 * Splits one spoken sentence into a chain of commands at the connector words ("rồi",
 * "sau đó"), so each part can run in order. Splitting works on the diacritic-free
 * normalized form, like all voice command matching, and the parts are returned in that
 * form; a connector at the very start or end simply drops away.
 */
object VoiceCommandChain {

  /** The connector phrases of one language, as normalized token sequences. */
  class Keywords(connectors: List<String>) {
    val connectorTokens: List<List<String>> =
      connectors.map { tokens(it) }.filter { it.isNotEmpty() }

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords =
        Keywords(context.getString(R.string.voice_chain_connectors).split(','))
    }
  }

  private fun plain(text: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(text))

  private fun tokens(text: String): List<String> =
    plain(text).split(' ').filter { it.isNotEmpty() }

  /**
   * The parts of [command] between its connectors, in order. Fewer than two parts means
   * the sentence is not a chain and the caller treats it as one command.
   */
  @JvmStatic
  fun split(command: String, keywords: Keywords): List<String> {
    val words = tokens(command)
    val parts = mutableListOf<String>()
    var current = mutableListOf<String>()
    var index = 0
    while (index < words.size) {
      val connector =
        keywords.connectorTokens.firstOrNull { connector ->
          words.size - index >= connector.size &&
            words.subList(index, index + connector.size) == connector
        }
      if (connector != null) {
        if (current.isNotEmpty()) {
          parts.add(current.joinToString(" "))
          current = mutableListOf()
        }
        index += connector.size
      } else {
        current.add(words[index])
        index++
      }
    }
    if (current.isNotEmpty()) {
      parts.add(current.joinToString(" "))
    }
    return parts
  }
}
