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

package com.google.android.accessibility.talkback.labeling

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.accessibility.utils.labeling.LabelsTable
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The user's custom labels as the settings backup carries them, following the text editor's
 * documents: [backupJson] reads every row of the labels database, and [restoreBackup]
 * replaces the whole table with the backup's rows. Screenshots are not carried: labels
 * never have one (see CustomLabelManager).
 *
 * The labels database lives in credential-protected storage, so before the first unlock
 * there is nothing to read and the backup leaves the labels out.
 */
object CustomLabelsBackup {
  private val LABELS_URI: Uri =
    Uri.Builder()
      .scheme("content")
      .authority(LabelProvider.AUTHORITY)
      .path(LabelsTable.TABLE_NAME)
      .build()

  private val COLUMNS =
    arrayOf(
      LabelsTable.KEY_PACKAGE_NAME,
      LabelsTable.KEY_PACKAGE_SIGNATURE,
      LabelsTable.KEY_VIEW_NAME,
      LabelsTable.KEY_TEXT,
      LabelsTable.KEY_LOCALE,
      LabelsTable.KEY_PACKAGE_VERSION,
      LabelsTable.KEY_TIMESTAMP,
      LabelsTable.KEY_SOURCE_TYPE,
    )

  /** Every custom label as JSON, or null when the labels cannot be read. */
  fun backupJson(context: Context): String? {
    val cursor =
      try {
        context.contentResolver.query(
          LABELS_URI,
          LabelsTable.ALL_COLUMNS,
          null,
          null,
          LabelsTable.KEY_ID,
        )
      } catch (e: RuntimeException) {
        return null
      } ?: return null
    val labels = JSONArray()
    cursor.use {
      val packageName = it.getColumnIndexOrThrow(LabelsTable.KEY_PACKAGE_NAME)
      val packageSignature = it.getColumnIndexOrThrow(LabelsTable.KEY_PACKAGE_SIGNATURE)
      val viewName = it.getColumnIndexOrThrow(LabelsTable.KEY_VIEW_NAME)
      val text = it.getColumnIndexOrThrow(LabelsTable.KEY_TEXT)
      val locale = it.getColumnIndexOrThrow(LabelsTable.KEY_LOCALE)
      val packageVersion = it.getColumnIndexOrThrow(LabelsTable.KEY_PACKAGE_VERSION)
      val timestamp = it.getColumnIndexOrThrow(LabelsTable.KEY_TIMESTAMP)
      val sourceType = it.getColumnIndexOrThrow(LabelsTable.KEY_SOURCE_TYPE)
      while (it.moveToNext()) {
        labels.put(
          JSONObject()
            .put(LabelsTable.KEY_PACKAGE_NAME, it.getString(packageName))
            .put(LabelsTable.KEY_PACKAGE_SIGNATURE, it.getString(packageSignature))
            .put(LabelsTable.KEY_VIEW_NAME, it.getString(viewName))
            .put(LabelsTable.KEY_TEXT, it.getString(text))
            .put(LabelsTable.KEY_LOCALE, it.getString(locale))
            .put(LabelsTable.KEY_PACKAGE_VERSION, it.getLong(packageVersion))
            .put(LabelsTable.KEY_TIMESTAMP, it.getLong(timestamp))
            .put(LabelsTable.KEY_SOURCE_TYPE, it.getInt(sourceType))
        )
      }
    }
    return labels.toString()
  }

  /**
   * Replaces every custom label with the ones in a settings backup, and tells the label
   * manager to rebuild its cache. Every row is read first, so a bad backup changes
   * nothing; the inserts themselves are not one transaction, so a database that fails
   * halfway leaves the rows inserted so far.
   */
  fun restoreBackup(context: Context, json: String): Boolean {
    val labels = ArrayList<ContentValues>()
    try {
      val array = JSONArray(json)
      for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: return false
        val values = ContentValues()
        for (column in COLUMNS) {
          if (!entry.has(column)) return false
          when (column) {
            LabelsTable.KEY_PACKAGE_VERSION,
            LabelsTable.KEY_TIMESTAMP -> values.put(column, entry.optLong(column))
            LabelsTable.KEY_SOURCE_TYPE -> values.put(column, entry.optInt(column))
            else -> values.put(column, entry.optString(column))
          }
        }
        values.put(LabelsTable.KEY_SCREENSHOT_PATH, "")
        labels += values
      }
    } catch (e: JSONException) {
      return false
    }
    val resolver = context.contentResolver
    try {
      resolver.delete(LABELS_URI, null, null)
      for (values in labels) {
        if (resolver.insert(LABELS_URI, values) == null) return false
      }
    } catch (e: RuntimeException) {
      return false
    }
    context.sendBroadcast(Intent(CustomLabelManager.ACTION_REFRESH_LABEL_CACHE))
    return true
  }
}
