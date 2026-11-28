package com.google.android.accessibility.talkback.recognition

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class PhotoQualityAnalyzer {
  private data class Sample(val box: PhotoBox, val pixels: FloatArray)
  private var previous = emptyList<Sample>()

  fun analyze(bitmap: Bitmap, subjects: List<PhotoSubject>): PhotoObservation {
    val samples = mutableListOf<Sample>()
    val regions = subjects.map { subject ->
      val measured = measure(bitmap, subject.box)
      val old = previous.filter { it.box.overlaps(subject.box) >= .15f }
        .maxByOrNull { it.box.overlaps(subject.box) }
      val mean = measured.second.average().toFloat()
      val oldMean = old?.pixels?.average()?.toFloat() ?: 0f
      val movement = if (old == null) 100f else measured.second.indices.sumOf { index ->
        abs((measured.second[index] - mean) - (old.pixels[index] - oldMean)).toDouble()
      }.div(measured.second.size).toFloat()
      samples.add(Sample(subject.box, measured.second))
      measured.first.copy(movement = movement)
    }
    previous = samples
    val brightness = if (regions.isNotEmpty()) regions.map { it.brightness }.average().toFloat()
      else measure(bitmap, PhotoBox(0f, 0f, 1f, 1f)).first.brightness
    return PhotoObservation(subjects, brightness,
      regions.minOfOrNull { it.sharpness } ?: 0f,
      regions.maxOfOrNull { it.movement } ?: 0f, false,
      regions, bitmap.width, bitmap.height)
  }

  private fun measure(bitmap: Bitmap, box: PhotoBox): Pair<PhotoRegionQuality, FloatArray> {
    val left = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
    val top = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
    val right = (box.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = (box.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
    val width = right - left
    val height = bottom - top
    val scale = min(1f, 192f / max(width, height))
    val w = max(3, (width * scale).toInt())
    val h = max(3, (height * scale).toInt())
    val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
    val reduced = try { Bitmap.createScaledBitmap(cropped, w, h, true) }
      catch (error: RuntimeException) { if (cropped !== bitmap) cropped.recycle(); throw error }
    val colors = IntArray(w * h)
    try { reduced.getPixels(colors, 0, w, 0, 0, w, h) }
    finally {
      if (reduced !== bitmap) reduced.recycle()
      if (cropped !== bitmap && cropped !== reduced) cropped.recycle()
    }
    val gray = FloatArray(colors.size) { index ->
      val color = colors[index]
      Color.red(color) * .299f + Color.green(color) * .587f + Color.blue(color) * .114f
    }
    var sum = 0.0
    var squares = 0.0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val index = y * w + x
      val value = gray[index - 1] + gray[index + 1] + gray[index - w] + gray[index + w] - 4 * gray[index]
      sum += value
      squares += value.toDouble() * value
    }
    val count = (w - 2) * (h - 2)
    val variance = (squares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0).toFloat()
    val small = FloatArray(32 * 32) { index ->
      gray[(index / 32 * h / 32).coerceAtMost(h - 1) * w + (index % 32 * w / 32).coerceAtMost(w - 1)]
    }
    return PhotoRegionQuality(box, gray.average().toFloat(), variance, 0f, width, height) to small
  }
}
