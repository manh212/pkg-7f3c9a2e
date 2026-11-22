package com.google.android.accessibility.talkback.imagecaption

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.accessibility.talkback.licensing.DeviceLicenseManager
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

internal class VietOcrEngine private constructor(context: Context) : AutoCloseable {
  private val environment = OrtEnvironment.getEnvironment()
  private val detector = PaddleTextDetector(OcrModelDownload.detectorFile(context))
  private val encoder: OrtSession
  private val decoder: OrtSession
  private val metadata: JSONObject

  init {
    var encoderSession: OrtSession? = null
    var decoderSession: OrtSession? = null
    try {
      metadata = JSONObject(OfflineModels.file(context, OfflineModels.vocabulary).readText())
      require(metadata.getInt("image_height") == 32 && metadata.getInt("max_seq_length") == 128)
      require(metadata.getInt("sos") == 1)
      OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        encoderSession = environment.createSession(OfflineModels.file(context, OfflineModels.encoder).path, options)
        decoderSession = environment.createSession(OfflineModels.file(context, OfflineModels.decoder).path, options)
      }
      encoder = requireNotNull(encoderSession)
      decoder = requireNotNull(decoderSession)
    } catch (error: Throwable) {
      encoderSession?.close()
      decoderSession?.close()
      detector.close()
      throw error
    }
  }

  private fun read(bitmap: Bitmap): List<TesseractOcr.TextLine> {
    val start = SystemClock.elapsedRealtime()
    val rgba = Mat()
    val bgr = Mat()
    try {
      Utils.bitmapToMat(bitmap, rgba)
      Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
      val boxes = detector.detect(bgr)
      val lines = boxes.mapNotNull { readLine(bgr, it) }
      LogUtils.i(TAG, "VietOCR detected=%d accepted=%d confidence=%d elapsedMs=%d",
        boxes.size, lines.size, if (lines.isEmpty()) 0 else lines.sumOf { it.confidence } / lines.size,
        SystemClock.elapsedRealtime() - start)
      return lines
    } finally {
      rgba.release()
      bgr.release()
    }
  }

  private fun distance(first: Point, second: Point): Double = hypot(first.x - second.x, first.y - second.y)

  private fun expandBoxForDiacritics(box: Array<Point>, maxW: Int, maxH: Int): Array<Point> {
    val dxTop = box[1].x - box[0].x
    val dyTop = box[1].y - box[0].y
    val dxLeft = box[3].x - box[0].x
    val dyLeft = box[3].y - box[0].y
    fun clamp(x: Double, y: Double) = Point(
      x.coerceIn(0.0, (maxW - 1).coerceAtLeast(0).toDouble()),
      y.coerceIn(0.0, (maxH - 1).coerceAtLeast(0).toDouble()),
    )
    return arrayOf(
      clamp(box[0].x - dxLeft * EXPAND_TOP_RATIO - dxTop * EXPAND_SIDE_RATIO,
        box[0].y - dyLeft * EXPAND_TOP_RATIO - dyTop * EXPAND_SIDE_RATIO),
      clamp(box[1].x - dxLeft * EXPAND_TOP_RATIO + dxTop * EXPAND_SIDE_RATIO,
        box[1].y - dyLeft * EXPAND_TOP_RATIO + dyTop * EXPAND_SIDE_RATIO),
      clamp(box[2].x + dxLeft * EXPAND_BOTTOM_RATIO + dxTop * EXPAND_SIDE_RATIO,
        box[2].y + dyLeft * EXPAND_BOTTOM_RATIO + dyTop * EXPAND_SIDE_RATIO),
      clamp(box[3].x + dxLeft * EXPAND_BOTTOM_RATIO - dxTop * EXPAND_SIDE_RATIO,
        box[3].y + dyLeft * EXPAND_BOTTOM_RATIO - dyTop * EXPAND_SIDE_RATIO),
    )
  }

  private fun darkBorder(crop: Mat): Boolean {
    val width = crop.cols()
    val height = crop.rows()
    if (width <= 0 || height <= 0) return false
    val top = ByteArray(3 * width)
    val bottom = ByteArray(3 * width)
    val row = ByteArray(3 * width)
    crop.get(0, 0, top)
    crop.get(height - 1, 0, bottom)
    var total = 0L
    for (i in top.indices) {
      total += (top[i].toInt() and 255) + (bottom[i].toInt() and 255)
    }
    for (y in 0 until height) {
      crop.get(y, 0, row)
      for (channel in 0 until 3) {
        total += (row[channel].toInt() and 255) + (row[3 * (width - 1) + channel].toInt() and 255)
      }
    }
    return total < 127L * 6 * (width + height)
  }

  private fun readLine(image: Mat, detected: Array<Point>): TesseractOcr.TextLine? {
    var box = detected
    var width = max(distance(box[0], box[1]), distance(box[3], box[2]))
    var height = max(distance(box[0], box[3]), distance(box[1], box[2]))
    if (height > width * 1.5) {
      box = arrayOf(box[1], box[2], box[3], box[0])
      val swap = width
      width = height
      height = swap
    }
    if (width < 4 || height < 10) return null
    box = expandBoxForDiacritics(box, image.cols(), image.rows())
    width = max(distance(box[0], box[1]), distance(box[3], box[2]))
    height = max(distance(box[0], box[3]), distance(box[1], box[2]))
    if (width < 4 || height < 10) return null
    val targetWidth = VietOcrTokens.inputWidth(ceil(width).toInt(), ceil(height).toInt())
    val sourcePoints = MatOfPoint2f(*box)
    val targetPoints = MatOfPoint2f(Point(0.0, 0.0), Point(targetWidth - 1.0, 0.0),
      Point(targetWidth - 1.0, 31.0), Point(0.0, 31.0))
    val transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
    val crop = Mat()
    try {
      Imgproc.warpPerspective(image, crop, transform, Size(targetWidth.toDouble(), 32.0),
        Imgproc.INTER_LANCZOS4, Core.BORDER_REPLICATE)
      Imgproc.cvtColor(crop, crop, Imgproc.COLOR_BGR2RGB)
      if (darkBorder(crop)) {
        Core.bitwise_not(crop, crop)
      }
      val pixels = ByteArray(targetWidth * 32 * 3)
      crop.get(0, 0, pixels)
      val area = targetWidth * 32
      val values = FloatArray(area * 3)
      for (i in 0 until area) for (c in 0..2) values[c * area + i] = (pixels[3 * i + c].toInt() and 255) / 255f
      val decoded = decode(values, targetWidth) ?: return null
      if (decoded.confidence < MIN_CONFIDENCE) return null
      val bounds = Rect(box.minOf { it.x }.toInt().coerceIn(0, image.cols()),
        box.minOf { it.y }.toInt().coerceIn(0, image.rows()),
        ceil(box.maxOf { it.x }).toInt().coerceIn(0, image.cols()),
        ceil(box.maxOf { it.y }).toInt().coerceIn(0, image.rows()))
      if (bounds.isEmpty) return null
      return TesseractOcr.TextLine(decoded.value, bounds, emptyList(), (decoded.confidence * 100).roundToInt())
    } finally {
      sourcePoints.release()
      targetPoints.release()
      transform.release()
      crop.release()
    }
  }

  private fun decode(values: FloatArray, width: Int): VietOcrTokens.Text? {
    val tokens = VietOcrTokens(metadata.getString("vocab"), metadata.getInt("offset"), metadata.getInt("eos"))
    OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 32, width.toLong())).use { input ->
      encoder.run(mapOf("image" to input)).use { encoded ->
        var hidden = encoded.get(0) as OnnxTensor
        val features = encoded.get(1) as OnnxTensor
        var previous: OrtSession.Result? = null
        try {
          for (step in 0 until metadata.getInt("max_seq_length")) {
            val result = OnnxTensor.createTensor(environment,
              LongBuffer.wrap(longArrayOf(tokens.token.toLong())), longArrayOf(1)).use { token ->
              decoder.run(mapOf("tgt" to token, "hidden" to hidden, "encoder_outputs" to features))
            }
            previous?.close()
            previous = result
            hidden = result.get(1) as OnnxTensor
            val buffer = (result.get(0) as OnnxTensor).floatBuffer
            val probabilities = FloatArray(buffer.remaining())
            buffer.get(probabilities)
            if (!tokens.accept(probabilities)) break
          }
          return tokens.result()
        } finally {
          previous?.close()
        }
      }
    }
  }

  override fun close() {
    try { encoder.close() } finally {
      try { decoder.close() } finally { detector.close() }
    }
  }

  companion object {
    private const val TAG = "VietOcrEngine"
    private const val MIN_CONFIDENCE = 0.70
    private const val EXPAND_TOP_RATIO = 0.22
    private const val EXPAND_BOTTOM_RATIO = 0.12
    private const val EXPAND_SIDE_RATIO = 0.06
    private val lock = Any()
    private var engine: VietOcrEngine? = null
    private val idle = Handler(Looper.getMainLooper())
    private var lastUsedAt = 0L
    private val release = object : Runnable {
      override fun run() {
        synchronized(lock) {
          if (engine == null) return
          val remaining = 180_000L - (SystemClock.elapsedRealtime() - lastUsedAt)
          if (remaining > 0) {
            idle.postDelayed(this, remaining)
          } else {
            val current = engine
            engine = null
            try { current?.close() } catch (_: Exception) { LogUtils.w(TAG, "Could not release idle camera OCR") }
          }
        }
      }
    }

    @JvmStatic fun recognize(context: Context, bitmap: Bitmap): List<TesseractOcr.TextLine>? {
      if (!DeviceLicenseManager.allows(context, "ocr") ||
          !OfflineModels.isReady(context, OfflineModels.Group.CAMERA_VIETOCR)) return null
      synchronized(lock) {
        idle.removeCallbacks(release)
        return try {
          OcrBreadcrumb.mark(context, "vietocr-create")
          val current = engine ?: VietOcrEngine(context.applicationContext).also { engine = it }
          OcrBreadcrumb.mark(context, "vietocr-read")
          current.read(bitmap).also { OcrBreadcrumb.mark(context, OcrBreadcrumb.STAGE_DONE) }
        } catch (error: Exception) {
          LogUtils.w(TAG, "Camera OCR failed: %s", error.javaClass.simpleName)
          null
        } catch (error: LinkageError) {
          LogUtils.w(TAG, "Camera OCR runtime failed: %s", error.javaClass.simpleName)
          null
        } catch (_: OutOfMemoryError) {
          null
        } finally {
          lastUsedAt = SystemClock.elapsedRealtime()
          idle.postDelayed(release, 180_000L)
        }
      }
    }
  }
}
