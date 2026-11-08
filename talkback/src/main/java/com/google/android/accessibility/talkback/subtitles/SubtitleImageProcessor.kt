package com.google.android.accessibility.talkback.subtitles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect

/** OCR coordinates belong to the prepared image; map them back to display coordinates. */
internal data class PreparedSubtitleImage(
  val bitmap: Bitmap,
  val scaleX: Float,
  val scaleY: Float,
) {
  fun screenBounds(ocrBounds: Rect, region: Rect): Rect = Rect(
    region.left + (ocrBounds.left / scaleX).toInt(),
    region.top + (ocrBounds.top / scaleY).toInt(),
    region.left + (ocrBounds.right / scaleX).toInt(),
    region.top + (ocrBounds.bottom / scaleY).toInt(),
  )
}

internal data class SubtitleImageStats(
  val darkPercent: Int,
  val contrastRange: Int,
  val nearlyUniformDark: Boolean,
)

internal object SubtitleImageProcessor {
  /**
   * Upscale small characters where memory permits and boost video-caption contrast.
   * Use on alternating unsuccessful OCR frames to retain access to coloured text.
   * Takes ownership of the input bitmap on success.
   */
  fun prepare(input: Bitmap, enhance: Boolean): PreparedSubtitleImage {
    if (!enhance) return PreparedSubtitleImage(input, 1f, 1f)
    var scaled = input
    val originalWidth = input.width
    val originalHeight = input.height
    try {
      val canDouble = originalWidth.toLong() * originalHeight * 4L <= 3_000_000L &&
        originalWidth <= 2048 && originalHeight <= 2048
      if (canDouble) {
        scaled = Bitmap.createScaledBitmap(input, originalWidth * 2, originalHeight * 2, true)
      }
      val result = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
      val contrast = 1.50f
      val offset = -128f * (contrast - 1f)
      val filter = ColorMatrix(floatArrayOf(
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
      ))
      val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(filter) }
      Canvas(result).drawBitmap(scaled, 0f, 0f, paint)
      if (scaled !== input) scaled.recycle()
      input.recycle()
      return PreparedSubtitleImage(result, result.width.toFloat() / originalWidth,
        result.height.toFloat() / originalHeight)
    } catch (e: RuntimeException) {
      if (scaled !== input) scaled.recycle()
      // A preprocessing failure must not disable the original image recognizer.
      return PreparedSubtitleImage(input, 1f, 1f)
    } catch (e: OutOfMemoryError) {
      if (scaled !== input) scaled.recycle()
      return PreparedSubtitleImage(input, 1f, 1f)
    }
  }

  /** No screenshot pixels or recognized words are stored or transmitted by this diagnostic. */
  fun inspect(bitmap: Bitmap): SubtitleImageStats {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return SubtitleImageStats(0, 0, false)
    var dark = 0
    var minLuma = 255
    var maxLuma = 0
    var count = 0
    for (y in 0 until 24) {
      val py = (y.toLong() * height / 24).toInt().coerceIn(0, height - 1)
      for (x in 0 until 32) {
        val px = (x.toLong() * width / 32).toInt().coerceIn(0, width - 1)
        val pixel = bitmap.getPixel(px, py)
        val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
          Color.blue(pixel) * 114) / 1000
        if (luma <= 20) dark++
        if (luma < minLuma) minLuma = luma
        if (luma > maxLuma) maxLuma = luma
        count++
      }
    }
    val percent = dark * 100 / count
    val range = maxLuma - minLuma
    return SubtitleImageStats(percent, range, percent >= 98 && range <= 28)
  }
}
