package com.google.android.accessibility.talkback.translation

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceScreen
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference
import com.google.android.accessibility.material.preference.AccessibilitySuiteSwitchPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.talkback.speech.VoiceProfileNames
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.VoiceProfiles

class BilingualTranslationFragment : TalkbackBaseFragment() {
  private lateinit var prefs: SharedPreferences
  private lateinit var original: AccessibilitySuiteListPreference
  private lateinit var translated: AccessibilitySuiteListPreference

  override fun getTitle(): CharSequence = getString(R.string.title_pref_translation_bilingual)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    preferenceManager.setStorageDeviceProtected()
    val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

    screen.addPreference(AccessibilitySuiteSwitchPreference(requireContext()).apply {
      key = TranslationSettings.PREF_BILINGUAL
      setTitle(R.string.title_pref_translation_bilingual_enabled)
      setDefaultValue(false)
      isIconSpaceReserved = false
    })

    original = profilePreference(
      TranslationSettings.PREF_BILINGUAL_ORIGINAL_PROFILE,
      R.string.title_pref_translation_bilingual_original_profile,
    )
    screen.addPreference(original)

    translated = profilePreference(
      TranslationSettings.PREF_BILINGUAL_TRANSLATED_PROFILE,
      R.string.title_pref_translation_bilingual_translated_profile,
    )
    screen.addPreference(translated)

    screen.addPreference(AccessibilitySuiteListPreference(requireContext()).apply {
      key = TranslationSettings.PREF_BILINGUAL_ORDER
      setTitle(R.string.title_pref_translation_bilingual_order)
      entries = arrayOf(
        getString(R.string.translation_bilingual_original_first),
        getString(R.string.translation_bilingual_translated_first),
        getString(R.string.translation_bilingual_parallel),
      )
      entryValues = arrayOf(
        TranslationSettings.BILINGUAL_ORIGINAL_FIRST,
        TranslationSettings.BILINGUAL_TRANSLATED_FIRST,
        TranslationSettings.BILINGUAL_PARALLEL,
      )
      setDefaultValue(TranslationSettings.BILINGUAL_ORIGINAL_FIRST)
      summary = "%s"
      isIconSpaceReserved = false
    })

    screen.addPreference(AccessibilitySuiteListPreference(requireContext()).apply {
      key = TranslationSettings.PREF_BILINGUAL_STREAM
      setTitle(R.string.title_pref_translation_bilingual_stream)
      entries = arrayOf(
        getString(R.string.translation_bilingual_stream_default),
        getString(R.string.timer_stream_accessibility),
        getString(R.string.timer_stream_media),
        getString(R.string.timer_stream_ring),
      )
      entryValues = arrayOf(
        "",
        FailoverTextToSpeech.STREAM_VALUE_ACCESSIBILITY,
        FailoverTextToSpeech.STREAM_VALUE_MEDIA,
        FailoverTextToSpeech.STREAM_VALUE_RING,
      )
      setDefaultValue("")
      summary = "%s"
      isIconSpaceReserved = false
    })

    screen.addPreference(AccessibilitySuiteSwitchPreference(requireContext()).apply {
      key = TranslationSettings.PREF_BILINGUAL_SWAP_PROFILES
      setTitle(R.string.title_pref_translation_bilingual_swap_profiles)
      setDefaultValue(false)
      isIconSpaceReserved = false
    })

    preferenceScreen = screen
  }

  override fun onResume() {
    super.onResume()
    updateProfiles()
  }

  private fun profilePreference(keyName: String, title: Int): AccessibilitySuiteListPreference =
    AccessibilitySuiteListPreference(requireContext()).apply {
      key = keyName
      setTitle(title)
      setDefaultValue("")
      summary = "%s"
      isIconSpaceReserved = false
    }

  private fun updateProfiles() {
    val ids = VoiceProfiles.ids(prefs)
    val names = VoiceProfileNames.names(requireContext(), prefs)
    val entries = arrayOf(getString(R.string.voice_profile_default), *ids.map { names[it] ?: it }.toTypedArray())
    val values = arrayOf("", *ids.toTypedArray())
    original.entries = entries
    original.entryValues = values
    original.value = TranslationSettings.originalProfile(prefs)
    translated.entries = entries
    translated.entryValues = values
    translated.value = TranslationSettings.translatedProfile(prefs)
  }
}
