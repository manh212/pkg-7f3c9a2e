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

package com.google.android.accessibility.talkback.recognition

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors

class LotteryCheckReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    val appContext = context.applicationContext
    if (!TalkBackService.isServiceActive()) {
      cancelAlarm(appContext)
      return
    }
    if (action == Intent.ACTION_BOOT_COMPLETED) {
      rescheduleAllPending(appContext)
      return
    }
    if (action != ACTION_CHECK_LOTTERY) return

    val pendingResult = goAsync()
    executor.execute {
      try {
        checkDueTickets(appContext)
      } catch (e: Throwable) {
        LogUtils.e(TAG, "Lottery background check failed: %s", e.javaClass.simpleName)
      } finally {
        pendingResult.finish()
      }
    }
  }

  private fun checkDueTickets(context: Context) {
    val now = System.currentTimeMillis()
    val tickets = LotteryTicketStore.list(context)
    var earliestPending = Long.MAX_VALUE

    for (saved in tickets) {
      if (saved.status != SavedTicketStatus.PENDING) continue
      if (saved.nextCheckAtMs > now + DUE_SLACK_MS) {
        earliestPending = minOf(earliestPending, saved.nextCheckAtMs)
        continue
      }
      when (val result = LotteryResultChecker.checkOnline(saved.ticket)) {
        is LotteryCheckResult.Won -> {
          val updated = saved.copy(
            status = SavedTicketStatus.WON,
            lastResultMessage = result.message,
          )
          LotteryTicketStore.update(context, updated)
          postResultNotification(
            context = context,
            notificationId = notificationIdFor(saved.id),
            title = context.getString(R.string.live_recognition_lottery_notify_won_title),
            body = result.message,
            highPriority = true,
          )
        }
        is LotteryCheckResult.NotWon -> {
          val updated = saved.copy(
            status = SavedTicketStatus.NOT_WON,
            lastResultMessage = result.message,
          )
          LotteryTicketStore.update(context, updated)
          postResultNotification(
            context = context,
            notificationId = notificationIdFor(saved.id),
            title = context.getString(R.string.live_recognition_lottery_notify_not_won_title),
            body = result.message,
            highPriority = false,
          )
        }
        is LotteryCheckResult.NotDrawnYet,
        is LotteryCheckResult.Unavailable -> {
          val nextRetries = saved.retryCount + 1
          if (nextRetries <= MAX_RETRIES) {
            val nextAt = now + RETRY_DELAY_MS
            LotteryTicketStore.update(
              context,
              saved.copy(retryCount = nextRetries, nextCheckAtMs = nextAt),
            )
            earliestPending = minOf(earliestPending, nextAt)
          }
        }
      }
    }

    if (earliestPending != Long.MAX_VALUE) {
      scheduleAlarmAt(context, earliestPending)
    }
  }

  private fun postResultNotification(
    context: Context,
    notificationId: Int,
    title: String,
    body: String,
    highPriority: Boolean,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.live_recognition_lottery_channel_name),
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = context.getString(R.string.live_recognition_lottery_channel_desc)
      }
      manager.createNotificationChannel(channel)
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.icon)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .build()
    manager.notify(notificationId, notification)
  }

  private fun notificationIdFor(ticketId: String): Int =
    BASE_NOTIFICATION_ID + (ticketId.hashCode() and 0x0FFF)

  companion object {
    private const val TAG = "LotteryCheckReceiver"
    internal const val ACTION_CHECK_LOTTERY =
      "com.google.android.accessibility.talkback.recognition.ACTION_CHECK_LOTTERY"
    private const val CHANNEL_ID = "meridian_lottery_results"
    private const val BASE_NOTIFICATION_ID = 49_000
    private const val ALARM_REQUEST_CODE = 49_101
    private const val DUE_SLACK_MS = 60_000L
    private const val RETRY_DELAY_MS = 20L * 60L * 1000L
    private const val MAX_RETRIES = 8
    private val executor = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun rescheduleAllPending(context: Context) {
      val appContext = context.applicationContext
      if (!TalkBackService.isServiceActive()) {
        cancelAlarm(appContext)
        return
      }
      val pending = LotteryTicketStore.list(appContext)
        .filter { it.status == SavedTicketStatus.PENDING && it.retryCount <= MAX_RETRIES }
      if (pending.isEmpty()) {
        cancelAlarm(appContext)
        return
      }
      val earliest = pending.minOf { it.nextCheckAtMs }
      scheduleAlarmAt(appContext, maxOf(earliest, System.currentTimeMillis() + 5_000L))
    }

    private fun scheduleAlarmAt(context: Context, triggerAtMillis: Long) {
      val alarms = context.getSystemService(AlarmManager::class.java) ?: return
      val pendingIntent = alarmPendingIntent(context)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
          alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
          alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
      } catch (_: SecurityException) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      }
    }

    @JvmStatic
    fun cancelAlarm(context: Context) {
      val alarms = context.getSystemService(AlarmManager::class.java) ?: return
      alarms.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
      val intent = Intent(context, LotteryCheckReceiver::class.java).setAction(ACTION_CHECK_LOTTERY)
      return PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}
