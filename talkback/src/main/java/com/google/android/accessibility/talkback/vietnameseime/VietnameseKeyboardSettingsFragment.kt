package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class VietnameseKeyboardSettingsFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_name)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val context = requireContext()
    val timing = VietnameseKeyboardTiming.read(context, SharedPreferencesUtils.getSharedPreferences(context))
    showCurrentValue(VietnameseKeyboardTiming.DOUBLE_TAP, timing.doubleTapMs)
    showCurrentValue(VietnameseKeyboardTiming.TAP_HOLD, timing.tapHoldMs)
  }

  private fun showCurrentValue(key: String, milliseconds: Long) {
    findPreference<SeekBarPreference>(key)?.apply {
      val wasPersistent = isPersistent
      isPersistent = false
      value = milliseconds.toInt()
      isPersistent = wasPersistent
    }
  }
}
