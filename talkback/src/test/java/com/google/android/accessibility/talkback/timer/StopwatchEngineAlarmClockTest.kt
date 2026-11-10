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

package com.google.android.accessibility.talkback.timer

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.utils.SharedPreferencesUtils
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
 * The stopwatch's alarms and milestones: a reminder is always a system alarm clock, and a
 * milestone whose alarm never arrived is said once when the engine next runs, not dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopwatchEngineAlarmClockTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val shadowAlarms: ShadowAlarmManager = Shadows.shadowOf(alarmManager)
  private val said = mutableListOf<CharSequence>()
  private var engine: StopwatchEngine? = null

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    said.clear()
  }

  @After
  fun tearDown() {
    engine?.stop()
    engine = null
  }

  @Test
  fun sliderIntervalOverridesTheOldSavedValue() {
    prefs.edit().putString(Stopwatch.KEY_INTERVAL_MINUTES, "10")
      .putInt(Stopwatch.KEY_INTERVAL_SLIDER, 20).commit()
    assertEquals(20, Stopwatch.intervalMinutes(context))
    prefs.edit().putInt(Stopwatch.KEY_INTERVAL_SLIDER, 60).commit()
    assertEquals(60, Stopwatch.intervalMinutes(context))
  }

  @Test
  fun savedIntervalsSnapToTheNearestChoice() {
    prefs.edit().putInt(Stopwatch.KEY_INTERVAL_SLIDER, 7).commit()
    assertEquals(5, Stopwatch.intervalMinutes(context))
    prefs.edit().putInt(Stopwatch.KEY_INTERVAL_SLIDER, 23).commit()
    assertEquals(25, Stopwatch.intervalMinutes(context))
    prefs.edit().remove(Stopwatch.KEY_INTERVAL_SLIDER)
      .putString(Stopwatch.KEY_INTERVAL_MINUTES, "40").commit()
    assertEquals(45, Stopwatch.intervalMinutes(context))
  }

  @Test
  fun legacyIntervalSurvivesUntilTheSliderIsSaved() {
    prefs.edit().putString(Stopwatch.KEY_INTERVAL_MINUTES, "15").commit()
    assertEquals(15, Stopwatch.intervalMinutes(context))
  }

  @Test
  fun start_setsAnAlarmClockAtTheNextMultiple() {
    val startedAt = System.currentTimeMillis() - 60_000L
    startEngine(startedAt)

    val info = alarmManager.nextAlarmClock
    assertNotNull(info)
    assertEquals(startedAt + 600_000L, info!!.triggerTime)
    assertNotNull(info.showIntent)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNotNull(shadowAlarms.scheduledAlarms[0].alarmClockInfo)
    assertEquals("no milestone has been reached yet", 0, said.size)
  }

  @Test
  fun stop_cancelsTheAlarmClock() {
    startEngine(System.currentTimeMillis() - 60_000L)
    assertNotNull(alarmManager.nextAlarmClock)

    engine!!.stop()
    engine = null

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(0, shadowAlarms.scheduledAlarms.size)
  }

  @Test
  fun missedMilestones_theNewestIsSaidOnceWhenTheEngineStarts() {
    // Twenty-five minutes in, the 10- and 20-minute milestones were never announced.
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)

    assertEquals("only the newest missed milestone is said", 1, said.size)
    assertEquals(Stopwatch.formatElapsed(context, 1_200_000L), said[0].toString())
    assertEquals(1_200_000L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))
    // The chain is armed again for the next milestone.
    assertEquals(startedAt + 1_800_000L, alarmManager.nextAlarmClock!!.triggerTime)
  }

  @Test
  fun startingAgain_doesNotSayTheMilestoneAgain() {
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)
    assertEquals(1, said.size)

    engine!!.stop()
    engine = null
    startEngine(startedAt)

    assertEquals(1, said.size)
  }

  @Test
  fun alarmBroadcast_doesNotSayAgain_andReschedules() {
    val startedAt = System.currentTimeMillis() - 605_000L
    startEngine(startedAt)
    assertEquals("the catch-up said the milestone it found", 1, said.size)

    val operation = shadowAlarms.scheduledAlarms.single().operation
    assertNotNull(operation)
    val savedIntent: Intent = Shadows.shadowOf(operation).savedIntent
    assertEquals(
      AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH),
      savedIntent.action,
    )

    context.sendBroadcast(
      Intent(AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH))
        .setPackage(context.packageName)
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("the same milestone is not said twice", 1, said.size)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertEquals(startedAt + 1_200_000L, shadowAlarms.scheduledAlarms.single().triggerAtMs)
    assertNotNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun stoppingTheStopwatch_forgetsTheAnnouncedMilestone() {
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)
    assertEquals(1_200_000L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))

    engine!!.toggle()

    assertEquals(0L, Stopwatch.startedAt(context))
    assertEquals(0L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))
    assertNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun vibrationMilestones_resetAfterEveryHour() {
    assertEquals(1, Stopwatch.pulsesForMilestone(10, 10))
    assertEquals(2, Stopwatch.pulsesForMilestone(20, 10))
    assertEquals(6, Stopwatch.pulsesForMilestone(60, 10))
    assertEquals(1, Stopwatch.pulsesForMilestone(70, 10))
    assertEquals(2, Stopwatch.pulsesForMilestone(80, 10))
    assertEquals(6, Stopwatch.pulsesForMilestone(120, 10))
  }

  @Test
  fun vibrationMilestones_stayOrderedForWideIntervals() {
    assertEquals(1, Stopwatch.pulsesForMilestone(25, 25))
    assertEquals(2, Stopwatch.pulsesForMilestone(50, 25))
    assertEquals(1, Stopwatch.pulsesForMilestone(75, 25))
    assertEquals(2, Stopwatch.pulsesForMilestone(100, 25))
    assertEquals(6, Stopwatch.pulsesForMilestone(180, 25))
    assertEquals(1, Stopwatch.pulsesForMilestone(45, 45))
    assertEquals(1, Stopwatch.pulsesForMilestone(90, 45))
    assertEquals(6, Stopwatch.pulsesForMilestone(180, 45))
  }

  @Test
  fun pause_freezesTheElapsedTimeAndDropsTheReminder() {
    startEngine(System.currentTimeMillis() - 60_000L)
    assertNotNull(alarmManager.nextAlarmClock)

    engine!!.pauseOrResume()

    assertTrue(Stopwatch.isPaused(context))
    assertNull(alarmManager.nextAlarmClock)
    assertEquals(context.getString(R.string.stopwatch_paused), said.last().toString())
    val frozen = Stopwatch.activeElapsedMillis(context)
    assertEquals(
      "time passing while paused does not count",
      frozen,
      Stopwatch.activeElapsedMillis(context, System.currentTimeMillis() + 600_000L),
    )
  }

  @Test
  fun resume_excludesThePausedSpanAndRearmsTheReminder() {
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)
    assertEquals(1, said.size)
    engine!!.pauseOrResume()
    // Ten minutes pass while the stopwatch is paused.
    prefs.edit()
      .putLong(Stopwatch.KEY_PAUSED_AT, System.currentTimeMillis() - 600_000L)
      .commit()

    engine!!.pauseOrResume()

    assertTrue(!Stopwatch.isPaused(context))
    val elapsed = Stopwatch.activeElapsedMillis(context)
    assertTrue("elapsed $elapsed excludes the paused span", elapsed in 890_000L..960_000L)
    assertEquals(
      context.getString(R.string.stopwatch_resumed),
      said.last().toString(),
    )
    assertEquals("the milestone already said is not said again", 3, said.size)
    val trigger = alarmManager.nextAlarmClock!!.triggerTime
    val now = System.currentTimeMillis()
    assertTrue("the next reminder is armed", trigger in (now + 240_000L)..(now + 360_000L))
  }

  @Test
  fun speechSwitchOff_milestonesStillCountButAreNotSaid() {
    prefs.edit().putBoolean(Stopwatch.KEY_SPEECH, false).commit()
    startEngine(System.currentTimeMillis() - 1_500_000L)

    assertEquals(0, said.size)
    assertEquals(1_200_000L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))
    assertNotNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun aRunOfSeconds_isNotKeptInTheHistory() {
    startEngine(System.currentTimeMillis() - 2_000L)

    engine!!.toggle()

    assertTrue(Stopwatch.history(context).isEmpty())
  }

  @Test
  fun aRealRun_isKeptInTheHistory() {
    startEngine(System.currentTimeMillis() - 65_000L)

    engine!!.toggle()

    val history = Stopwatch.history(context)
    assertEquals(1, history.size)
    assertTrue(history[0].durationMillis in 60_000L..70_000L)
  }

  @Test
  fun speakElapsed_saysHowLongItHasRun() {
    startEngine(System.currentTimeMillis() - 90_000L)
    assertEquals(0, said.size)

    engine!!.speakElapsed()

    assertEquals(1, said.size)
    assertTrue(
      said[0].toString().contains(
        context.resources.getQuantityString(
          R.plurals.notification_minutes, 1, 1)
      )
    )
  }

  @Test
  fun speakElapsed_whenStopped_saysItIsNotRunning() {
    engine = StopwatchEngine(context, { text, _ -> said += text }, {}).also { it.start() }

    engine!!.speakElapsed()

    assertEquals(
      listOf(context.getString(R.string.stopwatch_not_running)),
      said.map { it.toString() },
    )
  }

  @Test
  fun formatElapsed_keepsTheSecondsPastAnHour() {
    val text = Stopwatch.formatElapsed(context, 3_645_000L)

    assertTrue(
      text.contains(
        context.resources.getQuantityString(
          R.plurals.notification_seconds, 45, 45)
      )
    )
    assertTrue(
      text.contains(
        context.resources.getQuantityString(
          R.plurals.notification_hours, 1, 1)
      )
    )
  }

  private fun startEngine(startedAt: Long) {
    prefs
      .edit()
      .putLong(Stopwatch.KEY_STARTED_AT, startedAt)
      .putString(Stopwatch.KEY_INTERVAL_MINUTES, "10")
      .putBoolean(Stopwatch.KEY_SOUND, false)
      .putBoolean(Stopwatch.KEY_VIBRATION, false)
      .commit()
    engine = StopwatchEngine(context, { text, _ -> said += text }, {}).also { it.start() }
  }
}
