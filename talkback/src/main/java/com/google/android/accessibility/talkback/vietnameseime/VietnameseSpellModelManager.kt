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

package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object VietnameseSpellModelManager {
  private const val MODEL_DIR_NAME = "offline-vi-spell-v1"

  data class State(
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val failed: Boolean = false,
    val downloadedBytes: Long = 0L,
    val failure: ModelFiles.Failure? = null,
  )

  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ViSpellModelDl").apply { isDaemon = true }
  }
  private val active = AtomicBoolean(false)
  private val cancelled = AtomicBoolean(false)
  private val activeDownloader = AtomicReference<ModelDownloader?>(null)
  val state = MutableLiveData(State())

  @JvmStatic fun modelDir(context: Context) = File(context.applicationContext.filesDir, MODEL_DIR_NAME)
  @JvmStatic fun encoderFile(context: Context) = File(modelDir(context), "encoder_model.onnx")
  @JvmStatic fun decoderFile(context: Context) = File(modelDir(context), "decoder_model.onnx")
  @JvmStatic fun tokenizerFile(context: Context) = File(modelDir(context), "sentencepiece.bpe.model")
  @JvmStatic fun vocabularyFile(context: Context) = File(modelDir(context), "dict.txt")
  @JvmStatic fun configFile(context: Context) = File(modelDir(context), "config.json")
  @JvmStatic fun isReady(context: Context) = VietnameseSpellModels.ready(modelDir(context))

  @JvmStatic fun totalDiskBytes(context: Context): Long =
    modelDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") &&
      it.name in VietnameseSpellModels.files.map(VietnameseSpellModels.Spec::name) }?.sumOf { it.length() } ?: 0L

  @JvmStatic fun refreshState(context: Context): State {
    val ready = isReady(context)
    val current = state.value ?: State()
    return current.copy(
      downloading = active.get(),
      progress = if (ready) 100 else if (active.get()) current.progress else 0,
      ready = ready,
      failed = !ready && !active.get() && current.failed,
      downloadedBytes = if (ready) totalDiskBytes(context) else 0,
      failure = if (ready || active.get()) null else current.failure,
    ).also(state::postValue)
  }

  @JvmStatic fun isDownloading(): Boolean = active.get()

  @JvmStatic fun cancelDownload() {
    cancelled.set(true)
    activeDownloader.getAndSet(null)?.cancel()
  }

  @JvmStatic fun deleteModel(context: Context): Boolean {
    cancelDownload()
    VietnameseOnnxSpellCorrector.release()
    val directory = modelDir(context)
    val deleted = !directory.exists() || directory.deleteRecursively()
    state.postValue(State())
    return deleted
  }

  @JvmStatic fun startDownload(context: Context) {
    val app = context.applicationContext
    if (isReady(app)) {
      refreshState(app)
      return
    }
    if (!active.compareAndSet(false, true)) return
    cancelled.set(false)
    state.postValue(State(downloading = true))
    executor.execute {
      val downloader = ModelDownloader()
      activeDownloader.set(downloader)
      var result: ModelDownloader.Result = ModelDownloader.Result.Failed(ModelFiles.Failure.STORAGE)
      var reported = -1
      try {
        result = VietnameseSpellModels.install(modelDir(app), downloader, cancelled::get, { bytes ->
          val percent = (bytes * 100 / VietnameseSpellModels.totalBytes).toInt().coerceIn(0, 99)
          if (percent != reported) {
            reported = percent
            state.postValue(State(downloading = true, progress = percent, downloadedBytes = bytes))
          }
        })
      } catch (_: Exception) {
        result = if (cancelled.get()) ModelDownloader.Result.Cancelled
          else ModelDownloader.Result.Failed(ModelFiles.Failure.STORAGE)
      } finally {
        activeDownloader.compareAndSet(downloader, null)
        active.set(false)
        val ready = result == ModelDownloader.Result.Done && isReady(app)
        val failure = if (cancelled.get()) null else (result as? ModelDownloader.Result.Failed)?.failure
        state.postValue(State(
          ready = ready, progress = if (ready) 100 else 0,
          failed = !ready && !cancelled.get(), failure = failure,
          downloadedBytes = if (ready) totalDiskBytes(app) else 0,
        ))
      }
    }
  }
}
