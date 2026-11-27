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
import io.meridian.licensing.DistributionEndpoints
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages on-demand download, status tracking, and deletion of the offline Vietnamese AI
 * spell-correction ONNX model (~300 MB) in app internal storage so it is never bundled in the APK.
 */
object VietnameseSpellModelManager {
  private const val MODEL_DIR_NAME = "offline-vi-spell-v1"
  private const val MANIFEST_REL_PATH = "models/vi-spell-v1/manifest.json"
  private const val FALLBACK_HF_BASE =
    "https://huggingface.co/nrl-ai/vn-spell-correction-small-onnx-int8/resolve/main/"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000

  data class ModelFileSpec(
    val fileName: String,
    val url: String,
    val expectedBytes: Long = -1L,
  )

  data class State(
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val failed: Boolean = false,
    val downloadedBytes: Long = 0L,
  )

  private val defaultFiles = listOf(
    ModelFileSpec("encoder_model_quantized.onnx", FALLBACK_HF_BASE + "encoder_model_quantized.onnx"),
    ModelFileSpec("decoder_model_quantized.onnx", FALLBACK_HF_BASE + "decoder_model_quantized.onnx"),
    ModelFileSpec("tokenizer.json", FALLBACK_HF_BASE + "tokenizer.json"),
    ModelFileSpec("config.json", FALLBACK_HF_BASE + "config.json"),
  )

  private val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "ViSpellModelDl").apply { isDaemon = true }
  }
  private val active = AtomicBoolean(false)
  private val cancelled = AtomicBoolean(false)
  private val activeConnection = AtomicReference<HttpURLConnection?>(null)

  val state = MutableLiveData(State())

  @JvmStatic
  fun modelDir(context: Context): File = File(context.applicationContext.filesDir, MODEL_DIR_NAME)

  @JvmStatic
  fun encoderFile(context: Context): File {
    val dir = modelDir(context)
    val q = File(dir, "encoder_model_quantized.onnx")
    return if (q.isFile) q else File(dir, "encoder_model.onnx")
  }

  @JvmStatic
  fun decoderFile(context: Context): File {
    val dir = modelDir(context)
    val q = File(dir, "decoder_model_quantized.onnx")
    return if (q.isFile) q else File(dir, "decoder_model.onnx")
  }

  @JvmStatic
  fun tokenizerFile(context: Context): File = File(modelDir(context), "tokenizer.json")

  @JvmStatic
  fun configFile(context: Context): File = File(modelDir(context), "config.json")

  @JvmStatic
  fun isReady(context: Context): Boolean {
    val enc = encoderFile(context)
    val dec = decoderFile(context)
    val tok = tokenizerFile(context)
    val cfg = configFile(context)
    return enc.isFile && enc.length() > 1024L &&
      dec.isFile && dec.length() > 1024L &&
      tok.isFile && tok.length() > 64L &&
      cfg.isFile && cfg.length() > 16L
  }

  @JvmStatic
  fun totalDiskBytes(context: Context): Long {
    val dir = modelDir(context)
    if (!dir.isDirectory) return 0L
    return dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
  }

  @JvmStatic
  fun refreshState(context: Context): State {
    val ready = isReady(context)
    val current = State(
      downloading = active.get(),
      progress = if (ready) 100 else (state.value?.progress ?: 0),
      ready = ready,
      failed = false,
      downloadedBytes = if (ready) totalDiskBytes(context) else 0L,
    )
    state.postValue(current)
    return current
  }

  @JvmStatic
  fun isDownloading(): Boolean = active.get()

  @JvmStatic
  fun cancelDownload() {
    cancelled.set(true)
    activeConnection.getAndSet(null)?.disconnect()
  }

  @JvmStatic
  fun deleteModel(context: Context): Boolean {
    cancelDownload()
    VietnameseOnnxSpellCorrector.release()
    val dir = modelDir(context)
    val deleted = if (dir.exists()) dir.deleteRecursively() else true
    state.postValue(State(downloading = false, progress = 0, ready = false, failed = false, downloadedBytes = 0L))
    return deleted
  }

  @JvmStatic
  fun startDownload(context: Context) {
    val app = context.applicationContext
    if (isReady(app)) {
      refreshState(app)
      return
    }
    if (!active.compareAndSet(false, true)) return
    cancelled.set(false)
    state.postValue(State(downloading = true, progress = 0, ready = false, failed = false))

    executor.execute {
      var ok = false
      try {
        val dir = modelDir(app)
        dir.mkdirs()
        val specs = resolveManifestSpecs() ?: defaultFiles
        val totalFiles = specs.size.coerceAtLeast(1)
        val knownTotalBytes = specs.mapNotNull { it.expectedBytes.takeIf { b -> b > 0L } }
          .takeIf { it.size == specs.size }?.sum() ?: -1L
        var completedBytes = 0L

        ok = true
        for ((index, spec) in specs.withIndex()) {
          if (cancelled.get()) {
            ok = false
            break
          }
          val target = File(dir, spec.fileName)
          if (target.isFile && target.length() > 16L &&
            (spec.expectedBytes <= 0L || target.length() == spec.expectedBytes)
          ) {
            completedBytes += target.length()
            val pct = if (knownTotalBytes > 0L) {
              ((completedBytes * 100L) / knownTotalBytes).toInt().coerceIn(0, 99)
            } else {
              (((index + 1) * 100) / totalFiles).coerceIn(0, 99)
            }
            state.postValue(State(downloading = true, progress = pct, ready = false, failed = false))
            continue
          }
          val fileOk = downloadSingleFile(spec.url, target) { downloaded, fileTotal ->
            val pct = if (knownTotalBytes > 0L) {
              (((completedBytes + downloaded) * 100L) / knownTotalBytes).toInt().coerceIn(0, 99)
            } else if (fileTotal > 0L) {
              val basePct = (index * 100) / totalFiles
              val slicePct = ((downloaded * 100L) / (fileTotal * totalFiles)).toInt()
              (basePct + slicePct).coerceIn(0, 99)
            } else {
              ((index * 100) / totalFiles).coerceIn(0, 99)
            }
            state.postValue(State(downloading = true, progress = pct, ready = false, failed = false))
          }
          if (!fileOk) {
            ok = false
            break
          }
          completedBytes += target.length()
        }
      } catch (_: Exception) {
        ok = false
      } finally {
        activeConnection.set(null)
        active.set(false)
        val readyNow = ok && isReady(app)
        state.postValue(
          State(
            downloading = false,
            progress = if (readyNow) 100 else 0,
            ready = readyNow,
            failed = !readyNow && !cancelled.get(),
            downloadedBytes = if (readyNow) totalDiskBytes(app) else 0L,
          )
        )
      }
    }
  }

  private fun resolveManifestSpecs(): List<ModelFileSpec>? {
    val manifestUrl = DistributionEndpoints.dataCatalogRoot() + MANIFEST_REL_PATH
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        instanceFollowRedirects = true
      }
      if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
      val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      val json = JSONObject(body)
      val arr = json.optJSONArray("files") ?: return null
      val list = mutableListOf<ModelFileSpec>()
      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val name = item.optString("name", "").trim()
        val url = item.optString("url", "").trim()
        val size = item.optLong("size", -1L)
        if (name.isNotEmpty() && url.startsWith("https://")) {
          list += ModelFileSpec(name, url, size)
        }
      }
      list.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
      null
    } finally {
      conn?.disconnect()
    }
  }

  private fun downloadSingleFile(
    urlString: String,
    target: File,
    onProgress: (Long, Long) -> Unit,
  ): Boolean {
    val part = File(target.parentFile, target.name + ".part")
    var existing = if (part.isFile) part.length() else 0L
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        if (existing > 0L) {
          setRequestProperty("Range", "bytes=$existing-")
        }
      }
      activeConnection.set(connection)
      val code = connection.responseCode
      val append = when (code) {
        HttpURLConnection.HTTP_PARTIAL -> true
        HttpURLConnection.HTTP_OK -> {
          existing = 0L
          false
        }
        else -> return false
      }
      val contentLen = connection.contentLengthLong
      val total = if (contentLen > 0L) existing + contentLen else -1L
      var downloaded = existing
      var lastReportedPct = -1

      connection.inputStream.use { input ->
        FileOutputStream(part, append).use { output ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            if (cancelled.get()) return false
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            downloaded += n
            val pct = if (total > 0L) ((downloaded * 100L) / total).toInt() else -1
            if (pct != lastReportedPct) {
              lastReportedPct = pct
              onProgress(downloaded, total)
            }
          }
          output.flush()
        }
      }
      if (cancelled.get()) return false
      if (target.exists()) target.delete()
      part.renameTo(target)
    } catch (_: IOException) {
      false
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection?.disconnect()
    }
  }
}
