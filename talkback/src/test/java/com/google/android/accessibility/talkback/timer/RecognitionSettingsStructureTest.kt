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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.res.XmlResourceParser
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

/** The recognition and AI section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun recognitionScreen_isSevenRowsInOrder() {
    val rows = rowsOf(R.xml.settings_group_screen_info)
    assertEquals(
      listOf(
        "pref_category_group_screen_info_key",
        "pref_auto_image_captioning_key",
        "pref_ai_services_screen",
        "pref_on_device_ai",
        "pref_screen_recognition_settings",
        "pref_subtitle_settings_screen",
        "pref_ocr_settings_screen",
        "pref_live_recognition_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "AutomaticDescriptionsFragment",
        "AiServicesFragment",
        "OnDeviceAiFragment",
        "ScreenRecognitionSettingsFragment",
        "SubtitleSettingsFragment",
        "OcrSettingsFragment",
        null,
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun aiServicesScreen_holdsProviderChoice() {
    assertEquals(
      listOf(
        "pref_ai_services",
        "pref_ai_provider_choice",
      ),
      rowsOf(R.xml.ai_services_preferences).map { it.first },
    )
  }

  @Test
  fun automaticDescriptionsScreen_noLongerHoldsAiServices() {
    val rows = rowsOf(R.xml.automatic_descriptions_preferences).map { it.first }
    assertEquals(
      listOf(
        "pref_category_automatic_description_summary_key",
        "pref_detailed_image_description_key",
        "pref_ai_label_suggestion_key",
        "pref_image_description_key",
        "pref_icon_detection_key",
        "pref_text_recognition_key",
        "pref_ocr_settings_screen",
      ),
      rows,
    )
    assertFalse(rows.any { it == "pref_ai_services" })
    assertFalse(rows.any { it == "pref_ai_provider_choice" })
  }

  @Test
  fun screenRecognitionScreen_hasNoGeminiKeyRow() {
    assertEquals(
      listOf(
        "pref_screen_recognition_method",
        "pref_screen_recognition_ocr_settings",
        "screen_recognition_privacy",
      ),
      rowsOf(R.xml.screen_recognition_preferences).map { it.first },
    )
  }

  @Test
  fun subtitleScreen_hasNoGeminiKeyRow() {
    val rows = rowsOf(R.xml.subtitle_preferences).map { it.first }
    assertEquals(
      listOf(
        "pref_subtitle_engine",
        "pref_ocr_data_download_key",
        "pref_subtitle_region",
        "pref_subtitle_left",
        "pref_subtitle_top",
        "pref_subtitle_right",
        "pref_subtitle_bottom",
        "pref_subtitle_interval",
        "pref_subtitle_speech",
        "pref_subtitle_gemini_voice",
        "pref_subtitle_gemini_channel",
      ),
      rows,
    )
    assertFalse(rows.any { it == "pref_gemini_api_key" })
  }

  @Test
  fun geminiSettingsScreen_keepsApiKeyRow() {
    assertEquals(
      listOf(
        "pref_gemini_api_key",
        "pref_gemini_model",
      ),
      rowsOf(R.xml.gemini_settings_preferences).map { it.first },
    )
    assertTrue(
      rowsOf(R.xml.screen_recognition_preferences).none { it.first == "pref_gemini_api_key" })
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
