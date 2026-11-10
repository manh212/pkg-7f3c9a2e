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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.VibrationIntensity

/**
 * Runs the countdown alarms. The pending alarms are persisted (see [CountdownAlarm]) and only
 * the soonest is armed, as one system alarm clock; when it fires, every alarm that has fallen
 * due rings: its sound, vibration and name, repeated the number of times the settings choose,
 * then it stops on its own. An alarm whose moment passed while the phone was off or SandFly
 * was not running is not dropped either: it rings as soon as the engine next starts. Because
 * the state is in the device-protected preferences and the engine starts before the first
 * unlock, an alarm can ring at the lock screen too.
 */
class CountdownAlarmEngine(
  private val context: Context,
  /** Says a text, interrupting what notifications are saying when the second value is true. */
  private val say: (CharSequence, Boolean) -> Unit,
  private val playSound: (Int) -> Unit,
) {
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val handler = Handler(Looper.getMainLooper())
  private val wakeLock =
    context
      .getSystemService(PowerManager::class.java)
      ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Meridian:CountdownAlarm")

  private val alarmReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(receiverContext: Context, intent: Intent) {
        when (intent.action) {
          alarmAction() -> {
            fireDue()
            schedule()
          }
          Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> schedule()
        }
      }
    }
  private var started = false

  private val settingsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == CountdownAlarm.KEY_ALARMS) schedule()
    }

  /** The alarms waiting their turn to ring, soonest first. */
  private val ringQueue = ArrayDeque<CountdownAlarm.Entry>()
  private var repeatsLeft = 0

  private val repeatStep = Runnable { ringStep() }

  /** Rings the alarm at the head of the queue once, then queues the next ringing or alarm. */
  private fun ringStep() {
    val entry = ringQueue.firstOrNull() ?: return
    ringOnce(entry)
    repeatsLeft--
    if (repeatsLeft > 0) {
      handler.postDelayed(repeatStep, REPEAT_GAP_MS)
    } else {
      ringQueue.removeFirst()
      startNextRing()
    }
  }

  @MainThread
  fun start() {
    if (started) return
    started = true
    ContextCompat.registerReceiver(
      context,
      alarmReceiver,
      IntentFilter().apply {
        addAction(alarmAction())
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    prefs.registerOnSharedPreferenceChangeListener(settingsListener)
    fireDue()
    schedule()
  }

  @MainThread
  fun stop() {
    if (!started) return
    started = false
    prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
    runCatching { context.unregisterReceiver(alarmReceiver) }
    alarms?.let { cancelAlarm(it) }
    handler.removeCallbacks(repeatStep)
    ringQueue.clear()
    repeatsLeft = 0
    wakeLock?.takeIf { it.isHeld }?.release()
  }

  /** Adds an alarm and confirms it in the alarm's voice. */
  @MainThread
  fun add(name: String, durationMillis: Long) {
    val entry = CountdownAlarm.add(context, name, durationMillis)
    schedule()
    val duration = Stopwatch.formatElapsed(context, entry.durationMillis)
    say(
      if (entry.name.isNotEmpty())
        context.getString(R.string.countdown_alarm_added_named, entry.name, duration)
      else context.getString(R.string.countdown_alarm_added, duration),
      false,
    )
  }

  /** Cancels the alarm with [id] and confirms it in the alarm's voice. */
  @MainThread
  fun cancel(id: Long) {
    val entry = CountdownAlarm.alarms(context).firstOrNull { it.id == id } ?: return
    CountdownAlarm.remove(context, id)
    schedule()
    say(
      if (entry.name.isNotEmpty())
        context.getString(R.string.countdown_alarm_cancelled_named, entry.name)
      else
        context.getString(
          R.string.countdown_alarm_cancelled,
          Stopwatch.formatElapsed(context, entry.durationMillis),
        ),
      false,
    )
  }

  /** Arms the soonest alarm still to come; one that is already due is left to [fireDue]. */
  private fun schedule() {
    val alarms = alarms ?: return
    cancelAlarm(alarms)
    val now = System.currentTimeMillis()
    val next = CountdownAlarm.alarms(context).firstOrNull { it.deadlineMillis > now } ?: return
    set(alarms, next.deadlineMillis)
  }

  private fun set(alarms: AlarmManager, at: Long) {
    val pending = AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_COUNTDOWN_ALARM)
    try {
      alarms.setAlarmClock(
        AlarmManager.AlarmClockInfo(at, AlarmClockIntents.showSettings(context)),
        pending,
      )
    } catch (e: SecurityException) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
  }

  private fun cancelAlarm(alarms: AlarmManager) {
    alarms.cancel(AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_COUNTDOWN_ALARM))
  }

  private fun alarmAction(): String =
    AlarmClockIntents.action(context, AlarmClockIntents.ACTION_COUNTDOWN_ALARM)

  /** Rings every alarm whose moment has come, and takes it off the list. */
  private fun fireDue() {
    val now = System.currentTimeMillis()
    val due = CountdownAlarm.alarms(context).filter { it.deadlineMillis <= now }
    if (due.isEmpty()) return
    for (entry in due) CountdownAlarm.remove(context, entry.id)
    ringQueue.addAll(due)
    if (repeatsLeft <= 0) startNextRing()
  }

  private fun startNextRing() {
    val entry = ringQueue.firstOrNull()
    if (entry == null) {
      wakeLock?.takeIf { it.isHeld }?.release()
      return
    }
    repeatsLeft = CountdownAlarm.repeats(context)
    if (wakeLock?.isHeld != true) wakeLock?.acquire(RING_WAKE_MS)
    handler.post(repeatStep)
  }

  private fun ringOnce(entry: CountdownAlarm.Entry) {
    if (prefs.getBoolean(CountdownAlarm.KEY_SOUND, true)) playSound(R.raw.lib_new_clock)
    if (prefs.getBoolean(CountdownAlarm.KEY_VIBRATION, false)) vibrate()
    say(
      if (entry.name.isNotEmpty()) entry.name
      else
        context.getString(
          R.string.countdown_alarm_fired,
          Stopwatch.formatElapsed(context, entry.durationMillis),
        ),
      false,
    )
  }

  private fun vibrate() {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    val strength =
      prefs
        .getInt(
          context.getString(R.string.pref_vibration_strength_key),
          context.resources.getInteger(R.integer.pref_vibration_strength_default),
        )
        .coerceIn(0, 100)
    if (strength == 0) return
    val timings = LongArray(RING_PULSES * 2) { index ->
      if (index == 0) 0L else if (index % 2 == 0) 140L else 60L
    }
    val effect = VibrationIntensity.createWaveform(vibrator, timings, strength / 100f)
    runCatching { vibrator.vibrate(effect) }
  }

  private companion object {
    /** The pause between one ringing of an alarm and the next. */
    const val REPEAT_GAP_MS = 6_000L
    /** Vibration pulses in one ringing. */
    const val RING_PULSES = 3
    /** How long the phone is held awake for one alarm's ringings. */
    const val RING_WAKE_MS = 120_000L
  }
}
