/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.notifications

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationInfoTest {
  private fun notification(channel: String, ongoingActivity: Boolean): Notification {
    val notification =
      Notification.Builder(RuntimeEnvironment.getApplication(), channel)
        .setContentTitle("MediaOngoingActivity")
        .build()
    if (ongoingActivity) {
      notification.extras.putInt("android.ongoingActivityNoti.style", 1)
    }
    return notification
  }

  @Test
  fun oneUiMediaActivityIsMedia() {
    val info =
      NotificationInfo.from(
        "com.android.systemui",
        "System UI",
        notification("MediaOngoingActivity", ongoingActivity = true),
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertTrue(info.media)
  }

  @Test
  fun otherOngoingActivitiesAreNotMedia() {
    val info =
      NotificationInfo.from(
        "com.example.timer",
        "Timer",
        notification("Timer", ongoingActivity = true),
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertFalse(info.media)
  }

  @Test
  fun aMediaChannelAloneIsNotMedia() {
    val info =
      NotificationInfo.from(
        "com.example.app",
        "App",
        notification("MediaOngoingActivity", ongoingActivity = false),
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertFalse(info.media)
  }

  @Test
  fun lowPriority_comesFromTheChannelImportance() {
    val low =
      NotificationInfo.from(
        "com.example.app",
        "App",
        notification("Channel", ongoingActivity = false),
        NotificationManager.IMPORTANCE_LOW,
      )
    assertTrue(low.lowPriority)
    val min =
      NotificationInfo.from(
        "com.example.app",
        "App",
        notification("Channel", ongoingActivity = false),
        NotificationManager.IMPORTANCE_MIN,
      )
    assertTrue(min.lowPriority)
    val default =
      NotificationInfo.from(
        "com.example.app",
        "App",
        notification("Channel", ongoingActivity = false),
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertFalse(default.lowPriority)
    val high =
      NotificationInfo.from(
        "com.example.app",
        "App",
        notification("Channel", ongoingActivity = false),
        NotificationManager.IMPORTANCE_HIGH,
      )
    assertFalse(high.lowPriority)
  }

  @Test
  fun noClearFlagIsNotOngoing() {
    val built = notification("Channel", ongoingActivity = false)
    built.flags = built.flags or Notification.FLAG_NO_CLEAR
    val info =
      NotificationInfo.from(
        "com.example.app",
        "App",
        built,
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertFalse(info.ongoing)
  }

  @Test
  fun ongoingFlagIsOngoing() {
    val built = notification("Channel", ongoingActivity = false)
    built.flags = built.flags or Notification.FLAG_ONGOING_EVENT
    val info =
      NotificationInfo.from(
        "com.example.app",
        "App",
        built,
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    assertTrue(info.ongoing)
  }
}
