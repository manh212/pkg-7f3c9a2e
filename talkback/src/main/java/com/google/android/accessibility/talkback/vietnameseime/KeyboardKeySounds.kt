/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.timer.SoundChoiceStore
import com.google.android.accessibility.talkback.timer.SoundFileStore
import com.google.android.accessibility.talkback.timer.SoundSlot
import java.io.File
import java.io.InputStream

object KeyboardKeySounds : SoundChoiceStore {

  enum class Slot(
    override val key: String,
    override val fileName: String,
    override val defaultResId: Int,
  ) : SoundSlot {
    SPACE("pref_vietnamese_keyboard_key_sound_space", "space", R.raw.lib_new_space),
    NORMAL("pref_vietnamese_keyboard_key_sound_normal", "normal", R.raw.lib_new_click),
    DELETE("pref_vietnamese_keyboard_key_sound_delete", "delete", R.raw.lib_new_cancel),
    ENTER("pref_vietnamese_keyboard_key_sound_enter", "enter", R.raw.lib_new_clock),
  }

  override fun soundFile(context: Context, slot: SoundSlot): File? =
    SoundFileStore.soundFile(SoundFileStore.directory(context, "key_sounds"), slot.fileName)

  override fun label(context: Context, prefs: SharedPreferences, slot: SoundSlot): String? =
    prefs.getString(slot.key, null)?.takeIf { it.isNotEmpty() && soundFile(context, slot) != null }

  override fun setSound(
    context: Context,
    prefs: SharedPreferences,
    slot: SoundSlot,
    input: InputStream,
    extension: String,
    label: String,
  ): SoundFileStore.Result =
    SoundFileStore.setSound(
      context,
      prefs,
      "key_sounds",
      slot.fileName,
      slot.key,
      input,
      extension,
      label,
    )

  override fun useDefault(context: Context, prefs: SharedPreferences, slot: SoundSlot) {
    soundFile(context, slot)?.delete()
    prefs.edit().remove(slot.key).apply()
  }

  fun resolve(context: Context, slot: KeyboardKeySounds.Slot): KeySound =
    KeyboardKeySounds.soundFile(context, slot)?.let { KeySound.Custom(it) }
      ?: KeySound.Builtin(slot.defaultResId)

  fun defaultSounds(): KeySounds =
    KeySounds(
      space = KeySound.Builtin(KeyboardKeySounds.Slot.SPACE.defaultResId),
      normal = KeySound.Builtin(KeyboardKeySounds.Slot.NORMAL.defaultResId),
      delete = KeySound.Builtin(KeyboardKeySounds.Slot.DELETE.defaultResId),
      enter = KeySound.Builtin(KeyboardKeySounds.Slot.ENTER.defaultResId),
    )
}

sealed class KeySound {
  data class Builtin(val resId: Int) : KeySound()

  data class Custom(val file: File) : KeySound()
}

data class KeySounds(
  val space: KeySound,
  val normal: KeySound,
  val delete: KeySound,
  val enter: KeySound,
) {
  operator fun get(slot: KeyboardKeySounds.Slot): KeySound =
    when (slot) {
      KeyboardKeySounds.Slot.SPACE -> space
      KeyboardKeySounds.Slot.NORMAL -> normal
      KeyboardKeySounds.Slot.DELETE -> delete
      KeyboardKeySounds.Slot.ENTER -> enter
    }
}
