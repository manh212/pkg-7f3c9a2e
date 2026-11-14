package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class VietnameseKeyboardRowsFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_rows_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_keyboard_rows)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    updateSymbolsSummary()
  }

  override fun onResume() {
    super.onResume()
    updateSymbolsSummary()
  }

  private fun updateSymbolsSummary() {
    val context = context ?: return
    findPreference<Preference>("pref_vietnamese_keyboard_symbols_screen")?.summary =
      VietnameseKeyboardSymbols.read(context, SharedPreferencesUtils.getSharedPreferences(context))
  }
}
