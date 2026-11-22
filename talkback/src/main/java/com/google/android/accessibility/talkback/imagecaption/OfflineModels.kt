package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object OfflineModels {
  enum class Group { SCREEN_OCR, CAMERA_OCR, CURRENCY, OBJECTS }
  data class Status(val progress: Int? = null, val failed: Boolean = false)
  data class Model(val path: String, val url: String, val size: Long, val sha256: String)

  val detector = Model("offline-ocr-v1/inference.onnx",
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx",
    9_880_512, "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e")
  val vietnamese = Model("offline-ocr-v1/tessdata/vie.traineddata",
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/4.1.0/vie.traineddata",
    12_435_550, "b6b49293d95d0b6dbd8780174627e82c75be957b6f4ed9862155540d6b00bb45")
  private const val VIETOCR_URL = "https://github.com/duchoa1297/vietocr-onnx/releases/download/ocr-v1/"
  val encoder = Model("vietocr-v1/encoder.onnx", VIETOCR_URL + "vietocr_encoder.onnx",
    84_317_562, "7375c11ad23d52547a344ec5f53adf89db8302f035ceb3f1e67cff2c04069ae7")
  val decoder = Model("vietocr-v1/decoder.onnx", VIETOCR_URL + "vietocr_decoder.onnx",
    5_140_562, "73b92cd370563952b345703ecdce84d8374a9f0a7018afee062170efe04f21f5")
  val vocabulary = Model("vietocr-v1/vietocr.json", VIETOCR_URL + "vietocr.json",
    712, "518345b7672fd36e534fb8ffcf6f6599c687793541259170834789fe2f7dd6e6")
  val currency = Model("recognition-models-v1/currency.tflite",
    "https://raw.githubusercontent.com/manh212/pkg-7f3c9a2e/9cdc3c54988bc22e7bc88d452d7b3980fac86e70/talkback/src/main/assets/vn_currency_yolo11n.tflite",
    3_027_064, "b3d3727f9ca1fb41789071e5590f7677bca83e9dd61505b322580c52251fddd9")
  val objects = Model("recognition-models-v1/objects.tflite",
    "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
    4_602_795, "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb")

  private val executor = Executors.newFixedThreadPool(2)
  private val active = ConcurrentHashMap.newKeySet<Group>()
  private val states = ConcurrentHashMap<Group, Status>()
  private val fileLocks = ConcurrentHashMap<String, Any>()
  val status = MutableLiveData<Map<Group, Status>>(emptyMap())

  @JvmStatic fun file(context: Context, model: Model) = File(context.filesDir, model.path)

  fun models(group: Group): List<Model> = when (group) {
    Group.SCREEN_OCR -> listOf(detector, vietnamese)
    Group.CAMERA_OCR -> listOf(detector, encoder, decoder, vocabulary)
    Group.CURRENCY -> listOf(currency)
    Group.OBJECTS -> listOf(objects)
  }

  @JvmStatic fun isReady(context: Context, group: Group): Boolean =
    models(group).all { model -> file(context, model).let { it.isFile && it.length() == model.size } }

  fun state(group: Group): Status = states[group] ?: Status()

  private fun publish(group: Group, value: Status) {
    states[group] = value
    status.postValue(states.toMap())
    if (group == Group.SCREEN_OCR) OcrModelDownload.update(value)
  }

  @JvmStatic fun request(context: Context, group: Group) {
    if (isReady(context, group) || !active.add(group)) return
    val app = context.applicationContext
    publish(group, Status(progress = 0))
    executor.execute {
      val required = models(group)
      val total = required.sumOf { it.size }
      var completed = 0L
      var shown = -1
      val success = try {
        required.all { model ->
          val target = file(app, model)
          synchronized(fileLocks.getOrPut(model.path) { Any() }) {
            val ready = target.isFile && target.length() == model.size
            val result = ready || ModelDownloader().download(
              model.url, target, model.size, model.sha256,
              onProgress = { bytes ->
                val progress = ((completed + bytes) * 100 / total).toInt().coerceIn(0, 99)
                if (progress != shown) {
                  shown = progress
                  publish(group, Status(progress = progress))
                }
              },
              isCancelled = { false },
            ) == ModelDownloader.Result.Done
            if (result) completed += model.size
            result
          }
        }
      } catch (_: Exception) {
        false
      }
      active.remove(group)
      publish(group, Status(failed = !success))
    }
  }
}
