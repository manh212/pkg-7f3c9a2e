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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.EnumMap
import java.util.Locale

internal data class QrBarcodeResult(
  val rawValue: String,
  val format: BarcodeFormat,
  val spokenText: String,
)

internal class QrBarcodeRecognizer {
  private val reader = MultiFormatReader().apply {
    val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
    hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
      BarcodeFormat.QR_CODE,
      BarcodeFormat.EAN_13,
      BarcodeFormat.EAN_8,
      BarcodeFormat.UPC_A,
      BarcodeFormat.UPC_E,
      BarcodeFormat.CODE_128,
      BarcodeFormat.CODE_39,
      BarcodeFormat.DATA_MATRIX,
      BarcodeFormat.PDF_417,
      BarcodeFormat.AZTEC,
    )
    hints[DecodeHintType.TRY_HARDER] = true
    hints[DecodeHintType.ALSO_INVERTED] = true
    hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
    setHints(hints)
  }

  fun decode(bitmap: Bitmap): QrBarcodeResult? {
    val centered = if (bitmap.width >= 480 && bitmap.height >= 480) decodeRegion(bitmap, true) else null
    val result = centered ?: decodeRegion(bitmap, false) ?: return null
    val raw = result.text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return QrBarcodeResult(
      rawValue = raw,
      format = result.barcodeFormat,
      spokenText = QrContentFormatter.format(raw, result.barcodeFormat),
    )
  }

  private fun decodeRegion(bitmap: Bitmap, centerCrop: Boolean): Result? {
    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    if (srcWidth < 32 || srcHeight < 32) return null

    val (left, top, width, height) = if (centerCrop && srcWidth >= 480 && srcHeight >= 480) {
      val cropW = (srcWidth * 0.72f).toInt()
      val cropH = (srcHeight * 0.72f).toInt()
      intArrayOf((srcWidth - cropW) / 2, (srcHeight - cropH) / 2, cropW, cropH)
    } else {
      intArrayOf(0, 0, srcWidth, srcHeight)
    }

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    return tryDecode(BinaryBitmap(HybridBinarizer(source)))
      ?: tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source)))
  }

  private fun tryDecode(binary: BinaryBitmap): Result? {
    return try {
      reader.decodeWithState(binary)
    } catch (_: NotFoundException) {
      null
    } catch (_: Exception) {
      null
    } finally {
      reader.reset()
    }
  }
}

internal class QrBarcodeTracker {
  private var lastValue: String? = null
  private var lastSpokenAtMs = Long.MIN_VALUE

  fun observe(result: QrBarcodeResult?, nowMs: Long): String? {
    if (result == null) return null
    if (result.rawValue == lastValue && lastSpokenAtMs != Long.MIN_VALUE &&
        nowMs - lastSpokenAtMs < REPEAT_INTERVAL_MS) {
      return null
    }
    lastValue = result.rawValue
    lastSpokenAtMs = nowMs
    return result.spokenText
  }

  fun reset() {
    lastValue = null
    lastSpokenAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 4_500L
  }
}

internal object QrContentFormatter {
  private val VIETQR_BANKS = mapOf(
    "970436" to "Vietcombank",
    "970415" to "VietinBank",
    "970418" to "BIDV",
    "970405" to "Agribank",
    "970407" to "Techcombank",
    "970422" to "MB Bank",
    "970416" to "ACB",
    "970432" to "VPBank",
    "970423" to "TPBank",
    "970403" to "Sacombank",
    "970437" to "HDBank",
    "970441" to "VIB",
    "970443" to "SHB",
    "970426" to "MSB",
    "970448" to "OCB",
    "970431" to "Eximbank",
    "970428" to "Nam A Bank",
    "970429" to "SCB",
    "970454" to "BVBank",
  )

  fun format(raw: String, format: BarcodeFormat): String {
    if (format == BarcodeFormat.QR_CODE) {
      parseVietQr(raw)?.let { return it }
      parseVietnameseCccd(raw)?.let { return it }
      parseWifi(raw)?.let { return it }
      parseUrl(raw)?.let { return it }
      parsePhoneOrEmail(raw)?.let { return it }
      return "Mã QR: ${raw.take(260)}"
    }
    if (format == BarcodeFormat.EAN_13 || format == BarcodeFormat.EAN_8 ||
        format == BarcodeFormat.UPC_A || format == BarcodeFormat.UPC_E) {
      val origin = if (format == BarcodeFormat.EAN_13 && raw.startsWith("893")) " có tiền tố GS1 Việt Nam" else ""
      return "Mã vạch$origin: ${spaceDigits(raw)}"
    }
    return "Mã ${format.name}: ${raw.take(200)}"
  }

  internal fun parseVietQr(raw: String): String? {
    if (!raw.startsWith("000201") || !hasValidChecksum(raw)) return null
    val root = parseEmvTlv(raw)
    val merchant = root["38"] ?: return null
    val merchantTlv = parseEmvTlv(merchant)
    val guid = merchantTlv["00"]?.uppercase(Locale.ROOT).orEmpty()
    if (guid != "A000000727") return null
    val subAccount = merchantTlv["01"]?.let { parseEmvTlv(it) }.orEmpty()
    val bin = subAccount["00"]
    val account = subAccount["01"]?.takeIf { it.isNotBlank() } ?: return null
    val bankName = bin?.let { VIETQR_BANKS[it] } ?: bin?.let { "mã ngân hàng $it" }
    val amount = root["54"]?.takeIf { root["53"] == "704" }?.toBigDecimalOrNull()
      ?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }
    val extra = root["62"]?.let { parseEmvTlv(it) }.orEmpty()
    val note = extra["08"]

    val parts = mutableListOf("Mã VietQR chuyển khoản")
    if (!bankName.isNullOrBlank()) parts += "ngân hàng $bankName"
    parts += "số tài khoản ${spaceDigits(account)}"
    if (amount != null) {
      val formatted = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).apply {
        roundingMode = RoundingMode.UNNECESSARY
      }.format(amount)
      parts += "số tiền $formatted đồng"
    }
    if (!note.isNullOrBlank()) parts += "nội dung $note"
    return parts.joinToString(", ")
  }

  internal fun parseVietnameseCccd(raw: String): String? {
    val parts = raw.split("|")
    if (parts.size < 6) return null
    val cccd = parts[0].trim()
    if (!cccd.matches(Regex("\\d{12}"))) return null
    val fullName = parts[2].trim()
    val dobRaw = parts[3].trim()
    val gender = parts[4].trim()
    val address = parts[5].trim()
    val dob = if (dobRaw.matches(Regex("\\d{8}"))) {
      "${dobRaw.substring(0, 2)}/${dobRaw.substring(2, 4)}/${dobRaw.substring(4, 8)}"
    } else dobRaw
    val out = mutableListOf("Căn cước công dân: $fullName")
    if (dob.isNotEmpty()) out += "sinh ngày $dob"
    if (gender.isNotEmpty()) out += "giới tính $gender"
    out += "số ${spaceDigits(cccd)}"
    if (address.isNotEmpty()) out += "thường trú $address"
    return out.joinToString(", ")
  }

  internal fun parseWifi(raw: String): String? {
    if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
    val wifi = ResultParser.parseResult(Result(raw, null, null, BarcodeFormat.QR_CODE))
      as? WifiParsedResult ?: return null
    return if (!wifi.password.isNullOrEmpty()) {
      "Mã Wi-Fi, tên mạng ${wifi.ssid}, mật khẩu ${wifi.password}"
    } else {
      "Mã Wi-Fi, tên mạng ${wifi.ssid}, không mật khẩu"
    }
  }

  private fun parseUrl(raw: String): String? {
    val lower = raw.lowercase(Locale.ROOT)
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    val host = raw.substringAfter("://").substringBefore("/").substringBefore("?")
    return if (host.isNotBlank()) "Liên kết web: $host" else "Liên kết web: ${raw.take(120)}"
  }

  private fun parsePhoneOrEmail(raw: String): String? {
    val lower = raw.lowercase(Locale.ROOT)
    return when {
      lower.startsWith("tel:") -> "Số điện thoại: ${spaceDigits(raw.substring(4))}"
      lower.startsWith("mailto:") -> "Địa chỉ email: ${raw.substring(7).substringBefore("?")}"
      else -> null
    }
  }

  private fun hasValidChecksum(data: String): Boolean {
    if (data.length < 8 || !data.dropLast(4).endsWith("6304")) return false
    val expected = data.takeLast(4).toIntOrNull(16) ?: return false
    var checksum = 0xffff
    for (byte in data.dropLast(4).toByteArray(Charsets.UTF_8)) {
      checksum = checksum xor ((byte.toInt() and 0xff) shl 8)
      repeat(8) {
        checksum = if (checksum and 0x8000 != 0) (checksum shl 1) xor 0x1021 else checksum shl 1
        checksum = checksum and 0xffff
      }
    }
    return checksum == expected
  }

  private fun parseEmvTlv(data: String): Map<String, String> {
    val map = linkedMapOf<String, String>()
    var offset = 0
    while (offset < data.length) {
      if (offset + 4 > data.length) return emptyMap()
      val tag = data.substring(offset, offset + 2)
      val length = data.substring(offset + 2, offset + 4).toIntOrNull() ?: return emptyMap()
      val end = offset + 4 + length
      if (length <= 0 || end > data.length || tag in map) return emptyMap()
      map[tag] = data.substring(offset + 4, end)
      offset = end
    }
    return map
  }

  private fun spaceDigits(text: String): String =
    if (text.all { it.isDigit() } && text.length >= 6) text.chunked(3).joinToString(" ") else text
}
