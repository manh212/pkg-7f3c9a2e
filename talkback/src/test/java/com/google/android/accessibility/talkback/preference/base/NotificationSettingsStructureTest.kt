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

package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.content.res.XmlResourceParser
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun notificationLandingPage_listsSevenGroupsInOrder() {
    val rows = rowsOf(R.xml.notification_preferences)

    assertEquals(
      listOf(
        "pref_notification_reading_screen" to "NotificationReadingFragment",
        "pref_notification_apps_words_screen" to "NotificationAppsWordsFragment",
        "pref_notification_skip_filter_screen" to "NotificationSkipFilterFragment",
        "pref_notification_rules_screen" to "NotificationRulesFragment",
        "pref_notification_when_stop_screen" to "NotificationWhenStopFragment",
        "pref_notification_summary_history_screen" to "NotificationSummaryHistoryFragment",
        "pref_notification_automation_monitor_screen" to "NotificationAutomationMonitorFragment",
      ),
      rows.map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun appsAndWordsGroup_holdsTheAppsAndWordsScreens() {
    assertEquals(
      listOf(
        "pref_notification_apps_screen" to "NotificationAppsFragment",
        "pref_notification_words_screen" to "NotificationWordsFragment",
      ),
      rowsOf(R.xml.notification_group_apps_words_preferences)
        .map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun skipAndFilterGroup_holdsThirteenRowsInThreeCategories() {
    val rows = rowsOf(R.xml.notification_group_skip_filter_preferences).map { it.first }

    assertEquals(
      listOf(
        "pref_notification_numbers_alike",
        "pref_notification_cooldown",
        "pref_notification_skip_prefix",
        "pref_notification_prefix_memory",
        "pref_notification_repeat_seconds",
        "pref_notification_skip_media",
        "pref_notification_skip_ongoing",
        "pref_notification_skip_foreground",
        "pref_notification_skip_progress",
        "pref_notification_skip_group_summaries",
        "pref_notification_skip_low_priority",
        "pref_notification_skip_system",
        "pref_notification_repeat_minutes",
      ),
      rows,
    )
    val skipSeconds = rows.indexOf("pref_notification_repeat_seconds")
    val rereadMinutes = rows.indexOf("pref_notification_repeat_minutes")
    assertTrue(
      "the two repeats rows do opposite things, so they sit in different categories",
      rereadMinutes - skipSeconds > 1,
    )
  }

  @Test
  fun whenAndStopGroup_holdsTheWhenAndStopScreens() {
    assertEquals(
      listOf(
        "pref_notification_when_screen" to "NotificationWhenFragment",
        "pref_notification_stop_screen" to "NotificationStopFragment",
      ),
      rowsOf(R.xml.notification_group_when_stop_preferences)
        .map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun summaryHistoryAndListGroup_holdsThreeScreens() {
    assertEquals(
      listOf(
        "pref_notification_schedules_screen" to "NotificationSchedulesFragment",
        "pref_notification_history_screen" to "NotificationHistoryFragment",
        "pref_notification_list_settings" to "NotificationListSettingsFragment",
      ),
      rowsOf(R.xml.notification_group_summary_history_preferences)
        .map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun automationAndMonitorGroup_holdsAutomationAndFullScreenMonitor() {
    assertEquals(
      listOf(
        "pref_notification_automation_settings" to "NotificationAutomationFragment",
        "pref_full_screen_monitor" to "FullScreenMonitorSettingsFragment",
      ),
      rowsOf(R.xml.notification_group_automation_monitor_preferences)
        .map { it.first to it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun stopScreen_linksToTheVolumeKeySettings() {
    val rows = rowsOf(R.xml.notification_stop_preferences)

    assertEquals(
      listOf(
        "pref_notification_access",
        "pref_notification_shake_to_stop",
        "pref_notification_shake_strength",
        "pref_notification_wave_to_stop",
        "pref_notification_pocket_mode",
        "pref_notification_stop_volume_keys_screen",
        "pref_notification_dismiss_stops",
        "pref_notification_watch_dismiss_stops",
        "pref_notification_app_cancel_stops",
        "pref_notification_max_seconds",
      ),
      rows.map { it.first },
    )
    val link = rows.first { it.first == "pref_notification_stop_volume_keys_screen" }
    assertEquals(
      "VolumeKeySettingsFragment",
      link.second?.substringAfterLast('.'),
    )
    assertEquals(
      "the link sits with the physical stop methods, right after the pocket row",
      rows.indexOfFirst { it.first == "pref_notification_pocket_mode" } + 1,
      rows.indexOf(link),
    )
  }

  @Test
  fun deletedScreensAreGoneFromTheLandingPage() {
    val landing = rowsOf(R.xml.notification_preferences).map { it.first }

    assertFalse(landing.contains("pref_notification_filter_screen"))
    assertFalse(landing.contains("pref_notification_kinds_screen"))
    assertFalse(landing.contains("pref_notification_repeats_screen"))
  }

  /** The android:key of the parser's current tag, resolving @string references to their value. */
  private fun keyOf(parser: XmlResourceParser): String? {
    val resId = parser.getAttributeResourceValue(ANDROID_NS, "key", 0)
    return if (resId != 0) context.getString(resId) else parser.getAttributeValue(ANDROID_NS, "key")
  }

  /** Every row of an XML preference screen, in document order, as key to fragment. */
  private fun rowsOf(xml: Int): List<Pair<String?, String?>> {
    val rows = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(xml)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser)
        val fragment = parser.getAttributeValue(ANDROID_NS, "fragment")
        if (key != null || fragment != null) rows += key to fragment
      }
      event = parser.next()
    }
    return rows
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}
