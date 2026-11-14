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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
