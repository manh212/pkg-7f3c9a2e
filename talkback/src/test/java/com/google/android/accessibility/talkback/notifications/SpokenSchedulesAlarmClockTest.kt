/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.timer.Stopwatch
import com.google.android.accessibility.talkback.timer.StopwatchEngine
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.Calendar
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The spoken schedules' alarms: the spoken clock is an exact alarm when exact-alarm access is
 * granted and an inexact one otherwise, never an alarm clock; the daily summary is an alarm
 * clock when access is granted. Neither ever disturbs the stopwatch's alarm; a day's summary
 * whose alarm was lost is read when the engine next runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpokenSchedulesAlarmClockTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val shadowAlarms: ShadowAlarmManager = Shadows.shadowOf(alarmManager)
  private val said = mutableListOf<CharSequence>()
  private var playedClockSounds = 0
  private val stoppers = mutableListOf<() -> Unit>()

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    said.clear()
    playedClockSounds = 0
  }

  @After
  fun tearDown() {
    stoppers.forEach { it() }
    stoppers.clear()
  }

  @Test
  fun clock_setsAnExactAlarmAtTheNextBoundary() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    startSchedules { putStringSet(SpokenSchedules.KEY_CLOCK_HOURS, SpokenSchedules.ALL_CLOCK_HOURS.map { it.toString() }.toSet()) }

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    val alarm = shadowAlarms.scheduledAlarms[0]
    assertNull(alarm.alarmClockInfo)
    assertTrue(
      "trigger ${alarm.triggerAtMs} is a next hourly boundary",
      alarm.triggerAtMs in
        setOf(
          SpokenSchedules.nextClockTime(System.currentTimeMillis() - 5_000L, SpokenSchedules.ALL_CLOCK_HOURS),
          SpokenSchedules.nextClockTime(System.currentTimeMillis() + 5_000L, SpokenSchedules.ALL_CLOCK_HOURS),
        ),
    )
  }

  @Test
  fun dailySummary_setsAnAlarmClock() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val minutes = minuteOfDay(System.currentTimeMillis()) + 2
    startSchedules {
      putBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, true)
      putString(SpokenSchedules.KEY_SUMMARY_TIME, minutes.toString())
    }

    val info = alarmManager.nextAlarmClock
    assertNotNull(info)
    assertTrue(
      info!!.triggerTime in
        setOf(
          SpokenSchedules.nextDailyTime(System.currentTimeMillis() - 5_000L, minutes),
          SpokenSchedules.nextDailyTime(System.currentTimeMillis() + 5_000L, minutes),
        )
    )
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertEquals("its time has not come, so nothing is read yet", 0, said.size)
  }

  @Test
  fun stop_cancelsTheAlarmClocks() {
    val minutes = minuteOfDay(System.currentTimeMillis()) + 2
    val schedules = startSchedules {
      putStringSet(SpokenSchedules.KEY_CLOCK_HOURS, SpokenSchedules.ALL_CLOCK_HOURS.map { it.toString() }.toSet())
      putBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, true)
      putString(SpokenSchedules.KEY_SUMMARY_TIME, minutes.toString())
    }
    assertEquals(2, shadowAlarms.scheduledAlarms.size)

    schedules.stop()

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(0, shadowAlarms.scheduledAlarms.size)
  }

  @Test
  fun clockBroadcast_speaksTheTimeAndReschedules() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    startSchedules { putStringSet(SpokenSchedules.KEY_CLOCK_HOURS, SpokenSchedules.ALL_CLOCK_HOURS.map { it.toString() }.toSet()) }
    val operation = shadowAlarms.scheduledAlarms.single().operation
    assertNotNull(operation)
    assertEquals(
      AlarmClockIntents.action(context, AlarmClockIntents.ACTION_SPOKEN_CLOCK),
      Shadows.shadowOf(operation).savedIntent.action,
    )

    context.sendBroadcast(
      Intent(AlarmClockIntents.action(context, AlarmClockIntents.ACTION_SPOKEN_CLOCK))
        .setPackage(context.packageName)
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertTrue("the clock speaks when its alarm fires", said.isNotEmpty())
    assertEquals(1, playedClockSounds)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun summaryWhoseTimeHasPassed_isReadOnceWhenTheEngineStarts() {
    val minutes = minuteOfDay(System.currentTimeMillis()) - 30
    startSchedules {
      putBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, true)
      putString(SpokenSchedules.KEY_SUMMARY_TIME, minutes.toString())
    }

    // Without notification access the summary reads its no-access line; what matters here is
    // that the missed summary is read at all, and marked read for today.
    assertEquals(1, said.size)
    assertEquals(0, playedClockSounds)
    assertEquals(
      context.getString(R.string.notification_summary_no_access),
      said[0].toString(),
    )
    assertEquals(todayKey(), prefs.getInt(SpokenSchedules.KEY_SUMMARY_LAST_DAY, 0))
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
  }

  @Test
  fun summaryAlreadyReadToday_isNotReadAgainOnRestart() {
    val minutes = minuteOfDay(System.currentTimeMillis()) - 30
    val schedules = startSchedules {
      putBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, true)
      putString(SpokenSchedules.KEY_SUMMARY_TIME, minutes.toString())
    }
    assertEquals(1, said.size)
    schedules.stop()

    startSchedules {
      putBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, true)
      putString(SpokenSchedules.KEY_SUMMARY_TIME, minutes.toString())
    }

    assertEquals(1, said.size)
  }

  @Test
  fun theTwoEngines_neverCancelEachOthersAlarm() {
    val before = System.currentTimeMillis()
    startSchedules { putStringSet(SpokenSchedules.KEY_CLOCK_HOURS, SpokenSchedules.ALL_CLOCK_HOURS.map { it.toString() }.toSet()) }
    val spokenTrigger = shadowAlarms.scheduledAlarms.single().triggerAtMs
    val stopwatch =
      StopwatchEngine(context, { _, _ -> }, {}).also {
        prefs
          .edit()
          .putLong(Stopwatch.KEY_STARTED_AT, before - 60_000L)
          .putString(Stopwatch.KEY_INTERVAL_MINUTES, "10")
          .putBoolean(Stopwatch.KEY_SOUND, false)
          .putBoolean(Stopwatch.KEY_VIBRATION, false)
          .commit()
        it.start()
        stoppers += { it.stop() }
      }
    assertEquals(2, shadowAlarms.scheduledAlarms.size)

    stopwatch.stop()

    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertEquals(spokenTrigger, shadowAlarms.scheduledAlarms.single().triggerAtMs)
    assertNull(alarmManager.nextAlarmClock)
  }

  private fun startSchedules(seed: android.content.SharedPreferences.Editor.() -> Unit): SpokenSchedules {
    prefs.edit().apply { seed() }.commit()
    return SpokenSchedules(context, { playedClockSounds++ }) { text, _ -> said += text }
      .also {
        it.start()
        stoppers += { it.stop() }
      }
  }

  private fun minuteOfDay(now: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
  }

  private fun todayKey(): Int {
    val calendar = Calendar.getInstance()
    return calendar.get(Calendar.YEAR) * 10_000 +
      (calendar.get(Calendar.MONTH) + 1) * 100 +
      calendar.get(Calendar.DAY_OF_MONTH)
  }
}
