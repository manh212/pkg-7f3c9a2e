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

package com.google.android.accessibility.talkback.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import com.google.android.accessibility.talkback.actor.gemini.local.DownloadProgress
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val HUGGING_FACE =
  "https://huggingface.co/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/resolve/main/"

/**
 * The Vietnamese Zipformer model that Dictate can use on the phone: whether its files are
 * downloaded, and the download in progress. One instance lives for the whole process, so a
 * download carries on while Dictation settings are closed. The model is a fixed set of files
 * rather than a choice of sizes.
 */
class ZipformerModels private constructor(context: Context) {
  /** One file of the model, verified by size and hash as it is downloaded. */
  enum class Part(val fileName: String, val sizeBytes: Long, val sha256: String) {
    ENCODER(
      "encoder.int8.onnx",
      27_699_063,
      "8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68",
    ),
    DECODER(
      "decoder.onnx",
      5_165_084,
      "cf2aa385b82c9d5d40cd29c3188af52d0249b3b78f0d4b7eb84ad502d50c7e7f",
    ),
    JOINER(
      "joiner.int8.onnx",
      1_033_417,
      "7311d2e17b810ecea515d79c71cc4668af8759256a06fa01d27047772320c821",
    ),
    TOKENS(
      "tokens.txt",
      23_238,
      "ca8171f8bbd516c050b627582f2125c8f5f1f6ed967ab41b0fa9aae2cf61b492",
    );

    val url: String
      get() = HUGGING_FACE + fileName
  }

  sealed interface State {
    data object Idle : State

    data class Downloading(val bytes: Long) : State

    data class Failed(val failure: ModelFiles.Failure) : State
  }

  val directory = File(context.applicationContext.filesDir, "zipformer")
  private val main = Handler(Looper.getMainLooper())
  private val worker =
    Executors.newSingleThreadExecutor { Thread(it, "zipformer-model").apply { isDaemon = true } }
  private val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val cancelled = AtomicBoolean(false)

  @Volatile
  var state: State = State.Idle
    private set

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  fun file(part: Part) = File(directory, part.fileName)

  /** The model is usable only when every file is present at its published size. */
  fun isInstalled(): Boolean = Part.entries.all { file(it).length() == it.sizeBytes }

  fun startDownload() {
    if (state is State.Downloading) return
    directory.mkdirs()
    val remaining =
      Part.entries.filter { file(it).length() != it.sizeBytes }
        .sumOf { it.sizeBytes - ModelFiles.partFile(file(it)).length() }
    if (StatFs(directory.path).availableBytes < remaining + remaining / 10) {
      setState(State.Failed(ModelFiles.Failure.LOW_SPACE))
      return
    }
    cancelled.set(false)
    setState(State.Downloading(downloadedBytes()))
    worker.execute {
      var failure: ModelFiles.Failure? = null
      for (part in Part.entries) {
        if (cancelled.get()) break
        if (file(part).length() == part.sizeBytes) continue
        var lastReport = SystemClock.elapsedRealtime()
        // The progress callback counts this file's bytes on disk, so the base is every other
        // file's bytes, without this one's partial file.
        val base =
          Part.entries.filter { it != part }
            .sumOf { other ->
              if (file(other).length() == other.sizeBytes) other.sizeBytes
              else ModelFiles.partFile(file(other)).length()
            }
        val result =
          ModelDownloader()
            .download(
              part.url,
              file(part),
              part.sizeBytes,
              part.sha256,
              onProgress = { bytes ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                  lastReport = now
                  setState(State.Downloading(base + bytes))
                }
              },
              isCancelled = { cancelled.get() },
            )
        if (result is ModelDownloader.Result.Failed) {
          failure = result.failure
          break
        }
      }
      setState(if (failure != null) State.Failed(failure) else State.Idle)
    }
  }

  fun cancelDownload() {
    cancelled.set(true)
  }

  fun delete() {
    worker.execute {
      Part.entries.forEach {
        file(it).delete()
        ModelFiles.partFile(file(it)).delete()
      }
      setState(State.Idle)
    }
  }

  /** How far the download has got, in percent. */
  fun percent(state: State.Downloading) = DownloadProgress.percent(state.bytes, TOTAL_BYTES)

  private fun downloadedBytes(): Long =
    Part.entries.sumOf { part ->
      when {
        file(part).length() == part.sizeBytes -> part.sizeBytes
        else -> ModelFiles.partFile(file(part)).length()
      }
    }

  private fun setState(newState: State) {
    state = newState
    notifyListeners()
  }

  private fun notifyListeners() {
    main.post { listeners.forEach { it() } }
  }

  companion object {
    const val LABEL = "Zipformer 30M"
    val TOTAL_BYTES: Long = Part.entries.sumOf { it.sizeBytes }

    private const val PROGRESS_INTERVAL_MS = 500L

    @Volatile private var instance: ZipformerModels? = null

    fun get(context: Context): ZipformerModels =
      instance
        ?: synchronized(this) { instance ?: ZipformerModels(context).also { instance = it } }
  }
}
