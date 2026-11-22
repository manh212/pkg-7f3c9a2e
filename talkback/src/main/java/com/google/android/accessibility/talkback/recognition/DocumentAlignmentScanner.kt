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

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal enum class DocumentAlignmentState {
  LOW_LIGHT,
  NO_PAPER,
  MOVE_CLOSER,
  MOVE_AWAY,
  MOVE_LEFT,
  MOVE_RIGHT,
  MOVE_UP,
  MOVE_DOWN,
  HOLD_FLAT,
  BLURRY,
  READY,
}

internal data class DocumentAlignmentResult(
  val state: DocumentAlignmentState,
  val guidance: String,
  val corners: Array<Point>? = null,
  val sharpness: Double = 0.0,
)

internal object DocumentAlignmentScanner {
  private val openCvReady = AtomicBoolean(false)
  private const val ANALYSIS_MAX_DIM = 480
  private const val MIN_PAPER_AREA_RATIO = 0.14
  private const val IDEAL_MIN_AREA_RATIO = 0.26
  private const val MAX_AREA_RATIO = 0.90
  private const val MIN_MEAN_LUMA = 45.0
  private const val EDGE_MARGIN_RATIO = 0.025
  private const val CENTER_TOLERANCE = 0.15
  const val MIN_SHARPNESS_LAPLACIAN = 55.0

  private fun ensureOpenCv(): Boolean {
    if (openCvReady.get()) return true
    return try {
      val ok = OpenCVLoader.initLocal()
      if (ok) openCvReady.set(true)
      ok
    } catch (_: Throwable) {
      false
    }
  }

  fun analyze(bitmap: Bitmap): DocumentAlignmentResult {
    if (!ensureOpenCv()) {
      return DocumentAlignmentResult(
        state = DocumentAlignmentState.READY,
        guidance = "Giữ yên tay, đang đọc tài liệu.",
      )
    }

    val srcMat = Mat()
    val scaled = Mat()
    val gray = Mat()
    val blurred = Mat()
    val edges = Mat()
    val hierarchy = Mat()
    val laplacian = Mat()
    val mean = MatOfDouble()
    val stddev = MatOfDouble()
    try {
      Utils.bitmapToMat(bitmap, srcMat)
      val scale = min(1.0, ANALYSIS_MAX_DIM.toDouble() / max(bitmap.width, bitmap.height))
      if (scale < 1.0) {
        Imgproc.resize(srcMat, scaled, Size(bitmap.width * scale, bitmap.height * scale))
      } else {
        srcMat.copyTo(scaled)
      }
      Imgproc.cvtColor(scaled, gray, Imgproc.COLOR_RGBA2GRAY)
      if (Core.mean(gray).`val`[0] < MIN_MEAN_LUMA) {
        return DocumentAlignmentResult(
          DocumentAlignmentState.LOW_LIGHT,
          guidanceFor(DocumentAlignmentState.LOW_LIGHT),
        )
      }

      Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
      Core.meanStdDev(laplacian, mean, stddev)
      val sd = stddev.toArray().firstOrNull() ?: 0.0
      val sharpness = sd * sd

      Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
      Imgproc.Canny(blurred, edges, 45.0, 140.0)
      val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
      try {
        Imgproc.dilate(edges, edges, kernel)
      } finally {
        kernel.release()
      }

      val contours = ArrayList<MatOfPoint>()
      Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      val frameArea = scaled.width().toDouble() * scaled.height().toDouble()
      var bestQuad: Array<Point>? = null
      var bestArea = 0.0

      for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area < frameArea * MIN_PAPER_AREA_RATIO || area <= bestArea) {
          contour.release()
          continue
        }
        val pts2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        try {
          val peri = Imgproc.arcLength(pts2f, true)
          Imgproc.approxPolyDP(pts2f, approx, 0.03 * peri, true)
          val quad = approx.toArray()
          if (quad.size == 4) {
            bestQuad = quad
            bestArea = area
          }
        } finally {
          pts2f.release()
          approx.release()
          contour.release()
        }
      }

      if (bestQuad == null) {
        return DocumentAlignmentResult(
          state = DocumentAlignmentState.NO_PAPER,
          guidance = "Không thấy rõ mép tờ giấy. Hãy đặt tờ giấy trên nền tối hơn và đưa vào giữa khung hình.",
          sharpness = sharpness,
        )
      }

      val orderedScaled = orderCorners(bestQuad)
      val w = scaled.width().toDouble()
      val h = scaled.height().toDouble()
      val areaRatio = bestArea / frameArea

      val fullCorners = Array(4) { i ->
        Point(orderedScaled[i].x / scale, orderedScaled[i].y / scale)
      }

      val state = evaluateGeometry(orderedScaled, w, h, areaRatio, sharpness)
      return DocumentAlignmentResult(
        state = state,
        guidance = guidanceFor(state),
        corners = if (state == DocumentAlignmentState.READY) fullCorners else null,
        sharpness = sharpness,
      )
    } finally {
      srcMat.release()
      scaled.release()
      gray.release()
      blurred.release()
      edges.release()
      hierarchy.release()
      laplacian.release()
      mean.release()
      stddev.release()
    }
  }

  internal fun evaluateGeometry(
    ordered: Array<Point>,
    frameW: Double,
    frameH: Double,
    areaRatio: Double,
    sharpness: Double,
  ): DocumentAlignmentState {
    val marginX = frameW * EDGE_MARGIN_RATIO
    val marginY = frameH * EDGE_MARGIN_RATIO

    val clippedLeft = ordered.any { it.x <= marginX }
    val clippedRight = ordered.any { it.x >= frameW - marginX }
    val clippedTop = ordered.any { it.y <= marginY }
    val clippedBottom = ordered.any { it.y >= frameH - marginY }

    if ((clippedLeft && clippedRight) || (clippedTop && clippedBottom) || areaRatio > MAX_AREA_RATIO) {
      return DocumentAlignmentState.MOVE_AWAY
    }
    if (clippedLeft && !clippedRight) return DocumentAlignmentState.MOVE_LEFT
    if (clippedRight && !clippedLeft) return DocumentAlignmentState.MOVE_RIGHT
    if (clippedTop && !clippedBottom) return DocumentAlignmentState.MOVE_UP
    if (clippedBottom && !clippedTop) return DocumentAlignmentState.MOVE_DOWN

    if (areaRatio < IDEAL_MIN_AREA_RATIO) return DocumentAlignmentState.MOVE_CLOSER

    val cx = ordered.sumOf { it.x } / (4.0 * frameW)
    val cy = ordered.sumOf { it.y } / (4.0 * frameH)
    if (cx < 0.5 - CENTER_TOLERANCE) return DocumentAlignmentState.MOVE_LEFT
    if (cx > 0.5 + CENTER_TOLERANCE) return DocumentAlignmentState.MOVE_RIGHT
    if (cy < 0.5 - CENTER_TOLERANCE) return DocumentAlignmentState.MOVE_UP
    if (cy > 0.5 + CENTER_TOLERANCE) return DocumentAlignmentState.MOVE_DOWN

    val topW = hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
    val bottomW = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
    val leftH = hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y)
    val rightH = hypot(ordered[2].x - ordered[1].x, ordered[2].y - ordered[1].y)
    val wRatio = min(topW, bottomW) / max(1.0, max(topW, bottomW))
    val hRatio = min(leftH, rightH) / max(1.0, max(leftH, rightH))
    if (wRatio < 0.68 || hRatio < 0.68) return DocumentAlignmentState.HOLD_FLAT

    if (sharpness < MIN_SHARPNESS_LAPLACIAN) return DocumentAlignmentState.BLURRY

    return DocumentAlignmentState.READY
  }

  fun dewarp(bitmap: Bitmap, corners: Array<Point>): Bitmap? {
    if (!ensureOpenCv() || corners.size != 4) return null
    val srcMat = Mat()
    val dstMat = Mat()
    val srcPts = MatOfPoint2f(*corners)
    var dstPts: MatOfPoint2f? = null
    var transform: Mat? = null
    try {
      Utils.bitmapToMat(bitmap, srcMat)
      val widthTop = hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
      val widthBottom = hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)
      val targetW = max(widthTop, widthBottom).toInt().coerceIn(320, 1800)

      val heightLeft = hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y)
      val heightRight = hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)
      val targetH = max(heightLeft, heightRight).toInt().coerceIn(320, 2200)

      dstPts = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(targetW - 1.0, 0.0),
        Point(targetW - 1.0, targetH - 1.0),
        Point(0.0, targetH - 1.0),
      )
      transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
      Imgproc.warpPerspective(srcMat, dstMat, transform, Size(targetW.toDouble(), targetH.toDouble()))

      val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
      Utils.matToBitmap(dstMat, out)
      return out
    } catch (_: Throwable) {
      return null
    } finally {
      srcMat.release()
      dstMat.release()
      srcPts.release()
      dstPts?.release()
      transform?.release()
    }
  }

  internal fun orderCorners(points: Array<Point>): Array<Point> {
    val tl = points.minBy { it.x + it.y }
    val br = points.maxBy { it.x + it.y }
    val tr = points.minBy { it.y - it.x }
    val bl = points.maxBy { it.y - it.x }
    return arrayOf(tl, tr, br, bl)
  }

  fun enhanceForOcr(bitmap: Bitmap): Bitmap {
    if (!ensureOpenCv()) return bitmap
    val rgba = Mat()
    val rgb = Mat()
    val lab = Mat()
    val channels = ArrayList<Mat>(3)
    val blurred = Mat()
    val sharpened = Mat()
    var clahe: org.opencv.imgproc.CLAHE? = null
    var output: Bitmap? = null
    try {
      Utils.bitmapToMat(bitmap, rgba)
      Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
      Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
      Core.split(lab, channels)
      clahe = Imgproc.createCLAHE(2.4, Size(8.0, 8.0))
      clahe.apply(channels[0], channels[0])
      Imgproc.GaussianBlur(channels[0], blurred, Size(0.0, 0.0), 1.2)
      Core.addWeighted(channels[0], 1.45, blurred, -0.45, 0.0, sharpened)
      sharpened.copyTo(channels[0])
      Core.merge(channels, lab)
      Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
      Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
      val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
      output = out
      Utils.matToBitmap(rgba, out)
      output = null
      return out
    } catch (_: Throwable) {
      return bitmap
    } finally {
      output?.recycle()
      clahe?.collectGarbage()
      for (ch in channels) ch.release()
      rgba.release()
      rgb.release()
      lab.release()
      blurred.release()
      sharpened.release()
    }
  }

  fun guidanceFor(state: DocumentAlignmentState): String = when (state) {
    DocumentAlignmentState.LOW_LIGHT -> "Ánh sáng yếu, hãy bật đèn flash để đọc chữ rõ hơn."
    DocumentAlignmentState.NO_PAPER -> "Không thấy rõ 4 mép tờ giấy. Hãy đặt giấy trên nền sẫm hơn và đưa vào giữa khung hình."
    DocumentAlignmentState.MOVE_AWAY -> "Quá gần tờ giấy. Hãy nâng điện thoại cao lên để lấy đủ cả 4 góc."
    DocumentAlignmentState.MOVE_CLOSER -> "Quá xa tờ giấy. Hãy hạ điện thoại lại gần hơn một chút."
    DocumentAlignmentState.MOVE_LEFT -> "Mép trái bị lệch hoặc cắt. Hãy dịch điện thoại sang trái một chút."
    DocumentAlignmentState.MOVE_RIGHT -> "Mép phải bị lệch hoặc cắt. Hãy dịch điện thoại sang phải một chút."
    DocumentAlignmentState.MOVE_UP -> "Mép trên bị lệch hoặc cắt. Hãy dịch điện thoại lên trên một chút."
    DocumentAlignmentState.MOVE_DOWN -> "Mép dưới bị lệch hoặc cắt. Hãy dịch điện thoại xuống dưới một chút."
    DocumentAlignmentState.HOLD_FLAT -> "Điện thoại đang nghiêng. Hãy giữ điện thoại phẳng song song với mặt giấy."
    DocumentAlignmentState.BLURRY -> "Ảnh đang mờ, giữ yên tay 1 giây để lấy nét."
    DocumentAlignmentState.READY -> "Đã căn đủ 4 góc tờ giấy, giữ yên — đang đọc."
  }
}

internal class DocumentAlignmentTracker {
  private var readyStreak = 0
  private var noPaperStreak = 0
  private var lastGuidance: String? = null
  private var lastGuidanceAtMs = Long.MIN_VALUE
  private var lastReadAtMs = Long.MIN_VALUE

  sealed class Decision {
    data class Guide(val message: String) : Decision()
    data class Capture(val corners: Array<Point>?) : Decision()
    data object Wait : Decision()
  }

  fun observe(result: DocumentAlignmentResult, nowMs: Long): Decision {
    noPaperStreak = if (result.state == DocumentAlignmentState.NO_PAPER &&
        result.sharpness >= DocumentAlignmentScanner.MIN_SHARPNESS_LAPLACIAN) noPaperStreak + 1 else 0
    val readUnframedLabel = noPaperStreak >= REQUIRED_READY_FRAMES + 1
    if (result.state == DocumentAlignmentState.READY || readUnframedLabel) {
      readyStreak++
      if ((readyStreak >= REQUIRED_READY_FRAMES || readUnframedLabel) &&
          (lastReadAtMs == Long.MIN_VALUE || nowMs - lastReadAtMs >= READ_COOLDOWN_MS)) {
        readyStreak = 0
        lastReadAtMs = nowMs
        lastGuidance = null
        return Decision.Capture(result.corners)
      }
      return Decision.Wait
    }
    readyStreak = 0
    val shouldSpeak = result.guidance != lastGuidance ||
      lastGuidanceAtMs == Long.MIN_VALUE ||
      nowMs - lastGuidanceAtMs >= GUIDANCE_REPEAT_MS
    if (shouldSpeak) {
      lastGuidance = result.guidance
      lastGuidanceAtMs = nowMs
      return Decision.Guide(result.guidance)
    }
    return Decision.Wait
  }

  fun reset() {
    readyStreak = 0
    noPaperStreak = 0
    lastGuidance = null
    lastGuidanceAtMs = Long.MIN_VALUE
    lastReadAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REQUIRED_READY_FRAMES = 2
    const val GUIDANCE_REPEAT_MS = 2_400L
    const val READ_COOLDOWN_MS = 5_000L
  }
}

internal object ExpiryDateExtractor {
  private val EXPIRY_REGEX = Regex(
    "(?i)(?:HSD|Hạn\\s*sử\\s*dụng|Hạn\\s*dùng|EXP(?:IRY)?|BEST\\s*BEFORE|BB|USE\\s*BY)\\s*[:.\\-]?\\s*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{1,2}[/.\\-]\\d{2,4})"
  )
  private val MFG_REGEX = Regex(
    "(?i)(?:NSX|Ngày\\s*sản\\s*xuất|MFG|PRD)\\s*[:.\\-]?\\s*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{1,2}[/.\\-]\\d{2,4})"
  )

  fun formatDocumentSpeech(rawText: String, maxLength: Int = 1_200): String {
    val clean = rawText.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return ""
    val expiry = EXPIRY_REGEX.find(clean)?.groupValues?.getOrNull(1)
    val mfg = MFG_REGEX.find(clean)?.groupValues?.getOrNull(1)
    val prefix = buildString {
      if (!expiry.isNullOrBlank()) append("Hạn sử dụng: $expiry. ")
      if (!mfg.isNullOrBlank()) append("Ngày sản xuất: $mfg. ")
    }
    return (prefix + clean).take(maxLength)
  }
}
