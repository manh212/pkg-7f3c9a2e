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
  val nextWord: String? = null,
  val preferredFix: String? = null,
)

object VietnameseSpellChecker {
  private const val TONE_SAC = '\u0301'
  private const val TONE_HUYEN = '\u0300'
  private const val TONE_HOI = '\u0309'
  private const val TONE_NGA = '\u0303'
  private const val TONE_NANG = '\u0323'
  private val TONE_MARKS = setOf(TONE_SAC, TONE_HUYEN, TONE_HOI, TONE_NGA, TONE_NANG)
  private const val CONTEXT_BIGRAM_THRESHOLD = 75

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

  /**
   * Common Vietnamese dialect/typing confusions (l/n, tr/ch, s/x, r/d/gi, hỏi/ngã) that happen
   * to pass raw phonotactic rules but are NOT genuine Vietnamese words.
   */
  private val NON_EXISTENT_CONFUSABLE_SYLLABLES = setOf(
    // l/n non-words
    "nàm", "lói", "nời", "nớn", "nuôn", "noại", "nượng", "nịch", "nựa", "nưu", "nẽ", "lữa",
    "núc", "lày", "nấy", "lăm", "nắm", "nên",
    // tr/ch non-words
    "chời", "trính", "chên", "chiển", "chọng", "chách", "chình", "chạng", "truẩn", "truyển",
    "truyên", "trất", "trết", "chắng", "trơi", "chường", "trương", "chảu", "trủ", "trửa",
    // s/x non-words
    "xẵn", "xắp", "xẽ", "sẽn", "xữa", "xản", "suất",
    // r/d/gi non-words
    "giồi", "rữ", "dúp", "dống",
    // hỏi/ngã non-words
    "hõi", "chử", "mổi", "nhửng", "giửa", "mỷ", "rỏ", "dể", "hảy", "vửng", "chửng", "diển",
    "miển", "nhản", "giử", "gữi", "hiễu", "kiễm", "phãi", "khõe", "cãm", "bãn", "cãnh",
    "chĩnh", "chuyễn", "dẩn", "dử", "đãm", "đỗi", "giãm", "khã", "khỡi", "ngử", "nhõ",
    "phãn", "quãn", "sãn", "sữ", "tãi", "tỗng", "trã", "trãi", "trưỡng", "tưỡng", "ũng",
    "xữ", "ãnh", "đở", "tã",
  ) - setOf("nên", "nắm", "lăm", "trương", "suất", "tã", "núc")

  /**
   * Vietnamese phonetic confusion pairs for initial consonants.
   */
  private val INITIAL_CONFUSIONS: Map<String, List<String>> = mapOf(
    "l" to listOf("n"),
    "n" to listOf("l"),
    "tr" to listOf("ch"),
    "ch" to listOf("tr"),
    "s" to listOf("x"),
    "x" to listOf("s"),
    "r" to listOf("d", "gi"),
    "d" to listOf("gi", "r", "v"),
    "gi" to listOf("d", "r"),
    "v" to listOf("d"),
    "c" to listOf("k"),
    "k" to listOf("c"),
    "g" to listOf("gh"),
    "gh" to listOf("g"),
    "ng" to listOf("ngh"),
    "ngh" to listOf("ng"),
  )

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

  private data class ParsedToken(
    val start: Int,
    val end: Int,
    val word: String,
    val lower: String,
  )

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
    if (lower in NON_EXISTENT_CONFUSABLE_SYLLABLES) return false
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

  /**
   * Generates phonetically confusable Vietnamese variants of [lower] (l↔n, tr↔ch, s↔x, r↔d↔gi,
   * v↔d, c↔k, g↔gh, ng↔ngh, and hỏi↔ngã tone swaps).
   */
  internal fun phoneticConfusableVariants(lower: String): List<String> {
    if (lower.isEmpty() || lower.length > 7) return emptyList()
    val nfc = Normalizer.normalize(lower, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    val results = LinkedHashSet<String>()

    // 1. Swap initial consonant with confusable counterpart(s)
    var matchedInitial = ""
    for (cand in INITIALS_DESC) {
      if (nfc.startsWith(cand)) {
        if (cand == "gi" && (nfc.length == 2 || stripToneChar(nfc[2]) !in "aăâeêioôơuưy")) {
          matchedInitial = "g"
        } else {
          matchedInitial = cand
        }
        break
      }
    }
    val rest = nfc.substring(matchedInitial.length)
    if (matchedInitial.isNotEmpty() && rest.isNotEmpty()) {
      for (alt in INITIAL_CONFUSIONS[matchedInitial].orEmpty()) {
        val candidate = alt + rest
        if (candidate != nfc && candidate !in NON_EXISTENT_CONFUSABLE_SYLLABLES && isValidVietnameseSyllable(candidate)) {
          results += candidate
        }
      }
    }

    // 2. Swap hỏi ↔ ngã tone mark
    val swappedTone = swapHoiNgaTone(nfc)
    if (swappedTone != null && swappedTone != nfc &&
      swappedTone !in NON_EXISTENT_CONFUSABLE_SYLLABLES && isValidVietnameseSyllable(swappedTone)
    ) {
      results += swappedTone
      // Also combine initial swap + hỏi/ngã swap for double confusions (e.g. "chả" vs "trã")
      if (matchedInitial.isNotEmpty()) {
        val swappedRest = swappedTone.substring(matchedInitial.length)
        for (alt in INITIAL_CONFUSIONS[matchedInitial].orEmpty()) {
          val combo = alt + swappedRest
          if (combo != nfc && combo !in NON_EXISTENT_CONFUSABLE_SYLLABLES && isValidVietnameseSyllable(combo)) {
            results += combo
          }
        }
      }
    }

    return results.toList()
  }

  private fun stripToneChar(ch: Char): Char {
    val nfd = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
    val stripped = nfd.filterNot { it in TONE_MARKS }
    return Normalizer.normalize(stripped, Normalizer.Form.NFC).firstOrNull() ?: ch
  }

  private fun swapHoiNgaTone(nfc: String): String? {
    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    val sb = StringBuilder(nfd.length)
    var changed = false
    for (ch in nfd) {
      when (ch) {
        TONE_HOI -> {
          sb.append(TONE_NGA)
          changed = true
        }
        TONE_NGA -> {
          sb.append(TONE_HOI)
          changed = true
        }
        else -> sb.append(ch)
      }
    }
    return if (changed) Normalizer.normalize(sb.toString(), Normalizer.Form.NFC) else null
  }

  internal fun findErrors(context: Context?, text: CharSequence): List<SpellingErrorSpan> {
    if (text.isEmpty()) return emptyList()
    val str = text.toString()
    val tokens = ArrayList<ParsedToken>()
    for (match in WORD_REGEX.findAll(str)) {
      val token = match.value
      val trimmedStart = token.indexOfFirst { it.isLetterOrDigit() }
      val trimmedEnd = token.indexOfLast { it.isLetterOrDigit() }
      if (trimmedStart < 0 || trimmedEnd < trimmedStart) continue
      val clean = token.substring(trimmedStart, trimmedEnd + 1)
      val absStart = match.range.first + trimmedStart
      val absEnd = match.range.first + trimmedEnd + 1
      val lower = VietnameseLexicon.normalizeWord(clean)
      tokens += ParsedToken(absStart, absEnd, clean, lower)
    }
    if (tokens.isEmpty()) return emptyList()

    val errors = mutableListOf<SpellingErrorSpan>()
    for (i in tokens.indices) {
      val cur = tokens[i]
      val prevWord = tokens.getOrNull(i - 1)?.word
      val nextWord = tokens.getOrNull(i + 1)?.word

      if (!isValidWord(context, cur.word)) {
        errors += SpellingErrorSpan(cur.start, cur.end, cur.word, prevWord, nextWord)
        continue
      }

      // Context-aware check for real-word Vietnamese confusions (l/n, tr/ch, s/x, r/d/gi, hỏi/ngã):
      // e.g. "tập chung" -> "tập trung", "nói truyện" -> "nói chuyện", "đi nên" -> "đi lên".
      val contextualFix = findContextualConfusionFix(context, cur.lower, prevWord, nextWord)
      if (contextualFix != null) {
        val formattedFix = applyCaseLike(cur.word, contextualFix)
        errors += SpellingErrorSpan(cur.start, cur.end, cur.word, prevWord, nextWord, formattedFix)
      }
    }
    return errors
  }

  private fun findContextualConfusionFix(
    context: Context?,
    lowerWord: String,
    previousWord: String?,
    nextWord: String?,
  ): String? {
    if (lowerWord.length < 2) return null
    val currentLeftScore = if (previousWord != null) {
      VietnameseLexicon.bigramScore(context, previousWord, lowerWord)
    } else 0
    val currentRightScore = if (nextWord != null) {
      VietnameseLexicon.bigramScore(context, lowerWord, nextWord)
    } else 0
    // If current word already forms a valid known bigram with either neighbor, keep it.
    if (currentLeftScore > 0 || currentRightScore > 0) return null

    var bestVariant: String? = null
    var bestScore = 0
    for (variant in phoneticConfusableVariants(lowerWord)) {
      val leftScore = if (previousWord != null) {
        VietnameseLexicon.bigramScore(context, previousWord, variant)
      } else 0
      val rightScore = if (nextWord != null) {
        VietnameseLexicon.bigramScore(context, variant, nextWord)
      } else 0
      val combined = maxOf(leftScore, rightScore)
      if (combined >= CONTEXT_BIGRAM_THRESHOLD && combined > bestScore) {
        bestScore = combined
        bestVariant = variant
      }
    }
    return bestVariant
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

  /**
   * Offline Tier-1 full-field correction pass using dictionary + phonetic confusion rules +
   * bigram context. Used as an instant fallback when the optional AI ONNX model is not downloaded.
   */
  fun correctFullTextOffline(context: Context?, text: String): Pair<String, Int> {
    if (text.isBlank()) return text to 0
    val errors = findErrors(context, text)
    if (errors.isEmpty()) return text to 0
    val sb = StringBuilder(text)
    var fixedCount = 0
    // Apply from end to start so character offsets remain valid.
    for (span in errors.asReversed()) {
      val fix = span.preferredFix ?: suggestCorrections(
        context = context,
        misspelledWord = span.word,
        previousWord = span.previousWord,
        nextWord = span.nextWord,
        maxResults = 1,
      ).firstOrNull()
      if (fix != null && fix != span.word) {
        sb.replace(span.start, span.end, fix)
        fixedCount++
      }
    }
    return sb.toString() to fixedCount
  }

  fun suggestCorrections(
    context: Context?,
    misspelledWord: String,
    previousWord: String? = null,
    maxResults: Int = 4,
    nextWord: String? = null,
    preferredFix: String? = null,
  ): List<String> {
    val lower = VietnameseLexicon.normalizeWord(misspelledWord)
    if (lower.isEmpty()) return emptyList()

    val candidates = LinkedHashMap<String, Double>()

    if (!preferredFix.isNullOrBlank()) {
      val prefLower = VietnameseLexicon.normalizeWord(preferredFix)
      if (prefLower.isNotEmpty() && prefLower != lower) {
        candidates[prefLower] = 0.1
      }
    }

    val telexRepaired = replayTelex(lower)
    if (telexRepaired != null && telexRepaired != lower &&
      telexRepaired !in NON_EXISTENT_CONFUSABLE_SYLLABLES && isValidVietnameseSyllable(telexRepaired)
    ) {
      candidates.putIfAbsent(telexRepaired, 0.25)
    }

    // Prioritize Vietnamese phonetic confusion counterparts (l↔n, tr↔ch, s↔x, r↔d↔gi, hỏi↔ngã)
    for (variant in phoneticConfusableVariants(lower)) {
      val hasContext =
        VietnameseLexicon.bigramScore(context, previousWord, variant) > 0 ||
          (nextWord != null && VietnameseLexicon.bigramScore(context, variant, nextWord) > 0)
      val cost = if (hasContext) 0.2 else 0.35
      val prev = candidates[variant]
      if (prev == null || cost < prev) {
        candidates[variant] = cost
      }
    }

    val known = VietnameseLexicon.allKnownWords(context)
    val asciiTarget = VietnameseLexicon.foldAscii(lower)
    for (word in known) {
      if (word == lower || word in NON_EXISTENT_CONFUSABLE_SYLLABLES) continue
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

    return candidates.entries
      .sortedWith(
        compareBy<Map.Entry<String, Double>> { it.value }
          .thenByDescending {
            VietnameseLexicon.bigramScore(context, previousWord, it.key) +
              (if (nextWord != null) VietnameseLexicon.bigramScore(context, it.key, nextWord) else 0)
          }
          .thenByDescending { VietnameseLexicon.unigramScore(context, it.key) }
      )
      .take(maxResults)
      .map { (word, _) -> applyCaseLike(misspelledWord, word) }
  }

  private fun applyCaseLike(reference: String, word: String): String {
    val preserveUpper = reference.firstOrNull()?.isUpperCase() == true
    return if (preserveUpper) {
      word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    } else {
      word
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
          (ca == 'l' && cb == 'n') || (ca == 'n' && cb == 'l') -> 0.35
          (ca == 's' && cb == 'x') || (ca == 'x' && cb == 's') -> 0.35
          (ca == 'r' && cb == 'd') || (ca == 'd' && cb == 'r') -> 0.4
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
