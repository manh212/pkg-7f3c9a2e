package com.google.android.accessibility.talkback.vietnameseime

import androidx.preference.ListPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.VoiceProfiles

class VietnameseKeyboardSettingsFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_name)

  override fun onStart() {
    super.onStart()
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val names = VoiceProfiles.names(prefs, getString(R.string.voice_profile_default)) {
      getString(R.string.voice_profile_new_name, it)
    }
    findPreference<ListPreference>(getString(R.string.pref_vietnamese_keyboard_voice_profile_key))?.let {
      it.entries =
        (listOf(getString(R.string.vietnamese_ime_voice_profile_system_default)) + names.values)
          .toTypedArray()
      it.entryValues = (listOf("") + names.keys).toTypedArray()
      if (it.findIndexOfValue(it.value.orEmpty()) < 0) it.value = ""
    }
  }
}
