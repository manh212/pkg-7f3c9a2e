package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/** Finds a known video player surface so OCR avoids unrelated page text and controls. */
internal object SubtitleVideoViewport {
  class Player(val node: AccessibilityNodeInfo, val bounds: Rect) {
    val key = node.viewIdResourceName?.takeIf { it.isNotBlank() } ?: node.hashCode().toString()
  }

  fun findPlayer(root: AccessibilityNodeInfo, appWindow: Rect): Player? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    val deadline = SystemClock.uptimeMillis() + 35L
    var current: AccessibilityNodeInfo? = root
    var visited = 0
    var winner: Player? = null
    var bestRank = 0
    var bestArea = 0L
    try {
      while (current != null && visited++ < 240 && SystemClock.uptimeMillis() <= deadline) {
        val node = current
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val className = node.className?.toString().orEmpty()
        val description = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val rank = when {
          listOf("watch_player", "player_view", "exo_player", "video_view", "player_container", "jw-player-")
            .any { id.contains(it) } -> 3
          className.endsWith("SurfaceView") || className.endsWith("TextureView") ||
            className.endsWith("VideoView") -> 2
          description.contains("trình phát video") || description.contains("video player") -> 1
          else -> 0
        }
        if (rank > 0 && node.isVisibleToUser) {
          val box = Rect().also { node.getBoundsInScreen(it) }
          if (box.intersect(appWindow) && !box.isEmpty) {
            val area = box.width().toLong() * box.height()
            val windowArea = appWindow.width().toLong() * appWindow.height()
            if (area >= windowArea / 15 && box.width() * 2 >= box.height() &&
                (rank > bestRank || (rank == bestRank && area > bestArea))) {
              winner?.node?.recycle()
              winner = Player(AccessibilityNodeInfo.obtain(node), box)
              bestRank = rank
              bestArea = area
            }
          }
        }
        if (queue.size < 240) {
          for (i in 0 until minOf(node.childCount, 240 - queue.size)) {
            node.getChild(i)?.let(queue::addLast)
          }
        }
        if (node !== root) node.recycle()
        current = if (queue.isEmpty()) null else queue.removeFirst()
      }
    } catch (error: RuntimeException) {
      winner?.node?.recycle()
      throw error
    } finally {
      if (current != null && current !== root) current.recycle()
      while (!queue.isEmpty()) queue.removeFirst().recycle()
    }
    return winner
  }
}
