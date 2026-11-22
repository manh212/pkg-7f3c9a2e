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

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class LotteryRegion(val digitCount: Int) {
  MIEN_NAM(6),
  MIEN_TRUNG(6),
  MIEN_BAC(5),
}

internal data class LotteryStation(
  val displayName: String,
  val region: LotteryRegion,
  val rssSlug: String,
  val htmlSlug: String,
  val aliases: List<String>,
)

internal sealed class ParsedLotteryTicket {
  abstract fun speak(): String

  data class Traditional(
    val station: LotteryStation?,
    val rawStationName: String?,
    val drawDate: String?,
    val number: String,
  ) : ParsedLotteryTicket() {
    override fun speak(): String {
      val parts = mutableListOf<String>()
      val stationLabel = station?.displayName ?: rawStationName?.takeIf { it.isNotBlank() }
      parts += if (stationLabel != null) "Vé số $stationLabel" else "Vé số kiến thiết"
      if (!drawDate.isNullOrBlank()) parts += "ngày $drawDate"
      parts += "số: ${number.toCharArray().joinToString(", ")}"
      return parts.joinToString(", ") + "."
    }
  }

  data class Vietlott(
    val product: String,
    val drawId: String?,
    val drawDate: String?,
    val lines: List<Pair<String, List<String>>>,
  ) : ParsedLotteryTicket() {
    override fun speak(): String {
      val header = buildList {
        add("Vietlott $product")
        if (!drawId.isNullOrBlank()) add("kỳ $drawId")
        if (!drawDate.isNullOrBlank()) add("ngày $drawDate")
      }.joinToString(", ")
      val body = lines.joinToString(". ") { (label, nums) ->
        "Bộ $label: ${nums.joinToString(", ")}"
      }
      return "$header. $body."
    }
  }
}

internal sealed class LotteryRecognitionOutcome {
  data class Ticket(val ticket: ParsedLotteryTicket) : LotteryRecognitionOutcome()
  data object NotLottery : LotteryRecognitionOutcome()
  data class Error(val message: String) : LotteryRecognitionOutcome()
}

internal sealed class LotteryCheckResult {
  abstract val message: String

  data class Won(val prizes: List<String>, val specialPrize: String?, override val message: String) :
    LotteryCheckResult()

  data class NotWon(val specialPrize: String?, override val message: String) : LotteryCheckResult()

  data class NotDrawnYet(override val message: String) : LotteryCheckResult()

  data class Unavailable(override val message: String) : LotteryCheckResult()
}

internal object LotteryOnlinePrompt {
  const val PROMPT =
    "Chỉ đọc vé số Việt Nam (xổ số kiến thiết 3 miền hoặc Vietlott). " +
      "QUY TẮC BẮT BUỘC: Nếu ảnh KHÔNG PHẢI là tờ vé số, TUYỆT ĐỐI KHÔNG mô tả bất kỳ nội dung nào khác trong ảnh, chỉ trả về đúng JSON: {\"khong_phai_ve_so\":true}. " +
      "Nếu đúng là vé số nhưng ảnh quá mờ không đọc được dãy số, chỉ trả về: {\"loi\":\"Ảnh mờ, không đọc được số vé.\"}. " +
      "Nếu đọc được vé số kiến thiết truyền thống, chỉ trả về JSON ngắn gọn: " +
      "{\"loai\":\"truyen_thong\",\"dai\":\"tên tỉnh/thành phố hoặc Miền Bắc\",\"ngay\":\"DD/MM/YYYY\",\"so\":\"dãy 5 hoặc 6 chữ số\"}. " +
      "Nếu đọc được vé Vietlott (Mega 6/45, Power 6/55, Max 3D), chỉ trả về JSON ngắn gọn: " +
      "{\"loai\":\"vietlott\",\"san_pham\":\"Mega 6/45|Power 6/55|Max 3D\",\"ky\":\"mã kỳ không có dấu #\",\"ngay\":\"DD/MM/YYYY\",\"bo_so\":[{\"ky_hieu\":\"A\",\"so\":[\"01\",\"02\"]}]}. " +
      "Không giải thích, không thêm chữ nào ngoài JSON."
}

internal object LotteryOnlineResponseParser {
  val STATIONS = listOf(
    LotteryStation("Thành phố Hồ Chí Minh", LotteryRegion.MIEN_NAM, "ho-chi-minh-xshcm", "xshcm", listOf("tp hcm", "tphcm", "ho chi minh", "sai gon", "hcm")),
    LotteryStation("Đồng Tháp", LotteryRegion.MIEN_NAM, "dong-thap-xsdt", "xsdt", listOf("dong thap")),
    LotteryStation("Cà Mau", LotteryRegion.MIEN_NAM, "ca-mau-xscm", "xscm", listOf("ca mau")),
    LotteryStation("Bến Tre", LotteryRegion.MIEN_NAM, "ben-tre-xsbt", "xsbt", listOf("ben tre")),
    LotteryStation("Vũng Tàu", LotteryRegion.MIEN_NAM, "vung-tau-xsvt", "xsvt", listOf("vung tau", "ba ria vung tau")),
    LotteryStation("Bạc Liêu", LotteryRegion.MIEN_NAM, "bac-lieu-xsbl", "xsbl", listOf("bac lieu")),
    LotteryStation("Đồng Nai", LotteryRegion.MIEN_NAM, "dong-nai-xsdn", "xsdn", listOf("dong nai")),
    LotteryStation("Cần Thơ", LotteryRegion.MIEN_NAM, "can-tho-xsct", "xsct", listOf("can tho")),
    LotteryStation("Sóc Trăng", LotteryRegion.MIEN_NAM, "soc-trang-xsst", "xsst", listOf("soc trang")),
    LotteryStation("Tây Ninh", LotteryRegion.MIEN_NAM, "tay-ninh-xstn", "xstn", listOf("tay ninh")),
    LotteryStation("An Giang", LotteryRegion.MIEN_NAM, "an-giang-xsag", "xsag", listOf("an giang")),
    LotteryStation("Bình Thuận", LotteryRegion.MIEN_NAM, "binh-thuan-xsbth", "xsbth", listOf("binh thuan")),
    LotteryStation("Vĩnh Long", LotteryRegion.MIEN_NAM, "vinh-long-xsvl", "xsvl", listOf("vinh long")),
    LotteryStation("Bình Dương", LotteryRegion.MIEN_NAM, "binh-duong-xsbd", "xsbd", listOf("binh duong")),
    LotteryStation("Trà Vinh", LotteryRegion.MIEN_NAM, "tra-vinh-xstv", "xstv", listOf("tra vinh")),
    LotteryStation("Long An", LotteryRegion.MIEN_NAM, "long-an-xsla", "xsla", listOf("long an")),
    LotteryStation("Bình Phước", LotteryRegion.MIEN_NAM, "binh-phuoc-xsbp", "xsbp", listOf("binh phuoc")),
    LotteryStation("Hậu Giang", LotteryRegion.MIEN_NAM, "hau-giang-xshg", "xshg", listOf("hau giang")),
    LotteryStation("Tiền Giang", LotteryRegion.MIEN_NAM, "tien-giang-xstg", "xstg", listOf("tien giang")),
    LotteryStation("Kiên Giang", LotteryRegion.MIEN_NAM, "kien-giang-xskg", "xskg", listOf("kien giang")),
    LotteryStation("Đà Lạt", LotteryRegion.MIEN_NAM, "lam-dong-xsld", "xsld", listOf("da lat", "lam dong")),
    LotteryStation("Thừa Thiên Huế", LotteryRegion.MIEN_TRUNG, "thua-thien-hue-xstth", "xstth", listOf("thua thien hue", "hue")),
    LotteryStation("Phú Yên", LotteryRegion.MIEN_TRUNG, "phu-yen-xspy", "xspy", listOf("phu yen")),
    LotteryStation("Đắk Lắk", LotteryRegion.MIEN_TRUNG, "dak-lak-xsdlk", "xsdlk", listOf("dak lak", "dac lac")),
    LotteryStation("Quảng Nam", LotteryRegion.MIEN_TRUNG, "quang-nam-xsqnm", "xsqnm", listOf("quang nam")),
    LotteryStation("Đà Nẵng", LotteryRegion.MIEN_TRUNG, "da-nang-xsdng", "xsdng", listOf("da nang")),
    LotteryStation("Khánh Hòa", LotteryRegion.MIEN_TRUNG, "khanh-hoa-xskh", "xskh", listOf("khanh hoa")),
    LotteryStation("Bình Định", LotteryRegion.MIEN_TRUNG, "binh-dinh-xsbdi", "xsbdi", listOf("binh dinh")),
    LotteryStation("Quảng Trị", LotteryRegion.MIEN_TRUNG, "quang-tri-xsqt", "xsqt", listOf("quang tri")),
    LotteryStation("Quảng Bình", LotteryRegion.MIEN_TRUNG, "quang-binh-xsqb", "xsqb", listOf("quang binh")),
    LotteryStation("Gia Lai", LotteryRegion.MIEN_TRUNG, "gia-lai-xsgl", "xsgl", listOf("gia lai")),
    LotteryStation("Ninh Thuận", LotteryRegion.MIEN_TRUNG, "ninh-thuan-xsnt", "xsnt", listOf("ninh thuan")),
    LotteryStation("Quảng Ngãi", LotteryRegion.MIEN_TRUNG, "quang-ngai-xsqng", "xsqng", listOf("quang ngai")),
    LotteryStation("Đắk Nông", LotteryRegion.MIEN_TRUNG, "dak-nong-xsdno", "xsdno", listOf("dak nong")),
    LotteryStation("Kon Tum", LotteryRegion.MIEN_TRUNG, "kon-tum-xskt", "xskt", listOf("kon tum")),
    LotteryStation("Miền Bắc (Hà Nội)", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("thu do", "ha noi", "mien bac", "xsmb")),
    LotteryStation("Quảng Ninh", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("quang ninh")),
    LotteryStation("Bắc Ninh", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("bac ninh")),
    LotteryStation("Hải Phòng", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("hai phong")),
    LotteryStation("Nam Định", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("nam dinh")),
    LotteryStation("Thái Bình", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", "xsmb", listOf("thai binh")),
  )

  private val DATE_REGEX = Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})")
  private val DRAW_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

  fun parse(rawResponse: String): LotteryRecognitionOutcome {
    val jsonText = extractJsonObject(rawResponse) ?: return LotteryRecognitionOutcome.NotLottery
    val obj = try {
      JSONObject(jsonText)
    } catch (_: Exception) {
      return LotteryRecognitionOutcome.NotLottery
    }
    if (obj.optBoolean("khong_phai_ve_so", false)) {
      return LotteryRecognitionOutcome.NotLottery
    }
    val err = obj.optString("loi", "").trim()
    if (err.isNotEmpty()) {
      return LotteryRecognitionOutcome.Error(err)
    }
    val type = normalizeAscii(obj.optString("loai", ""))
    return when {
      type.contains("vietlott") || obj.has("bo_so") -> parseVietlottJson(obj)
      type.contains("truyen") || obj.has("so") -> parseTraditionalJson(obj)
      else -> LotteryRecognitionOutcome.NotLottery
    }
  }

  private fun parseTraditionalJson(obj: JSONObject): LotteryRecognitionOutcome {
    val digits = obj.optString("so", "").filter(Char::isDigit)
    if (digits.length !in 5..6) {
      return LotteryRecognitionOutcome.Error("Không đọc rõ dãy số trên vé. Hãy chụp lại rõ hơn.")
    }
    val rawStation = obj.optString("dai", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
    val station = rawStation?.let(::matchStation)
    val drawDate = parseDate(obj.optString("ngay", ""))
    return LotteryRecognitionOutcome.Ticket(
      ParsedLotteryTicket.Traditional(station, rawStation, drawDate, digits)
    )
  }

  private fun parseVietlottJson(obj: JSONObject): LotteryRecognitionOutcome {
    val rawProduct = normalizeAscii(obj.optString("san_pham", ""))
    val product = when {
      rawProduct.contains("6/55") || rawProduct.contains("power") -> "Power 6/55"
      rawProduct.contains("6/45") || rawProduct.contains("mega") -> "Mega 6/45"
      rawProduct.contains("3d") -> "Max 3D"
      else -> return LotteryRecognitionOutcome.NotLottery
    }
    val lines = mutableListOf<Pair<String, List<String>>>()
    val array = obj.optJSONArray("bo_so") ?: JSONArray()
    for (i in 0 until array.length()) {
      val item = array.opt(i)
      val (label, nums) = when (item) {
        is JSONObject -> {
          val lbl = item.optString("ky_hieu", ('A' + i).toString()).trim().uppercase(Locale.ROOT)
          val arr = item.optJSONArray("so")
          val list = if (arr != null) {
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx, "").filter(Char::isDigit).takeIf { it.isNotEmpty() } }
          } else {
            item.optString("so", "").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
          }
          lbl to list
        }
        is JSONArray -> {
          val list = (0 until item.length()).mapNotNull { idx -> item.optString(idx, "").filter(Char::isDigit).takeIf { it.isNotEmpty() } }
          ('A' + i).toString() to list
        }
        else -> continue
      }
      if (nums.isNotEmpty()) lines += label to nums
    }
    if (lines.isEmpty()) {
      return LotteryRecognitionOutcome.Error("Không đọc rõ các bộ số Vietlott. Hãy chụp lại rõ hơn.")
    }
    val drawId = obj.optString("ky", "").filter(Char::isDigit).takeIf { it.isNotEmpty() }
    val drawDate = parseDate(obj.optString("ngay", ""))
    return LotteryRecognitionOutcome.Ticket(
      ParsedLotteryTicket.Vietlott(product, drawId, drawDate, lines)
    )
  }

  fun matchStation(name: String): LotteryStation? {
    val norm = normalizeAscii(name)
    if (norm.isEmpty()) return null
    return STATIONS.firstOrNull { station ->
      val disp = normalizeAscii(station.displayName)
      norm == disp || norm.contains(disp) || station.aliases.any { alias ->
        " $alias " in " $norm " || norm == alias
      }
    }
  }

  internal fun parseDate(raw: String): String? {
    val match = DATE_REGEX.find(raw) ?: return null
    val day = match.groupValues[1].padStart(2, '0')
    val month = match.groupValues[2].padStart(2, '0')
    val yearRaw = match.groupValues[3]
    val year = if (yearRaw.length == 2) "20$yearRaw" else yearRaw
    val formatted = "$day/$month/$year"
    return try {
      LocalDate.parse(formatted, DRAW_DATE)
      formatted
    } catch (_: DateTimeParseException) {
      null
    }
  }

  private fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return raw.substring(start, end + 1)
  }

  internal fun normalizeAscii(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return decomposed
      .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
      .replace('đ', 'd')
      .replace(Regex("[^a-z0-9/\\s]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }
}

internal object LotteryResultChecker {
  private const val MAX_RESPONSE_BYTES = 512L * 1024L
  private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Meridian/1.1"
  private val client = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  private val rssItems = Regex("<item(?:\\s[^>]*)?>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
  private val publishedDate = Regex("/ngay-(\\d{1,2})-(\\d{1,2})-(\\d{4})(?:/|$)")
  private val prizesOrder = listOf(
    "ĐB" to "Giải Đặc biệt",
    "1" to "Giải Nhất",
    "2" to "Giải Nhì",
    "3" to "Giải Ba",
    "4" to "Giải Tư",
    "5" to "Giải Năm",
    "6" to "Giải Sáu",
    "7" to "Giải Bảy",
    "8" to "Giải Tám",
  )

  fun checkOnline(ticket: ParsedLotteryTicket): LotteryCheckResult = when (ticket) {
    is ParsedLotteryTicket.Traditional -> checkTraditional(ticket)
    is ParsedLotteryTicket.Vietlott -> checkVietlott(ticket)
  }

  private fun checkTraditional(ticket: ParsedLotteryTicket.Traditional): LotteryCheckResult {
    val station = ticket.station
      ?: return LotteryCheckResult.Unavailable("Không xác định được đài mở thưởng trên vé để đối chiếu.")
    val drawDate = ticket.drawDate
      ?: return LotteryCheckResult.Unavailable("Không xác định được ngày mở thưởng trên vé để đối chiếu.")

    val rssUrl = "https://xskt.com.vn/rss-feed/${station.rssSlug}.rss"
    val rssXml = fetchText(rssUrl)
    if (rssXml != null) {
      val rssResult = evaluateRssXml(rssXml, drawDate, ticket.number, station)
      if (rssResult != null) return rssResult
    }

      val parts = drawDate.split("/")
    if (parts.size == 3) {
      val day = parts[0].toIntOrNull()
      val month = parts[1].toIntOrNull()
      val year = parts[2].toIntOrNull()
      if (day != null && month != null && year != null) {
        val htmlUrl = "https://xskt.com.vn/${station.htmlSlug}/ngay-$day-$month-$year"
        val html = fetchText(htmlUrl)
        if (html != null) {
          val htmlResult = evaluateTraditionalHtml(html, drawDate, ticket.number, station)
          if (htmlResult != null) return htmlResult
        }
      }
    }

    if (rssXml == null) {
      return LotteryCheckResult.Unavailable("Không tải được dữ liệu kết quả xổ số. Hãy kiểm tra kết nối mạng và thử lại.")
    }
    return LotteryCheckResult.NotDrawnYet(
      "Chưa có kết quả mở thưởng cho đài ${station.displayName} ngày $drawDate."
    )
  }

  private fun checkVietlott(ticket: ParsedLotteryTicket.Vietlott): LotteryCheckResult {
    val drawDate = ticket.drawDate
      ?: return LotteryCheckResult.Unavailable("Không xác định được ngày quay thưởng của vé Vietlott.")
    val path = when (ticket.product) {
      "Mega 6/45" -> "xs-mega-645"
      "Power 6/55" -> "xs-power-655"
      else -> return LotteryCheckResult.Unavailable("Chưa hỗ trợ tự động đối chiếu loại vé ${ticket.product}.")
    }
    val html = fetchText("https://xskt.com.vn/$path")
      ?: return LotteryCheckResult.Unavailable("Không tải được kết quả Vietlott. Hãy kiểm tra mạng và thử lại.")
    return evaluateVietlottHtml(html, ticket, drawDate)
  }

  private fun fetchText(url: String): String? = try {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      response.body()?.let(::readLimited)
    }
  } catch (_: Exception) {
    null
  }

  private fun readLimited(body: ResponseBody): String? {
    val source = body.source()
    if (source.request(MAX_RESPONSE_BYTES + 1)) return null
    return source.readUtf8()
  }

  internal fun evaluateRssXml(
    rssXml: String,
    drawDate: String,
    ticketNumber: String,
    station: LotteryStation? = null,
  ): LotteryCheckResult? {
    val item = rssItems.findAll(rssXml).map { it.groupValues[1] }.firstOrNull { chunk ->
      val link = extractTag(chunk, "link") ?: return@firstOrNull false
      val date = publishedDate.find(link)?.groupValues ?: return@firstOrNull false
      "${date[1].padStart(2, '0')}/${date[2].padStart(2, '0')}/${date[3]}" == drawDate
    } ?: return null
    val description = extractTag(item, "description") ?: return null
    val parsedPrizes = parsePrizesFromRss(description, ticketNumber.length)
    if (parsedPrizes.isEmpty()) return null
    return buildTraditionalResult(parsedPrizes, ticketNumber, drawDate, station)
  }

  internal fun evaluateTraditionalHtml(
    html: String,
    drawDate: String,
    ticketNumber: String,
    station: LotteryStation,
  ): LotteryCheckResult? {
    val parsed = parsePrizesFromHtml(html, ticketNumber.length)
    if (parsed.isEmpty()) return null
    return buildTraditionalResult(parsed, ticketNumber, drawDate, station)
  }

  internal fun evaluateVietlottHtml(
    html: String,
    ticket: ParsedLotteryTicket.Vietlott,
    drawDate: String,
  ): LotteryCheckResult {
    val dateShort = drawDate.substring(0, 5)
    val sectionRegex = Regex(
      "(?:$drawDate|$dateShort).*?(?:class=\"[^\"]*mega[^\"]*\"|class=\"[^\"]*power[^\"]*\"|<tr>)(.*?)(?:</table>|</div>)",
      setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    val match = sectionRegex.find(html)
      ?: return LotteryCheckResult.NotDrawnYet("Chưa tìm thấy kết quả ${ticket.product} ngày $drawDate.")
    val nums = Regex("\\b(0[1-9]|[1-5]\\d)\\b").findAll(match.groupValues[1])
      .map { it.groupValues[1] }.distinct().toList()
    val isPower = ticket.product == "Power 6/55"
    val needed = if (isPower) 7 else 6
    if (nums.size < needed) {
      return LotteryCheckResult.NotDrawnYet("Chưa có đầy đủ dãy số ${ticket.product} ngày $drawDate.")
    }
    val mainWinning = nums.take(6).toSet()
    val bonus = if (isPower) nums[6] else null
    val wins = mutableListOf<String>()
    for ((label, lineNums) in ticket.lines) {
      val lineSet = lineNums.map { it.padStart(2, '0') }.toSet()
      val matched = lineSet.count { it in mainWinning }
      val hasBonus = bonus != null && bonus in lineSet
      val prize = when {
        matched == 6 -> if (isPower) "Jackpot 1" else "Jackpot"
        isPower && matched == 5 && hasBonus -> "Jackpot 2"
        matched == 5 -> "Giải Nhất"
        matched == 4 -> "Giải Nhì"
        matched == 3 -> "Giải Ba"
        else -> null
      }
      if (prize != null) wins += "Bộ $label trúng $prize"
    }
    val winningSummary = if (bonus != null) {
      "${nums.take(6).joinToString(" - ")} | số đặc biệt $bonus"
    } else {
      nums.take(6).joinToString(" - ")
    }
    return if (wins.isNotEmpty()) {
      LotteryCheckResult.Won(
        wins,
        winningSummary,
        "Chúc mừng! Vé ${ticket.product} ngày $drawDate: ${wins.joinToString(", ")}. Kết quả kỳ này: $winningSummary. Hãy đối chiếu lại với kết quả chính thức.",
      )
    } else {
      LotteryCheckResult.NotWon(
        winningSummary,
        "Rất tiếc, vé ${ticket.product} ngày $drawDate không trúng giải nào. Bộ số trúng thưởng: $winningSummary.",
      )
    }
  }

  private fun buildTraditionalResult(
    prizeMap: Map<String, List<String>>,
    ticketNumber: String,
    drawDate: String,
    station: LotteryStation?,
  ): LotteryCheckResult {
    val matches = evaluateMatches(prizeMap, ticketNumber, station?.region)
    val specialPrize = prizeMap["ĐB"]?.firstOrNull()
    val stationText = station?.displayName?.let { "đài $it " }.orEmpty()
    return if (matches.isNotEmpty()) {
      LotteryCheckResult.Won(
        matches,
        specialPrize,
        "Chúc mừng! Vé $stationText ngày $drawDate khớp ${matches.joinToString(", ")}. Hãy đối chiếu lại với bảng kết quả chính thức.",
      )
    } else {
      val dbSuffix = specialPrize?.let { " Giải Đặc biệt ngày này là ${it.toCharArray().joinToString(", ")}." }.orEmpty()
      LotteryCheckResult.NotWon(
        specialPrize,
        "Rất tiếc, vé $stationText ngày $drawDate không trúng giải nào.$dbSuffix",
      )
    }
  }

  private fun extractTag(item: String, tag: String): String? =
    Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
      .find(item)?.groupValues?.get(1)?.trim()?.removePrefix("<![CDATA[")?.removeSuffix("]]>")

  internal fun parsePrizesFromRss(description: String, ticketLength: Int): Map<String, List<String>> {
    val expectedLengths = expectedLengthsFor(ticketLength) ?: return emptyMap()
    val text = description.replace(Regex("(?im)<br\\s*/?>"), "\n")
      .replace("\r\n", "\n")
      .replace(Regex("(?m)^(7\\s*:\\s*\\d{3})8\\s*:"), "$1\n8:")
    val result = linkedMapOf<String, List<String>>()
    for ((prize, length) in prizesOrder.zip(expectedLengths)) {
      val code = prize.first
      val raw = Regex("(?m)^ *$code *: *([^\\n]+)").find(text)?.groupValues?.get(1) ?: continue
      val values = raw.trim().split(Regex("\\s*-\\s*|\\s+")).filter { it.isNotEmpty() }
      if (values.isNotEmpty() && values.all { it.length == length && it.all(Char::isDigit) }) {
        result[code] = values
      }
    }
    return result
  }

  private fun parsePrizesFromHtml(html: String, ticketLength: Int): Map<String, List<String>> {
    val expectedLengths = expectedLengthsFor(ticketLength) ?: return emptyMap()
    val result = linkedMapOf<String, List<String>>()
    val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val tagRegex = Regex("<[^>]+>")
    for (rowMatch in rowRegex.findAll(html)) {
      val rowText = tagRegex.replace(rowMatch.groupValues[1], " ").replace("&nbsp;", " ").trim()
      if (rowText.isEmpty()) continue
      for ((prize, length) in prizesOrder.zip(expectedLengths)) {
        val code = prize.first
        val labelMatch = when (code) {
          "ĐB" -> Regex("^(?:ĐB|G\\.ĐB|Đặc\\s*biệt)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rowText)
          else -> Regex("^(?:G\\.?\\s*$code|Giải\\s*$code|$code)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rowText)
        }
        if (labelMatch && code !in result) {
          val nums = Regex("(?<!\\d)\\d{$length}(?!\\d)").findAll(rowText).map { it.value }.toList()
          if (nums.isNotEmpty()) result[code] = nums
        }
      }
    }
    return result
  }

  internal fun evaluateMatches(
    prizeMap: Map<String, List<String>>,
    ticketNumber: String,
    region: LotteryRegion?,
  ): List<String> {
    val matches = mutableListOf<String>()
    for ((code, name) in prizesOrder) {
      val values = prizeMap[code] ?: continue
      if (values.any { ticketNumber.endsWith(it) }) {
        matches += name
      }
    }
    val special = prizeMap["ĐB"]?.firstOrNull()
    val isSixDigitRegion = region == LotteryRegion.MIEN_NAM || region == LotteryRegion.MIEN_TRUNG ||
      (region == null && ticketNumber.length == 6)
    if (isSixDigitRegion && special != null && special.length == 6 && ticketNumber.length == 6 &&
        "Giải Đặc biệt" !in matches) {
      if (ticketNumber.substring(1) == special.substring(1) && ticketNumber[0] != special[0]) {
        matches += "Giải Phụ Đặc biệt"
      } else if (ticketNumber[0] == special[0]) {
        val diffCount = (1 until 6).count { ticketNumber[it] != special[it] }
        if (diffCount == 1) {
          matches += "Giải Khuyến khích"
        }
      }
    }
    return matches
  }

  internal fun checkPrizes(description: String, ticketNumber: String): List<String> {
    val map = parsePrizesFromRss(description, ticketNumber.length)
    return evaluateMatches(map, ticketNumber, null)
  }

  private fun expectedLengthsFor(ticketLength: Int): List<Int>? = when (ticketLength) {
    5 -> listOf(5, 5, 5, 5, 4, 4, 3, 2)
    6 -> listOf(6, 5, 5, 5, 5, 4, 4, 3, 2)
    else -> null
  }
}
