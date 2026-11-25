package com.google.android.accessibility.talkback.preference.base

import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class VoiceSettingsStructureTest {

  @Test
  fun voiceSettingsScreenHasNoDuplicateSubScreensAndIncludesGeneralOptions() {
    val activity = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
    val fragment = TextToSpeechSettingsFragment()
    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()

    // Core single-responsibility sections
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_voice_profiles_screen_key)))
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_voice_language_screen_key)))
    assertNotNull(fragment.findPreference<Preference>("pref_speech_dictionary_screen"))
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_voice_feature_voices_screen_key)))

    // General reading options directly on the screen
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_speech_stream_key)))
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_intonation_key)))
    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_keep_screen_on_while_speaking_key)))

    // Duplicate sub-screens and duplicate settings removed from top level
    assertNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_voice_engine_stream_screen_key)))
    assertNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_voice_pronunciation_screen_key)))
    assertNull(fragment.findPreference<Preference>("pref_speech_adjustment_screen"))
    assertNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_tts_engine_key)))
    assertNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_speak_in_phrases_key)))
  }

  @Test
  fun defaultVoiceProfileIncludesDirectVoicePicker() {
    val activity = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
    val fragment = VoiceProfileFragment()
    fragment.arguments = android.os.Bundle().apply { putString("id", "") }
    activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()

    assertNotNull(fragment.findPreference<Preference>(activity.getString(R.string.pref_tts_engine_key)))
    assertNotNull(fragment.findPreference<Preference>("default_profile_voice_picker"))
  }
}
