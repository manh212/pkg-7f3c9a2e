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

package com.google.android.accessibility.talkback.timer

import android.content.Context
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
class TimerSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun timerScreen_isOnlyTheWarningAndTheWayIntoTheThreePages() {
    val rows = rowsOf(R.xml.timer_preferences)

    assertEquals(
      listOf(
        "pref_timer_exact_alarm",
        "pref_spoken_clock_screen",
        "pref_stopwatch_screen",
        "pref_countdown_alarm_screen",
      ),
      rows.map { it.first },
    )
    assertTrue(rows[1].second?.endsWith("SpokenClockSettingsFragment") == true)
    assertTrue(rows[2].second?.endsWith("StopwatchSettingsFragment") == true)
    assertTrue(rows[3].second?.endsWith("CountdownAlarmSettingsFragment") == true)
    assertFalse(
      "the initial setup moved off this screen",
      rows.any { it.first == SETUP_KEY || it.second?.contains("SetupFragment") == true },
    )
    assertFalse(
      "the exact-times switch is gone",
      rows.any { it.first == "pref_notification_clock_exact" },
    )
  }

  @Test
  fun spokenClockScreen_hasItsOwnSwitchIntervalSoundVibrationAndVoice() {
    val rows = rowsOf(R.xml.spoken_clock_preferences)

    assertEquals(
      listOf(
        "pref_notification_clock_enabled",
        "pref_notification_clock_hours_screen",
        "pref_notification_clock_interval",
        "pref_notification_clock_format",
        "pref_notification_clock_sound",
        "pref_timer_sound_choice",
        "pref_notification_clock_vibration",
        "pref_timer_voice_profile",
        "pref_timer_audio_stream",
      ),
      rows.map { it.first },
    )
  }

  @Test
  fun stopwatchScreen_hasSpeechSwitchListIntervalAndItsOwnVoice() {
    val rows = rowsOf(R.xml.stopwatch_preferences)

    assertEquals(
      listOf(
        "pref_stopwatch_sound",
        "pref_stopwatch_sound_choice",
        "pref_stopwatch_vibration",
        "pref_stopwatch_speech",
        "pref_stopwatch_interval_minutes",
        "pref_stopwatch_voice_profile",
        "pref_stopwatch_audio_stream",
        "pref_stopwatch_history_screen",
      ),
      rows.map { it.first },
    )
    assertFalse(
      "the interval slider is gone",
      rows.any { it.first == "pref_stopwatch_interval_slider" },
    )
    assertTrue(rows.last().second?.endsWith("StopwatchHistoryFragment") == true)
  }

  @Test
  fun countdownAlarmScreen_hasRepeatsSoundVibrationAndItsOwnVoice() {
    val rows = rowsOf(R.xml.countdown_alarm_preferences)

    assertEquals(
      listOf(
        "pref_countdown_alarm_repeats",
        "pref_countdown_alarm_sound",
        "pref_countdown_alarm_sound_choice",
        "pref_countdown_alarm_vibration",
        "pref_countdown_alarm_voice_profile",
        "pref_countdown_alarm_audio_stream",
      ),
      rows.map { it.first },
    )
  }

  @Test
  fun mainScreen_startsWithUpdatesThenSetup_andUsesTheNewGroups() {
    val topLevel = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(R.xml.preferences)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.depth == 2) {
        val currentKey = parser.getAttributeValue(ANDROID_NS, "key")
        topLevel += currentKey to parser.getAttributeValue(ANDROID_NS, "fragment")
      }
      event = parser.next()
    }

    assertEquals("pref_updates_screen", topLevel[0].first)
    assertTrue(topLevel[0].second?.contains("UpdatesFragment") == true)
    assertEquals(SETUP_KEY, topLevel[1].first)
    assertTrue(topLevel[1].second?.contains("InitialSetupFragment") == true)
    assertEquals(
      listOf(
        "UpdatesFragment",
        "InitialSetupFragment",
        "TextToSpeechSettingsFragment",
        "SpeechGroupFragment",
        "NavigationGroupFragment",
        "MenuControlsGroupFragment",
        "SoundAndVibrationFragment",
        "NotificationsFragment",
        "TypingGroupFragment",
        "ScreenInfoGroupFragment",
        "ToolsGroupFragment",
        "AdvancedGroupFragment",
        "HelpCenterGroupFragment",
      ),
      topLevel.map { it.second?.substringAfterLast('.') },
    )
    assertTrue(topLevel.all { !it.second.isNullOrBlank() })
    assertEquals(13, topLevel.size)
    assertEquals(
      "one initial setup entry point on the main screen",
      1,
      topLevel.count { it.second?.contains("SetupFragment") == true },
    )
    assertTrue(
      rowsOf(R.xml.settings_group_tools).any {
        it.first == "pref_timer_settings_screen" && it.second?.contains("TimerSettingsFragment") == true
      }
    )
    assertTrue(
      rowsOf(R.xml.settings_group_help_center).any {
        it.first == ACCESSIBILITY_SETTINGS_KEY && it.second == null
      }
    )
    assertTrue(
      rowsOf(R.xml.settings_group_help_center).any {
        it.first == "forum_open" && it.second == null
      }
    )
    assertTrue(
      rowsOf(R.xml.settings_group_help_center).any {
        it.first == "meridian_owner_private_chat" && it.second == null
      }
    )
    assertTrue(
      "the tutorial row opens the tutorial itself, so it has no fragment",
      rowsOf(R.xml.settings_group_help_center).any {
        it.first == TUTORIAL_KEY && it.second == null
      },
    )
    assertFalse(
      "the resume row moved to the advanced screen",
      rowsOf(R.xml.settings_group_help_center).any { it.first == RESUME_KEY },
    )
    assertFalse(
      "the Play installer row moved to the advanced screen",
      rowsOf(R.xml.settings_group_help_center).any { it.first == PLAY_INSTALLER_KEY },
    )
  }

  @Test
  fun advancedScreen_holdsDeveloperWallpaperAndTheMovedRowsInOrder() {
    val rows = rowsOf(R.xml.settings_group_advanced)

    assertEquals(
      listOf(
        "pref_category_group_advanced_key",
        "pref_category_developer_key",
        "pref_meridian_wallpaper",
        RESUME_KEY,
        PLAY_INSTALLER_KEY,
        BACKUP_RESET_KEY,
        APPS_KEY,
      ),
      rows.map { it.first },
    )
    assertTrue(rows[5].second?.contains("BackupResetFragment") == true)
    assertTrue(rows[6].second?.contains("AppsGroupFragment") == true)
  }

  /** Every row of an XML preference screen, in document order, as key to fragment. */
  private fun rowsOf(xml: Int): List<Pair<String?, String?>> {
    val rows = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(xml)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = parser.getAttributeValue(ANDROID_NS, "key")
        val fragment = parser.getAttributeValue(ANDROID_NS, "fragment")
        if (key != null || fragment != null) rows += key to fragment
      }
      event = parser.next()
    }
    return rows
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val SETUP_KEY = "pref_initial_setup_screen"
    const val ACCESSIBILITY_SETTINGS_KEY = "pref_open_accessibility_settings"
    const val TUTORIAL_KEY = "pref_tutorial_and_help_key"
    const val RESUME_KEY = "pref_resume_backtalk_key"
    const val PLAY_INSTALLER_KEY = "pref_play_installer_key"
    const val BACKUP_RESET_KEY = "pref_backup_reset_screen"
    const val APPS_KEY = "pref_settings_group_apps_key"
  }
}

