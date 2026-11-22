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
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.accessibility.utils.output.ColorNameUtils
import kotlin.math.roundToInt

internal data class ColorSample(
  val rgb: Int,
  val label: String,
  val lowLight: Boolean,
)

internal object ColorRecognizer {
  private const val ROI_FRACTION = 0.12f
  private const val MAX_SAMPLE_DIM = 48
  private const val LOW_LIGHT_VALUE_THRESHOLD = 0.16f

  fun detect(context: Context, bitmap: Bitmap): ColorSample? {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 8 || h < 8) return null

    val roiW = (w * ROI_FRACTION).roundToInt().coerceIn(4, w)
    val roiH = (h * ROI_FRACTION).roundToInt().coerceIn(4, h)
    val left = (w - roiW) / 2
    val top = (h - roiH) / 2
    val stepX = (roiW / MAX_SAMPLE_DIM).coerceAtLeast(1)
    val stepY = (roiH / MAX_SAMPLE_DIM).coerceAtLeast(1)

    val pixels = IntArray(roiW * roiH)
    bitmap.getPixels(pixels, 0, roiW, left, top, roiW, roiH)

    val sampled = ArrayList<Int>((roiW / stepX + 1) * (roiH / stepY + 1))
    var y = 0
    while (y < roiH) {
      var x = 0
      while (x < roiW) {
        sampled.add(pixels[y * roiW + x])
        x += stepX
      }
      y += stepY
    }
    if (sampled.isEmpty()) return null

    sampled.sortBy { pixel ->
      299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)
    }
    val trim = (sampled.size * 0.15f).toInt()
    val start = trim.coerceAtMost(sampled.size - 1)
    val end = (sampled.size - trim).coerceAtLeast(start + 1)

    val count = end - start
    val reds = IntArray(count)
    val greens = IntArray(count)
    val blues = IntArray(count)
    for (i in 0 until count) {
      val px = sampled[start + i]
      reds[i] = Color.red(px)
      greens[i] = Color.green(px)
      blues[i] = Color.blue(px)
    }
    reds.sort()
    greens.sort()
    blues.sort()
    val mid = count / 2
    val medianRgb = Color.rgb(reds[mid], greens[mid], blues[mid])
    val hsv = FloatArray(3)
    Color.colorToHSV(medianRgb, hsv)
    val lowLight = hsv[2] < LOW_LIGHT_VALUE_THRESHOLD

    val label = describeColor(context, medianRgb, hsv)
    return ColorSample(rgb = medianRgb, label = label, lowLight = lowLight)
  }

  internal fun describeColor(context: Context?, rgb: Int, hsv: FloatArray): String {
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2]

    if (value < 0.13f) return "Màu đen"
    if (sat < 0.12f) {
      return when {
        value >= 0.82f -> "Màu trắng"
        value >= 0.55f -> "Màu xám sáng"
        value >= 0.30f -> "Màu xám"
        else -> "Màu xám đậm"
      }
    }

    val baseName = context?.let {
      try {
        ColorNameUtils.getColorName(it, rgb)
      } catch (_: Exception) {
        null
      }
    }?.takeIf { it.isNotBlank() } ?: vietnameseHueName(hue, sat, value)

    val shade = when {
      value < 0.35f && !baseName.contains("đậm", ignoreCase = true) && !baseName.contains("nâu", ignoreCase = true) -> " đậm"
      value > 0.85f && sat < 0.45f && !baseName.contains("nhạt", ignoreCase = true) && !baseName.contains("sáng", ignoreCase = true) -> " nhạt"
      else -> ""
    }
    val prefix = if (baseName.startsWith("Màu ", ignoreCase = true)) "" else "Màu "
    return "$prefix$baseName$shade".trim()
  }

  private fun vietnameseHueName(hue: Float, sat: Float, value: Float): String {
    if (hue in 15f..42f && value < 0.55f) return "nâu"
    return when {
      hue < 12f || hue >= 345f -> if (sat < 0.45f && value > 0.72f) "hồng" else "đỏ"
      hue < 40f -> "cam"
      hue < 68f -> "vàng"
      hue < 160f -> "xanh lá"
      hue < 195f -> "xanh ngọc"
      hue < 255f -> "xanh dương"
      hue < 295f -> "tím"
      else -> "hồng"
    }
  }
}

internal class ColorTracker {
  private var candidate: String? = null
  private var consecutive = 0
  private var lastSpoken: String? = null
  private var lastSpokenAtMs = Long.MIN_VALUE
  private var lowLightWarnedAtMs = Long.MIN_VALUE

  fun observe(sample: ColorSample?, nowMs: Long): String? {
    if (sample == null) {
      candidate = null
      consecutive = 0
      return null
    }
    if (candidate != sample.label) {
      candidate = sample.label
      consecutive = 1
      return null
    }
    consecutive++
    if (consecutive < 2) return null

    if (sample.label == lastSpoken && lastSpokenAtMs != Long.MIN_VALUE &&
        nowMs - lastSpokenAtMs < REPEAT_INTERVAL_MS) {
      return null
    }
    lastSpoken = sample.label
    lastSpokenAtMs = nowMs
    if (sample.lowLight && (lowLightWarnedAtMs == Long.MIN_VALUE || nowMs - lowLightWarnedAtMs >= LOW_LIGHT_REMIND_MS)) {
      lowLightWarnedAtMs = nowMs
      return "${sample.label}. Ánh sáng yếu, hãy bật đèn flash để nhận màu chính xác hơn."
    }
    return sample.label
  }

  fun reset() {
    candidate = null
    consecutive = 0
    lastSpoken = null
    lastSpokenAtMs = Long.MIN_VALUE
    lowLightWarnedAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 2_500L
    private const val LOW_LIGHT_REMIND_MS = 12_000L
  }
}
