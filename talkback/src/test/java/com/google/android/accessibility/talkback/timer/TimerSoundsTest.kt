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
import androidx.test.core.app.ApplicationProvider
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The per-timer sound choices: storage, labelling, and falling back to the own sound. */
@RunWith(RobolectricTestRunner::class)
class TimerSoundsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    File(context.createDeviceProtectedStorageContext().dataDir, "timer_sounds")
      .listFiles()
      ?.forEach { it.delete() }
  }

  @Test
  fun noChoice_usesTheTimersOwnSound() {
    for (slot in TimerSounds.Slot.entries) {
      assertNull(TimerSounds.soundFile(context, slot))
      assertNull(TimerSounds.label(context, prefs, slot))
      assertFalse(TimerSounds.play(context, slot))
    }
  }

  @Test
  fun chosenSound_isStoredUnderItsLabel_andPlays() {
    val stored = byteArrayOf(1, 2, 3, 4)
    // Robolectric's shadow player accepts the copied file, so the copy succeeds.
    val result =
      TimerSounds.setSound(
        context,
        prefs,
        TimerSounds.Slot.STOPWATCH,
        ByteArrayInputStream(stored),
        "ogg",
        "my_sound",
      )

    assertEquals(TimerSounds.Result.OK, result)
    assertEquals("my_sound", TimerSounds.label(context, prefs, TimerSounds.Slot.STOPWATCH))
    assertTrue(TimerSounds.soundFile(context, TimerSounds.Slot.STOPWATCH)!!.readBytes()
      .contentEquals(stored))
    assertTrue(TimerSounds.play(context, TimerSounds.Slot.STOPWATCH))
    // The other timers are untouched.
    assertNull(TimerSounds.label(context, prefs, TimerSounds.Slot.CLOCK))
    assertFalse(TimerSounds.play(context, TimerSounds.Slot.CLOCK))
  }

  @Test
  fun useDefault_removesTheChoiceAndItsFile() {
    TimerSounds.setSound(
      context,
      prefs,
      TimerSounds.Slot.CLOCK,
      ByteArrayInputStream(byteArrayOf(9, 9)),
      "ogg",
      "tick",
    )

    TimerSounds.useDefault(context, prefs, TimerSounds.Slot.CLOCK)

    assertNull(TimerSounds.label(context, prefs, TimerSounds.Slot.CLOCK))
    assertNull(TimerSounds.soundFile(context, TimerSounds.Slot.CLOCK))
    assertFalse(prefs.contains(TimerSounds.Slot.CLOCK.key))
    assertFalse(TimerSounds.play(context, TimerSounds.Slot.CLOCK))
  }

  @Test
  fun missingCopiedFile_fallsBackToTheOwnSound() {
    TimerSounds.setSound(
      context,
      prefs,
      TimerSounds.Slot.COUNTDOWN,
      ByteArrayInputStream(byteArrayOf(7)),
      "ogg",
      "bell",
    )
    TimerSounds.soundFile(context, TimerSounds.Slot.COUNTDOWN)!!.delete()

    assertNull(TimerSounds.label(context, prefs, TimerSounds.Slot.COUNTDOWN))
    assertFalse(TimerSounds.play(context, TimerSounds.Slot.COUNTDOWN))
  }

  @Test
  fun oversizedSound_isRefused() {
    val result =
      TimerSounds.setSound(
        context,
        prefs,
        TimerSounds.Slot.CLOCK,
        ByteArrayInputStream(ByteArray(5 * 1024 * 1024 + 1)),
        "ogg",
        "huge",
      )

    assertEquals(TimerSounds.Result.TOO_LARGE, result)
    assertNull(TimerSounds.soundFile(context, TimerSounds.Slot.CLOCK))
    assertNull(TimerSounds.label(context, prefs, TimerSounds.Slot.CLOCK))
  }
}
