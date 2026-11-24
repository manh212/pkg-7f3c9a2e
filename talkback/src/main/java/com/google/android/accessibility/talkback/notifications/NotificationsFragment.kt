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
import com.google.android.accessibility.talkback.notificationlist.NotificationList
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Settings > Notifications: master toggle, quick actions, and beginner-friendly groups. */
class NotificationsFragment : TalkbackBaseFragment(R.xml.notification_preferences) {
  override fun getTitle(): CharSequence = getString(R.string.notification_category_title)

  private lateinit var prefs: SharedPreferences

  private val sharedPreferenceChangeListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == NotificationSettings.Companion.Keys.APPS) {
        updateAppsSummary()
      }
    }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    NotificationSettingsEntries.setUpPreferences(this)
    findPreference<Preference>(NotificationXmlFragment.KEY_ACCESS)?.fragment =
      InitialSetupFragment::class.java.name
    updateAccess()
    updateAppsSummary()
  }

  override fun onResume() {
    super.onResume()
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
    updateAccess()
    updateAppsSummary()
  }

  override fun onPause() {
    super.onPause()
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
  }

  private fun updateAccess() {
    val access = findPreference<Preference>(NotificationXmlFragment.KEY_ACCESS) ?: return
    access.isVisible = !NotificationList.isAccessGranted(requireContext())
    access.summary = getString(R.string.notification_access_summary)
  }

  private fun updateAppsSummary() {
    val appsPref = findPreference<Preference>(APPS_SCREEN_KEY) ?: return
    val specialCount = NotificationSettings.read(prefs).apps.values.count { it.readWhenScreenOff }
    appsPref.summary =
      if (specialCount > 0) {
        getString(R.string.notification_apps_special_count_summary, specialCount)
      } else {
        null
      }
  }

  private companion object {
    const val APPS_SCREEN_KEY = "pref_notification_apps_screen"
  }
}
