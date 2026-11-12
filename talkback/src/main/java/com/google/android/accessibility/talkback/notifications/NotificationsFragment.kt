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

package com.google.android.accessibility.talkback.notifications

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Settings > Notifications: how SandFly reads notifications, and SandFly's notification list. */
class NotificationsFragment : TalkbackBaseFragment(R.xml.notification_preferences) {
  override fun getTitle(): CharSequence = getString(R.string.notification_category_title)

  private lateinit var prefs: SharedPreferences

  private val sharedPreferenceChangeListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == getString(R.string.pref_speak_notifications_key)) {
        updateReadingSummary()
      }
    }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    updateReadingSummary()
  }

  override fun onResume() {
    super.onResume()
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
    updateReadingSummary()
  }

  override fun onPause() {
    super.onPause()
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
  }

  private fun updateReadingSummary() {
    findPreference<Preference>(READING_SCREEN_KEY)?.summary =
      getString(
        if (prefs.getBoolean(getString(R.string.pref_speak_notifications_key), true)) {
          R.string.notification_on
        } else {
          R.string.notification_off
        }
      )
  }

  private companion object {
    const val READING_SCREEN_KEY = "pref_notification_reading_screen"
  }
}
