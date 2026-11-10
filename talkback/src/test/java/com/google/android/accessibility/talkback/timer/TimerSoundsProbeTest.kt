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
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Temporary probe: isolates which TimerSounds call trips the Robolectric FD interceptor. */
@RunWith(RobolectricTestRunner::class)
class TimerSoundsProbeTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Test
  fun probePrefsCommit() {
    prefs.edit().clear().commit()
    assertNull(prefs.getString(TimerSounds.Slot.CLOCK.key, null))
  }

  @Test
  fun probeDirectoryListing() {
    val dir = File(context.createDeviceProtectedStorageContext().dataDir, "timer_sounds")
    dir.listFiles()?.forEach { it.delete() }
  }

  @Test
  fun probeObjectInit() {
    assertEquals(3, TimerSounds.Slot.entries.size)
    assertFalse(TimerSounds.copyExecutor.isShutdown)
  }

  @Test
  fun probeSoundFileAndLabel() {
    assertNull(TimerSounds.soundFile(context, TimerSounds.Slot.CLOCK))
    assertNull(TimerSounds.label(context, prefs, TimerSounds.Slot.CLOCK))
  }

  @Test
  fun probePlay() {
    assertFalse(TimerSounds.play(context, TimerSounds.Slot.CLOCK))
  }
}
