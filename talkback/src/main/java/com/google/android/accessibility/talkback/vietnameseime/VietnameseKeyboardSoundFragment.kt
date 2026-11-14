package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.talkback.soundthemes.SOUND_LIBRARY
import com.google.android.accessibility.talkback.timer.SoundChooser

class VietnameseKeyboardSoundFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_sound_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_sound_and_vibration)

  private val spaceSoundChooser: SoundChooser by lazy {
    keySoundChooser(KeyboardKeySounds.Slot.SPACE)
  }
  private val normalSoundChooser: SoundChooser by lazy {
    keySoundChooser(KeyboardKeySounds.Slot.NORMAL)
  }
  private val deleteSoundChooser: SoundChooser by lazy {
    keySoundChooser(KeyboardKeySounds.Slot.DELETE)
  }
  private val enterSoundChooser: SoundChooser by lazy {
    keySoundChooser(KeyboardKeySounds.Slot.ENTER)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<Preference>(getString(R.string.pref_vietnamese_keyboard_key_sound_space_key))
      ?.let { spaceSoundChooser.bind(it) }
    findPreference<Preference>(getString(R.string.pref_vietnamese_keyboard_key_sound_normal_key))
      ?.let { normalSoundChooser.bind(it) }
    findPreference<Preference>(getString(R.string.pref_vietnamese_keyboard_key_sound_delete_key))
      ?.let { deleteSoundChooser.bind(it) }
    findPreference<Preference>(getString(R.string.pref_vietnamese_keyboard_key_sound_enter_key))
      ?.let { enterSoundChooser.bind(it) }
  }

  override fun onPause() {
    spaceSoundChooser.stopPreview()
    normalSoundChooser.stopPreview()
    deleteSoundChooser.stopPreview()
    enterSoundChooser.stopPreview()
    super.onPause()
  }

  private fun keySoundChooser(slot: KeyboardKeySounds.Slot): SoundChooser {
    val defaultName = SOUND_LIBRARY.find { it.resId == slot.defaultResId }?.name
    return SoundChooser(
      this,
      slot,
      KeyboardKeySounds,
      library = SOUND_LIBRARY.distinctBy { it.name },
      defaultSummary = defaultName,
      libraryDefaultLabel =
        defaultName?.let { getString(R.string.vietnamese_ime_key_sound_default, it) },
    )
  }
}
