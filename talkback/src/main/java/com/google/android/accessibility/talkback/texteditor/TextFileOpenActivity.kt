/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.texteditor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.granularediting.GranularEditing
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Opens a text file in the standalone text editor: the file manager's "open with" choice for a
 * supported text file lands here, the file's text becomes a document of the editor, and the
 * editor opens on it. The file itself is left untouched; saving stays inside the editor.
 */
class TextFileOpenActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = intent?.data
    if (intent?.action == Intent.ACTION_VIEW && uri != null) {
      open(uri)
    }
    finish()
  }

  private fun open(uri: Uri) {
    val bytes = readBytes(uri) ?: return failed()
    if (bytes.size > TextFileReader.MAX_BYTES) return tooLarge()
    val format =
      TextFileReader.formatFor(contentResolver.getType(uri), displayName(uri)) ?: return failed()
    val text = TextFileReader.readText(bytes, format) ?: return failed()
    EditorDocuments.init(applicationContext.filesDir)
    val name = documentName(uri)
    val document = EditorDocuments.create(name, text) ?: return failed()
    GranularEditing.openDocument(this, document.id)
  }

  /** The file's bytes, up to one byte past the size limit, or null when it cannot be read. */
  private fun readBytes(uri: Uri): ByteArray? {
    return try {
      contentResolver.openInputStream(uri)?.use { input ->
        readUpTo(input, TextFileReader.MAX_BYTES + 1)
      }
    } catch (e: Exception) {
      null
    }
  }

  /** Up to [cap] bytes of [input], stopping once that many have been read. */
  private fun readUpTo(input: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < cap) {
      val read = input.read(buffer, 0, minOf(buffer.size, cap - total))
      if (read < 0) break
      out.write(buffer, 0, read)
      total += read
    }
    return out.toByteArray()
  }

  /** The file's display name, without its extension, or a dated name when it has none. */
  private fun documentName(uri: Uri): String {
    val raw = displayName(uri) ?: uri.lastPathSegment ?: ""
    val base = raw.substringBeforeLast('.', raw).ifEmpty { null }
    return base ?: EditorDocuments.defaultName(System.currentTimeMillis())
  }

  private fun displayName(uri: Uri): String? {
    return try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.ifEmpty { null } else null
      }
    } catch (e: Exception) {
      null
    }
  }

  private fun failed() {
    Toast.makeText(this, R.string.text_editor_open_file_failed, Toast.LENGTH_SHORT).show()
  }

  private fun tooLarge() {
    Toast.makeText(this, R.string.text_editor_open_file_too_large, Toast.LENGTH_SHORT).show()
  }
}
