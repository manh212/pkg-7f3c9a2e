package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class VietnameseKeyboardSizeFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_size_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_keyboard_size)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    showCurrentValue(
      context.getString(R.string.pref_vietnamese_keyboard_size_portrait_key),
      prefs.getInt(context.getString(R.string.pref_vietnamese_keyboard_size_portrait_key), 100),
    )
    showCurrentValue(
      context.getString(R.string.pref_vietnamese_keyboard_size_landscape_key),
      prefs.getInt(context.getString(R.string.pref_vietnamese_keyboard_size_landscape_key), 100),
    )
    val spacingKey = context.getString(R.string.pref_vietnamese_keyboard_bottom_spacing_key)
    showCurrentValue(
      spacingKey,
      prefs.getInt(
        spacingKey,
        resources.getInteger(R.integer.pref_vietnamese_keyboard_bottom_spacing_default),
      ),
    )
  }

  private fun showCurrentValue(key: String, currentValue: Int) {
    findPreference<SeekBarPreference>(key)?.apply {
      val wasPersistent = isPersistent
      isPersistent = false
      value = currentValue
      isPersistent = wasPersistent
    }
  }
}
