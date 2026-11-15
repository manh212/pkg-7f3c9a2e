/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object SoundFileStore {

  enum class Result {
    OK,
    TOO_LARGE,
    NOT_AUDIO,
    FAILED,
  }

  private const val MAX_SOUND_BYTES = 5L * 1024 * 1024

  val copyExecutor: ExecutorService =
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "sound-file-copy").apply { isDaemon = true }
    }

  fun directory(context: Context, dirName: String): File =
    File(context.createDeviceProtectedStorageContext().dataDir, dirName)

  fun soundFile(directory: File, fileName: String): File? =
    directory.listFiles()
      ?.filter {
        it.isFile && it.name.startsWith(fileName + ".") && !it.name.endsWith(".part")
      }
      ?.minByOrNull { it.name }

  fun setSound(
    context: Context,
    prefs: SharedPreferences,
    dirName: String,
    fileName: String,
    prefKey: String,
    input: InputStream,
    extension: String,
    label: String,
  ): Result {
    val directory = directory(context, dirName).apply { mkdirs() }
    val staging = File(directory, fileName + ".part")
    val result = copy(input, staging)
    if (result != Result.OK) return result
    val target = File(directory, fileName + "." + extension)
    soundFile(directory, fileName)?.delete()
    if (!staging.renameTo(target)) {
      staging.delete()
      return Result.FAILED
    }
    prefs.edit().putString(prefKey, label).apply()
    return Result.OK
  }

  /**
   * The sound files in [dirName] for the settings backup: one entry per slot that has a
   * file, with the slot, the file's name and its contents. The chosen labels ride the
   * settings file's preferences, so they need no entry of their own.
   */
  fun backupSoundsJson(context: Context, dirName: String, slots: Array<out SoundSlot>): String {
    val directory = directory(context, dirName)
    val sounds = JSONArray()
    for (slot in slots) {
      val file = soundFile(directory, slot.fileName) ?: continue
      sounds.put(
        JSONObject()
          .put("slot", slot.fileName)
          .put("name", file.name)
          .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
      )
    }
    return sounds.toString()
  }

  /**
   * Replaces the sound files in [dirName] with the ones in a settings backup. Every
   * entry is read first, so a bad backup changes nothing. The files are not re-checked
   * for being audio: they were checked when they were chosen, and the player that
   * checks them reads more formats than the one that plays them.
   */
  fun restoreSounds(context: Context, dirName: String, slots: Array<out SoundSlot>, json: String): Boolean {
    val byFileName = slots.associateBy { it.fileName }
    val files = ArrayList<Triple<String, String, ByteArray>>()
    try {
      val sounds = JSONArray(json)
      for (i in 0 until sounds.length()) {
        val entry = sounds.optJSONObject(i) ?: return false
        val slot = entry.optString("slot", "")
        val name = entry.optString("name", "")
        val data = entry.optString("data", "")
        if (slot !in byFileName || !name.startsWith("$slot.") || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
          return false
        }
        val bytes =
          try {
            Base64.decode(data, Base64.DEFAULT)
          } catch (e: IllegalArgumentException) {
            return false
          }
        if (bytes.isEmpty() || bytes.size > MAX_SOUND_BYTES) return false
        files += Triple(slot, name, bytes)
      }
    } catch (e: JSONException) {
      return false
    }
    val directory = directory(context, dirName)
    for (slot in slots) {
      directory.listFiles()
        ?.filter { it.isFile && it.name.startsWith(slot.fileName + ".") && !it.name.endsWith(".part") }
        ?.forEach { it.delete() }
    }
    for ((_, name, bytes) in files) {
      val file = File(directory, name)
      try {
        directory.mkdirs()
        file.writeBytes(bytes)
      } catch (e: IOException) {
        return false
      }
    }
    return true
  }

  fun copy(input: InputStream, file: File): Result {
    try {
      file.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          if (total > MAX_SOUND_BYTES) {
            output.close()
            file.delete()
            return Result.TOO_LARGE
          }
          output.write(buffer, 0, count)
        }
      }
    } catch (e: IOException) {
      file.delete()
      return Result.FAILED
    }
    if (!isAudio(file)) {
      file.delete()
      return Result.NOT_AUDIO
    }
    return Result.OK
  }

  private fun isAudio(file: File): Boolean {
    val player = MediaPlayer()
    return try {
      player.setDataSource(file.path)
      player.prepare()
      true
    } catch (e: Exception) {
      false
    } finally {
      player.release()
    }
  }
}
