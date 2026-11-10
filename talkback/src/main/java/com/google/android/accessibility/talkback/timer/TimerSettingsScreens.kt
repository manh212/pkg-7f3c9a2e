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

import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.talkback.notifications.ExactAlarmSettingsRow
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.talkback.speech.VoiceProfileNames
import com.google.android.accessibility.utils.output.VoiceProfiles
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.notifications.NotificationXmlFragment
import java.util.Date

/**
 * The timers' door: the exact-alarm warning while that access is missing, and the way into
 * the speaking clock's settings and the stopwatch's, which are separate from each other.
 */
class TimerSettingsFragment :
  NotificationXmlFragment(R.xml.timer_preferences, R.string.title_pref_clock_stopwatch) {

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<Preference>(ExactAlarmSettingsRow.KEY)?.let {
      ExactAlarmSettingsRow.bind(it, requireContext())
    }
  }

  override fun onResume() {
    super.onResume()
    findPreference<Preference>(ExactAlarmSettingsRow.KEY)?.let {
      ExactAlarmSettingsRow.refresh(it, requireContext())
    }
  }
}

/** The speaking clock: when it speaks, what it says, and the voice it says it with. */
class SpokenClockSettingsFragment :
  NotificationXmlFragment(R.xml.spoken_clock_preferences, R.string.spoken_clock_title) {

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    findPreference<SwitchPreferenceCompat>(SpokenSchedules.KEY_CLOCK_ENABLED)?.let {
      it.isChecked = SpokenSchedules.clockEnabled(prefs)
    }
    findPreference<ListPreference>(SpokenSchedules.KEY_CLOCK_INTERVAL)?.let {
      it.value = SpokenSchedules.clockIntervalMinutes(prefs).toString()
    }
    bindVoiceChoice(TimerSpeechSettings.KEY_PROFILE) { TimerSpeechSettings.profileId(it) }
    findPreference<Preference>("pref_notification_clock_hours_screen")?.let { hours ->
      fun updateSummary() {
        val selected = SpokenSchedules.selectedHours(prefs)
        hours.summary =
          when (selected.size) {
            0 -> getString(R.string.timer_clock_hours_off)
            24 -> getString(R.string.timer_clock_hours_all)
            else -> getString(R.string.timer_clock_hours_count, selected.size)
          }
      }
      updateSummary()
      hours.setOnPreferenceClickListener {
        val labels = (0..23).map { getString(R.string.timer_clock_hour_label, it) }.toTypedArray()
        val selected =
          if (prefs.contains(SpokenSchedules.KEY_CLOCK_HOURS))
            SpokenSchedules.selectedHours(prefs)
          else SpokenSchedules.ALL_CLOCK_HOURS
        val checked = BooleanArray(24) { it in selected }
        AlertDialog.Builder(requireContext())
          .setTitle(R.string.timer_clock_hours_title)
          .setMultiChoiceItems(labels, checked) { _, position, isChecked ->
            checked[position] = isChecked
          }
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok) { _, _ ->
            prefs.edit().putStringSet(
              SpokenSchedules.KEY_CLOCK_HOURS,
              (0..23).filter { checked[it] }.map { it.toString() }.toSet()
            ).apply()
            updateSummary()
          }
          .show()
        true
      }
    }
  }
}

/** The stopwatch: its reminders, its signals, and the voice it speaks with. */
class StopwatchSettingsFragment :
  NotificationXmlFragment(R.xml.stopwatch_preferences, R.string.stopwatch_title) {

  override fun onCreate(savedInstanceState: Bundle?) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    if (prefs.contains(Stopwatch.KEY_INTERVAL_SLIDER)) {
      // The reminder interval is a list now: the slider's last value becomes the list's,
      // and the slider's key goes away so the list's key is the only one.
      prefs.edit()
        .putString(
          Stopwatch.KEY_INTERVAL_MINUTES,
          Stopwatch.intervalMinutes(requireContext()).toString(),
        )
        .remove(Stopwatch.KEY_INTERVAL_SLIDER)
        .apply()
    }
    super.onCreate(savedInstanceState)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    findPreference<ListPreference>(Stopwatch.KEY_INTERVAL_MINUTES)?.let {
      it.value = Stopwatch.intervalMinutes(requireContext()).toString()
    }
    bindVoiceChoice(TimerSpeechSettings.KEY_STOPWATCH_PROFILE) {
      TimerSpeechSettings.stopwatchProfileId(it)
    }
    findPreference<ListPreference>(TimerSpeechSettings.KEY_STOPWATCH_STREAM)?.let {
      it.value = TimerSpeechSettings.stopwatchStreamValue(prefs)
    }
  }
}

/** Fills a voice-profile list with the profiles on the phone and shows the one in effect. */
private fun NotificationXmlFragment.bindVoiceChoice(
  key: String,
  currentId: (SharedPreferences) -> String,
) {
  val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
  findPreference<ListPreference>(key)?.let { voiceChoice ->
    val ids = VoiceProfiles.ids(prefs)
    voiceChoice.entries =
      (listOf(getString(R.string.timer_voice_default)) +
        ids.map { VoiceProfileNames.nameOf(requireContext(), prefs, it) }).toTypedArray()
    voiceChoice.entryValues = (listOf("") + ids).toTypedArray()
    voiceChoice.value = currentId(prefs)
    voiceChoice.summary = voiceChoice.entry ?: getString(R.string.timer_voice_default)
    voiceChoice.setOnPreferenceChangeListener { preference, value ->
      val id = value as String
      preference.summary =
        if (id.isEmpty()) getString(R.string.timer_voice_default)
        else VoiceProfileNames.nameOf(requireContext(), prefs, id)
      true
    }
  }
}

/** Timer history: the stopwatch runs that have finished, with when each started and ran for. */
class StopwatchHistoryFragment : NotificationCodeFragment(R.string.stopwatch_history_title) {

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val entries = Stopwatch.history(context)
    if (entries.isNotEmpty()) {
      screen.action(getString(R.string.stopwatch_history_clear)) {
        Stopwatch.clearHistory(context)
        view?.post { rebuild() }
      }
    }
    val list = screen.category(R.string.stopwatch_history_title)
    if (entries.isEmpty()) {
      list.information(getString(R.string.stopwatch_history_empty))
      return
    }
    val date = DateFormat.getDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    for (entry in entries) {
      val started = Date(entry.startedAtMillis)
      list.information(
        "${date.format(started)}, ${time.format(started)}",
        Stopwatch.formatElapsed(context, entry.durationMillis),
      )
    }
  }
}
