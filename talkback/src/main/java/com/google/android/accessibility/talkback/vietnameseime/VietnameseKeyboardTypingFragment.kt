package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment

class VietnameseKeyboardTypingFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_typing_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_typing)
}
