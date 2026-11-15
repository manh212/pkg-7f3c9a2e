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

package com.google.android.accessibility.talkback.backup

import android.util.Base64
import com.google.android.accessibility.talkback.migration.MigrationFiles
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Directory trees as JSON for the settings backup: the installed sound themes' files and the
 * installed scripts' files. A tree is a JSON array of `{"path", "data"}` entries, paths relative
 * to the tree's root, file contents base64.
 *
 * Nothing is dropped silently: [toJson] throws when a file cannot be read, and [parse]
 * returns null for any tree it cannot trust, so a bad backup fails the import instead of
 * landing half a theme or script.
 */
internal object FileTreeBackup {
  /**
   * The files under [root] as JSON, or null when [root] is not a directory. Throws
   * IOException when a file cannot be read or the tree does not fit [maxBytes]/[maxFiles],
   * and when a file points outside the tree.
   */
  @Throws(IOException::class)
  fun toJson(root: File, maxBytes: Long, maxFiles: Int): JSONArray? {
    if (!root.isDirectory) return null
    val canonicalRoot = root.canonicalFile
    val files = ArrayList<Pair<String, ByteArray>>()
    var total = 0L
    val pending = ArrayList<File>()
    pending += root.listFiles()?.sortedBy { it.name }.orEmpty()
    while (pending.isNotEmpty()) {
      val file = pending.removeAt(pending.lastIndex)
      if (!file.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
        throw IOException("A file in $root points outside it")
      }
      if (file.isDirectory) {
        pending += file.listFiles()?.sortedBy { it.name }.orEmpty()
        continue
      }
      if (!file.isFile) continue
      if (files.size >= maxFiles) throw IOException("$root holds more than $maxFiles files")
      val bytes = file.readBytes()
      total += bytes.size
      if (total > maxBytes) throw IOException("$root is larger than $maxBytes bytes")
      files += file.relativeTo(root).invariantSeparatorsPath to bytes
    }
    val array = JSONArray()
    for ((path, bytes) in files) {
      array.put(JSONObject().put("path", path).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }
    return array
  }

  /**
   * Reads a tree backup: the files it holds, or null when it cannot be read. Every path
   * must be relative, with safe components, and the tree must fit [maxBytes]/[maxFiles].
   */
  fun parse(array: JSONArray?, maxBytes: Long, maxFiles: Int): List<Pair<String, ByteArray>>? {
    array ?: return null
    val files = ArrayList<Pair<String, ByteArray>>()
    val seen = HashSet<String>()
    var total = 0L
    try {
      for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: return null
        val path = entry.optString("path", "")
        val data = entry.optString("data", "")
        if (!isSafeRelativePath(path) || !seen.add(path)) return null
        val bytes =
          try {
            Base64.decode(data, Base64.DEFAULT)
          } catch (e: IllegalArgumentException) {
            return null
          }
        total += bytes.size
        if (files.size >= maxFiles || total > maxBytes) return null
        files += path to bytes
      }
    } catch (e: JSONException) {
      return null
    }
    return files
  }

  private fun isSafeRelativePath(path: String): Boolean {
    if (path.isEmpty() || path.startsWith("/")) return false
    return path.split('/').all { MigrationFiles.isSafeName(it) }
  }

  /**
   * Replaces the files under [root] with [files]. Returns false when a file cannot be
   * written, and then the files already written stay written.
   */
  fun write(root: File, files: List<Pair<String, ByteArray>>): Boolean {
    root.deleteRecursively()
    for ((path, bytes) in files) {
      val file = File(root, path)
      try {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
      } catch (e: IOException) {
        return false
      }
    }
    return true
  }
}
