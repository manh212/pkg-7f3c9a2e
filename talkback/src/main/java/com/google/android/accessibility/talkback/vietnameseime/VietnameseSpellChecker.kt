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

package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

internal data class SpellingErrorSpan(
  val start: Int,
  val end: Int,
  val word: String,
  val previousWord: String?,
)

object VietnameseSpellChecker {
  private const val TONE_SAC = '\u0301'
  private const val TONE_HUYEN = '\u0300'
  private const val TONE_HOI = '\u0309'
  private const val TONE_NGA = '\u0303'
  private const val TONE_NANG = '\u0323'
  private val TONE_MARKS = setOf(TONE_SAC, TONE_HUYEN, TONE_HOI, TONE_NGA, TONE_NANG)

  private val INITIALS_DESC = listOf(
    "ngh", "ch", "gh", "gi", "kh", "ng", "nh", "ph", "qu", "th", "tr",
    "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x",
  )

  private val VALID_RIMES = setOf(
    "a", "ai", "ao", "au", "ay",
    "ă",
    "â", "âu", "ây",
    "e", "eo",
    "ê", "êu",
    "i", "ia", "iê", "iêu", "iu",
    "o", "oa", "oai", "oao", "oay", "oe", "oeo", "oi", "oo",
    "ô", "ôi",
    "ơ", "ơi",
    "u", "ua", "uâ", "uây", "uê", "ui", "uô", "uôi", "uơ", "uy", "uya", "uyê", "uyu",
    "ư", "ưa", "ưi", "ươ", "ươi", "ươu", "ưu",
    "y", "ya", "yê", "yêu",
    "am", "an", "ang", "anh",
    "ăm", "ăn", "ăng",
    "âm", "ân", "âng",
    "em", "en", "eng",
    "êm", "ên", "ênh",
    "im", "in", "inh", "iêm", "iên", "iêng",
    "om", "on", "ong", "oam", "oan", "oang", "oanh", "oăn", "oăng", "oem", "oen", "oong",
    "ôm", "ôn", "ông",
    "ơm", "ơn",
    "um", "un", "ung", "uân", "uâng", "uên", "uôm", "uôn", "uông", "uyên", "uyn", "uynh",
    "ưm", "ưn", "ưng", "ươm", "ươn", "ương",
    "yêm", "yên",
    "ac", "ach", "ap", "at",
    "ăc", "ăp", "ăt",
    "âc", "âp", "ât",
    "ec", "ep", "et",
    "êch", "êp", "êt",
    "ic", "ich", "ip", "it", "iêc", "iêp", "iêt",
    "oc", "op", "ot", "oac", "oach", "oap", "oat", "oăc", "oăt", "oet", "ooc",
    "ôc", "ôp", "ôt",
    "ơp", "ơt",
    "uc", "up", "ut", "uât", "uêch", "uêt", "uôc", "uôt", "uych", "uyp", "uyt", "uyêt",
    "ưc", "ưt", "ươc", "ươp", "ươt",
    "yêt",
  )

  private val STOP_CODAS = listOf("ch", "c", "p", "t")

  private val COMMON_LOANWORDS = setOf(
    "ok", "okay", "wifi", "zalo", "facebook", "fb", "youtube", "google", "meridian", "talkback",
    "android", "iphone", "ios", "xiaomi", "redmi", "samsung", "oppo", "vivo", "realme", "nokia",
    "app", "web", "link", "file", "pdf", "apk", "zip", "email", "mail", "gmail", "chat", "call",
    "video", "audio", "mp3", "mp4", "game", "vip", "id", "pin", "sim", "sms", "otp", "atm",
    "qr", "usb", "gps", "ram", "rom", "cpu", "ai", "ocr", "tts", "telex", "vni", "vietlott",
    "jackpot", "power", "mega", "max", "ship", "shop", "sale", "live", "share", "like", "comment",
    "inbox", "ib", "admin", "ad", "user", "pass", "login", "logout", "online", "offline",
    "camera", "flash", "menu", "home", "back", "next", "auto", "pro", "plus", "mini", "lite",
    "cafe", "coffee", "taxi", "grab", "be", "gojek", "shopee", "lazada", "tiki", "tiktok",
    "messenger", "telegram", "viber", "whatsapp", "skype", "zoom", "meet", "drive", "docs",
    "sheets", "slides", "chrome", "safari", "edge", "lina", "vn", "vnd", "usd", "eur", "jpy",
  )

  private val QWERTY_NEIGHBORS = mapOf(
    'q' to "wa", 'w' to "qeas", 'e' to "wrds", 'r' to "etdf", 't' to "ryfg",
    'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
    'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
    'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
    'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
    'n' to "bhjm", 'm' to "njk",
  )

  private val WORD_REGEX = Regex("[\\p{L}\\p{M}\\p{N}@._:/-]+")

  fun isValidWord(context: Context?, rawWord: String): Boolean {
    val trimmed = rawWord.trim { !it.isLetterOrDigit() }
    if (trimmed.isEmpty()) return true
    if (trimmed.any { it.isDigit() || it == '@' || it == '/' || it == ':' || it == '.' || it == '_' || it == '-' }) {
      return true
    }
    if (trimmed.length >= 2 && trimmed.all { it.isUpperCase() }) return true

    val lower = Normalizer.normalize(trimmed, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    if (lower.length == 1) {
      return lower in setOf("a", "à", "á", "ả", "ã", "ạ", "e", "é", "è", "i", "o", "ò", "ó", "ô", "ơ", "u", "ù", "ư", "y", "ý")
    }
    if (lower in COMMON_LOANWORDS) return true
    if (VietnameseLexicon.unigramScore(context, lower) > 0) return true
    return isValidVietnameseSyllable(lower)
  }

  internal fun isValidVietnameseSyllable(word: String): Boolean {
    if (word.isEmpty() || word.length > 7) return false
    val nfc = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    var tone: Char? = null
    val noToneNfd = StringBuilder(nfd.length)
    for (ch in nfd) {
      if (ch in TONE_MARKS) {
        if (tone != null) return false
        tone = ch
      } else {
        noToneNfd.append(ch)
      }
    }
    val plain = Normalizer.normalize(noToneNfd.toString(), Normalizer.Form.NFC)
    if (plain.isEmpty()) return false

    var initial = ""
    for (cand in INITIALS_DESC) {
      if (plain.startsWith(cand)) {
        if (cand == "gi" && (plain.length == 2 || plain[2] !in "aăâeêioôơuưy")) {
          initial = "g"
        } else {
          initial = cand
        }
        break
      }
    }
    val rime = plain.substring(initial.length)
    if (rime.isEmpty()) return false
    if (rime !in VALID_RIMES) return false

    val firstRimeChar = rime[0]
    val frontVowel = firstRimeChar == 'e' || firstRimeChar == 'ê' || firstRimeChar == 'i' || firstRimeChar == 'y'
    when (initial) {
      "c" -> if (frontVowel) return false
      "k" -> if (!frontVowel) return false
      "g" -> if (firstRimeChar == 'e' || firstRimeChar == 'ê') return false
      "gh" -> if (firstRimeChar != 'e' && firstRimeChar != 'ê' && firstRimeChar != 'i') return false
      "ng" -> if (firstRimeChar == 'e' || firstRimeChar == 'ê' || firstRimeChar == 'i') return false
      "ngh" -> if (firstRimeChar != 'e' && firstRimeChar != 'ê' && firstRimeChar != 'i') return false
    }

    val hasStopCoda = STOP_CODAS.any { rime.endsWith(it) }
    if (hasStopCoda && tone != TONE_SAC && tone != TONE_NANG) {
      return false
    }
    return true
  }

  internal fun findErrors(context: Context?, text: CharSequence): List<SpellingErrorSpan> {
    if (text.isEmpty()) return emptyList()
    val str = text.toString()
    val errors = mutableListOf<SpellingErrorSpan>()
    var previousWord: String? = null
    for (match in WORD_REGEX.findAll(str)) {
      val token = match.value
      val trimmedStart = token.indexOfFirst { it.isLetterOrDigit() }
      val trimmedEnd = token.indexOfLast { it.isLetterOrDigit() }
      if (trimmedStart < 0 || trimmedEnd < trimmedStart) continue
      val clean = token.substring(trimmedStart, trimmedEnd + 1)
      val absStart = match.range.first + trimmedStart
      val absEnd = match.range.first + trimmedEnd + 1
      if (!isValidWord(context, clean)) {
        errors += SpellingErrorSpan(absStart, absEnd, clean, previousWord)
      }
      previousWord = clean
    }
    return errors
  }

  internal fun findErrorAtCursor(
    context: Context?,
    text: CharSequence,
    cursorPos: Int,
  ): SpellingErrorSpan? {
    val errors = findErrors(context, text)
    if (errors.isEmpty()) return null
    val pos = cursorPos.coerceIn(0, text.length)
    return errors.firstOrNull { pos in it.start..it.end }
  }

  internal fun findNextError(
    context: Context?,
    text: CharSequence,
    cursorPos: Int,
  ): SpellingErrorSpan? {
    val errors = findErrors(context, text)
    if (errors.isEmpty()) return null
    val pos = cursorPos.coerceIn(0, text.length)
    return errors.firstOrNull { it.start > pos } ?: errors.first()
  }

  internal fun findPreviousError(
    context: Context?,
    text: CharSequence,
    cursorPos: Int,
  ): SpellingErrorSpan? {
    val errors = findErrors(context, text)
    if (errors.isEmpty()) return null
    val pos = cursorPos.coerceIn(0, text.length)
    return errors.lastOrNull { it.end < pos } ?: errors.last()
  }

  fun suggestCorrections(
    context: Context?,
    misspelledWord: String,
    previousWord: String? = null,
    maxResults: Int = 4,
  ): List<String> {
    val lower = VietnameseLexicon.normalizeWord(misspelledWord)
    if (lower.isEmpty()) return emptyList()

    val candidates = LinkedHashMap<String, Double>()

    val telexRepaired = replayTelex(lower)
    if (telexRepaired != null && telexRepaired != lower && isValidVietnameseSyllable(telexRepaired)) {
      candidates[telexRepaired] = 0.25
    }

    val known = VietnameseLexicon.allKnownWords(context)
    val asciiTarget = VietnameseLexicon.foldAscii(lower)
    for (word in known) {
      if (word == lower) continue
      val lenDiff = kotlin.math.abs(word.length - lower.length)
      if (lenDiff > 2) continue
      val dist = weightedDistance(lower, word, asciiTarget)
      if (dist <= 2.1) {
        val prev = candidates[word]
        if (prev == null || dist < prev) {
          candidates[word] = dist
        }
      }
    }

    val preserveUpper = misspelledWord.firstOrNull()?.isUpperCase() == true
    return candidates.entries
      .sortedWith(
        compareBy<Map.Entry<String, Double>> { it.value }
          .thenByDescending { VietnameseLexicon.bigramScore(context, previousWord, it.key) }
          .thenByDescending { VietnameseLexicon.unigramScore(context, it.key) }
      )
      .take(maxResults)
      .map { (word, _) ->
        if (preserveUpper) {
          word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
          word
        }
      }
  }

  private fun replayTelex(raw: String): String? {
    var acc = ""
    for (ch in raw) {
      acc = Telex.apply(acc, ch, quickTelex = false)
    }
    return acc.takeIf { it.isNotEmpty() }
  }

  private fun weightedDistance(a: String, b: String, asciiA: String): Double {
    val asciiB = VietnameseLexicon.foldAscii(b)
    if (asciiA == asciiB) {
      return 0.4
    }
    val m = asciiA.length
    val n = asciiB.length
    val dp = Array(m + 1) { DoubleArray(n + 1) }
    for (i in 0..m) dp[i][0] = i.toDouble()
    for (j in 0..n) dp[0][j] = j.toDouble()
    for (i in 1..m) {
      for (j in 1..n) {
        val ca = asciiA[i - 1]
        val cb = asciiB[j - 1]
        val subCost = when {
          ca == cb -> 0.0
          QWERTY_NEIGHBORS[ca]?.contains(cb) == true -> 0.65
          else -> 1.0
        }
        var best = min(
          min(dp[i - 1][j] + 1.0, dp[i][j - 1] + 1.0),
          dp[i - 1][j - 1] + subCost,
        )
        if (i > 1 && j > 1 && asciiA[i - 1] == asciiB[j - 2] && asciiA[i - 2] == asciiB[j - 1]) {
          best = min(best, dp[i - 2][j - 2] + 0.7)
        }
        dp[i][j] = best
      }
    }
    return dp[m][n]
  }
}
