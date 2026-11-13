package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.res.Configuration
import com.google.android.accessibility.utils.output.SpeechCleanupUtils
import java.util.Locale

internal class VietnameseKeyNames(context: Context) {
  private val vietnameseContext = context.createConfigurationContext(
    Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("vi-VN")) },
  )

  fun spokenName(label: String): String {
    val key = label.singleOrNull() ?: return label
    val letter = LETTER_NAMES[key.lowercaseChar()] ?: DIGIT_NAMES[key] ?: SYMBOL_NAMES[key]
    if (letter != null) return if (key.isUpperCase()) "$letter hoa" else letter
    return SpeechCleanupUtils.characterToName(vietnameseContext, key) ?: label
  }

  private companion object {
    val LETTER_NAMES = mapOf(
      'a' to "a", 'b' to "bê", 'c' to "xê", 'd' to "dê", 'e' to "e", 'f' to "ép",
      'g' to "gờ", 'h' to "hát", 'i' to "i", 'j' to "gi", 'k' to "ca", 'l' to "lờ",
      'm' to "mờ", 'n' to "nờ", 'o' to "o", 'p' to "pê", 'q' to "quy", 'r' to "rờ",
      's' to "sờ", 't' to "tê", 'u' to "u", 'v' to "vê", 'w' to "vê kép", 'x' to "ích",
      'y' to "i dài", 'z' to "dét", 'đ' to "đê",
    )
    val DIGIT_NAMES = mapOf(
      '0' to "không", '1' to "một", '2' to "hai", '3' to "ba", '4' to "bốn",
      '5' to "năm", '6' to "sáu", '7' to "bảy", '8' to "tám", '9' to "chín",
    )
    val SYMBOL_NAMES = mapOf('+' to "cộng")
  }
}
