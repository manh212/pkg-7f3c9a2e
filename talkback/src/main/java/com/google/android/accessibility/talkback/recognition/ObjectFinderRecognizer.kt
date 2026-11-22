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

package com.google.android.accessibility.talkback.recognition

import android.content.Context
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class DetectedObject(
  val classId: Int,
  val labelVi: String,
  val confidence: Float,
  val cx: Float,
  val cy: Float,
  val width: Float,
  val height: Float,
) {
  fun describeLocation(): String {
    val direction = when {
      cx < 0.35f -> "bên trái"
      cx > 0.65f -> "bên phải"
      else -> "ở giữa"
    }
    return "$labelVi, $direction"
  }
}

internal object CocoObjectCatalog {
  const val NUM_CLASSES = 80
  const val FEATURES = NUM_CLASSES + 4
  const val MIN_CONFIDENCE = 0.48f
  private const val IOU_THRESHOLD = 0.45f

  val DAILY_LABELS_VI: Map<Int, String> = linkedMapOf(
    0 to "Người",
    1 to "Xe đạp",
    2 to "Ô tô",
    3 to "Xe máy",
    13 to "Ghế dài",
    15 to "Con mèo",
    16 to "Con chó",
    24 to "Ba lô",
    25 to "Ô dù",
    26 to "Túi xách",
    28 to "Va li",
    39 to "Cái chai",
    41 to "Cái cốc",
    42 to "Cái nĩa",
    43 to "Con dao",
    44 to "Cái thìa",
    45 to "Cái bát",
    56 to "Cái ghế",
    57 to "Ghế sofa",
    58 to "Chậu cây",
    59 to "Cái giường",
    60 to "Cái bàn",
    61 to "Bồn cầu",
    62 to "Tivi hoặc màn hình",
    63 to "Máy tính xách tay",
    64 to "Chuột máy tính",
    65 to "Điều khiển từ xa",
    66 to "Bàn phím",
    67 to "Điện thoại",
    68 to "Lò vi sóng",
    69 to "Lò nướng",
    71 to "Bồn rửa",
    72 to "Tủ lạnh",
    73 to "Quyển sách",
    74 to "Đồng hồ",
    76 to "Cái kéo",
  )

  fun parseDetections(
    values: FloatArray,
    dimensions: IntArray,
    inputWidth: Int,
    inputHeight: Int,
    targetClassId: Int? = null,
  ): List<DetectedObject> {
    if (targetClassId != null && targetClassId !in DAILY_LABELS_VI) return emptyList()
    require(dimensions.size == 3 && dimensions[0] == 1)
    val channelsFirst = dimensions[1] == FEATURES
    val channelsLast = dimensions[2] == FEATURES
    require(channelsFirst || channelsLast)
    val count = if (channelsFirst) dimensions[2] else dimensions[1]
    require(count > 0 && values.size == FEATURES * count)

    fun value(candidate: Int, feature: Int): Float =
      if (channelsFirst) values[feature * count + candidate] else values[candidate * FEATURES + feature]

    val raw = ArrayList<DetectedObject>()
    for (candidate in 0 until count) {
      var bestClass = -1
      var bestScore = MIN_CONFIDENCE
      if (targetClassId != null) {
        val score = value(candidate, targetClassId + 4)
        if (score.isFinite() && score >= MIN_CONFIDENCE) {
          bestClass = targetClassId
          bestScore = score
        }
      } else {
        for (classId in DAILY_LABELS_VI.keys) {
          val score = value(candidate, classId + 4)
          if (score.isFinite() && score >= bestScore) {
            bestClass = classId
            bestScore = score
          }
        }
      }
      if (bestClass < 0) continue
      val label = DAILY_LABELS_VI[bestClass] ?: continue
      var cx = value(candidate, 0)
      var cy = value(candidate, 1)
      var w = value(candidate, 2)
      var h = value(candidate, 3)
      if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue
      if (cx > 2f || cy > 2f || w > 2f || h > 2f) {
        cx /= inputWidth.toFloat()
        cy /= inputHeight.toFloat()
        w /= inputWidth.toFloat()
        h /= inputHeight.toFloat()
      }
      raw.add(DetectedObject(bestClass, label, bestScore, cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), w.coerceIn(0f, 1f), h.coerceIn(0f, 1f)))
    }
    return nms(raw)
  }

  private fun nms(candidates: List<DetectedObject>): List<DetectedObject> {
    val sorted = candidates.sortedByDescending { it.confidence }
    val kept = ArrayList<DetectedObject>()
    for (cand in sorted) {
      if (kept.none { it.classId == cand.classId && iou(it, cand) > IOU_THRESHOLD }) {
        kept.add(cand)
        if (kept.size >= 5) break
      }
    }
    return kept
  }

  private fun iou(a: DetectedObject, b: DetectedObject): Float {
    val ax1 = a.cx - a.width / 2f
    val ay1 = a.cy - a.height / 2f
    val ax2 = a.cx + a.width / 2f
    val ay2 = a.cy + a.height / 2f
    val bx1 = b.cx - b.width / 2f
    val by1 = b.cy - b.height / 2f
    val bx2 = b.cx + b.width / 2f
    val by2 = b.cy + b.height / 2f
    val interW = max(0f, min(ax2, bx2) - max(ax1, bx1))
    val interH = max(0f, min(ay2, by2) - max(ay1, by1))
    val inter = interW * interH
    val union = a.width * a.height + b.width * b.height - inter
    return if (union > 0f) inter / union else 0f
  }
}

internal interface ObjectFinderRecognizer : Closeable {
  fun detect(bitmap: Bitmap, targetClassId: Int?): DetectedObject?

  companion object {
    fun open(context: Context): ObjectFinderRecognizer =
      EfficientDetObjectFinder(context, OfflineModels.file(context, OfflineModels.objects))

  }
}

private class EfficientDetObjectFinder(context: Context, modelFile: File) : ObjectFinderRecognizer {
  private var target: Int? = null
  private var detector = open(context, modelFile, null)
  private val context = context.applicationContext
  private val modelFile = modelFile

  override fun detect(bitmap: Bitmap, targetClassId: Int?): DetectedObject? {
    if (target != targetClassId) {
      val replacement = open(context, modelFile, targetClassId)
      detector.close()
      detector = replacement
      target = targetClassId
    }
    return BitmapImageBuilder(bitmap).build().use { image ->
      detector.detect(image).detections().mapNotNull { detection ->
        val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
        val entry = objectsByName[category.categoryName()] ?: return@mapNotNull null
        val box = detection.boundingBox()
        toDetectedObject(entry.key, entry.value, category.score(), box, bitmap.width, bitmap.height)
      }.maxByOrNull { it.confidence }
    }
  }

  override fun close() = detector.close()

  companion object {
    private val objectsByName = listOf(
      0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
      13 to "bench", 15 to "cat", 16 to "dog", 24 to "backpack", 25 to "umbrella",
      26 to "handbag", 28 to "suitcase", 39 to "bottle", 41 to "cup", 42 to "fork",
      43 to "knife", 44 to "spoon", 45 to "bowl", 56 to "chair", 57 to "couch",
      58 to "potted plant", 59 to "bed", 60 to "dining table", 61 to "toilet", 62 to "tv",
      63 to "laptop", 64 to "mouse", 65 to "remote", 66 to "keyboard", 67 to "cell phone",
      68 to "microwave", 69 to "oven", 71 to "sink", 72 to "refrigerator",
      73 to "book", 74 to "clock", 76 to "scissors",
    ).associate { (id, name) ->
      name to CocoObjectCatalog.DAILY_LABELS_VI.entries.first { it.key == id }
    }

    private fun open(context: Context, modelFile: File, target: Int?): ObjectDetector {
      val bytes = modelFile.readBytes()
      val model = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
      val labels = objectsByName.filterValues { target == null || it.key == target }.keys.toList()
      val options = ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
        .setScoreThreshold(CocoObjectCatalog.MIN_CONFIDENCE)
        .setCategoryAllowlist(labels)
        .setMaxResults(5)
        .build()
      return ObjectDetector.createFromOptions(context, options)
    }

    private fun toDetectedObject(
      id: Int, label: String, score: Float, box: RectF, width: Int, height: Int,
    ): DetectedObject? {
      val left = (box.left / width).coerceIn(0f, 1f)
      val right = (box.right / width).coerceIn(0f, 1f)
      val top = (box.top / height).coerceIn(0f, 1f)
      val bottom = (box.bottom / height).coerceIn(0f, 1f)
      if (!(right > left && bottom > top)) return null
      return DetectedObject(id, label, score, (left + right) / 2, (top + bottom) / 2, right - left, bottom - top)
    }
  }
}

private class YoloObjectFinder(context: Context, asset: String) : ObjectFinderRecognizer {
  private val interpreter: Interpreter
  private val width: Int
  private val height: Int
  private val channelsFirst: Boolean
  private val floatInput: FloatArray?
  private val inputType: DataType
  private val outputType: DataType
  private val outputShape: IntArray
  private val inputScale: Float
  private val inputZeroPoint: Int
  private val outputScale: Float
  private val outputZeroPoint: Int
  private val inputBuffer: ByteBuffer
  private val outputBuffer: ByteBuffer

  init {
    val bytes = File(context.filesDir, asset).readBytes()
    val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    model.put(bytes).rewind()
    interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    val input = interpreter.getInputTensor(0)
    val layout = CurrencyInputLayout.from(input.shape())
    height = layout.height
    width = layout.width
    channelsFirst = layout.channelsFirst
    inputType = input.dataType()
    floatInput = if (inputType == DataType.FLOAT32) FloatArray(width * height * 3) else null
    require(inputType == DataType.FLOAT32 || inputType == DataType.UINT8)
    inputScale = input.quantizationParams().scale
    inputZeroPoint = input.quantizationParams().zeroPoint
    if (inputType == DataType.UINT8) require(inputScale > 0f)
    val output = interpreter.getOutputTensor(0)
    outputType = output.dataType()
    require(outputType == DataType.FLOAT32 || outputType == DataType.UINT8)
    outputScale = output.quantizationParams().scale
    outputZeroPoint = output.quantizationParams().zeroPoint
    if (outputType == DataType.UINT8) require(outputScale > 0f)
    outputShape = output.shape()
    require(outputShape.size == 3 && outputShape[0] == 1 &&
      (outputShape[1] == CocoObjectCatalog.FEATURES || outputShape[2] == CocoObjectCatalog.FEATURES))
    inputBuffer = ByteBuffer.allocateDirect(width * height * 3 * inputType.byteSize()).order(ByteOrder.nativeOrder())
    outputBuffer = ByteBuffer.allocateDirect(outputShape.reduce(Int::times) * outputType.byteSize()).order(ByteOrder.nativeOrder())
  }

  override fun detect(bitmap: Bitmap, targetClassId: Int?): DetectedObject? {
    val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
    val imageWidth = bitmap.width * scale
    val imageHeight = bitmap.height * scale
    val left = (width - imageWidth) / 2
    val top = (height - imageHeight) / 2
    val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(scaled).apply {
      drawColor(Color.rgb(114, 114, 114))
      drawBitmap(bitmap, null, RectF(left, top, left + imageWidth, top + imageHeight), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    try {
      val pixels = IntArray(width * height)
      scaled.getPixels(pixels, 0, width, 0, 0, width, height)
      inputBuffer.clear()
      if (inputType == DataType.FLOAT32) {
        val floats = requireNotNull(floatInput)
        if (channelsFirst) {
          val planeSize = pixels.size
          for (i in pixels.indices) {
            val px = pixels[i]
            floats[i] = Color.red(px) / 255f
            floats[planeSize + i] = Color.green(px) / 255f
            floats[2 * planeSize + i] = Color.blue(px) / 255f
          }
        } else {
          var i = 0
          for (px in pixels) {
            floats[i++] = Color.red(px) / 255f
            floats[i++] = Color.green(px) / 255f
            floats[i++] = Color.blue(px) / 255f
          }
        }
        inputBuffer.asFloatBuffer().put(floats)
      } else {
        for (px in pixels) {
          putQuantized(Color.red(px))
          putQuantized(Color.green(px))
          putQuantized(Color.blue(px))
        }
      }
      inputBuffer.rewind()
      outputBuffer.clear()
      interpreter.run(inputBuffer, outputBuffer)
      outputBuffer.rewind()
      val scores = FloatArray(outputShape.reduce(Int::times))
      if (outputType == DataType.FLOAT32) {
        outputBuffer.asFloatBuffer().get(scores)
      } else {
        for (i in scores.indices) {
          scores[i] = (outputBuffer.get().toInt() and 0xff).minus(outputZeroPoint) * outputScale
        }
      }
      return CocoObjectCatalog.parseDetections(scores, outputShape, width, height, targetClassId)
        .firstOrNull()?.let {
          it.copy(cx = ((it.cx * width - left) / imageWidth).coerceIn(0f, 1f))
        }
    } finally {
      scaled.recycle()
    }
  }

  private fun putQuantized(channel: Int) {
    val normalized = channel / 255f
    inputBuffer.put((normalized / inputScale + inputZeroPoint).roundToInt().coerceIn(0, 255).toByte())
  }

  override fun close() = interpreter.close()
}

internal class ObjectFinderTracker {
  private var candidateClass: Int? = null
  private var consecutive = 0
  private var lastSpoken: String? = null
  private var lastSpokenAtMs = Long.MIN_VALUE

  fun observe(detected: DetectedObject?, nowMs: Long): String? {
    if (detected == null) {
      candidateClass = null
      consecutive = 0
      return null
    }
    if (candidateClass != detected.classId) {
      candidateClass = detected.classId
      consecutive = 1
      return null
    }
    consecutive++
    if (consecutive < 2) return null

    val spoken = detected.describeLocation()
    if (spoken == lastSpoken && lastSpokenAtMs != Long.MIN_VALUE &&
        nowMs - lastSpokenAtMs < REPEAT_INTERVAL_MS) {
      return null
    }
    lastSpoken = spoken
    lastSpokenAtMs = nowMs
    return spoken
  }

  fun reset() {
    candidateClass = null
    consecutive = 0
    lastSpoken = null
    lastSpokenAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 2_000L
  }
}
