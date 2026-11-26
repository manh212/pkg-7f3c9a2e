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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal enum class SavedTicketStatus {
  PENDING,
  WON,
  NOT_WON,
}

internal data class SavedLotteryTicket(
  val id: String,
  val ticket: ParsedLotteryTicket,
  val savedAtMs: Long,
  val status: SavedTicketStatus = SavedTicketStatus.PENDING,
  val lastResultMessage: String = "",
  val retryCount: Int = 0,
  val nextCheckAtMs: Long = 0L,
) {
  fun summary(): String {
    val statusText = when (status) {
      SavedTicketStatus.PENDING -> "Đang chờ kết quả"
      SavedTicketStatus.WON -> "Đã trúng thưởng"
      SavedTicketStatus.NOT_WON -> "Không trúng thưởng"
    }
    return if (lastResultMessage.isNotBlank() && status != SavedTicketStatus.PENDING) {
      "${ticket.speak()} Trạng thái: $statusText. $lastResultMessage"
    } else {
      "${ticket.speak()} Trạng thái: $statusText."
    }
  }
}

internal object LotteryTicketStore {
  private const val FILE_NAME = "saved_lottery_tickets.json"
  private const val MAX_TICKETS = 50
  private val VIETNAM_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
  private val DRAW_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
  private val lock = Any()

  fun list(context: Context): List<SavedLotteryTicket> = synchronized(lock) {
    readFile(context)
  }

  fun saveNew(context: Context, ticket: ParsedLotteryTicket, nowMs: Long = System.currentTimeMillis()): SavedLotteryTicket =
    synchronized(lock) {
      val current = readFile(context).toMutableList()
      val nextCheck = computeFirstCheckTimeMs(ticket, nowMs)
      val entry = SavedLotteryTicket(
        id = UUID.randomUUID().toString(),
        ticket = ticket,
        savedAtMs = nowMs,
        status = SavedTicketStatus.PENDING,
        lastResultMessage = "",
        retryCount = 0,
        nextCheckAtMs = nextCheck,
      )
      current.add(0, entry)
      if (current.size > MAX_TICKETS) {
        current.subList(MAX_TICKETS, current.size).clear()
      }
      writeFile(context, current)
      entry
    }

  fun update(context: Context, updated: SavedLotteryTicket) = synchronized(lock) {
    val current = readFile(context).toMutableList()
    val idx = current.indexOfFirst { it.id == updated.id }
    if (idx >= 0) {
      current[idx] = updated
      writeFile(context, current)
    }
  }

  fun delete(context: Context, id: String) = synchronized(lock) {
    val current = readFile(context).filterNot { it.id == id }
    writeFile(context, current)
  }

  internal fun computeFirstCheckTimeMs(ticket: ParsedLotteryTicket, nowMs: Long): Long {
    val drawDateStr = when (ticket) {
      is ParsedLotteryTicket.Traditional -> ticket.drawDate
      is ParsedLotteryTicket.Vietlott -> ticket.drawDate
    }
    val drawDate = drawDateStr?.let {
      try {
        LocalDate.parse(it, DRAW_DATE_FMT)
      } catch (_: Exception) {
        null
      }
    } ?: ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), VIETNAM_ZONE).toLocalDate()

    val checkTime = when (ticket) {
      is ParsedLotteryTicket.Traditional -> when (ticket.station?.region) {
        LotteryRegion.MIEN_NAM -> LocalTime.of(16, 40)
        LotteryRegion.MIEN_TRUNG -> LocalTime.of(17, 40)
        LotteryRegion.MIEN_BAC -> LocalTime.of(18, 40)
        null -> if (ticket.number.length == 5) LocalTime.of(18, 40) else LocalTime.of(17, 45)
      }
      is ParsedLotteryTicket.Vietlott -> LocalTime.of(18, 30)
    }
    val scheduledMs = ZonedDateTime.of(drawDate, checkTime, VIETNAM_ZONE).toInstant().toEpochMilli()
    return maxOf(scheduledMs, nowMs + 60_000L)
  }

  private fun storageFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

  private fun readFile(context: Context): List<SavedLotteryTicket> {
    val file = storageFile(context)
    if (!file.exists()) return emptyList()
    return try {
      val arr = JSONArray(file.readText(Charsets.UTF_8))
      buildList {
        for (i in 0 until arr.length()) {
          arr.optJSONObject(i)?.let(::fromJson)?.let(::add)
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun writeFile(context: Context, tickets: List<SavedLotteryTicket>) {
    try {
      val arr = JSONArray()
      tickets.forEach { arr.put(toJson(it)) }
      storageFile(context).writeText(arr.toString(), Charsets.UTF_8)
    } catch (_: Exception) {
    }
  }

  internal fun toJson(item: SavedLotteryTicket): JSONObject {
    val obj = JSONObject()
      .put("id", item.id)
      .put("savedAtMs", item.savedAtMs)
      .put("status", item.status.name)
      .put("lastResultMessage", item.lastResultMessage)
      .put("retryCount", item.retryCount)
      .put("nextCheckAtMs", item.nextCheckAtMs)
    when (val t = item.ticket) {
      is ParsedLotteryTicket.Traditional -> {
        obj.put("kind", "traditional")
          .put("stationSlug", t.station?.rssSlug ?: "")
          .put("rawStationName", t.rawStationName ?: "")
          .put("drawDate", t.drawDate ?: "")
          .put("number", t.number)
          .put("symbolCode", t.symbolCode ?: "")
      }
      is ParsedLotteryTicket.Vietlott -> {
        val linesArr = JSONArray()
        for ((label, nums) in t.lines) {
          val numsArr = JSONArray()
          nums.forEach { numsArr.put(it) }
          linesArr.put(JSONObject().put("label", label).put("nums", numsArr))
        }
        obj.put("kind", "vietlott")
          .put("product", t.product)
          .put("drawId", t.drawId ?: "")
          .put("drawDate", t.drawDate ?: "")
          .put("lines", linesArr)
      }
    }
    return obj
  }

  internal fun fromJson(obj: JSONObject): SavedLotteryTicket? {
    val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: return null
    val savedAtMs = obj.optLong("savedAtMs", 0L)
    val status = try {
      SavedTicketStatus.valueOf(obj.optString("status", SavedTicketStatus.PENDING.name))
    } catch (_: Exception) {
      SavedTicketStatus.PENDING
    }
    val lastResultMessage = obj.optString("lastResultMessage", "")
    val retryCount = obj.optInt("retryCount", 0)
    val nextCheckAtMs = obj.optLong("nextCheckAtMs", 0L)
    val kind = obj.optString("kind", "")
    val ticket: ParsedLotteryTicket = when (kind) {
      "traditional" -> {
        val slug = obj.optString("stationSlug", "")
        val rawName = obj.optString("rawStationName", "").takeIf { it.isNotEmpty() }
        val station = LotteryOnlineResponseParser.STATIONS.firstOrNull { it.rssSlug == slug }
          ?: rawName?.let(LotteryOnlineResponseParser::matchStation)
        val drawDate = obj.optString("drawDate", "").takeIf { it.isNotEmpty() }
        val number = obj.optString("number", "").takeIf { it.isNotEmpty() } ?: return null
        val symbolCode = obj.optString("symbolCode", "").takeIf { it.isNotEmpty() }
        ParsedLotteryTicket.Traditional(station, rawName, drawDate, number, symbolCode)
      }
      "vietlott" -> {
        val product = obj.optString("product", "").takeIf { it.isNotEmpty() } ?: return null
        val drawId = obj.optString("drawId", "").takeIf { it.isNotEmpty() }
        val drawDate = obj.optString("drawDate", "").takeIf { it.isNotEmpty() }
        val linesArr = obj.optJSONArray("lines") ?: return null
        val lines = mutableListOf<Pair<String, List<String>>>()
        for (i in 0 until linesArr.length()) {
          val lineObj = linesArr.optJSONObject(i) ?: continue
          val label = lineObj.optString("label", ('A' + i).toString())
          val numsArr = lineObj.optJSONArray("nums") ?: continue
          val nums = (0 until numsArr.length()).mapNotNull { numsArr.optString(it).takeIf { s -> s.isNotEmpty() } }
          if (nums.isNotEmpty()) lines += label to nums
        }
        if (lines.isEmpty()) return null
        ParsedLotteryTicket.Vietlott(product, drawId, drawDate, lines)
      }
      else -> return null
    }
    return SavedLotteryTicket(id, ticket, savedAtMs, status, lastResultMessage, retryCount, nextCheckAtMs)
  }
}
