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

package com.google.android.accessibility.talkback.selector

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object CustomReadingControls {

  const val PREF_KEY = "pref_custom_reading_controls"
  const val ITEM_PREFIX = "custom:"

  private const val KEY_ID = "id"
  private const val KEY_NAME = "name"
  private const val KEY_UP_ACTION = "upAction"
  private const val KEY_DOWN_ACTION = "downAction"

  data class Control(
    val id: String,
    val name: String,
    val upAction: String,
    val downAction: String,
  ) {
    val itemId: String
      get() = itemIdFor(id)
  }

  @JvmStatic fun itemIdFor(id: String): String = ITEM_PREFIX + id

  @JvmStatic fun isCustomItem(itemId: String?): Boolean =
    itemId != null && itemId.startsWith(ITEM_PREFIX)

  @JvmStatic fun customId(itemId: String?): String =
    if (isCustomItem(itemId)) itemId!!.substring(ITEM_PREFIX.length) else ""

  @JvmStatic
  fun list(prefs: SharedPreferences): List<Control> {
    val raw = prefs.getString(PREF_KEY, null) ?: return emptyList()
    return parseJson(raw)
  }

  @JvmStatic
  fun find(prefs: SharedPreferences, id: String?): Control? {
    if (id.isNullOrEmpty()) return null
    return list(prefs).firstOrNull { it.id == id }
  }

  @JvmStatic
  fun create(
    prefs: SharedPreferences,
    name: String,
    upAction: String,
    downAction: String,
  ): Control {
    val control =
      Control(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        upAction = upAction,
        downAction = downAction,
      )
    save(prefs, control)
    return control
  }

  @JvmStatic
  fun save(prefs: SharedPreferences, control: Control) {
    val trimmed = control.copy(name = control.name.trim())
    if (trimmed.name.isEmpty() || trimmed.upAction.isEmpty() || trimmed.downAction.isEmpty()) {
      return
    }
    val current = list(prefs).toMutableList()
    val index = current.indexOfFirst { it.id == trimmed.id }
    if (index >= 0) {
      current[index] = trimmed
    } else {
      current.add(trimmed)
    }
    writeAll(prefs, current)
  }

  @JvmStatic
  fun delete(prefs: SharedPreferences, id: String): Boolean {
    val current = list(prefs).toMutableList()
    val removed = current.removeAll { it.id == id }
    if (removed) {
      writeAll(prefs, current)
    }
    return removed
  }

  internal fun serializeJson(controls: List<Control>): String {
    val array = JSONArray()
    for (control in controls) {
      val obj = JSONObject()
      obj.put(KEY_ID, control.id)
      obj.put(KEY_NAME, control.name)
      obj.put(KEY_UP_ACTION, control.upAction)
      obj.put(KEY_DOWN_ACTION, control.downAction)
      array.put(obj)
    }
    return array.toString()
  }

  internal fun parseJson(raw: String): List<Control> {
    val result = mutableListOf<Control>()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: continue
      val id = obj.optString(KEY_ID, "").trim()
      val name = obj.optString(KEY_NAME, "").trim()
      val up = obj.optString(KEY_UP_ACTION, "").trim()
      val down = obj.optString(KEY_DOWN_ACTION, "").trim()
      if (id.isNotEmpty() && name.isNotEmpty() && up.isNotEmpty() && down.isNotEmpty()) {
        result.add(Control(id, name, up, down))
      }
    }
    return result
  }

  private fun writeAll(prefs: SharedPreferences, controls: List<Control>) {
    prefs.edit().putString(PREF_KEY, serializeJson(controls)).apply()
  }
}
