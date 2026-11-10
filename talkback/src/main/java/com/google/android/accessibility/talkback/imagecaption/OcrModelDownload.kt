package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import java.io.File
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
    models.all { File(directory(context), it.path).let { file -> file.isFile && file.length() == it.size } }

  fun start(context: Context) {
    if (!downloading.compareAndSet(false, true)) return
    status.value = Status(progress = 0)
    val directory = directory(context.applicationContext)
    executor.execute {
      val ok = try {
        var completed = 0L
        var shown = 0
        val total = models.sumOf { it.size }
        val downloader = ModelDownloader()
        models.all { model ->
          val target = File(directory, model.path)
          val installed = target.isFile && target.length() == model.size ||
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
      } catch (_: Exception) {
        false
      }
      downloading.set(false)
      status.postValue(Status(failed = !ok))
    }
  }
}
