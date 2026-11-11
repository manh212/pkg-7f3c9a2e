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

package com.google.android.accessibility.talkback.texteditor

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The text editor's documents, kept in one file of their own that is rewritten atomically on
 * every change, so a document is saved as its text is written. The settings backup carries the
 * documents as its own section; see [backupJson] and [restoreBackup].
 */
object EditorDocuments {
  data class Document(
    val id: String,
    val name: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
  )

  private const val FILE_NAME = "editor-documents.json"
  private const val KEY_ID = "id"
  private const val KEY_NAME = "name"
  private const val KEY_CONTENT = "content"
  private const val KEY_CREATED = "created"
  private const val KEY_UPDATED = "updated"

  private val documents = ArrayList<Document>()
  private var store: AtomicFile? = null
  private var readable = false

  @Synchronized
  fun init(directory: File?) {
    documents.clear()
    store = directory?.let { AtomicFile(File(it, FILE_NAME)) }
    readable = store != null
    val file = store ?: return
    try {
      load(JSONArray(String(file.readFully(), StandardCharsets.UTF_8)))
    } catch (e: FileNotFoundException) {
      readable = !file.baseFile.exists()
    } catch (e: IOException) {
      documents.clear()
      readable = false
    } catch (e: JSONException) {
      documents.clear()
      readable = false
    } catch (e: RuntimeException) {
      documents.clear()
      readable = false
    }
  }

  /** Every document, the most recently changed first. */
  @Synchronized
  fun documents(): List<Document> = documents.sortedByDescending { it.updatedAt }

  /** The document the user was writing last, or null when there is none. */
  @Synchronized
  fun mostRecent(): Document? = documents.maxByOrNull { it.updatedAt }

  @Synchronized
  fun get(id: String): Document? = documents.firstOrNull { it.id == id }

  /** The name a new document gets: the date and time it is created, such as "11/11/2026 21:05". */
  fun defaultName(createdAtMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date(createdAtMillis))

  @Synchronized
  fun create(name: String, content: String): Document? {
    val now = System.currentTimeMillis()
    val document = Document(UUID.randomUUID().toString(), name, content, now, now)
    val updated = ArrayList(documents)
    updated += document
    return if (save(updated)) document else null
  }

  /** A new empty document, named by when it is created. */
  @Synchronized
  fun createDefault(content: String = ""): Document? {
    val now = System.currentTimeMillis()
    val document = Document(UUID.randomUUID().toString(), defaultName(now), content, now, now)
    val updated = ArrayList(documents)
    updated += document
    return if (save(updated)) document else null
  }

  @Synchronized
  fun rename(id: String, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    val index = documents.indexOfFirst { it.id == id }
    if (index < 0) return false
    if (documents[index].name == trimmed) return true
    val updated = ArrayList(documents)
    updated[index] = documents[index].copy(name = trimmed)
    return save(updated)
  }

  /** Saves a document's text, making it the most recently changed document. */
  @Synchronized
  fun updateContent(id: String, content: String): Boolean {
    val index = documents.indexOfFirst { it.id == id }
    if (index < 0) return false
    if (documents[index].content == content) return true
    val updated = ArrayList(documents)
    updated[index] =
      documents[index].copy(content = content, updatedAt = System.currentTimeMillis())
    return save(updated)
  }

  @Synchronized
  fun delete(id: String): Boolean {
    val updated = documents.filter { it.id != id }
    if (updated.size == documents.size) return false
    return save(updated)
  }

  @Synchronized
  fun clear(): Boolean = save(emptyList())

  /** The documents as the JSON the settings backup carries, or null when there is no store. */
  @Synchronized
  fun backupJson(context: Context): String? {
    if (store == null) init(context.applicationContext.filesDir)
    if (store == null) return null
    return toJson(documents).toString()
  }

  /**
   * Replaces every document with the ones in a settings backup. A damaged store file does not
   * block this: the backup's documents are the ones to keep.
   */
  @Synchronized
  fun restoreBackup(context: Context, json: String): Boolean {
    if (store == null) init(context.applicationContext.filesDir)
    if (store == null) return false
    val parsed =
      try {
        parse(JSONArray(json))
      } catch (e: JSONException) {
        return false
      } catch (e: RuntimeException) {
        return false
      }
    readable = true
    return save(parsed)
  }

  private fun load(array: JSONArray) {
    documents.clear()
    documents += parse(array)
  }

  private fun parse(array: JSONArray): List<Document> {
    val parsed = ArrayList<Document>()
    for (i in 0 until array.length()) {
      val entry = array.optJSONObject(i) ?: continue
      val id = entry.optString(KEY_ID, "")
      if (id.isBlank() || parsed.any { it.id == id }) continue
      parsed +=
        Document(
          id = id,
          name = entry.optString(KEY_NAME, ""),
          content = entry.optString(KEY_CONTENT, ""),
          createdAt = entry.optLong(KEY_CREATED, 0L),
          updatedAt = entry.optLong(KEY_UPDATED, 0L),
        )
    }
    return parsed
  }

  private fun toJson(values: List<Document>): JSONArray {
    val array = JSONArray()
    for (document in values) {
      array.put(
        JSONObject()
          .put(KEY_ID, document.id)
          .put(KEY_NAME, document.name)
          .put(KEY_CONTENT, document.content)
          .put(KEY_CREATED, document.createdAt)
          .put(KEY_UPDATED, document.updatedAt)
      )
    }
    return array
  }

  private fun save(updated: List<Document>): Boolean {
    val file = store
    if (file == null || !readable) return false
    var output: FileOutputStream? = null
    return try {
      val data = toJson(updated).toString().toByteArray(StandardCharsets.UTF_8)
      output = file.startWrite()
      output.write(data)
      file.finishWrite(output)
      output = null
      if (!data.contentEquals(file.readFully())) return false
      documents.clear()
      documents += updated
      true
    } catch (e: IOException) {
      output?.let { file.failWrite(it) }
      false
    } catch (e: RuntimeException) {
      output?.let { file.failWrite(it) }
      false
    }
  }
}
