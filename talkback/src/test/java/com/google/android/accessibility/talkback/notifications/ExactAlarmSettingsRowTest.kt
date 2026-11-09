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
import android.provider.Settings
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExactAlarmSettingsRowTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun granted_showsTheGrantedSummary() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val preference = Preference(context)

    ExactAlarmSettingsRow.bind(preference, context)

    assertEquals(
      context.getString(R.string.timer_setup_exact_alarm_granted),
      preference.summary?.toString(),
    )
  }

  @Test
  fun missing_showsTheWarningAndClickOpensTheSystemPage() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    val preference = Preference(context)

    ExactAlarmSettingsRow.bind(preference, context)

    assertEquals(
      context.getString(R.string.timer_setup_exact_alarm_missing),
      preference.summary?.toString(),
    )
    preference.performClick()
    val intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
  }

  @Test
  fun refresh_followsTheCurrentAccess() {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val preference = Preference(context)
    ExactAlarmSettingsRow.bind(preference, context)

    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    ExactAlarmSettingsRow.refresh(preference, context)

    assertEquals(
      context.getString(R.string.timer_setup_exact_alarm_missing),
      preference.summary?.toString(),
    )
  }
}
