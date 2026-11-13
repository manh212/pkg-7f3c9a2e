package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY

internal class VietnameseKeyTouch(
  private val doubleTapTimeout: Long,
  private val doubleTapSlop: Int,
) {
  data class Key(val id: String, val textEntry: Boolean = false, val editorAction: Boolean = false)

  private data class Tap(val key: Key, val x: Float, val y: Float, val time: Long)

  var selected: Key? = null
    private set
  private var firstTap: Tap? = null
  private var exploring = false

  fun start(key: Key?, method: Int) {
    exploring = selected?.let { confirmsWithDoubleTap(it, method) } != true
    if (exploring) selected = key
  }

  fun explore(key: Key?) {
    firstTap = null
    selected = key
    exploring = true
  }

  fun release(key: Key?, method: Int, x: Float, y: Float, time: Long, tap: Boolean): Key? {
    if (key == null) {
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
      firstTap = null
      return null
    }
    val prior = firstTap
    if (prior != null && prior.key == target && time - prior.time in 0..doubleTapTimeout) {
      val dx = x - prior.x
      val dy = y - prior.y
      if (dx * dx + dy * dy <= doubleTapSlop.toFloat() * doubleTapSlop) {
        cancel()
        return target
      }
    }
    firstTap = Tap(target, x, y, time)
    return null
  }

  fun cancel() {
    selected = null
    firstTap = null
    exploring = false
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
