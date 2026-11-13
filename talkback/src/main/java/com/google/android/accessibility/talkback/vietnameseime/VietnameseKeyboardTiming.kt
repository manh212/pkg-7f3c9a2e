package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.SharedPreferences
import android.view.ViewConfiguration
import com.google.android.accessibility.talkback.R

internal data class VietnameseKeyboardTiming(
  val exploreSpeechMs: Long = 0,
  val releaseSpeechMs: Long = 0,
  val directInputMs: Long = 100,
  val doubleTapMs: Long = ViewConfiguration.getDoubleTapTimeout().toLong(),
  val tapHoldMs: Long = ViewConfiguration.getLongPressTimeout().toLong(),
) {
  companion object {
    const val EXPLORE_SPEECH = "pref_vietnamese_keyboard_explore_speech"
    const val RELEASE_SPEECH = "pref_vietnamese_keyboard_release_speech"
    const val DOUBLE_TAP = "pref_vietnamese_keyboard_double_tap"
    const val TAP_HOLD = "pref_vietnamese_keyboard_tap_hold"

    fun read(context: Context, prefs: SharedPreferences): VietnameseKeyboardTiming {
      val sharedDelay = prefs.getInt(context.getString(R.string.pref_direct_input_tap_delay_key), 100)
      return VietnameseKeyboardTiming(
        prefs.getInt(EXPLORE_SPEECH, 0).coerceIn(0, 300).toLong(),
        prefs.getInt(RELEASE_SPEECH, 0).coerceIn(0, 300).toLong(),
        sharedDelay.coerceIn(0, 300).toLong(),
        prefs.getInt(DOUBLE_TAP, ViewConfiguration.getDoubleTapTimeout()).coerceIn(150, 1000).toLong(),
        prefs.getInt(TAP_HOLD, ViewConfiguration.getLongPressTimeout()).coerceIn(100, 1500).toLong(),
      )
    }

    fun isTimingKey(key: String?): Boolean =
      key == EXPLORE_SPEECH || key == RELEASE_SPEECH || key == DOUBLE_TAP || key == TAP_HOLD
  }
}
