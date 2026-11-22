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
import okhttp3.ResponseBody
import okhttp3.Request
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
  val aliases: List<String>,
)

internal sealed class ParsedLotteryTicket {
  abstract val dedupeKey: String
  abstract fun speak(): String

  data class Traditional(
    val station: LotteryStation?,
    val drawDate: String?,
    val number: String,
  ) : ParsedLotteryTicket() {
    override val dedupeKey: String
      get() = "trad:${station?.displayName ?: "unknown"}:${drawDate ?: ""}:$number"

    override fun speak(): String {
      val parts = mutableListOf<String>()
      parts += if (station != null) "Vé số ${station.displayName}" else "Vé số kiến thiết"
      if (!drawDate.isNullOrBlank()) parts += "ngày $drawDate"
      parts += "số dự thưởng: ${number.toCharArray().joinToString(", ")}"
      return parts.joinToString(", ")
    }
  }

  data class Vietlott(
    val product: String,
    val drawId: String?,
    val drawDate: String?,
    val lines: List<Pair<String, List<String>>>,
  ) : ParsedLotteryTicket() {
    override val dedupeKey: String
      get() = "vietlott:$product:${drawId ?: ""}:${drawDate ?: ""}:${lines.joinToString("|") { "${it.first}:${it.second.joinToString("-")}" }}"

    override fun speak(): String {
      val header = buildList {
        add("Vé Vietlott $product")
        if (!drawId.isNullOrBlank()) add("kỳ $drawId")
        if (!drawDate.isNullOrBlank()) add("ngày $drawDate")
      }.joinToString(", ")
      val body = lines.joinToString(". ") { (label, nums) ->
        "Bộ $label: ${nums.joinToString(", ")}"
      }
      return "$header. $body"
    }
  }
}

internal object LotteryTicketParser {
  private val STATIONS = listOf(
    LotteryStation("Thành phố Hồ Chí Minh", LotteryRegion.MIEN_NAM, "ho-chi-minh-xshcm", listOf("tp hcm", "tphcm", "ho chi minh", "sai gon")),
    LotteryStation("Đồng Tháp", LotteryRegion.MIEN_NAM, "dong-thap-xsdt", listOf("dong thap")),
    LotteryStation("Cà Mau", LotteryRegion.MIEN_NAM, "ca-mau-xscm", listOf("ca mau")),
    LotteryStation("Bến Tre", LotteryRegion.MIEN_NAM, "ben-tre-xsbt", listOf("ben tre")),
    LotteryStation("Vũng Tàu", LotteryRegion.MIEN_NAM, "vung-tau-xsvt", listOf("vung tau", "ba ria vung tau")),
    LotteryStation("Bạc Liêu", LotteryRegion.MIEN_NAM, "bac-lieu-xsbl", listOf("bac lieu")),
    LotteryStation("Đồng Nai", LotteryRegion.MIEN_NAM, "dong-nai-xsdn", listOf("dong nai")),
    LotteryStation("Cần Thơ", LotteryRegion.MIEN_NAM, "can-tho-xsct", listOf("can tho")),
    LotteryStation("Sóc Trăng", LotteryRegion.MIEN_NAM, "soc-trang-xsst", listOf("soc trang")),
    LotteryStation("Tây Ninh", LotteryRegion.MIEN_NAM, "tay-ninh-xstn", listOf("tay ninh")),
    LotteryStation("An Giang", LotteryRegion.MIEN_NAM, "an-giang-xsag", listOf("an giang")),
    LotteryStation("Bình Thuận", LotteryRegion.MIEN_NAM, "binh-thuan-xsbth", listOf("binh thuan")),
    LotteryStation("Vĩnh Long", LotteryRegion.MIEN_NAM, "vinh-long-xsvl", listOf("vinh long")),
    LotteryStation("Bình Dương", LotteryRegion.MIEN_NAM, "binh-duong-xsbd", listOf("binh duong")),
    LotteryStation("Trà Vinh", LotteryRegion.MIEN_NAM, "tra-vinh-xstv", listOf("tra vinh")),
    LotteryStation("Long An", LotteryRegion.MIEN_NAM, "long-an-xsla", listOf("long an")),
    LotteryStation("Bình Phước", LotteryRegion.MIEN_NAM, "binh-phuoc-xsbp", listOf("binh phuoc")),
    LotteryStation("Hậu Giang", LotteryRegion.MIEN_NAM, "hau-giang-xshg", listOf("hau giang")),
    LotteryStation("Tiền Giang", LotteryRegion.MIEN_NAM, "tien-giang-xstg", listOf("tien giang")),
    LotteryStation("Kiên Giang", LotteryRegion.MIEN_NAM, "kien-giang-xskg", listOf("kien giang")),
    LotteryStation("Đà Lạt", LotteryRegion.MIEN_NAM, "lam-dong-xsld", listOf("da lat", "lam dong")),
    LotteryStation("Thừa Thiên Huế", LotteryRegion.MIEN_TRUNG, "thua-thien-hue-xstth", listOf("thua thien hue", "hue")),
    LotteryStation("Phú Yên", LotteryRegion.MIEN_TRUNG, "phu-yen-xspy", listOf("phu yen")),
    LotteryStation("Đắk Lắk", LotteryRegion.MIEN_TRUNG, "dak-lak-xsdlk", listOf("dak lak", "dac lac")),
    LotteryStation("Quảng Nam", LotteryRegion.MIEN_TRUNG, "quang-nam-xsqnm", listOf("quang nam")),
    LotteryStation("Đà Nẵng", LotteryRegion.MIEN_TRUNG, "da-nang-xsdng", listOf("da nang")),
    LotteryStation("Khánh Hòa", LotteryRegion.MIEN_TRUNG, "khanh-hoa-xskh", listOf("khanh hoa")),
    LotteryStation("Bình Định", LotteryRegion.MIEN_TRUNG, "binh-dinh-xsbdi", listOf("binh dinh")),
    LotteryStation("Quảng Trị", LotteryRegion.MIEN_TRUNG, "quang-tri-xsqt", listOf("quang tri")),
    LotteryStation("Quảng Bình", LotteryRegion.MIEN_TRUNG, "quang-binh-xsqb", listOf("quang binh")),
    LotteryStation("Gia Lai", LotteryRegion.MIEN_TRUNG, "gia-lai-xsgl", listOf("gia lai")),
    LotteryStation("Ninh Thuận", LotteryRegion.MIEN_TRUNG, "ninh-thuan-xsnt", listOf("ninh thuan")),
    LotteryStation("Quảng Ngãi", LotteryRegion.MIEN_TRUNG, "quang-ngai-xsqng", listOf("quang ngai")),
    LotteryStation("Đắk Nông", LotteryRegion.MIEN_TRUNG, "dak-nong-xsdno", listOf("dak nong")),
    LotteryStation("Kon Tum", LotteryRegion.MIEN_TRUNG, "kon-tum-xskt", listOf("kon tum")),
    LotteryStation("Miền Bắc (Hà Nội)", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("thu do", "ha noi", "mien bac")),
    LotteryStation("Quảng Ninh", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("quang ninh")),
    LotteryStation("Bắc Ninh", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("bac ninh")),
    LotteryStation("Hải Phòng", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("hai phong")),
    LotteryStation("Nam Định", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("nam dinh")),
    LotteryStation("Thái Bình", LotteryRegion.MIEN_BAC, "mien-bac-xsmb", listOf("thai binh")),
  )

  private val DATE_REGEX = Regex("\\b(0?[1-9]|[12]\\d|3[01])[/.\\-](0?[1-9]|1[0-2])[/.\\-](20\\d{2}|\\d{2})\\b")
  private val VIETLOTT_LINE_REGEX = Regex("(?mi)^[ \t]*([A-F])[.:]?[ \t]+([0-9 \t-]+)$")
  private val DRAW_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
  private val VIETLOTT_DRAW_REGEX = Regex("#\\s*([0-9]{1,8})(?![0-9])")

  fun parse(ocrText: String): ParsedLotteryTicket? {
    if (ocrText.isBlank()) return null
    val normalized = normalizeAscii(ocrText)
    if (listOf("vietlott", "mega", "power", "6/45", "6/55", "max 3d").any { " $it " in " $normalized " }) {
      return parseVietlott(ocrText, normalized)
    }
    return parseTraditional(ocrText, normalized)
  }

  private fun parseVietlott(raw: String, normalized: String): ParsedLotteryTicket.Vietlott? {
    val product = when {
      normalized.contains("power") || normalized.contains("6/55") -> "Power 6/55"
      normalized.contains("mega") || normalized.contains("6/45") -> "Mega 6/45"
      normalized.contains("vietlott") && normalized.contains("max 3d") -> "Max 3D"
      else -> return null
    }
    val lines = VIETLOTT_LINE_REGEX.findAll(raw).mapNotNull { match ->
      val label = match.groupValues[1].uppercase(Locale.ROOT)
      val nums = match.groupValues[2].trim().split(Regex("[ \t-]+"))
      val valid = when (product) {
        "Max 3D" -> nums.size == 2 && nums.all { it.length == 3 }
        "Mega 6/45", "Power 6/55" -> {
          val max = if (product == "Mega 6/45") 45 else 55
          (nums.size == 6 || nums.size in 7..15 || nums.size == 18) && nums.toSet().size == nums.size &&
            nums.all { it.length == 2 && it.toInt() in 1..max }
        }
        else -> false
      }
      if (valid) label to nums else null
    }.toList()
    if (lines.isEmpty() || lines.map { it.first }.distinct().size != lines.size) return null
    val drawId = VIETLOTT_DRAW_REGEX.find(raw)?.groupValues?.getOrNull(1)
    val drawDate = extractDate(raw)
    return ParsedLotteryTicket.Vietlott(product, drawId, drawDate, lines)
  }

  private fun parseTraditional(raw: String, normalized: String): ParsedLotteryTicket.Traditional? {
    fun stationsIn(text: String): List<LotteryStation> = STATIONS.filter { station ->
      station.aliases.any { alias -> " $alias " in " $text " }
    }
    val headerStations = raw.lineSequence().map(::normalizeAscii)
      .filter { hasLotteryKeyword(it) }.flatMap { stationsIn(it).asSequence() }.distinct().toList()
    val station = if (headerStations.isNotEmpty()) headerStations.singleOrNull()
      else stationsIn(normalized).singleOrNull()
    val drawDate = extractDate(raw)
    if (!hasLotteryKeyword(normalized)) return null

    val stripped = raw
      .replace(DATE_REGEX, " ")
      .replace(Regex("\\b\\d{1,3}(?:[.,]\\d{3})+\\b"), " ")

    val expectedLen = station?.region?.digitCount ?: 6
    val lengths = if (station == null) listOf(6, 5) else listOf(expectedLen)
    val number = lengths.firstNotNullOfOrNull { length ->
      val digits = Regex("(?<!\\d)\\d{$length}(?!\\d)")
      val isolated = stripped.lineSequence().map {
        normalizeAscii(it).removePrefix("so du thuong ").removePrefix("day so ").removePrefix("so ")
      }
        .filter { it.matches(Regex("[0-9 \\t]+")) }
        .map { it.replace(Regex("[ \\t]+"), "") }
        .filter(digits::matches).distinct().toList()
      if (isolated.isNotEmpty()) isolated.singleOrNull()
      else digits.findAll(stripped).map { it.value }.distinct().singleOrNull()
    }

    if (number == null) return null
    return ParsedLotteryTicket.Traditional(station, drawDate, number)
  }

  private fun hasLotteryKeyword(text: String): Boolean =
    listOf("xo so", "kien thiet", "ve so", "mo thuong", "xskt")
      .any { " $it " in " $text " }

  private fun extractDate(raw: String): String? {
    val drawLines = raw.lineSequence().filter { line ->
      val ascii = normalizeAscii(line)
      listOf("mo thuong", "quay so", "qsmt", "ngay quay").any(ascii::contains)
    }.toList()
    val preferred = drawLines.flatMap { DATE_REGEX.findAll(it).mapNotNull(::validDate).toList() }.distinct()
    if (drawLines.any { DATE_REGEX.containsMatchIn(it) }) return preferred.singleOrNull()
    return DATE_REGEX.findAll(raw).mapNotNull(::validDate).distinct().toList().singleOrNull()
  }

  private fun validDate(m: MatchResult): String? {
    val day = m.groupValues[1].padStart(2, '0')
    val month = m.groupValues[2].padStart(2, '0')
    val yearRaw = m.groupValues[3]
    val year = if (yearRaw.length == 2) "20$yearRaw" else yearRaw
    val formatted = "$day/$month/$year"
    return try {
      LocalDate.parse(formatted, DRAW_DATE)
      formatted
    } catch (_: DateTimeParseException) {
      null
    }
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
  private const val MAX_FEED_BYTES = 256L * 1024L
  private val client = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()
  private val items = Regex("<item(?:\\s[^>]*)?>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
  private val publishedDate = Regex("/ngay-(\\d{1,2})-(\\d{1,2})-(\\d{4})(?:/|$)")
  private val prizes = listOf(
    "ĐB" to "Giải Đặc biệt", "1" to "Giải Nhất", "2" to "Giải Nhì",
    "3" to "Giải Ba", "4" to "Giải Tư", "5" to "Giải Năm", "6" to "Giải Sáu",
    "7" to "Giải Bảy", "8" to "Giải Tám",
  )

  fun checkOnline(ticket: ParsedLotteryTicket.Traditional): String? {
    val station = ticket.station ?: return null
    val drawDate = ticket.drawDate ?: return null
    val url = "https://xskt.com.vn/rss-feed/${station.rssSlug}.rss"
    val xml = try {
      client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        response.body()?.let(::readFeed)
      }
    } catch (_: Exception) {
      return null
    } ?: return null
    return evaluateRssXml(xml, drawDate, ticket.number)
  }

  private fun readFeed(body: ResponseBody): String? {
    val source = body.source()
    if (source.request(MAX_FEED_BYTES + 1)) return null
    return source.readUtf8()
  }

  internal fun evaluateRssXml(rssXml: String, drawDate: String, ticketNumber: String): String? {
    val item = items.findAll(rssXml).map { it.groupValues[1] }.firstOrNull { chunk ->
      val link = extractTag(chunk, "link") ?: return@firstOrNull false
      val date = publishedDate.find(link)?.groupValues ?: return@firstOrNull false
      "${date[1].padStart(2, '0')}/${date[2].padStart(2, '0')}/${date[3]}" == drawDate
    } ?: return null
    val description = extractTag(item, "description") ?: return null
    val matches = checkPrizes(description, ticketNumber)
    if (matches.isEmpty()) return null
    return "Nguồn tra cứu ngày $drawDate báo khớp ${matches.joinToString(", ")}. Hãy đối chiếu vé với kết quả chính thức."
  }

  private fun extractTag(item: String, tag: String): String? =
    Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
      .find(item)?.groupValues?.get(1)?.trim()?.removePrefix("<![CDATA[")?.removeSuffix("]]>")

  internal fun checkPrizes(description: String, ticketNumber: String): List<String> {
    val expectedLengths = when (ticketNumber.length) {
      5 -> listOf(5, 5, 5, 5, 4, 4, 3, 2)
      6 -> listOf(6, 5, 5, 5, 5, 4, 4, 3, 2)
      else -> return emptyList()
    }
    val text = description.replace(Regex("(?im)<br\\s*/?>"), "\n")
      .replace("\r\n", "\n")
      .replace(Regex("(?m)^(7\\s*:\\s*\\d{3})8\\s*:"), "$1\n8:")
    return prizes.zip(expectedLengths).mapNotNull { (prize, length) ->
      val (code, name) = prize
      val numbers = Regex("(?m)^ *$code *: *([^\\n]+)").find(text)?.groupValues?.get(1)
        ?: return@mapNotNull null
      val values = numbers.trim().split(Regex("\\s+-\\s+"))
      if (values.all { it.length == length && it.all(Char::isDigit) } &&
          values.any { ticketNumber.endsWith(it) }) name else null
    }
  }
}

internal class LotteryTicketTracker {
  private var candidateKey: String? = null
  private var consecutive = 0
  private var lastSpokenKey: String? = null
  private var lastSpokenAtMs = Long.MIN_VALUE

  fun observe(ticket: ParsedLotteryTicket?, nowMs: Long): ParsedLotteryTicket? {
    if (ticket == null) {
      candidateKey = null
      consecutive = 0
      return null
    }
    val key = ticket.dedupeKey
    if (candidateKey != key) {
      candidateKey = key
      consecutive = 1
    } else {
      consecutive++
    }
    if (consecutive < 2) return null
    if (key == lastSpokenKey && lastSpokenAtMs != Long.MIN_VALUE &&
        nowMs - lastSpokenAtMs < REPEAT_INTERVAL_MS) {
      return null
    }
    lastSpokenKey = key
    lastSpokenAtMs = nowMs
    return ticket
  }

  fun reset() {
    candidateKey = null
    consecutive = 0
    lastSpokenKey = null
    lastSpokenAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 6_000L
  }
}
