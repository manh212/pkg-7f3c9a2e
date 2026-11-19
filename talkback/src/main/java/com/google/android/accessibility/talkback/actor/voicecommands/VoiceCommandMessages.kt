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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.accessibility.talkback.R

/**
 * Reads a spoken message command — "nhắn cho An rằng mai đi học nhé", "gửi mô tả màn hình cho
 * An qua Zalo" — and opens the messaging app for it.
 *
 * Matching runs on the diacritic-free normalized form, like all voice command matching, but the
 * recipient and the text are taken from the words as they were said, so the message keeps its
 * diacritics and case. A word that normalizes to several tokens, "0903.567" to "0903" and "567",
 * is tracked back to the one word it came from, so the text never starts mid-word.
 *
 * The app is SMS unless the command names one: "qua Zalo", "bằng WhatsApp", before the
 * recipient or after it. SMS opens the thread with the text filled in; WhatsApp opens the chat
 * through its public send link; Zalo has no public send link, so its chat is opened with Zalo's
 * own link for the number and the text is typed into its message box instead.
 */
object VoiceCommandMessages {

  /** The messaging app a message goes by. */
  enum class App {
    SMS,
    WHATSAPP,
    ZALO,
  }

  /** The keyword phrases of every language listened for, normalized without diacritics. */
  class Keywords(
    messagePrefixes: List<String>,
    describeScreenPrefixes: List<String>,
    describeImagePrefixes: List<String>,
    appConnectors: List<String>,
    zaloNames: List<String>,
    whatsappNames: List<String>,
    smsNames: List<String>,
    textSeparators: List<String>,
  ) {
    val messagePrefixTokens: List<List<String>> = tokenSets(messagePrefixes)
    val describeScreenPrefixTokens: List<List<String>> = tokenSets(describeScreenPrefixes)
    val describeImagePrefixTokens: List<List<String>> = tokenSets(describeImagePrefixes)
    val appConnectorTokens: Set<String> =
      appConnectors.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val zaloTokens: List<List<String>> = tokenSets(zaloNames)
    val whatsappTokens: List<List<String>> = tokenSets(whatsappNames)
    val smsTokens: List<List<String>> = tokenSets(smsNames)
    val separatorTokens: Set<String> =
      textSeparators.map { plain(it) }.filter { it.isNotEmpty() }.toSet()

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords =
        Keywords(
          phrases(context, R.string.voice_message_prefixes),
          phrases(context, R.string.voice_message_describe_screen_prefixes),
          phrases(context, R.string.voice_message_describe_image_prefixes),
          phrases(context, R.string.voice_message_app_connectors),
          phrases(context, R.string.voice_message_app_zalo),
          phrases(context, R.string.voice_message_app_whatsapp),
          phrases(context, R.string.voice_message_app_sms),
          phrases(context, R.string.voice_message_text_separator),
        )

      /** The resource's keyword list, or the user's own phrasings when they replaced it. */
      private fun phrases(context: Context, resId: Int): List<String> =
        VoiceCommandSpeechOverrides.forResource(context, resId)
          ?: VoiceCommandBilingual.list(context, resId)
    }
  }

  /**
   * What a message command asks for: the app, or null for SMS; the recipient and the text,
   * still together in [whoAndText]; and, for a description send, which description, or null
   * for a plain message.
   */
  class Request(val app: App?, val whoAndText: String?, val describeScreen: Boolean?)

  private const val WHATSAPP = "com.whatsapp"
  private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
  private const val ZALO = "com.zing.zalo"

  private const val CHO_TOKEN = "cho"

  /**
   * What [command] asks about messaging, or null when it starts with no message phrase. The
   * recipient and the text are the words as they were said.
   */
  @JvmStatic
  fun parseCommand(command: String, keywords: Keywords): Request? {
    val tokenized = Tokenized(command)
    if (tokenized.plain.isEmpty()) return null
    // A description send first: its phrases start with "gửi" too.
    for ((prefixes, screen) in
      listOf(
        keywords.describeScreenPrefixTokens to true,
        keywords.describeImagePrefixTokens to false,
      )) {
      val used = matchPrefix(tokenized.plain, prefixes) ?: continue
      var index = used
      val (app, afterApp) = appAfter(tokenized.plain, index, keywords)
      index = afterApp
      index = dropCho(tokenized.plain, index)
      val rest = tokenized.textFrom(index)
      if (rest.isBlank()) return null
      val (trailingApp, recipient) = trailingApp(rest, keywords)
      if (recipient.isBlank()) return null
      return Request(app ?: trailingApp, recipient, screen)
    }
    val used = matchPrefix(tokenized.plain, keywords.messagePrefixTokens) ?: return null
    var index = used
    val (app, afterApp) = appAfter(tokenized.plain, index, keywords)
    index = afterApp
    index = dropCho(tokenized.plain, index)
    val rest = tokenized.textFrom(index)
    if (rest.isBlank()) return null
    return Request(app, rest, null)
  }

  /**
   * The app named at the start of [text] — "qua Zalo ..." — and the rest of [text], or null
   * and [text] when it names none.
   */
  @JvmStatic
  fun leadingApp(text: String, keywords: Keywords): Pair<App?, String> {
    val tokenized = Tokenized(text)
    val (app, used) = appAfter(tokenized.plain, 0, keywords)
    if (app == null) return null to text
    return app to tokenized.textFrom(used)
  }

  /** [text] without a leading separator word — "rằng" — between the recipient and the text. */
  @JvmStatic
  fun dropTextSeparator(text: String, keywords: Keywords): String {
    val tokenized = Tokenized(text)
    if (tokenized.plain.isNotEmpty() && tokenized.plain[0] in keywords.separatorTokens) {
      return tokenized.textFrom(1)
    }
    return text
  }

  /**
   * The intents that open the chat for [app] with [number] and [text], in the order to try
   * them. Empty when the app isn't installed: WhatsApp and Zalo have nothing to open then.
   * SMS always opens the thread with the text filled in; WhatsApp opens its public send link;
   * Zalo opens its own link for the number, then its web link.
   */
  @JvmStatic
  fun intentsFor(context: Context, app: App, number: String, text: String): List<Intent> =
    when (app) {
      App.SMS ->
        listOf(
          Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", text)
        )
      App.WHATSAPP -> {
        val pkg = installed(context, WHATSAPP) ?: installed(context, WHATSAPP_BUSINESS)
        if (pkg == null) {
          emptyList()
        } else {
          val phone = internationalDigits(number)
          listOf(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                  "https://api.whatsapp.com/send?phone=$phone&text=" + Uri.encode(text)
                ),
              )
              .setPackage(pkg)
          )
        }
      }
      App.ZALO -> {
        if (installed(context, ZALO) == null) {
          emptyList()
        } else {
          val digits = internationalDigits(number)
          listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("zalo://qr/p/$digits")).setPackage(ZALO),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/$digits")),
          )
        }
      }
    }

  /** Whether the text has to be typed into the app's message box after it opens: Zalo only. */
  @JvmStatic fun needsTyping(app: App): Boolean = app == App.ZALO

  /**
   * [number] in international form, digits only, as WhatsApp and Zalo links take it. A
   * Vietnamese number starting with 0 becomes 84 and the rest; a number already starting
   * with + or 84 is kept as it is.
   */
  @JvmStatic
  fun internationalDigits(number: String): String {
    val digits = number.filter { it.isDigit() }
    return when {
      number.trim().startsWith("+") -> digits
      digits.startsWith("84") -> digits
      digits.startsWith("0") -> "84" + digits.substring(1)
      else -> digits
    }
  }

  /** The longest of [alternatives] that [words] starts with, as a token count, or null. */
  private fun matchPrefix(words: List<String>, alternatives: List<List<String>>): Int? =
    alternatives.filter { words.startsWith(it) }.maxOfOrNull { it.size }

  /** The app named at [index] — a connector word and an app name — and the index after it. */
  private fun appAfter(
    words: List<String>,
    index: Int,
    keywords: Keywords,
  ): Pair<App?, Int> {
    if (index >= words.size || words[index] !in keywords.appConnectorTokens) {
      return null to index
    }
    val rest = words.subList(index + 1, words.size)
    for ((tokens, app) in
      listOf(
        keywords.zaloTokens to App.ZALO,
        keywords.whatsappTokens to App.WHATSAPP,
        keywords.smsTokens to App.SMS,
      )) {
      val used = matchPrefix(rest, tokens)
      if (used != null) return app to index + 1 + used
    }
    return null to index
  }

  /** [words] without a leading "cho" separator, and the index after it. */
  private fun dropCho(words: List<String>, index: Int): Int =
    if (index < words.size && words[index] == CHO_TOKEN) index + 1 else index

  /**
   * The app named at the end of [text] — "... qua Zalo" — and [text] without it, or null and
   * [text] when it names none.
   */
  private fun trailingApp(text: String, keywords: Keywords): Pair<App?, String> {
    val tokenized = Tokenized(text)
    val plain = tokenized.plain
    for ((tokens, app) in
      listOf(
        keywords.zaloTokens to App.ZALO,
        keywords.whatsappTokens to App.WHATSAPP,
        keywords.smsTokens to App.SMS,
      )) {
      for (candidate in tokens) {
        // Only a connector word introduces the app: "... qua Zalo", "... bằng WhatsApp".
        val withConnector =
          keywords.appConnectorTokens.map { connector -> listOf(connector) + candidate }
        val match = withConnector.firstOrNull { ending ->
          plain.size > ending.size && plain.subList(plain.size - ending.size, plain.size) == ending
        }
          ?: continue
        val cut = plain.size - match.size
        return app to tokenized.textUpTo(cut)
      }
    }
    return null to text
  }

  private fun installed(context: Context, pkg: String): String? =
    try {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(pkg, 0)
      pkg
    } catch (e: PackageManager.NameNotFoundException) {
      null
    }

  private fun plain(text: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(text))

  private fun tokenSets(phrases: List<String>): List<List<String>> =
    phrases
      .map { it.split(Regex("\\s+")).map { word -> plain(word) }.filter { token -> token.isNotEmpty() } }
      .filter { it.isNotEmpty() }
      .sortedByDescending { it.size }

  private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix

  /**
   * The words of a command as they were said, with the normalized token of each word and the
   * word each token came from, so a match on normalized tokens maps back to whole said words.
   */
  private class Tokenized(command: String) {
    val words: List<String> =
      command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val plain: List<String>
    val wordOf: List<Int>

    init {
      val tokens = mutableListOf<String>()
      val owners = mutableListOf<Int>()
      words.forEachIndexed { index, word ->
        VoiceCommandMatcher.normalize(word)
          .split(' ')
          .filter { it.isNotEmpty() }
          .forEach { token ->
            tokens.add(VoiceCommandMatcher.stripDiacritics(token))
            owners.add(index)
          }
      }
      plain = tokens
      wordOf = owners
    }

    /** The said words from the word holding plain token [flatIndex] on. */
    fun textFrom(flatIndex: Int): String {
      if (flatIndex >= plain.size) return ""
      return words.subList(wordOf[flatIndex], words.size).joinToString(" ")
    }

    /** The said words holding the first [flatCount] plain tokens. */
    fun textUpTo(flatCount: Int): String {
      if (flatCount <= 0) return ""
      if (flatCount >= plain.size) return words.joinToString(" ")
      return words.subList(0, wordOf[flatCount - 1] + 1).joinToString(" ")
    }
  }
}
