package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.util.Xml
import androidx.annotation.XmlRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The voice section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun voiceScreen_isSevenSubScreensInOrder() {
    val rows = rowsOf(R.xml.text_to_speech_preferences)
    assertEquals(
      listOf(
        "pref_category_group_voice_key",
        "pref_voice_engine_stream_screen",
        "pref_tts_voice_profiles",
        "pref_voice_language_screen",
        "pref_voice_pronunciation_screen",
        "pref_speech_dictionary_screen",
        "pref_speech_adjustment_screen",
        "pref_voice_feature_voices_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "VoiceEngineFragment",
        "VoiceProfilesFragment",
        "VoiceLanguageFragment",
        "VoicePronunciationFragment",
        "SpeechDictionaryFragment",
        null,
        "VoiceFeatureVoicesFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun engineScreen_holdsReaderStreamAndSystemSettings() {
    assertEquals(
      listOf("pref_tts_engine", "pref_speech_stream", "pref_system_tts_settings"),
      rowsOf(R.xml.voice_engine_preferences).map { it.first },
    )
  }

  @Test
  fun languageScreen_holdsSwitchesAndVoiceOrder() {
    val rows = rowsOf(R.xml.voice_language_preferences)
    assertEquals(
      listOf("pref_switch_languages", "pref_switch_dialects", "pref_voice_switching_screen"),
      rows.map { it.first },
    )
    assertTrue(rows[2].second?.endsWith("VoiceSwitchingFragment") == true)
  }

  @Test
  fun pronunciationScreen_holdsIntonationAndPhrases() {
    assertEquals(
      listOf("pref_intonation", "pref_speak_in_phrases"),
      rowsOf(R.xml.voice_pronunciation_preferences).map { it.first },
    )
  }

  @Test
  fun featureVoicesScreen_listsEachFeatureVoiceAndItsScreen() {
    val rows = rowsOf(R.xml.voice_feature_voices_preferences)
    assertEquals(
      listOf(
        "pref_voice_feature_notification",
        "pref_voice_feature_clock",
        "pref_voice_feature_stopwatch",
        "pref_voice_feature_alarm",
        "pref_voice_feature_bilingual_original",
        "pref_voice_feature_bilingual_translated",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        "NotificationReadingFragment",
        "SpokenClockSettingsFragment",
        "StopwatchSettingsFragment",
        "CountdownAlarmSettingsFragment",
        "BilingualTranslationFragment",
        "BilingualTranslationFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun movedRowsLiveOnTheirNewScreensAndTheSlidersAreGone() {
    val voiceRows = rowsOf(R.xml.text_to_speech_preferences)
    assertFalse(voiceRows.any { it.first == "pref_speech_rate_seekbar_key_int" })
    assertFalse(voiceRows.any { it.first == "pref_speech_pitch_seekbar_key_int" })
    assertFalse(voiceRows.any { it.first == "pref_speech_volume_seekbar_key_int" })
    assertFalse(voiceRows.any { it.first == "pref_touch_stops_speech_key" })
    assertFalse(voiceRows.any { it.first == "pref_mute_speech_while_recording" })
    assertTrue(
      rowsOf(R.xml.settings_group_navigation)
        .any { it.first == "pref_touch_stops_speech_key" && it.second == null })
    assertTrue(
      rowsOf(R.xml.sound_and_vibration_preferences)
        .any { it.first == "pref_mute_speech_while_recording" && it.second == null })
  }

  private fun rowsOf(@XmlRes resId: Int): List<Pair<String, String?>> {
    val parser = context.resources.getXml(resId)
    val rows = mutableListOf<Pair<String, String?>>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser.getAttributeValue(NAMESPACE, "key"))
        if (key != null) {
          rows.add(key to parser.getAttributeValue(NAMESPACE, "fragment"))
        }
      }
    }
    return rows
  }

  /**
   * Robolectric's getAttributeValue does not resolve @string keys, so look the resource up by
   * name instead.
   */
  private fun keyOf(raw: String?): String? {
    if (raw == null) {
      return null
    }
    if (!raw.startsWith("@string/")) {
      return raw
    }
    val name = raw.removePrefix("@string/")
    val resId = context.resources.getIdentifier(name, "string", context.packageName)
    return if (resId == 0) raw else context.getString(resId)
  }

  companion object {
    private const val NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
