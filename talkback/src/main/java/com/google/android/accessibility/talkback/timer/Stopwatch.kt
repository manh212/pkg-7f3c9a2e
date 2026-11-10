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
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** The stopwatch's state and history, in preferences so they survive SandFly restarting. */
object Stopwatch {
  const val KEY_SOUND = "pref_stopwatch_sound"
  const val KEY_VIBRATION = "pref_stopwatch_vibration"
  const val KEY_SPEECH = "pref_stopwatch_speech"
  const val KEY_INTERVAL_MINUTES = "pref_stopwatch_interval_minutes"
  const val KEY_INTERVAL_SLIDER = "pref_stopwatch_interval_slider"
  const val KEY_STARTED_AT = "pref_stopwatch_started_at"
  const val KEY_LAST_ANNOUNCED = "pref_stopwatch_last_announced"
  const val KEY_PAUSED_AT = "pref_stopwatch_paused_at"
  const val KEY_PAUSED_TOTAL = "pref_stopwatch_paused_total"
  const val KEY_HISTORY = "pref_stopwatch_history"
  const val MAX_HISTORY = 50
  const val DEFAULT_INTERVAL_MINUTES = 10
  val INTERVAL_CHOICES = listOf(5, 10, 15, 20, 25, 30, 45, 60)

  /**
   * How strongly a milestone is felt: the pulses rise through the milestones of the hour and an
   * exact hour is the strongest, whatever the reminder interval is.
   */
  fun pulsesForMilestone(elapsedMinutes: Long, intervalMinutes: Int): Int {
    if (elapsedMinutes <= 0 || intervalMinutes <= 0) return 1
    val minuteInHour = elapsedMinutes % 60
    if (minuteInHour == 0L) return 6
    val withinHour = (minuteInHour + intervalMinutes - 1) / intervalMinutes
    return withinHour.toInt().coerceIn(1, 5)
  }

  /** The interval choice nearest to [minutes], which an older setting may hold any value of. */
  fun snapInterval(minutes: Int): Int =
    INTERVAL_CHOICES.minByOrNull { kotlin.math.abs(it - minutes) } ?: DEFAULT_INTERVAL_MINUTES

  /** One finished run of the stopwatch. */
  data class Entry(val startedAtMillis: Long, val durationMillis: Long)

  @JvmStatic
  fun startedAt(context: Context): Long =
    SharedPreferencesUtils.getSharedPreferences(context).getLong(KEY_STARTED_AT, 0)

  @JvmStatic fun isRunning(context: Context): Boolean = startedAt(context) > 0

  fun intervalMinutes(context: Context): Int {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    // Until the stopwatch settings replace it, the slider the settings used to show is the
    // value the user last chose; the saved text under the list's key can be older than it.
    val saved = if (prefs.contains(KEY_INTERVAL_SLIDER)) prefs.getInt(KEY_INTERVAL_SLIDER, DEFAULT_INTERVAL_MINUTES)
      else prefs.getString(KEY_INTERVAL_MINUTES, null)?.toIntOrNull() ?: DEFAULT_INTERVAL_MINUTES
    return snapInterval(saved)
  }

  @JvmStatic
  fun isPaused(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getLong(KEY_PAUSED_AT, 0) > 0

  /** How long the stopwatch has actively run: paused spans do not count. */
  @JvmStatic
  fun activeElapsedMillis(context: Context, now: Long = System.currentTimeMillis()): Long {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val startedAt = prefs.getLong(KEY_STARTED_AT, 0)
    if (startedAt <= 0) return 0
    val pausedAt = prefs.getLong(KEY_PAUSED_AT, 0)
    val end = if (pausedAt > 0) pausedAt else now
    return (end - startedAt - prefs.getLong(KEY_PAUSED_TOTAL, 0)).coerceAtLeast(0)
  }

  /** How long the stopwatch has run, as it is said, or null while it is not running. */
  @JvmStatic
  fun elapsedText(context: Context): String? {
    if (startedAt(context) <= 0) return null
    return formatElapsed(context, activeElapsedMillis(context))
  }

  /** A length of time as it is said: hours, minutes and seconds, leaving out the hours under one. */
  @JvmStatic
  fun formatElapsed(context: Context, elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
    val resources = context.resources
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    val parts = mutableListOf<String>()
    if (hours > 0) {
      parts += resources.getQuantityString(R.plurals.notification_hours, hours, hours)
    }
    parts += resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes)
    parts += resources.getQuantityString(R.plurals.notification_seconds, seconds, seconds)
    return parts.joinToString(" ")
  }

  /** The finished runs, newest first. */
  fun history(context: Context): List<Entry> {
    val stored =
      SharedPreferencesUtils.getSharedPreferences(context).getString(KEY_HISTORY, null)
        ?: return emptyList()
    return stored
      .split(";")
      .mapNotNull { part ->
        val fields = part.split(":")
        val startedAt = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val duration = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Entry(startedAt, duration)
      }
  }

  fun addHistory(context: Context, entry: Entry) {
    val entries = (listOf(entry) + history(context)).take(MAX_HISTORY)
    saveHistory(context, entries)
  }

  fun clearHistory(context: Context) {
    SharedPreferencesUtils.getSharedPreferences(context).edit().remove(KEY_HISTORY).apply()
  }

  private fun saveHistory(context: Context, entries: List<Entry>) {
    val stored = entries.joinToString(";") { "${it.startedAtMillis}:${it.durationMillis}" }
    SharedPreferencesUtils.getSharedPreferences(context)
      .edit()
      .putString(KEY_HISTORY, stored)
      .apply()
  }
}
