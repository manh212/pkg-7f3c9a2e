package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandPhoneNumbers
import java.text.Normalizer
import java.util.Locale

internal sealed interface OfflineTaskCommand {
  data class Open(val app: String) : OfflineTaskCommand
  data class Click(val label: String) : OfflineTaskCommand
  data class LongClick(val label: String) : OfflineTaskCommand
  data class Input(val text: String, val label: String = "", val append: Boolean = false) : OfflineTaskCommand
  data class Edit(val action: OfflineTextAction) : OfflineTaskCommand
  data class Toggle(val label: String, val checked: Boolean) : OfflineTaskCommand
  data class Item(val index: Int) : OfflineTaskCommand
  data class Video(val index: Int) : OfflineTaskCommand
  data class Search(val query: String) : OfflineTaskCommand
  data class Panel(val panel: OfflinePanel) : OfflineTaskCommand
  data class Sequence(val commands: List<OfflineTaskCommand>) : OfflineTaskCommand
  data class Scroll(val forward: Boolean, val count: Int) : OfflineTaskCommand
  data class Navigate(val action: TaskOperation) : OfflineTaskCommand
  data class YouTube(val openApp: Boolean, val query: String?, val openFirst: Boolean) :
    OfflineTaskCommand
  data object FirstVideo : OfflineTaskCommand
  data class Draft(val app: MessageApp, val recipient: String, val text: String) :
    OfflineTaskCommand
  data object AskMessageApp : OfflineTaskCommand
  data class Call(val recipient: String, val number: String?) : OfflineTaskCommand
  data object AskCallTarget : OfflineTaskCommand

  enum class MessageApp { ZALO, SMS }

  companion object {
    private const val DRAFT = "(?:soan|viet|tao)\\s+tin\\s+nhan"
    private const val APP = "(zalo|sms|tin\\s+nhan\\s+mac\\s+dinh)"
    private const val BODY =
      "(?:(?:\\s*[,;:]\\s*|\\s+(?:voi\\s+)?noi\\s+dung\\s*[: ]\\s*|\\s+(?:rang|bao)\\s+)(.+))?"
    private val draftBefore =
      Regex("$DRAFT\\s+(?:(?:tren|trong|qua|bang|cho)\\s+)?$APP\\s+(?:cho|toi|den)\\s+(.+?)$BODY", RegexOption.DOT_MATCHES_ALL)
    private val draftAfter =
      Regex("$DRAFT\\s+(?:cho|toi|den)\\s+(.+?)\\s+(?:tren|trong|qua|bang)\\s+$APP$BODY", RegexOption.DOT_MATCHES_ALL)
    private val call = Regex("(?:goi(?:\\s+dien)?|quay\\s+so)(?:\\s+(?:cho|toi|den))?(?:\\s+so)?(?:\\s+(.+))?")
    private val open = Regex("(?:mo|vao|khoi\\s+dong)\\s+(?:ung\\s+dung\\s+)?(.+)")
    private val longClick = Regex("(?:nhan\\s+giu|bam\\s+giu|giu)(?:\\s+(?:vao|nut|muc))*\\s+(.+)")
    private val input = Regex("(?:nhap|dien|viet)(?:\\s+(?:noi\\s+dung|chu))?\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val namedInput = Regex("(?:nhap|dien)\\s+(?:vao\\s+)?o\\s+(.+?)(?:\\s*[:;,]\\s*|\\s+(?:voi\\s+)?noi\\s+dung\\s+)(.+)", RegexOption.DOT_MATCHES_ALL)
    private val append = Regex("(?:them|viet\\s+them|nhap\\s+them)\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val toggle = Regex("(bat|tat)(?:\\s+(?:nut|tuy\\s+chon|cong\\s+tac))?\\s+(.+)")
    private val item = Regex("(?:mo|chon|bam|nhan)(?:\\s+vao)?\\s+(?:muc|ket\\s+qua(?:\\s+tim\\s+kiem)?)(?:\\s+thu)?\\s+(\\d{1,2}|mot|nhat|hai|ba|bon|nam|sau|bay|tam|chin|muoi|dau\\s+tien)")
    private val video = Regex("(?:mo|chon|bam|nhan)(?:\\s+vao)?\\s+video(?:\\s+thu)?\\s+(\\d{1,2}|mot|nhat|hai|ba|bon|nam|sau|bay|tam|chin|muoi|dau\\s+tien)")
    private val search = Regex("tim(?:\\s+kiem)?\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val connector = Regex("[,;]?\\s+(?:roi|sau\\s+do)\\s+")
    private val click = Regex("(?:bam|nhan|cham|chon)(?:\\s+(?:vao|nut|muc))*\\s+(.+)")
    private const val FIRST =
      "(?:(?:mo|bam|nhan|chon)(?:\\s+vao)?\\s+(?:ket\\s+qua(?:\\s+tim\\s+kiem)?|video)(?:\\s+dau\\s+tien|\\s+(?:thu\\s+)?nhat))"
    private val first = Regex(FIRST)
    private val youtube = Regex(
      "(?:(?:mo|vao)\\s+(?:ung\\s+dung\\s+)?(you\\s*tube)[,\\s]+(?:(?:roi|sau\\s+do)[,\\s]+)?)?" +
        "(?:tim(?:\\s+kiem)?(?:\\s+tren\\s+you\\s*tube)?\\s+(.+?))(?:\\s+tren\\s+you\\s*tube)?" +
        "(?:[,\\s]+(?:roi|sau\\s+do)[,\\s]+($FIRST))?",
    )
    private val openSearch = Regex(
      "(?:mo|vao)\\s+you\\s*tube[,\\s]+(?:(?:roi|sau\\s+do)[,\\s]+)?" +
        "(?:bam|nhan|cham|chon)(?:\\s+(?:vao|nut))*\\s+tim\\s+kiem",
    )
    private val scroll = Regex("(?:cuon|vuot)\\s+(len|xuong)(?:\\s+(\\d{1,2}|mot|hai|ba|bon|nam)\\s+lan)?")
    private val draftStart = Regex("^$DRAFT(?:\\s|$)")

    fun parse(goal: String): OfflineTaskCommand? {
      val raw = Normalizer.normalize(goal.trim(), Normalizer.Form.NFC)
      val folded = fold(raw)
      if (raw.length != folded.length) return null
      val single = parseSingle(raw)
      if (single is Draft || single is Input || single is Search || single is YouTube || single is Call) return single
      val separators = connector.findAll(folded).toList()
      if (separators.isEmpty()) return single
      val parts = mutableListOf<OfflineTaskCommand>()
      var start = 0
      for (separator in separators) {
        val remaining = parseSingle(raw.substring(start))
        if (remaining is Input || remaining is Search) {
          parts += remaining
          return Sequence(parts)
        }
        val part = parseSingle(raw.substring(start, separator.range.first)) ?: return null
        if (!canChain(part)) return null
        parts += part
        start = separator.range.last + 1
      }
      val last = parseSingle(raw.substring(start)) ?: return null
      if (!canChain(last) || parts.size >= 20) return null
      parts += last
      return Sequence(parts)
    }

    private fun canChain(command: OfflineTaskCommand): Boolean = when (command) {
      is Draft, is Call, AskCallTarget, AskMessageApp, is Sequence -> false
      else -> true
    }

    private fun ordinal(word: String): Int? = when (word) {
      "mot", "nhat", "dau tien" -> 1
      else -> word.toIntOrNull() ?: listOf("hai", "ba", "bon", "nam", "sau", "bay", "tam", "chin", "muoi")
        .indexOf(word).takeIf { it >= 0 }?.plus(2)
    }?.takeIf { it in 1..20 }

    private fun parseSingle(goal: String): OfflineTaskCommand? {
      val raw = Normalizer.normalize(goal.trim(), Normalizer.Form.NFC)
      val folded = fold(raw)
      if (raw.length != folded.length) return null
      fun MatchResult.part(index: Int): String = groups[index]?.range?.let { raw.substring(it) }?.trim().orEmpty()
      fun MatchResult.recipient(index: Int): String = part(index).trimEnd('.', '!', '?').trimEnd()

      draftBefore.matchEntire(folded)?.let {
        return Draft(messageApp(it.groupValues[1]), it.recipient(2), it.part(3))
      }
      draftAfter.matchEntire(folded)?.let {
        return Draft(messageApp(it.groupValues[2]), it.recipient(1), it.part(3))
      }
      if (draftStart.containsMatchIn(folded)) return AskMessageApp
      namedInput.matchEntire(folded)?.let { return Input(it.part(2), it.part(1)) }
      append.matchEntire(folded)?.let { return Input(it.part(1), append = true) }
      input.matchEntire(folded)?.let { return Input(it.part(1)) }
      val command = folded.trimEnd('.', '!', '?').trimEnd()
      when (command) {
        "sao chep", "sao chep van ban" -> return Edit(OfflineTextAction.COPY)
        "cat", "cat van ban" -> return Edit(OfflineTextAction.CUT)
        "dan", "dan van ban" -> return Edit(OfflineTextAction.PASTE)
        "chon tat ca", "chon toan bo" -> return Edit(OfflineTextAction.SELECT_ALL)
        "xoa noi dung", "xoa van ban", "xoa het chu" -> return Edit(OfflineTextAction.CLEAR)
        "mo thong bao", "mo bang thong bao" -> return Panel(OfflinePanel.NOTIFICATIONS)
        "mo cai dat nhanh", "mo bang cai dat nhanh" -> return Panel(OfflinePanel.QUICK_SETTINGS)
        "mo ung dung gan day", "ung dung gan day", "da nhiem" -> return Panel(OfflinePanel.RECENTS)
      }
      call.matchEntire(command)?.let {
        val recipient = it.part(1)
        return if (recipient.isBlank()) AskCallTarget
        else Call(recipient, VoiceCommandPhoneNumbers.parseNumber(recipient))
      }
      if (first.matches(command)) return if ("video" in command) FirstVideo else Item(1)
      video.matchEntire(command)?.let { match -> ordinal(match.groupValues[1])?.let { return Video(it) } }
      item.matchEntire(command)?.let { match -> ordinal(match.groupValues[1])?.let { return Item(it) } }
      if (openSearch.matches(command)) return YouTube(openApp = true, query = null, openFirst = false)
      youtube.matchEntire(command)?.let {
        val specifiesApp = it.groups[1] != null || Regex("\\btren\\s+you\\s*tube\\b").containsMatchIn(folded)
        if (specifiesApp || it.groups[3] != null) {
          return YouTube(specifiesApp, it.part(2), it.groups[3] != null)
        }
      }
      search.matchEntire(command)?.let { return Search(it.part(1)) }
      open.matchEntire(command)?.let { return Open(it.part(1)) }
      longClick.matchEntire(command)?.let { return LongClick(it.part(1)) }
      toggle.matchEntire(command)?.let { return Toggle(it.part(2), it.groupValues[1] == "bat") }
      click.matchEntire(command)?.let { return Click(it.part(1)) }
      scroll.matchEntire(command)?.let {
        val count = it.groupValues[2].let { word ->
          word.toIntOrNull() ?: listOf("mot", "hai", "ba", "bon", "nam").indexOf(word).plus(1).coerceAtLeast(1)
        }
        if (count in 1..10) return Scroll(it.groupValues[1] == "xuong", count)
      }
      return when (command) {
        "quay lai", "tro lai", "lui lai", "dong ban phim", "an ban phim" -> Navigate(TaskOperation.BACK)
        "ve man hinh chinh", "man hinh chinh", "ve trang chu" -> Navigate(TaskOperation.HOME)
        else -> null
      }
    }

    private fun messageApp(text: String) = if (text == "zalo") MessageApp.ZALO else MessageApp.SMS

    private fun fold(text: String): String =
      Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('đ', 'd')
  }
}

internal enum class OfflineTextAction { COPY, CUT, PASTE, SELECT_ALL, CLEAR }

internal enum class OfflinePanel { RECENTS, NOTIFICATIONS, QUICK_SETTINGS }
