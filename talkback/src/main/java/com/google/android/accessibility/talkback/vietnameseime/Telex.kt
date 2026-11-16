package com.google.android.accessibility.talkback.vietnameseime

import java.text.Normalizer

internal object Telex {
  private const val TONES = "\u0301\u0300\u0309\u0303\u0323"
  private const val TONE_KEYS = "sfrxj"
  private const val SHAPED_VOWELS = "ăâêôơư"
  private const val VOWELS = "aeiouyăâêôơư"
  private val quickInitials = mapOf(
    "cc" to "ch", "tt" to "tr", "gg" to "gi", "kk" to "kh", "nn" to "ng", "pp" to "ph",
    "qq" to "qu", "hh" to "nh", "ggg" to "gh", "ttt" to "th",
  )

  fun apply(word: String, key: Char, quickTelex: Boolean = false): String {
    val letter = key.lowercaseChar()
    if (quickTelex && letter in "aeiouy") {
      val initial = quickInitials[word.lowercase()]
      if (initial != null) {
        val spelledInitial = if (initial == "ng" && letter in "ei") "ngh" else initial
        return buildString {
          spelledInitial.forEachIndexed { index, character ->
            append(if (word[minOf(index, word.lastIndex)].isUpperCase()) character.uppercaseChar()
              else character)
          }
          append(key)
        }
      }
    }
    val (plain, tone) = splitTone(word)
    val newTone = TONE_KEYS.indexOf(letter)
    if (newTone >= 0) {
      if (plain.lastOrNull()?.lowercaseChar() == letter) return plain + key
      val target = tonePosition(plain)
      if (target >= 0) {
        return if (newTone == tone) plain + key else withToneAt(plain, newTone, target)
      }
    }
    if (letter == 'z' && tone >= 0) return plain

    val shaped = changeShape(plain, key, tone)
    if (shaped != null) return withTone(shaped, tone)
    val continued = if (letter in "iuycgmnpt") completeHornPair(plain) else plain
    return withTone(continued + key, tone)
  }

  fun deleteLast(word: String): String {
    val (plain, tone) = splitTone(word)
    return withTone(plain.dropLast(1), tone)
  }

  private fun splitTone(word: String): Pair<String, Int> {
    if (word.all { it in 'a'..'z' || it in 'A'..'Z' }) return word to -1
    var tone = -1
    val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
    val plain = buildString {
      for (character in decomposed) {
        val index = TONES.indexOf(character)
        if (index < 0) append(character) else tone = index
      }
    }
    return Normalizer.normalize(plain, Normalizer.Form.NFC) to tone
  }

  private fun withTone(word: String, tone: Int): String {
    if (tone < 0) return word
    val index = tonePosition(word)
    if (index < 0) return word
    return withToneAt(word, tone, index)
  }

  private fun withToneAt(word: String, tone: Int, index: Int): String =
    Normalizer.normalize(
      word.substring(0, index + 1) + TONES[tone] + word.substring(index + 1),
      Normalizer.Form.NFC,
    )

  private fun tonePosition(word: String): Int {
    val lower = word.lowercase()
    val vowels = word.indices.filter { lower[it] in VOWELS }
    if (vowels.isEmpty()) return -1
    val syllableVowels = if (vowels.size > 1 && (lower.startsWith("qu") || lower.startsWith("gi"))) {
      vowels.drop(1)
    } else vowels
    val shaped = syllableVowels.lastOrNull { lower[it] in SHAPED_VOWELS }
    if (shaped != null) return shaped
    val last = syllableVowels.last()
    return when {
      syllableVowels.size >= 3 -> syllableVowels[1]
      syllableVowels.size == 1 || last < word.lastIndex -> last
      syllableVowels.size == 2 && lower[syllableVowels[0]] == 'u' && lower[last] == 'y' -> last
      else -> syllableVowels.first()
    }
  }

  private fun changeShape(word: String, key: Char, tone: Int): String? {
    val lower = word.lowercase()
    return when (key.lowercaseChar()) {
      'd' -> {
        if (lower.startsWith('đ')) replace(word, 0, 'd') + key
        else if (lower.startsWith('d')) replace(word, 0, 'đ')
        else null
      }
      'a' -> replaceShape(word, lower, key, 'a', 'â', tone)
      'e' -> replaceShape(word, lower, key, 'e', 'ê', tone)
      'o' -> replaceShape(word, lower, key, 'o', 'ô', tone)
      'w' -> addHornOrBreve(word, lower, key)
      else -> null
    }
  }

  private fun replaceShape(word: String, lower: String, key: Char, plain: Char, shaped: Char, tone: Int): String? {
    if (tone >= 0) return null
    val index = lower.indexOfLast { it == plain || it == shaped }
    if (index < 0 || index != word.lastIndex) return null
    return if (lower[index] == shaped) replace(word, index, plain) + key
    else replace(word, index, shaped)
  }

  private fun addHornOrBreve(word: String, lower: String, key: Char): String? {
    val pair = lower.lastIndexOf("uo")
    if (pair >= 0) {
      val horned = if (pair == 1 && lower.startsWith("quo")) word
      else if (pair + 2 < word.length) replace(word, pair, 'ư')
      else word
      return replace(horned, pair + 1, 'ơ')
    }
    val firstHorned = lower.lastIndexOf("ưo")
    if (firstHorned >= 0) return replace(word, firstHorned + 1, 'ơ')
    val hornedPair = lower.lastIndexOf("ươ")
    if (hornedPair >= 0) {
      return replace(replace(word, hornedPair, 'u'), hornedPair + 1, 'o') + key
    }
    val openPair = lower.lastIndexOf("uơ")
    if (openPair >= 0 && !(openPair == 1 && lower.startsWith("qu"))) {
      return replace(word, openPair, 'ư')
    }
    val index = hornTargetIndex(word, lower)
    if (index < 0) return null
    if (lower[index] == 'u' && index == 1 && lower.startsWith("qu")) return null
    return when (lower[index]) {
      'a' -> replace(word, index, 'ă')
      'o' -> replace(word, index, 'ơ')
      'u' -> replace(word, index, 'ư')
      'ă' -> replace(word, index, 'a') + key
      'ơ' -> replace(word, index, 'o') + key
      'ư' -> replace(word, index, 'u') + key
      else -> null
    }
  }

  private fun hornTargetIndex(word: String, lower: String): Int {
    val vowels = word.indices.filter { lower[it] in VOWELS }
    if (vowels.isEmpty()) return -1
    val syllableVowels =
      if (vowels.size > 1 && (lower.startsWith("qu") || lower.startsWith("gi"))) vowels.drop(1)
      else vowels
    var start = syllableVowels.size - 1
    while (start > 0 && syllableVowels[start - 1] == syllableVowels[start] - 1) start--
    val cluster = syllableVowels.subList(start, syllableVowels.size)
    val open = cluster.last() == word.lastIndex
    if (open && cluster.size == 2) {
      val first = lower[cluster[0]]
      val second = lower[cluster[1]]
      return if (second in SHAPED_VOWELS || isOnGlidePair(first, second)) cluster[1] else cluster[0]
    }
    if (open && cluster.size >= 3) return cluster[1]
    return lower.indexOfLast { it in "aouăơư" }
  }

  private fun isOnGlidePair(first: Char, second: Char): Boolean =
    when (first) {
      'o' -> second == 'a' || second == 'e'
      'i' -> second == 'a' || second == 'e' || second == 'u' || second == 'o'
      'y' -> second == 'u'
      'u' -> second == 'e'
      else -> false
    }

  private fun completeHornPair(word: String): String {
    val index = word.length - 2
    return when {
      word.endsWith("ưo", ignoreCase = true) -> replace(word, index + 1, 'ơ')
      word.endsWith("uơ", ignoreCase = true) &&
        !(index == 1 && word.startsWith("qu", ignoreCase = true)) -> replace(word, index, 'ư')
      else -> word
    }
  }

  private fun replace(word: String, index: Int, letter: Char): String =
    word.replaceRange(index, index + 1, if (word[index].isUpperCase()) letter.uppercase() else letter.toString())
}
