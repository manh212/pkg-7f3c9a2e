package com.google.android.accessibility.talkback.vietnameseime

import java.text.Normalizer

internal object Telex {
  private const val TONES = "\u0301\u0300\u0309\u0303\u0323"
  private const val TONE_KEYS = "sfrxj"
  private const val SHAPED_VOWELS = "ăâêôơư"
  private const val VOWELS = "aeiouyăâêôơư"

  fun apply(word: String, key: Char): String {
    val letter = key.lowercaseChar()
    val (plain, tone) = splitTone(word)
    val newTone = TONE_KEYS.indexOf(letter)
    if (newTone >= 0) {
      val target = tonePosition(plain)
      if (target >= 0) {
        return if (newTone == tone) plain + key else withToneAt(plain, newTone, target)
      }
    }
    if (letter == 'z' && tone >= 0) return plain

    val shaped = changeShape(plain, key)
    if (shaped != null) return withTone(shaped, tone)
    val continued = if (key.lowercaseChar() in "iuycgmnpt") upgradeOpenUo(plain) else plain
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
      else if (pair + 2 < word.length) replace(word, pair, 'ư')
      else word
      return replace(horned, pair + 1, 'ơ')
    }
    val hornedPair = lower.lastIndexOf("ươ")
    if (hornedPair >= 0) {
      return replace(replace(word, hornedPair, 'u'), hornedPair + 1, 'o') + key
    }
    val openPair = lower.lastIndexOf("uơ")
    if (openPair >= 0 && !(openPair == 1 && lower.startsWith("qu"))) {
      return replace(word, openPair, 'ư')
    }
    val index = lower.indexOfLast { it in "aouăơư" }
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

  private fun upgradeOpenUo(word: String): String {
    val lower = word.lowercase()
    val index = lower.lastIndexOf("uơ")
    if (index < 0 || index + 2 != word.length) return word
    if (index == 1 && lower.startsWith("qu")) return word
    return replace(word, index, 'ư')
  }

  private fun replace(word: String, index: Int, letter: Char): String =
    word.replaceRange(index, index + 1, if (word[index].isUpperCase()) letter.uppercase() else letter.toString())
}
