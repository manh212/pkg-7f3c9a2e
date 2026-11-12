/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.content.res.XmlResourceParser
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun readingLandingPage_listsSixGroupsInOrder() {
    val rows = rowsOf(R.xml.settings_group_speech)

    assertEquals(
      listOf(
        context.getString(R.string.pref_category_group_speech_key) to null,
        "pref_reading_style_screen" to "ReadingStyleFragment",
        "pref_reading_item_info_screen" to "ReadingItemInfoFragment",
        "pref_reading_text_screen" to "ReadingTextFragment",
        "pref_screen_announcements" to "ScreenAnnouncementsFragment",
        context.getString(R.string.pref_status_readout_key) to "StatusReadoutFragment",
        "pref_navigation_feedback_screen" to "NavigationFeedbackFragment",
      ),
      rows.map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun readingStyleScreen_hasPresetTimeFormatAndInterrupt() {
    assertEquals(
      listOf(
        context.getString(R.string.pref_verbosity_preset_key),
        context.getString(R.string.pref_time_feedback_format_key),
        "pref_always_interrupt_speech",
      ),
      rowsOf(R.xml.settings_group_reading_style).map { it.first },
    )
  }

  @Test
  fun readingItemInfoScreen_hasFourRows() {
    assertEquals(
      listOf(
        "pref_spoken_item_information",
        context.getString(R.string.pref_speak_element_ids_key),
        context.getString(R.string.pref_a11y_hints_key),
        context.getString(R.string.pref_speak_roles_key),
        context.getString(R.string.pref_node_desc_order_key),
      ),
      rowsOf(R.xml.settings_group_reading_item_info).map { it.first },
    )
  }

  @Test
  fun readingTextScreen_hasSevenVisibleRowsPlusHiddenPunctuationSwitch() {
    val rows = rowsOf(R.xml.settings_group_reading_text)

    assertEquals(
      listOf(
        "pref_speech_text_options",
        context.getString(R.string.pref_capital_letters_key),
        context.getString(R.string.pref_phonetic_letters_mode_key),
        context.getString(R.string.pref_formatting_inline_key),
        context.getString(R.string.pref_formatting_inline_detail_key),
        context.getString(R.string.pref_punctuation_key),
        context.getString(R.string.pref_punctuation_verbosity),
        context.getString(R.string.pref_emoji_speech_key),
        context.getString(R.string.pref_emoji_repeat_count_key),
        context.getString(R.string.pref_count_repeated_symbols_key),
      ),
      rows.map { it.first },
    )
    assertTrue(
      "the old punctuation switch stays in the XML only to keep its key in the preset map",
      rows.any { it.first == context.getString(R.string.pref_punctuation_key) },
    )
  }

  @Test
  fun movedRowsLeftTheReadingScreenAndLandInTheirNewHomes() {
    val landing = rowsOf(R.xml.settings_group_speech)
    assertFalse(
      "call settings moved to the main screen",
      landing.any { it.first == "pref_call_settings" },
    )
    assertFalse(
      "full-screen monitoring moved to Notifications",
      landing.any { it.first == "pref_full_screen_monitor" },
    )

    val main = rowsOf(R.xml.preferences)
    val calls = main.indexOfFirst { it.first == "pref_call_settings" }
    assertTrue(calls > 0)
    assertEquals("SpeechGroupFragment", main[calls - 1].second?.substringAfterLast('.'))
    assertEquals("CallSettingsFragment", main[calls].second?.substringAfterLast('.'))

    assertTrue(
      rowsOf(R.xml.notification_group_automation_monitor_preferences).any {
        it.first == "pref_full_screen_monitor"
            && it.second?.contains("FullScreenMonitorSettingsFragment") == true
      },
    )
    assertFalse(
      "navigation feedback moved into the reading landing page",
      rowsOf(R.xml.settings_group_navigation).any {
        it.first == "pref_navigation_feedback_screen"
      },
    )
  }

  /** The android:key of the parser's current tag, resolving @string references to their value. */
  private fun keyOf(parser: XmlResourceParser): String? {
    val resId = parser.getAttributeResourceValue(ANDROID_NS, "key", 0)
    return if (resId != 0) context.getString(resId) else parser.getAttributeValue(ANDROID_NS, "key")
  }

  /** Every row of an XML preference screen, in document order, as key to fragment. */
  private fun rowsOf(xml: Int): List<Pair<String?, String?>> {
    val rows = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(xml)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser)
        val fragment = parser.getAttributeValue(ANDROID_NS, "fragment")
        if (key != null || fragment != null) rows += key to fragment
      }
      event = parser.next()
    }
    return rows
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}
