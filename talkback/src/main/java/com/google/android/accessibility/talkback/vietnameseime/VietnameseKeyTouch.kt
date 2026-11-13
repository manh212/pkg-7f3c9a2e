package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY

internal class VietnameseKeyTouch(
  var doubleTapTimeout: Long,
  private val doubleTapSlop: Int,
  private val firstTaps: MutableMap<Key, Tap> = LinkedHashMap(),
) {
  data class Key(val id: String, val textEntry: Boolean = false, val editorAction: Boolean = false)

  internal data class Tap(val key: Key, val x: Float, val y: Float, val time: Long)

  var selected: Key? = null
    private set
  private var exploring = false
  private var exploredOntoKey = false

  fun start(key: Key?, method: Int) {
    exploring = selected?.let { confirmsWithDoubleTap(it, method) } != true
    exploredOntoKey = false
    if (exploring) selected = key
  }

  fun explore(key: Key?) {
    selected?.let { firstTaps.remove(it) }
    exploredOntoKey = key != null
    selected = key
    exploring = true
  }

  fun hasPendingFirstTap(key: Key?, now: Long): Boolean {
    if (key == null) return false
    pruneStaleFirstTaps(now)
    return firstTaps.containsKey(key)
  }

  fun release(key: Key?, method: Int, x: Float, y: Float, time: Long, tap: Boolean): Key? {
    if (key == null) {
      selected?.let { firstTaps.remove(it) }
      cancel()
      return null
    }
    if (exploring) selected = key
    val target = selected ?: return null
    if (!confirmsWithDoubleTap(target, method)) {
      cancel()
      return target
    }
    if (!tap) {
      firstTaps.remove(target)
      return null
    }
    if (exploredOntoKey) return null
    pruneStaleFirstTaps(time)
    val prior = firstTaps[target]
    if (prior != null && time - prior.time in 0..doubleTapTimeout) {
      val dx = x - prior.x
      val dy = y - prior.y
      if (dx * dx + dy * dy <= doubleTapSlop.toFloat() * doubleTapSlop) {
        firstTaps.remove(target)
        cancel()
        return target
      }
    }
    firstTaps[target] = Tap(target, x, y, time)
    return null
  }

  fun cancel() {
    selected = null
    exploring = false
    exploredOntoKey = false
  }

  private fun pruneStaleFirstTaps(now: Long) {
    val iterator = firstTaps.entries.iterator()
    while (iterator.hasNext()) {
      if (now - iterator.next().value.time > doubleTapTimeout) iterator.remove()
    }
  }

  companion object {
    fun confirmsWithDoubleTap(key: Key, method: Int): Boolean = when (method) {
      DOUBLE_TAP -> true
      LIFT_TO_TYPE -> !key.textEntry
      LIFT_TO_TYPE_EXCEPT_ACTION_KEY -> key.editorAction
      DIRECT_SILENT_TYPING -> false
      else -> false
    }
  }
}
