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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Keeps Meridian in the foreground while the speaking clock or a timer is armed, so the phone
 * maker cannot delay its alarms while the phone rests. It runs only with the user's leave,
 * asked for in the initial setup, and only while something is still to fire.
 */
class TimerKeepAliveService : Service() {
  override fun onCreate() {
    super.onCreate()
    createChannel()
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!allowed(this) || !anyActiveNow(this)) stopSelf()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun notification(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.icon)
      .setContentTitle(getString(R.string.timer_keep_alive_title))
      .setContentText(getString(R.string.timer_keep_alive_text))
      .setContentIntent(AlarmClockIntents.showSettings(this))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()

  private fun createChannel() {
    getSystemService(NotificationManager::class.java)
      ?.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.timer_keep_alive_channel),
          NotificationManager.IMPORTANCE_LOW,
        )
      )
  }

  companion object {
    const val PREF_ALLOWED = "pref_timer_keep_alive_allowed"

    private const val CHANNEL_ID = "timer_keep_alive"
    private const val NOTIFICATION_ID = 0x0DA3

    /** Whether the user let Meridian keep timers on time, in the initial setup. */
    fun allowed(context: Context): Boolean =
      SharedPreferencesUtils.getSharedPreferences(context).getBoolean(PREF_ALLOWED, false)

    /** Records the user's leave and applies it at once. */
    fun setAllowed(context: Context, allowed: Boolean) {
      SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putBoolean(PREF_ALLOWED, allowed)
        .apply()
      refresh(context)
    }

    /** Starts the service when it is allowed and something is armed; stops it otherwise. */
    fun refresh(context: Context) {
      if (allowed(context) && anyActiveNow(context)) {
        runCatching {
          ContextCompat.startForegroundService(
            context,
            Intent(context, TimerKeepAliveService::class.java),
          )
        }
      } else {
        stop(context)
      }
    }

    /** Stops the service whatever is armed. */
    fun stop(context: Context) {
      context.stopService(Intent(context, TimerKeepAliveService::class.java))
    }

    private fun anyActiveNow(context: Context): Boolean {
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      return TimerKeepAlivePolicy.anyActive(
        clockOn =
          SpokenSchedules.clockEnabled(prefs) && SpokenSchedules.selectedHours(prefs).isNotEmpty(),
        summaryOn = prefs.getBoolean(SpokenSchedules.KEY_SUMMARY_DAILY, false),
        stopwatchRunning = Stopwatch.isRunning(context) && !Stopwatch.isPaused(context),
        countdownPending =
          CountdownAlarm.alarms(context).any {
            it.deadlineMillis > System.currentTimeMillis()
          },
      )
    }
  }
}

/** Which armed clocks and timers keep the keep-alive service running. */
object TimerKeepAlivePolicy {
  fun anyActive(
    clockOn: Boolean,
    summaryOn: Boolean,
    stopwatchRunning: Boolean,
    countdownPending: Boolean,
  ): Boolean = clockOn || summaryOn || stopwatchRunning || countdownPending
}
