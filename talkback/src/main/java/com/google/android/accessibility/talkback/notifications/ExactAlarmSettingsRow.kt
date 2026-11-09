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
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R

object ExactAlarmSettingsRow {
  const val KEY = "pref_timer_exact_alarm"

  fun bind(preference: Preference, context: Context) {
    preference.setOnPreferenceClickListener {
      TimerPermissionChecks.openExactAlarmSettings(context)
      true
    }
    refresh(preference, context)
  }

  fun refresh(preference: Preference, context: Context) {
    val status = TimerPermissionChecks.exactAlarmStatus(context)
    preference.isVisible = status != TimerPermissionChecks.Status.GRANTED
    preference.summary =
      context.getString(
        when (status) {
          TimerPermissionChecks.Status.GRANTED -> R.string.timer_setup_exact_alarm_granted
          TimerPermissionChecks.Status.MISSING -> R.string.timer_setup_exact_alarm_missing
          TimerPermissionChecks.Status.UNKNOWN -> R.string.timer_setup_status_unknown
        }
      )
  }
}
