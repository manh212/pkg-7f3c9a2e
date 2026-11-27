package com.google.android.accessibility.talkback.gesture

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.granularediting.Granularity
import com.google.android.accessibility.talkback.granularediting.Piece
import com.google.android.accessibility.talkback.granularediting.TextPieces

class EdgeGestureActions(private val service: TalkBackService) {
  enum class Action(val value: Int, val title: Int) {
    PREVIOUS_SENTENCE(R.string.edge_gesture_value_previous_sentence, R.string.edge_gesture_previous_sentence),
    NEXT_SENTENCE(R.string.edge_gesture_value_next_sentence, R.string.edge_gesture_next_sentence),
    PREVIOUS_RECENT(R.string.edge_gesture_value_previous_recent, R.string.edge_gesture_previous_recent),
    NEXT_RECENT(R.string.edge_gesture_value_next_recent, R.string.edge_gesture_next_recent),
  }

  private data class FocusedText(val window: Int, val node: Int, val text: String, val caret: Int)
  private data class SentenceCursor(val source: FocusedText, val pieces: List<Piece>, var position: Int)

  private var sentenceCursor: SentenceCursor? = null

  fun stepSentence(forward: Boolean): String? {
    val source = focusedText(service) ?: return null
    val cursor = sentenceCursor?.takeIf { it.source == source } ?: run {
      val pieces = TextPieces.split(source.text, Granularity.SENTENCES)
      if (pieces.isEmpty()) return null
      val caret = pieces.indexOfFirst { source.caret in it.start until it.end }
      val position = when {
        caret >= 0 -> caret
        source.caret >= source.text.length -> pieces.lastIndex
        forward -> -1
        else -> pieces.size
      }
      SentenceCursor(source, pieces, position).also { sentenceCursor = it }
    }
    val next = cursor.position + if (forward) 1 else -1
    val piece = cursor.pieces.getOrNull(next) ?: return null
    cursor.position = next
    return cursor.source.text.substring(piece.coreStart, piece.coreEnd)
  }

  fun stepRecent(): Boolean = service.recentAppSwitcher.switchToPrevious()

  companion object {
    @JvmStatic
    fun find(context: Context, action: String): Action? {
      if (!action.startsWith("EDGE_GESTURE_")) return null
      return Action.entries.firstOrNull { context.getString(it.value) == action }
    }

    @JvmStatic
    fun repeats(context: Context, action: String): Boolean =
      action == context.getString(R.string.shortcut_value_increase_volume) ||
        action == context.getString(R.string.shortcut_value_decrease_volume) ||
        action == context.getString(R.string.shortcut_value_increase_media_volume) ||
        action == context.getString(R.string.shortcut_value_decrease_media_volume) ||
        action == context.getString(R.string.shortcut_value_increase_contextual_volume) ||
        action == context.getString(R.string.shortcut_value_decrease_contextual_volume) ||
        action == context.getString(R.string.shortcut_value_speech_rate_increase) ||
        action == context.getString(R.string.shortcut_value_speech_rate_decrease) ||
        action == context.getString(R.string.shortcut_value_speech_pitch_increase) ||
        action == context.getString(R.string.shortcut_value_speech_pitch_decrease) ||
        find(context, action).let { it == Action.PREVIOUS_SENTENCE || it == Action.NEXT_SENTENCE }

    @JvmStatic
    fun isAvailable(service: TalkBackService, action: String): Boolean =
      when (find(service, action)) {
        Action.PREVIOUS_SENTENCE, Action.NEXT_SENTENCE -> focusedText(service) != null
        Action.PREVIOUS_RECENT, Action.NEXT_RECENT -> service.recentAppSwitcher.isAvailable()
        null -> true
      }

    private fun focusedText(service: TalkBackService): FocusedText? {
      val node = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        ?: return null
      try {
        if (node.isPassword) return null
        val text = node.text?.toString().orEmpty()
        if (text.isBlank()) return null
        return FocusedText(node.windowId, node.hashCode(), text, node.textSelectionStart)
      } finally {
        node.recycle()
      }
    }
  }
}
