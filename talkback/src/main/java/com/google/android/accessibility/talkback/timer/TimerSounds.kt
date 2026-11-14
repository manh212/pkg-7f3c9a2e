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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.SharedPreferences
import android.media.SoundPool
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.output.FeedbackController
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService

/**
 * The sound each timer plays when its own sound switch is on, chosen by the user apart from
 * the sound themes: one of the bundled library sounds, or an audio file of their own. The
 * chosen sound is copied into device-protected storage, like the sound themes' files, so
 * it still plays before the first unlock; the choice itself is per-device and stays out of
 * the settings backup, like the Braille keyboard's custom sounds. Until a sound is chosen
 * — or if the copied file is gone — the timer keeps the sound it has always played.
 */
object TimerSounds : SoundChoiceStore {

  enum class Slot(
    override val key: String,
    override val fileName: String,
    override val defaultResId: Int,
  ) : SoundSlot {
    CLOCK("pref_timer_sound_choice", "clock", R.raw.lib_new_clock),
    STOPWATCH("pref_stopwatch_sound_choice", "stopwatch", R.raw.chime_up),
    COUNTDOWN("pref_countdown_alarm_sound_choice", "countdown", R.raw.lib_new_clock),
  }

  typealias Result = SoundFileStore.Result

  val copyExecutor: ExecutorService get() = SoundFileStore.copyExecutor

  private val lock = Any()
  private var pool: SoundPool? = null
  // SoundPool sound IDs by file path, with the file stamp each was loaded from, so that a
  // re-chosen sound replaces the loaded one instead of playing the old copy.
  private val soundIds = HashMap<String, Int>()
  private val soundStamps = HashMap<String, Long>()

  override fun soundFile(context: Context, slot: SoundSlot): File? =
    SoundFileStore.soundFile(SoundFileStore.directory(context, "timer_sounds"), slot.fileName)

  override fun label(context: Context, prefs: SharedPreferences, slot: SoundSlot): String? =
    prefs.getString(slot.key, null)?.takeIf { it.isNotEmpty() && soundFile(context, slot) != null }

  override fun setSound(
    context: Context,
    prefs: SharedPreferences,
    slot: SoundSlot,
    input: InputStream,
    extension: String,
    label: String,
  ): Result {
    val result =
      SoundFileStore.setSound(
        context,
        prefs,
        "timer_sounds",
        slot.fileName,
        slot.key,
        input,
        extension,
        label,
      )
    if (result == Result.OK) preload(context)
    return result
  }

  override fun useDefault(context: Context, prefs: SharedPreferences, slot: SoundSlot) {
    soundFile(context, slot)?.delete()
    prefs.edit().remove(slot.key).apply()
  }

  /** Loads every chosen sound, so the first time a timer fires it plays at once. */
  fun preload(context: Context) {
    synchronized(lock) {
      for (slot in Slot.entries) soundFile(context, slot)?.let { load(it) }
    }
  }

  /**
   * Plays [slot]'s chosen sound and returns true, or returns false while the timer is on
   * its own sound, leaving the caller to play that instead. A sound still loading is
   * skipped rather than played late, like the sound themes' player.
   */
  fun play(context: Context, slot: Slot): Boolean {
    val file = soundFile(context, slot) ?: return false
    synchronized(lock) {
      val soundId = load(file)
      if (soundId != 0) pool?.play(soundId, 1f, 1f, 1, 0, 1f)
    }
    return true
  }

  /** Loads [file] into the pool, reloading it when the file on disk has changed. */
  private fun load(file: File): Int {
    val pool =
      pool
        ?: SoundPool.Builder()
          .setMaxStreams(4)
          .setAudioAttributes(FeedbackController.feedbackAttributes())
          .build()
          .also { pool = it }
    val path = file.absolutePath
    val stamp = file.lastModified()
    val loaded = soundIds[path]
    if (loaded != null && soundStamps[path] == stamp) return loaded
    if (loaded != null) pool.unload(loaded)
    val soundId = pool.load(path, 1)
    soundIds[path] = soundId
    soundStamps[path] = stamp
    return soundId
  }
}
