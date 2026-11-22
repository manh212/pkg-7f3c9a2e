package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object OcrModelDownload {
  data class Status(val progress: Int? = null, val failed: Boolean = false)

  private data class Model(val path: String, val url: String, val size: Long, val sha256: String)

  private val detector = Model(
    "inference.onnx",
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
    9_880_512,
    "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
  )
  private val vietnamese = Model(
    "tessdata/vie.traineddata",
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/vie.traineddata",
    531_275,
    "79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1",
  )
  private val models = listOf(detector, vietnamese)
  private val executor = Executors.newSingleThreadExecutor()
  private val downloading = AtomicBoolean()
  val status = MutableLiveData(Status())

  @JvmStatic fun directory(context: Context) = File(context.filesDir, "offline-ocr-v1")

  @JvmStatic fun detectorFile(context: Context) = File(directory(context), detector.path)

  @JvmStatic fun isReady(context: Context): Boolean =
    models.all { isInstalled(File(directory(context), it.path), it) }

  @Synchronized
  fun installBundled(context: Context): Boolean {
    return models.all { model ->
      val target = File(directory(context), model.path)
      try {
        isInstalled(target, model) || installAsset(context, target, model)
      } catch (_: IOException) {
        false
      }
    }
  }

  private fun isInstalled(target: File, model: Model): Boolean =
    target.isFile && target.length() == model.size

  private fun installAsset(context: Context, target: File, model: Model): Boolean {
    val file = AtomicFile(target)
    var output: FileOutputStream? = null
    try {
      context.assets.open("offline-ocr-v1/${model.path}").use { input ->
        val stream = file.startWrite()
        output = stream
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = input.copyTo(DigestOutputStream(stream, digest))
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (copied != model.size || actual != model.sha256) return false
        file.finishWrite(stream)
        output = null
        return isInstalled(target, model)
      }
    } finally {
      output?.let(file::failWrite)
    }
  }

  fun start(context: Context) {
    if (!downloading.compareAndSet(false, true)) return
    status.postValue(Status(progress = 0))
    val directory = directory(context.applicationContext)
    executor.execute {
      val ok = try {
        installBundled(context.applicationContext) || synchronized(this) {
          var completed = 0L
          var shown = 0
          val total = models.sumOf { it.size }
          val downloader = ModelDownloader()
          models.all { model ->
            val target = File(directory, model.path)
            val installed = isInstalled(target, model) ||
              downloader.download(
                model.url, target, model.size, model.sha256,
                onProgress = { bytes ->
                  val progress = ((completed + bytes) * 100 / total).toInt().coerceIn(0, 99)
                  if (progress != shown) {
                    shown = progress
                    status.postValue(Status(progress = progress))
                  }
                },
                isCancelled = { false },
              ) == ModelDownloader.Result.Done
            if (installed) completed += model.size
            installed
          }
        }
      } catch (_: Exception) {
        false
      }
      downloading.set(false)
      status.postValue(Status(failed = !ok))
    }
  }
}
