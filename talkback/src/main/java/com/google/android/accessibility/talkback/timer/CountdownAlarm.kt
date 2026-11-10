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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * The countdown alarms that are still to fire, kept in the default preferences (which are
 * device-protected, so the list is readable before the first unlock). An alarm is a name, the
 * length of time it was set for, and the moment it falls due; the alarms themselves do not
 * survive a reboot, so the engine re-arms from this list every time it starts. The list is
 * runtime state tied to this phone's timeline, and the settings backup leaves it out.
 */
object CountdownAlarm {
  const val KEY_ALARMS = "pref_countdown_alarms"
  const val KEY_REPEATS = "pref_countdown_alarm_repeats"
  const val KEY_SOUND = "pref_countdown_alarm_sound"
  const val KEY_VIBRATION = "pref_countdown_alarm_vibration"

  const val DEFAULT_REPEATS = 3

  /** One alarm still to fire. */
  data class Entry(
    val id: Long,
    val name: String,
    val deadlineMillis: Long,
    val durationMillis: Long,
  )

  /** The alarms still to fire, the soonest first. */
  @JvmStatic
  fun alarms(context: Context): List<Entry> {
    val stored =
      SharedPreferencesUtils.getSharedPreferences(context).getString(KEY_ALARMS, null)
        ?: return emptyList()
    val entries = mutableListOf<Entry>()
    runCatching {
      val array = JSONArray(stored)
      for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        entries +=
          Entry(
            item.getLong("id"),
            item.optString("name"),
            item.getLong("deadline"),
            item.getLong("duration"),
          )
      }
    }
    return entries.sortedBy { it.deadlineMillis }
  }

  /** Adds an alarm [durationMillis] from now and returns it. */
  @JvmStatic
  fun add(context: Context, name: String, durationMillis: Long): Entry {
    val now = System.currentTimeMillis()
    val existing = alarms(context)
    val id = maxOf(now, (existing.maxOfOrNull { it.id } ?: 0L) + 1)
    val entry = Entry(id, name.trim(), now + durationMillis, durationMillis)
    save(context, existing + entry)
    return entry
  }

  /** Removes the alarm with [id], if it is still there. */
  @JvmStatic
  fun remove(context: Context, id: Long) {
    save(context, alarms(context).filterNot { it.id == id })
  }

  /** How many times a firing alarm repeats before it stops on its own. */
  @JvmStatic
  fun repeats(context: Context): Int =
    SharedPreferencesUtils.getSharedPreferences(context)
      .getString(KEY_REPEATS, null)
      ?.toIntOrNull()
      ?.coerceIn(1, 10)
      ?: DEFAULT_REPEATS

  private fun save(context: Context, entries: List<Entry>) {
    val array = JSONArray()
    for (entry in entries) {
      array.put(
        JSONObject()
          .put("id", entry.id)
          .put("name", entry.name)
          .put("deadline", entry.deadlineMillis)
          .put("duration", entry.durationMillis)
      )
    }
    SharedPreferencesUtils.getSharedPreferences(context)
      .edit()
      .putString(KEY_ALARMS, array.toString())
      .apply()
  }
}
