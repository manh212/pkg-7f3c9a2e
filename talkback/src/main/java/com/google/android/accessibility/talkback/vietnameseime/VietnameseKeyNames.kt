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
    val letter = LETTER_NAMES[key.lowercaseChar()]
    if (letter != null) return if (key.isUpperCase()) "$letter hoa" else letter
    return SpeechCleanupUtils.characterToName(vietnameseContext, key) ?: label
  }

  private companion object {
    val LETTER_NAMES = mapOf(
      'a' to "a", 'b' to "bê", 'c' to "xê", 'd' to "dê", 'e' to "e", 'f' to "ép",
      'g' to "giê", 'h' to "hát", 'i' to "i", 'j' to "giây", 'k' to "ca", 'l' to "e lờ",
      'm' to "em", 'n' to "en", 'o' to "o", 'p' to "pê", 'q' to "quy", 'r' to "e rờ",
      's' to "ét", 't' to "tê", 'u' to "u", 'v' to "vê", 'w' to "vê kép", 'x' to "ích",
      'y' to "i dài", 'z' to "dét", 'đ' to "đê",
    )
  }
}
