/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
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

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The countdown alarms: the soonest is always a system alarm clock, several run at once, and
 * one whose moment passed while the engine was away rings when the engine next starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountdownAlarmEngineAlarmClockTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val said = mutableListOf<CharSequence>()
  private var sounds = 0
  private var engine: CountdownAlarmEngine? = null

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    said.clear()
    sounds = 0
  }

  @After
  fun tearDown() {
    engine?.stop()
    engine = null
  }

  @Test
  fun add_armsAnAlarmClockAtTheDeadline_andConfirms() {
    startEngine()
    engine!!.add("Take the medicine", 600_000L)

    val entry = CountdownAlarm.alarms(context).single()
    val info = alarmManager.nextAlarmClock
    assertNotNull(info)
    assertEquals(entry.deadlineMillis, info!!.triggerTime)
    assertNotNull(info.showIntent)
    assertEquals(
      listOf(
        context.getString(
          R.string.countdown_alarm_added_named,
          "Take the medicine",
          Stopwatch.formatElapsed(context, 600_000L),
        )
      ),
      said.map { it.toString() },
    )
  }

  @Test
  fun twoAlarms_onlyTheSoonestIsArmed_andCancellingItArmsTheOther() {
    startEngine()
    engine!!.add("Later", 1_200_000L)
    engine!!.add("Sooner", 600_000L)
    val sooner = CountdownAlarm.alarms(context).first { it.name == "Sooner" }
    val later = CountdownAlarm.alarms(context).first { it.name == "Later" }
    assertEquals(sooner.deadlineMillis, alarmManager.nextAlarmClock!!.triggerTime)

    engine!!.cancel(sooner.id)

    assertEquals(listOf(later), CountdownAlarm.alarms(context))
    assertEquals(later.deadlineMillis, alarmManager.nextAlarmClock!!.triggerTime)
    assertTrue(
      said.any {
        it.toString() ==
          context.getString(R.string.countdown_alarm_cancelled_named, "Sooner")
      }
    )
  }

  @Test
  fun cancellingTheOnlyAlarm_disarms() {
    startEngine()
    engine!!.add("", 600_000L)
    val entry = CountdownAlarm.alarms(context).single()

    engine!!.cancel(entry.id)

    assertTrue(CountdownAlarm.alarms(context).isEmpty())
    assertNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun start_ringsAnAlarmWhoseMomentPassedWhileAway() {
    prefs.edit().putString(CountdownAlarm.KEY_REPEATS, "1").commit()
    CountdownAlarm.add(context, "Missed", -60_000L)

    startEngine()
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf("Missed"), said.map { it.toString() })
    assertEquals(1, sounds)
    assertTrue(CountdownAlarm.alarms(context).isEmpty())
  }

  @Test
  fun firing_repeatsTheSetNumberOfTimes_thenStops() {
    prefs.edit().putString(CountdownAlarm.KEY_REPEATS, "2").commit()
    CountdownAlarm.add(context, "Twice", -1_000L)

    startEngine()
    val looper = Shadows.shadowOf(Looper.getMainLooper())
    looper.idle()
    assertEquals(1, said.count { it.toString() == "Twice" })
    looper.idleFor(6, TimeUnit.SECONDS)
    assertEquals(2, said.count { it.toString() == "Twice" })
    looper.idleFor(30, TimeUnit.SECONDS)
    assertEquals(2, said.count { it.toString() == "Twice" })
    assertEquals(2, sounds)
  }

  @Test
  fun broadcast_ringsADueAlarm_andArmsTheNextOne() {
    prefs.edit().putString(CountdownAlarm.KEY_REPEATS, "1").commit()
    startEngine()
    CountdownAlarm.add(context, "Due", -1_000L)
    val future = CountdownAlarm.add(context, "Future", 3_600_000L)

    context.sendBroadcast(
      Intent(AlarmClockIntents.action(context, AlarmClockIntents.ACTION_COUNTDOWN_ALARM))
        .setPackage(context.packageName)
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertTrue(said.any { it.toString() == "Due" })
    assertEquals(listOf(future), CountdownAlarm.alarms(context))
    assertEquals(future.deadlineMillis, alarmManager.nextAlarmClock!!.triggerTime)
  }

  @Test
  fun store_keepsNamesDurationsAndOrder_andRepeatsDefaultsToThree() {
    assertEquals(3, CountdownAlarm.repeats(context))
    val later = CountdownAlarm.add(context, "  Later  ", 1_200_000L)
    val sooner = CountdownAlarm.add(context, "Sooner", 600_000L)

    val stored = CountdownAlarm.alarms(context)
    assertEquals(listOf(sooner, later), stored)
    assertEquals("Later", stored[1].name)
    assertEquals(1_200_000L, stored[1].durationMillis)
    assertTrue(stored[0].id != stored[1].id)

    CountdownAlarm.remove(context, sooner.id)
    assertEquals(listOf(later), CountdownAlarm.alarms(context))
    prefs.edit().putString(CountdownAlarm.KEY_REPEATS, "5").commit()
    assertEquals(5, CountdownAlarm.repeats(context))
  }

  @Test
  fun dialogDurations_mustBePositiveAndWithinBounds() {
    assertEquals(3600L, CountdownAlarmDialogs.durationSeconds("1", "0", "0"))
    assertEquals(1800L, CountdownAlarmDialogs.durationSeconds("", "30", ""))
    assertEquals(59L, CountdownAlarmDialogs.durationSeconds("0", "0", "59"))
    assertNull(CountdownAlarmDialogs.durationSeconds("0", "0", "0"))
    assertNull(CountdownAlarmDialogs.durationSeconds("", "", ""))
    assertNull(CountdownAlarmDialogs.durationSeconds("0", "60", "0"))
    assertNull(CountdownAlarmDialogs.durationSeconds("0", "0", "60"))
    assertNull(CountdownAlarmDialogs.durationSeconds("100", "0", "0"))
    assertNull(CountdownAlarmDialogs.durationSeconds("-1", "0", "0"))
    assertNull(CountdownAlarmDialogs.durationSeconds("x", "0", "0"))
  }

  private fun startEngine() {
    engine =
      CountdownAlarmEngine(context, { text, _ -> said += text }, { sounds++ }).also { it.start() }
  }
}
