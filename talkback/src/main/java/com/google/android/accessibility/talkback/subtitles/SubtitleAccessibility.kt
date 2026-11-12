package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

internal object SubtitleAccessibility {
  private const val MAX_NODES = 120
  private const val SCAN_BUDGET_MS = 25L

  data class Branch(val path: List<Int>, val marked: Boolean)
  data class Group(val branch: Branch, val lines: List<SubtitleLine>)

  private data class Entry(
    val node: AccessibilityNodeInfo,
    val path: List<Int>,
    val branch: Branch?,
  )

  fun findPlayerGroups(
    player: AccessibilityNodeInfo,
    playerBounds: Rect,
    allowedBounds: Rect,
    manual: Boolean = false,
  ): List<Group> {
    val knownJwPlayer = player.viewIdResourceName?.contains("jw-player-", ignoreCase = true) == true
    val groups = linkedMapOf<Branch, MutableList<SubtitleLine>>()
    val queue = ArrayDeque<Entry>()
    queue.add(Entry(player, emptyList(), if (manual) MANUAL_BRANCH else null))
    val deadline = SystemClock.uptimeMillis() + SCAN_BUDGET_MS
    var visited = 0
    try {
      while (queue.isNotEmpty() && visited < MAX_NODES && SystemClock.uptimeMillis() <= deadline) {
        val (node, path, inheritedBranch) = queue.removeFirst()
        visited++
        try {
          if (node !== player && isControl(node)) continue
          val bounds = Rect().also(node::getBoundsInScreen)
          if (manual && !bounds.isEmpty && !Rect.intersects(bounds, allowedBounds)) continue
          val branch = inheritedBranch ?: if (node !== player && isCaptionContainer(node)) {
            Branch(path, marked = true)
          } else null
          val rawText = node.text?.toString()?.trim().orEmpty()
          val jwBranch = if (knownJwPlayer && path.size >= 4 && node.childCount == 0 &&
            node.className == "android.widget.TextView" && playerBounds.contains(bounds.centerX(), bounds.centerY())) {
            Branch(path.dropLast(3), marked = false)
          } else null
          val group = branch ?: jwBranch
          val text = if (branch != null && node.childCount == 0 && rawText.isEmpty()) {
            node.contentDescription?.toString()?.trim().orEmpty()
          } else rawText
          if (group != null && !isControl(node) && node.isVisibleToUser && !node.extras.getBoolean("offscreen") &&
            !bounds.isEmpty && allowedBounds.contains(bounds.centerX(), bounds.centerY()) &&
            bounds.height() <= maxOf(1, playerBounds.height() / 5) && text.length in 1..240 &&
            text.any { it.isLetterOrDigit() } &&
            (group.marked || !SubtitleTracker.isControlTime(text))) {
            groups.getOrPut(group) { mutableListOf() }.add(SubtitleLine(text, bounds))
            continue
          }
          val limit = minOf(node.childCount, MAX_NODES - visited - queue.size)
          for (index in 0 until limit) {
            if (SystemClock.uptimeMillis() > deadline) break
            node.getChild(index)?.let { queue.addLast(Entry(it, path + index, branch)) }
          }
        } finally {
          if (node !== player) node.recycle()
        }
      }
    } finally {
      while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
    }
    return groups.map { (branch, lines) -> Group(branch, lines.distinct()) }
  }

  private fun isCaptionContainer(node: AccessibilityNodeInfo): Boolean {
    val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
    return ("caption" in id || "subtitle" in id || "text-track" in id) &&
      listOf("button", "toggle", "menu", "setting", "option", "control", "icon", "track-list", "language", "search", "title")
        .none { it in id }
  }

  private fun isControl(node: AccessibilityNodeInfo): Boolean {
    val type = node.className?.toString().orEmpty()
    return node.isEditable || node.isPassword || node.isClickable ||
      node.rangeInfo != null || type.endsWith("Button") || type.endsWith("SeekBar") ||
      type.endsWith("ProgressBar")
  }

  val MANUAL_BRANCH = Branch(emptyList(), marked = true)
}
