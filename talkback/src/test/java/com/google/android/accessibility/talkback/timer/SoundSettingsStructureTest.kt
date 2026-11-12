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

/** The sound and vibration section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun soundScreen_isSevenSubScreensInOrder() {
    val rows = rowsOf(R.xml.sound_and_vibration_preferences)
    assertEquals(
      listOf(
        "pref_category_group_sound_key",
        "pref_sound_feedback_screen",
        "pref_sound_output_screen",
        "pref_sound_ducking_screen",
        "pref_sound_themes",
        "pref_individual_feedback",
        "pref_sound_vibration_screen",
        "pref_sound_feature_sounds_screen",
        "pref_speech_footer",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "SoundFeedbackFragment",
        "SoundOutputFragment",
        "SoundDuckingFragment",
        "SoundThemesFragment",
        "IndividualFeedbackFragment",
        "SoundVibrationFragment",
        "SoundFeatureSoundsFragment",
        null,
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun feedbackScreen_holdsFeedbackSounds() {
    assertEquals(
      listOf(
        "pref_soundback",
        "pref_soundback_volume_seekbar_int",
        "pref_mute_screen_reader_sounds_in_silent",
        "pref_mute_speech_while_recording",
        "pref_speak_volume_changes",
      ),
      rowsOf(R.xml.sound_feedback_preferences).map { it.first },
    )
  }

  @Test
  fun outputScreen_holdsOutputAndLatency() {
    assertEquals(
      listOf(
        "pref_audio_output_device",
        "pref_speaker_devices",
        "pref_low_latency_audio",
      ),
      rowsOf(R.xml.sound_output_preferences).map { it.first },
    )
  }

  @Test
  fun duckingScreen_holdsDuckingAndMuting() {
    assertEquals(
      listOf("pref_use_audio_focus", "pref_proximity"),
      rowsOf(R.xml.sound_ducking_preferences).map { it.first },
    )
  }

  @Test
  fun vibrationScreen_holdsVibration() {
    assertEquals(
      listOf("pref_vibration", "pref_vibration_strength"),
      rowsOf(R.xml.sound_vibration_preferences).map { it.first },
    )
  }

  @Test
  fun featureSoundsScreen_linksOnly() {
    val rows = rowsOf(R.xml.sound_feature_sounds_preferences)
    assertEquals(
      listOf(
        "pref_sound_feature_clock",
        "pref_sound_feature_stopwatch",
        "pref_sound_feature_alarm",
        "pref_sound_feature_notification",
        "pref_sound_feature_bilingual",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        "SpokenClockSettingsFragment",
        "StopwatchSettingsFragment",
        "CountdownAlarmSettingsFragment",
        "NotificationReadingFragment",
        "BilingualTranslationFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun movedRowsLiveOnTheirNewScreens() {
    val soundScreens =
      listOf(
        R.xml.sound_and_vibration_preferences,
        R.xml.sound_feedback_preferences,
        R.xml.sound_output_preferences,
        R.xml.sound_ducking_preferences,
        R.xml.sound_vibration_preferences,
        R.xml.sound_feature_sounds_preferences,
      )
    assertTrue(
      rowsOf(R.xml.call_preferences)
        .any { it.first == "pref_speakerphone_away_from_ear" && it.second == null })
    assertFalse(
      soundScreens.any { resId ->
        rowsOf(resId).any { it.first == "pref_speakerphone_away_from_ear" }
      })
    assertFalse(
      soundScreens.any { resId ->
        rowsOf(resId).any { it.first == "pref_control_sounds_speak_roles" }
      })
  }

  @Test
  @Config(sdk = [29])
  fun landingRowsMatchOnOlderSdk() {
    assertEquals(
      listOf(
        "pref_category_group_sound_key",
        "pref_sound_feedback_screen",
        "pref_sound_output_screen",
        "pref_sound_ducking_screen",
        "pref_sound_themes",
        "pref_individual_feedback",
        "pref_sound_vibration_screen",
        "pref_sound_feature_sounds_screen",
        "pref_speech_footer",
      ),
      rowsOf(R.xml.sound_and_vibration_preferences).map { it.first },
    )
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
