package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.res.XmlResourceParser
import android.os.Bundle
import android.util.Xml
import androidx.annotation.XmlRes
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
  fun voiceScreen_hasLandingSectionsAndOptionsInOrder() {
    val rows = rowsOf(R.xml.text_to_speech_preferences)
    assertEquals(
      listOf(
        "pref_category_group_voice_key",
        "pref_tts_voice_profiles",
        "pref_voice_language_screen",
        "pref_speech_dictionary_screen",
        "pref_voice_feature_voices_screen",
        "pref_category_voice_general_key",
        "pref_speech_stream",
        "pref_intonation",
        "pref_keep_screen_on_while_speaking",
        "pref_system_tts_settings",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "VoiceProfilesFragment",
        "VoiceLanguageFragment",
        "SpeechDictionaryFragment",
        "VoiceFeatureVoicesFragment",
        null,
        null,
        null,
        null,
        null,
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun profileScreen_holdsEngineAndPhraseSettings() {
    val activity =
      Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    val fragment = VoiceProfileFragment()
    fragment.arguments = Bundle().apply {
      putString(VoiceProfileFragment.ARG_PROFILE_ID, "")
    }
    activity.supportFragmentManager
      .beginTransaction()
      .add(android.R.id.content, fragment)
      .commitNow()

    assertNotNull(
      fragment.findPreference<Preference>(activity.getString(R.string.pref_tts_engine_key)),
    )
    assertNotNull(
      fragment.findPreference<Preference>(activity.getString(R.string.pref_speak_in_phrases_key)),
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
  fun voiceScreen_holdsGeneralReadingOptions() {
    assertEquals(
      listOf(
        "pref_speech_stream",
        "pref_intonation",
        "pref_keep_screen_on_while_speaking",
        "pref_system_tts_settings",
      ),
      rowsOf(R.xml.text_to_speech_preferences).map { it.first }.takeLast(4),
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
      rowsOf(R.xml.touch_activation_preferences)
        .any { it.first == "pref_touch_stops_speech_key" && it.second == null })
    assertTrue(
      rowsOf(R.xml.sound_feedback_preferences)
        .any { it.first == "pref_mute_speech_while_recording" && it.second == null })
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
