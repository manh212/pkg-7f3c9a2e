package com.google.android.accessibility.talkback.taskassistant

import java.text.Normalizer
import java.util.Locale

internal sealed interface OfflineTaskCommand {
  data class Click(val label: String) : OfflineTaskCommand
  data class LongClick(val label: String) : OfflineTaskCommand
  data class Input(val text: String, val label: String = "", val append: Boolean = false) : OfflineTaskCommand
  data class Toggle(val label: String, val checked: Boolean) : OfflineTaskCommand
  data class Item(val index: Int, val video: Boolean = false) : OfflineTaskCommand
  data class Search(val query: String) : OfflineTaskCommand
  data class Sequence(val commands: List<OfflineTaskCommand>) : OfflineTaskCommand
  data class Scroll(val forward: Boolean, val count: Int) : OfflineTaskCommand
  data class Drag(val label: String, val direction: String) : OfflineTaskCommand

  companion object {
    private const val NUMBER = "(\\d{1,2}|mot|nhat|hai|ba|bon|nam|sau|bay|tam|chin|muoi|dau\\s+tien)"
    private val open = Regex("(?:mo|vao)\\s+(?:ung\\s+dung\\s+)?(.+)")
    private val longClick = Regex("(?:nhan\\s+giu|bam\\s+giu|giu)(?:\\s+(?:vao|nut|muc))*\\s+(.+)")
    private val input = Regex("(?:nhap|dien|viet|soan)(?:\\s+(?:noi\\s+dung|chu|tin\\s+nhan))?(?:\\s+la)?\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val namedInput = Regex("(?:nhap|dien)\\s+(?:vao\\s+)?o\\s+(.+?)(?:\\s*[:;,]\\s*|\\s+(?:voi\\s+)?noi\\s+dung\\s+)(.+)", RegexOption.DOT_MATCHES_ALL)
    private val append = Regex("(?:them|viet\\s+them|nhap\\s+them)\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val toggle = Regex("(bat|tat)(?:\\s+(?:nut|tuy\\s+chon|cong\\s+tac))?\\s+(.+)")
    private val item = Regex("(?:mo|chon|bam|nhan)(?:\\s+vao)?\\s+(muc|ket\\s+qua(?:\\s+tim\\s+kiem)?|video)(?:\\s+thu)?\\s+$NUMBER")
    private val search = Regex("tim(?:\\s+kiem)?\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
    private val connector = Regex("\\s*[,;]\\s*|\\s+(?:roi|sau\\s+do)\\s+")
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”", RegexOption.DOT_MATCHES_ALL)
    private val commandStart = Regex("^(?:nhan|bam|cham|chon|mo|vao|tim|nhap|dien|viet|soan|them|bat|tat|cuon|vuot|giu|keo)\\b")
    private val click = Regex("(?:bam|nhan|cham|chon)(?:\\s+(?:vao|nut|muc))*\\s+(.+)")
    private val scroll = Regex("(?:cuon|vuot)\\s+(len|xuong)(?:\\s+(\\d{1,2}|mot|hai|ba|bon|nam)\\s+lan)?")
    private val drag = Regex("(?:keo|vuot)(?:\\s+(?:vao|nut|muc))*\\s+(.+?)\\s+(len|xuong|(?:sang|qua)\\s+trai|(?:sang|qua)\\s+phai|trai|phai)")

    fun parse(goal: String): OfflineTaskCommand? {
      val raw = Normalizer.normalize(goal.trim(), Normalizer.Form.NFC)
      val folded = fold(raw)
      if (raw.length != folded.length) return null
      val protected = quoted.findAll(raw).map { it.range }.toList()
      val parts = mutableListOf<OfflineTaskCommand>()
      var start = 0
      for (separator in connector.findAll(folded)) {
        if (protected.any { separator.range.first in it }) continue
        val tail = folded.substring(separator.range.last + 1).trimStart()
        val before = parseSingle(raw.substring(start, separator.range.first))
        if (!commandStart.containsMatchIn(tail)) {
          if (before !is Input && before !is Search && separator.value.trim() !in setOf(",", ";")) return null
          continue
        }
        parts += before ?: return null
        if (parts.size >= 20) return null
        start = separator.range.last + 1
      }
      parts += parseSingle(raw.substring(start)) ?: return null
      return parts.singleOrNull() ?: Sequence(parts)
    }

    private fun parseSingle(goal: String): OfflineTaskCommand? {
      val raw = goal.trim()
      val folded = fold(raw)
      if (raw.length != folded.length) return null
      fun MatchResult.part(index: Int): String = groups[index]?.range?.let { raw.substring(it) }?.trim().orEmpty()
      namedInput.matchEntire(folded)?.let { return Input(content(it.part(2)), it.part(1)) }
      append.matchEntire(folded)?.let { return Input(content(it.part(1)), append = true) }
      input.matchEntire(folded)?.let { return Input(content(it.part(1))) }
      val command = folded.trimEnd('.', '!', '?').trimEnd()
      item.matchEntire(command)?.let {
        val index = ordinal(it.groupValues[2]) ?: return null
        return Item(index, it.groupValues[1] == "video")
      }
      search.matchEntire(command)?.let { return Search(content(it.part(1))) }
      longClick.matchEntire(command)?.let { return LongClick(it.part(1)) }
      toggle.matchEntire(command)?.let { return Toggle(it.part(2), it.groupValues[1] == "bat") }
      click.matchEntire(command)?.let { return Click(it.part(1)) }
      open.matchEntire(command)?.let { return Click(it.part(1)) }
      drag.matchEntire(command)?.let { return Drag(it.part(1), it.groupValues[2].substringAfterLast(' ')) }
      scroll.matchEntire(command)?.let {
        val count = ordinal(it.groupValues[2].ifBlank { "mot" }) ?: return null
        if (count <= 10) return Scroll(it.groupValues[1] == "xuong", count)
      }
      return null
    }

    private fun ordinal(word: String): Int? = when (word) {
      "mot", "nhat", "dau tien" -> 1
      else -> word.toIntOrNull() ?: listOf("hai", "ba", "bon", "nam", "sau", "bay", "tam", "chin", "muoi")
        .indexOf(word).takeIf { it >= 0 }?.plus(2)
    }?.takeIf { it in 1..20 }

    private fun content(text: String): String = when {
      text.startsWith('"') && text.endsWith('"') -> text.substring(1, text.lastIndex)
      text.startsWith('“') && text.endsWith('”') -> text.substring(1, text.lastIndex)
      else -> text
    }

    private fun fold(text: String): String =
      Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('đ', 'd')
  }
}
