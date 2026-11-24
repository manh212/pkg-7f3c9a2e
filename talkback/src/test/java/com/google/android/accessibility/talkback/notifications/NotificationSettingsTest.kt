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

import android.content.Context
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun reset() {
    prefs.edit().clear().commit()
  }

  @Test
  fun busyBehaviorOfMapsNamesAndDefaultsToAfter() {
    assertEquals(BusyBehavior.NOW, BusyBehavior.of("NOW"))
    assertEquals(BusyBehavior.AFTER, BusyBehavior.of("AFTER"))
    assertEquals(BusyBehavior.SKIP, BusyBehavior.of("SKIP"))
    assertEquals(BusyBehavior.AFTER, BusyBehavior.of("LATER"))
    assertEquals(BusyBehavior.AFTER, BusyBehavior.of(null))
  }

  @Test
  fun busyBehaviorDefaultsToAfterWhenThePreferenceIsAbsent() {
    assertEquals(BusyBehavior.AFTER, NotificationSettings.read(prefs).busyBehavior)
  }

  @Test
  fun busyBehaviorReadsAfterFromThePreference() {
    prefs.edit().putString("pref_notification_while_reading", "AFTER").commit()
    assertEquals(BusyBehavior.AFTER, NotificationSettings.read(prefs).busyBehavior)
  }

  @Test
  fun appSettingsReadWhenScreenOff_roundTripsAndDefaultsFalse() {
    val app = AppSettings(mode = AppMode.READ, voiceProfile = "v1", readWhenScreenOff = true)
    val restored = AppSettings.fromJson(app.toJson())
    assertEquals(app, restored)
    assertEquals(false, AppSettings.fromJson(org.json.JSONObject()).readWhenScreenOff)
  }
}
