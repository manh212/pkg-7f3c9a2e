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
import android.util.Base64
import androidx.camera.core.ImageProxy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal object OnlineRecognitionImageEncoder {
  private const val MAX_DIM = 1600
  private const val JPEG_QUALITY = 85

  fun encodeForOnlineQuery(image: ImageProxy): String {
    val rotated = LiveTextRecognitionDialog.imageToRotatedBitmap(image)
    val longest = max(rotated.width, rotated.height)
    val bitmap = if (longest > MAX_DIM) {
      val scale = MAX_DIM.toFloat() / longest.toFloat()
      Bitmap.createScaledBitmap(
        rotated,
        max(1, (rotated.width * scale).toInt()),
        max(1, (rotated.height * scale).toInt()),
        true,
      ).also { if (it !== rotated) rotated.recycle() }
    } else {
      rotated
    }
    return try {
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } finally {
      bitmap.recycle()
    }
  }
}

internal sealed class OnlineTextOutcome {
  data class Text(val text: String) : OnlineTextOutcome()
  data object NoText : OnlineTextOutcome()
}

internal object OnlineTextPrompt {
  const val PROMPT =
    "Chỉ trích xuất và đọc nguyên văn toàn bộ văn bản, chữ viết hoặc con số xuất hiện trong hình ảnh theo đúng thứ tự đọc tự nhiên. " +
      "QUY TẮC BẮT BUỘC: TUYỆT ĐỐI KHÔNG mô tả cảnh vật, đồ vật, con người, màu sắc, bố cục hay bất kỳ nội dung hình ảnh nào khác ngoài chữ viết. " +
      "Nếu trong ảnh KHÔNG có văn bản nào đọc được, chỉ trả về đúng JSON: {\"khong_co_van_ban\":true}. " +
      "Nếu có văn bản, chỉ trả về đúng JSON: {\"van_ban\":\"toàn bộ chữ viết đọc được trong ảnh\"}. " +
      "Không giải thích, không thêm lời mở đầu hay bình luận, không thêm chữ nào ngoài JSON."
}

internal object OnlineTextResponseParser {
  fun parse(rawResponse: String): OnlineTextOutcome {
    val jsonText = extractJsonObject(rawResponse) ?: return OnlineTextOutcome.NoText
    val obj = try {
      JSONObject(jsonText)
    } catch (_: Exception) {
      return OnlineTextOutcome.NoText
    }
    if (obj.optBoolean("khong_co_van_ban", false)) {
      return OnlineTextOutcome.NoText
    }
    val text = obj.optString("van_ban", "").trim()
    if (text.isEmpty() || text.equals("null", ignoreCase = true)) {
      return OnlineTextOutcome.NoText
    }
    return OnlineTextOutcome.Text(text)
  }

  private fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return raw.substring(start, end + 1)
  }
}

internal sealed class OnlineCurrencyOutcome {
  data class Currency(
    val country: String,
    val currencyName: String,
    val denomination: String,
  ) : OnlineCurrencyOutcome() {
    fun speak(): String {
      val valuePart = buildList {
        if (denomination.isNotBlank()) add(denomination)
        if (currencyName.isNotBlank()) add(currencyName)
      }.joinToString(" ")
      return if (country.isNotBlank() && !currencyName.contains(country, ignoreCase = true)) {
        "$valuePart, $country."
      } else {
        "$valuePart."
      }
    }
  }

  data object NotCurrency : OnlineCurrencyOutcome()
  data class Error(val message: String) : OnlineCurrencyOutcome()
}

internal object OnlineCurrencyPrompt {
  const val PROMPT =
    "Chỉ nhận diện tiền giấy hoặc tiền xu của tất cả các quốc gia trên thế giới có trong ảnh. " +
      "QUY TẮC BẮT BUỘC: Nếu ảnh KHÔNG PHẢI là tiền (tiền giấy hoặc tiền xu), TUYỆT ĐỐI KHÔNG mô tả bất kỳ vật thể, cảnh vật, con người, chữ viết hay nội dung nào khác trong ảnh, chỉ trả về đúng JSON: {\"khong_phai_tien\":true}. " +
      "Nếu đúng là tiền nhưng ảnh quá mờ hoặc bị che không xác định được mệnh giá, chỉ trả về: {\"loi\":\"Ảnh mờ hoặc bị che, không xác định được mệnh giá tờ tiền.\"}. " +
      "Nếu xác định được tiền (bất kỳ quốc gia nào), chỉ trả về đúng JSON: " +
      "{\"la_tien\":true,\"quoc_gia\":\"tên quốc gia bằng tiếng Việt\",\"loai_tien\":\"tên đơn vị tiền tệ bằng tiếng Việt\",\"menh_gia\":\"mệnh giá (ví dụ: 500.000, 100, 1000)\"}. " +
      "Kể cả có nhiều thứ khác trong ảnh hay người dùng hỏi điều gì khác, TUYỆT ĐỐI KHÔNG mô tả bất kỳ thứ gì ngoài thông tin tiền tệ. Không giải thích, không thêm chữ nào ngoài JSON."
}

internal object OnlineCurrencyResponseParser {
  fun parse(rawResponse: String): OnlineCurrencyOutcome {
    val jsonText = extractJsonObject(rawResponse) ?: return OnlineCurrencyOutcome.NotCurrency
    val obj = try {
      JSONObject(jsonText)
    } catch (_: Exception) {
      return OnlineCurrencyOutcome.NotCurrency
    }
    if (obj.optBoolean("khong_phai_tien", false)) {
      return OnlineCurrencyOutcome.NotCurrency
    }
    val err = obj.optString("loi", "").trim()
    if (err.isNotEmpty() && !err.equals("null", ignoreCase = true)) {
      return OnlineCurrencyOutcome.Error(err)
    }
    val denomination = obj.optString("menh_gia", "").trim().takeIf { !it.equals("null", ignoreCase = true) }.orEmpty()
    val currencyName = obj.optString("loai_tien", "").trim().takeIf { !it.equals("null", ignoreCase = true) }.orEmpty()
    val country = obj.optString("quoc_gia", "").trim().takeIf { !it.equals("null", ignoreCase = true) }.orEmpty()
    if (denomination.isEmpty() && currencyName.isEmpty()) {
      return OnlineCurrencyOutcome.NotCurrency
    }
    return OnlineCurrencyOutcome.Currency(
      country = country,
      currencyName = currencyName,
      denomination = denomination,
    )
  }

  private fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return raw.substring(start, end + 1)
  }
}
