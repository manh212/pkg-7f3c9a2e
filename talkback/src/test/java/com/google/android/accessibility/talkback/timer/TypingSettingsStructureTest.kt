package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.res.XmlResourceParser
import android.util.Xml
import androidx.annotation.XmlRes
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The typing section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypingSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun typingScreen_listsKeyboardAndOtherGroupsInOrder() {
    val rows = rowsOf(R.xml.settings_group_typing)
    assertEquals(
      listOf(
        "pref_category_group_typing_key",
        "pref_typing_confirmation",
        "pref_dictation_screen",
        "pref_typing_echo_screen",
        "meridian_braille_settings",
        "pref_vietnamese_keyboard_screen",
        "pref_typing_interrupt_screen",
        "pref_physical_keyboard_key",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "TypingMethodPrefFragment",
        "DictationFragment",
        "TypingEchoFragment",
        null,
        "VietnameseKeyboardSettingsFragment",
        "TypingInterruptFragment",
        null,
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun typingMethodScreen_holdsLongPressDurationAndTapDelay() {
    assertEquals(
      listOf(
        "pref_typing_method_prefix_key",
        "pref_typing_confirmation",
        "pref_direct_input_tap_delay",
        "pref_typing_long_press_duration",
      ),
      rowsOf(R.xml.typing_method_preferences).map { it.first },
    )
  }

  @Test
  fun vietnameseKeyboardScreen_listsOwnVoiceLayoutAndFiveSubScreens() {
    val rows = rowsOf(R.xml.vietnamese_keyboard_preferences)
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_android_settings",
        "pref_vietnamese_keyboard_own_voice",
        "pref_vietnamese_keyboard_layout",
        "pref_vietnamese_keyboard_size_screen",
        "pref_vietnamese_keyboard_sound_screen",
        "pref_vietnamese_keyboard_timing_screen",
        "pref_vietnamese_keyboard_rows_screen",
        "pref_vietnamese_keyboard_typing_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        null,
        null,
        "VietnameseKeyboardSizeFragment",
        "VietnameseKeyboardSoundFragment",
        "VietnameseKeyboardTimingFragment",
        "VietnameseKeyboardRowsFragment",
        "VietnameseKeyboardTypingFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun vietnameseKeyboardSizeScreen_holdsTwoSizeSliders() {
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_size_portrait",
        "pref_vietnamese_keyboard_size_landscape",
      ),
      rowsOf(R.xml.vietnamese_keyboard_size_preferences).map { it.first },
    )
  }

  @Test
  fun vietnameseKeyboardSoundScreen_holdsVibrationAndKeySound() {
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_vibrate_on_key",
        "pref_vietnamese_keyboard_key_sound",
      ),
      rowsOf(R.xml.vietnamese_keyboard_sound_preferences).map { it.first },
    )
  }

  @Test
  fun vietnameseKeyboardTimingScreen_holdsFiveTimingSliders() {
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_timing",
        "pref_vietnamese_keyboard_explore_speech",
        "pref_vietnamese_keyboard_release_speech",
        "pref_direct_input_tap_delay",
        "pref_vietnamese_keyboard_double_tap",
        "pref_vietnamese_keyboard_tap_hold",
      ),
      rowsOf(R.xml.vietnamese_keyboard_timing_preferences).map { it.first },
    )
  }

  @Test
  fun vietnameseKeyboardRowsScreen_holdsRowTogglesAndSymbolsCustomization() {
    val rows = rowsOf(R.xml.vietnamese_keyboard_rows_preferences)
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_show_number_row",
        "pref_vietnamese_keyboard_show_symbols_row",
        "pref_vietnamese_keyboard_symbols_screen",
      ),
      rows.map { it.first },
    )
    assertTrue(rows[2].second?.endsWith("VietnameseKeyboardSymbolsFragment") == true)
  }

  @Test
  fun vietnameseKeyboardTypingScreen_holdsAutoCapitalizeDoubleSpaceAndMic() {
    assertEquals(
      listOf(
        "pref_vietnamese_keyboard_auto_capitalize",
        "pref_vietnamese_keyboard_double_space_period",
        "pref_vietnamese_keyboard_mic_enabled",
      ),
      rowsOf(R.xml.vietnamese_keyboard_typing_preferences).map { it.first },
    )
  }

  @Test
  fun echoScreen_holdsEchoRowsAndCacheKeyboardKeys() {
    assertEquals(
      listOf(
        "pref_echo_on_screen_keyboard",
        "pref_echo_physical_keyboard",
        "pref_echo_braille_keyboard",
        "pref_cache_keyboard_keys",
      ),
      rowsOf(R.xml.typing_echo_preferences).map { it.first },
    )
  }

  @Test
  fun interruptScreen_holdsTwoRows() {
    assertEquals(
      listOf(
        "pref_interrupt_typing",
        "pref_interrupt_enter",
      ),
      rowsOf(R.xml.typing_interrupt_preferences).map { it.first },
    )
  }

  @Test
  fun movedRowsAreGoneFromTheLanding() {
    val rows = rowsOf(R.xml.settings_group_typing).map { it.first }
    assertFalse(rows.any { it == "pref_echo_on_screen_keyboard" })
    assertFalse(rows.any { it == "pref_echo_physical_keyboard" })
    assertFalse(rows.any { it == "pref_echo_braille_keyboard" })
    assertFalse(rows.any { it == "pref_interrupt_typing" })
    assertFalse(rows.any { it == "pref_interrupt_enter" })
    assertFalse(rows.any { it == "pref_cache_keyboard_keys" })
    assertFalse(rows.any { it == "pref_typing_long_press_duration" })
    assertTrue(rows.any { it == "pref_typing_echo_screen" })
    assertTrue(rows.any { it == "pref_typing_interrupt_screen" })
    val keyboardRows = rowsOf(R.xml.vietnamese_keyboard_preferences).map { it.first }
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_timing" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_explore_speech" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_release_speech" })
    assertFalse(keyboardRows.any { it == "pref_direct_input_tap_delay" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_double_tap" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_tap_hold" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_size_portrait" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_size_landscape" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_vibrate_on_key" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_key_sound" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_show_number_row" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_show_symbols_row" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_auto_capitalize" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_double_space_period" })
    assertFalse(keyboardRows.any { it == "pref_vietnamese_keyboard_mic_enabled" })
    assertTrue(keyboardRows.any { it == "pref_vietnamese_keyboard_timing_screen" })
    assertTrue(keyboardRows.any { it == "pref_vietnamese_keyboard_size_screen" })
    assertTrue(keyboardRows.any { it == "pref_vietnamese_keyboard_sound_screen" })
    assertTrue(keyboardRows.any { it == "pref_vietnamese_keyboard_rows_screen" })
    assertTrue(keyboardRows.any { it == "pref_vietnamese_keyboard_typing_screen" })
    val timingRows = rowsOf(R.xml.vietnamese_keyboard_timing_preferences).map { it.first }
    assertTrue(timingRows.any { it == "pref_vietnamese_keyboard_explore_speech" })
    assertTrue(timingRows.any { it == "pref_direct_input_tap_delay" })
  }

  private fun rowsOf(@XmlRes resId: Int): List<Pair<String, String?>> {
    val parser = context.resources.getXml(resId)
    val rows = mutableListOf<Pair<String, String?>>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser)
        if (key != null) {
          rows.add(key to parser.getAttributeValue(NAMESPACE, "fragment"))
        }
      }
    }
    return rows
  }

  /**
   * Robolectric's getAttributeValue does not resolve @string keys, so read the resource id
   * directly from the parser and resolve it instead.
   */
  private fun keyOf(parser: XmlResourceParser): String? {
    val resId = parser.getAttributeResourceValue(NAMESPACE, "key", 0)
    return if (resId != 0) context.getString(resId) else parser.getAttributeValue(NAMESPACE, "key")
  }

  companion object {
    private const val NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
