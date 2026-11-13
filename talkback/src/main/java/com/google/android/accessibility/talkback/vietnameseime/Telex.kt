package com.google.android.accessibility.talkback.vietnameseime

import java.text.Normalizer

internal object Telex {
  private const val TONES = "\u0301\u0300\u0309\u0303\u0323"
  private const val TONE_KEYS = "sfrxj"
  private const val SHAPED_VOWELS = "ăâêôơư"
  private const val VOWELS = "aeiouyăâêôơư"

  fun apply(word: String, key: Char): String {
    val letter = key.lowercaseChar()
    val (plain, tone) = takeTone(word)
    val target = tonePosition(plain)
    val newTone = TONE_KEYS.indexOf(letter)
    if (newTone >= 0 && target >= 0) {
      return if (newTone == tone) plain + key else withTone(plain, newTone)
    }
    if (letter == 'z' && tone >= 0) return plain

    val shaped = changeShape(plain, key)
    if (shaped != null) return withTone(shaped, tone)
    return withTone(plain + key, tone)
  }

  private fun takeTone(word: String): Pair<String, Int> {
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
    return Normalizer.normalize(
      word.substring(0, index + 1) + TONES[tone] + word.substring(index + 1),
      Normalizer.Form.NFC,
    )
  }

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
      else -> syllableVowels.first()
    }
  }

  private fun changeShape(word: String, key: Char): String? {
    val lower = word.lowercase()
    return when (key.lowercaseChar()) {
      'd' -> {
        if (lower.startsWith('đ')) replace(word, 0, 'd') + key
        else if (lower.startsWith('d')) replace(word, 0, 'đ')
        else null
      }
      'a' -> replaceShape(word, lower, key, 'a', 'â')
      'e' -> replaceShape(word, lower, key, 'e', 'ê')
      'o' -> replaceShape(word, lower, key, 'o', 'ô')
      'w' -> addHornOrBreve(word, lower, key)
      else -> null
    }
  }

  private fun replaceShape(word: String, lower: String, key: Char, plain: Char, shaped: Char): String? {
    val index = lower.indexOfLast { it == plain || it == shaped }
    if (index < 0) return null
    return if (lower[index] == shaped) replace(word, index, plain) + key
    else replace(word, index, shaped)
  }

  private fun addHornOrBreve(word: String, lower: String, key: Char): String? {
    val pair = lower.lastIndexOf("uo")
    if (pair >= 0) {
      val horned = if (pair == 1 && lower.startsWith("quo")) word
      else replace(word, pair, 'ư')
      return replace(horned, pair + 1, 'ơ')
    }
    val hornedPair = lower.lastIndexOf("ươ")
    if (hornedPair >= 0) {
      return replace(replace(word, hornedPair, 'u'), hornedPair + 1, 'o') + key
    }
    val index = lower.indexOfLast { it in "aouăơư" }
    if (index < 0) return null
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

  private fun replace(word: String, index: Int, letter: Char): String =
    word.replaceRange(index, index + 1, if (word[index].isUpperCase()) letter.uppercase() else letter.toString())
}
