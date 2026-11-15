package com.google.android.accessibility.talkback.vietnameseime

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

internal object VietnameseKeyboardShortcuts {
  const val ENTRIES_KEY = "pref_vietnamese_keyboard_shortcut_entries"
  const val LITERAL_CODE_FIELD = "com.google.android.accessibility.talkback.vietnameseime.shortcutCode"
  const val WORD_CHARACTERS = """[\p{L}\p{N}\p{M}]+"""
  private val codePattern = Regex(WORD_CHARACTERS)

  fun isValidCode(code: String): Boolean = codePattern.matches(code)

  fun read(prefs: SharedPreferences): Map<String, String> {
    val saved = prefs.getString(ENTRIES_KEY, null) ?: return emptyMap()
    val json = try {
      JSONObject(saved)
    } catch (e: JSONException) {
      return emptyMap()
    }
    return buildMap {
      for (code in json.keys()) {
        val text = json.opt(code) as? String
        if (isValidCode(code) && !text.isNullOrBlank()) put(code, text)
      }
    }
  }

  fun write(prefs: SharedPreferences, entries: Map<String, String>) {
    val json = JSONObject()
    for ((code, text) in entries) json.put(code, text)
    prefs.edit().putString(ENTRIES_KEY, json.toString()).apply()
  }
}
