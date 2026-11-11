package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs

internal object SubtitleAccessibility {
  private const val MAX_NODES = 120
  private const val SCAN_BUDGET_MS = 25L

  fun findLines(
    source: AccessibilityNodeInfo,
    region: Rect,
    maxLineHeight: Int,
    automatic: Boolean,
    allowUpper: Boolean,
    scanChildren: Boolean,
    scanWindow: Boolean,
  ): List<SubtitleLine> {
    val lines = mutableListOf<SubtitleLine>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(source)
    val deadline = SystemClock.uptimeMillis() + SCAN_BUDGET_MS
    var visited = 0
    try {
      while (queue.isNotEmpty() && visited < MAX_NODES && SystemClock.uptimeMillis() <= deadline) {
        val node = queue.removeFirst()
        visited++
        try {
          val bounds = Rect().also(node::getBoundsInScreen)
          if (!bounds.isEmpty && !Rect.intersects(bounds, region)) continue
          if (node.isEditable || (node.isClickable && bounds.height() in 1..maxLineHeight)) continue
          val line = readLine(node, bounds, region, maxLineHeight, automatic, allowUpper, scanWindow)
          if (line != null) {
            lines.add(line)
          } else if (scanChildren) {
            val childLimit = minOf(node.childCount, MAX_NODES - visited - queue.size)
            for (index in 0 until childLimit) node.getChild(index)?.let(queue::addLast)
          }
        } finally {
          if (node !== source) node.recycle()
        }
      }
    } finally {
      while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (node !== source) node.recycle()
      }
    }
    return lines.distinct()
  }

  private fun readLine(
    node: AccessibilityNodeInfo,
    bounds: Rect,
    region: Rect,
    maxLineHeight: Int,
    automatic: Boolean,
    allowUpper: Boolean,
    scanWindow: Boolean,
  ): SubtitleLine? {
    if (!node.isVisibleToUser || node.isClickable || bounds.isEmpty ||
      bounds.height() > maxLineHeight || !region.contains(bounds.centerX(), bounds.centerY())) return null
    if (automatic) {
      if (abs(bounds.centerX() - region.centerX()) > region.width() * 35 / 100) return null
      val verticalPosition = bounds.centerY() - region.top
      val upper = verticalPosition < region.height() * 35 / 100
      if (upper && !allowUpper) return null
      if (scanWindow && allowUpper && !upper && verticalPosition < region.height() * 55 / 100) return null
    }
    val text = node.text?.toString()?.trim().orEmpty().ifEmpty {
      if (node.childCount == 0) node.contentDescription?.toString()?.trim().orEmpty() else ""
    }
    if (text.length !in 2..160 || !text.any { it.isLetter() }) return null
    return SubtitleLine(text, bounds)
  }
}
