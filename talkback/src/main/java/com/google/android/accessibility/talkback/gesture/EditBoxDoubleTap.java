package com.google.android.accessibility.talkback.gesture;

import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_BEGINNING;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_END;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.MOVE_CURSOR;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.input.CursorGranularity;

/**
 * Handles double-tap activation on editable text boxes:
 *
 * <ol>
 *   <li>First tap enters the edit box while preserving the current cursor position.
 *   <li>Second tap moves the cursor to the end (of the paragraph/line/text depending on active
 *       reading granularity), or to the beginning if already at the end.
 *   <li>Subsequent taps alternate between end and beginning within that scope until focus leaves.
 * </ol>
 */
public final class EditBoxDoubleTap {
  private AccessibilityNodeInfoCompat previous;
  private boolean moveToEnd = true;

  public static boolean handles(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null
        && node.isEnabled()
        && node.isEditable()
        && Role.getRole(node) == Role.ROLE_EDIT_TEXT;
  }

  void onAccessibilityFocusChanged(@Nullable AccessibilityNodeInfoCompat node) {
    if (previous != null && !previous.equals(node)) {
      previous.recycle();
      previous = null;
      moveToEnd = true;
    }
  }

  boolean activate(
      @Nullable AccessibilityNodeInfoCompat node,
      EventId eventId,
      Pipeline.FeedbackReturner pipeline) {
    return activate(node, eventId, pipeline, CursorGranularity.DEFAULT);
  }

  boolean activate(
      @Nullable AccessibilityNodeInfoCompat node,
      EventId eventId,
      Pipeline.FeedbackReturner pipeline,
      @Nullable CursorGranularity granularity) {
    if (node == null || !handles(node)) {
      return false;
    }
    onAccessibilityFocusChanged(node);
    boolean opening = previous == null || !node.isFocused();
    if (opening) {
      moveToEnd = true;
      boolean opened =
          pipeline.returnFeedback(
              eventId, Feedback.focus(Feedback.Focus.Action.CLICK_NODE).setTarget(node));
      if (!opened) {
        return false;
      }
      node.refresh();
      if (previous == null) {
        previous = AccessibilityNodeInfoCompat.obtain(node);
      }
      return true;
    }

    CharSequence text = node.getText();
    if (node.isShowingHintText() || text == null || text.length() == 0) {
      return true;
    }

    if (!node.isFocused()) {
      node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS);
      node.refresh();
    }

    if (granularity == CursorGranularity.LINE) {
      return moveWithinLine(node, text, eventId, pipeline);
    }
    if (granularity == CursorGranularity.PARAGRAPH) {
      return moveWithinParagraph(node, text, eventId, pipeline);
    }
    return moveWithinWholeText(node, text, eventId, pipeline);
  }

  private boolean moveWithinLine(
      AccessibilityNodeInfoCompat node,
      CharSequence text,
      EventId eventId,
      Pipeline.FeedbackReturner pipeline) {
    int lineAction =
        moveToEnd
            ? AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            : AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY;
    boolean supportsLineGranularity =
        (node.getMovementGranularities() & AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE)
                != 0
            && AccessibilityNodeInfoUtils.supportsAction(node, lineAction);
    if (supportsLineGranularity) {
      Bundle args = new Bundle();
      args.putInt(
          AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
          AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE);
      args.putBoolean(
          AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
      boolean moved =
          pipeline.returnFeedback(eventId, Feedback.nodeAction(node, lineAction, args));
      if (moved) {
        moveToEnd = !moveToEnd;
      }
      return moved;
    }
    return moveWithinParagraph(node, text, eventId, pipeline);
  }

  private boolean moveWithinParagraph(
      AccessibilityNodeInfoCompat node,
      CharSequence text,
      EventId eventId,
      Pipeline.FeedbackReturner pipeline) {
    if (!AccessibilityNodeInfoUtils.supportsAction(
        node, AccessibilityNodeInfoCompat.ACTION_SET_SELECTION)) {
      return moveWithinWholeText(node, text, eventId, pipeline);
    }
    int cursor = clampCursor(node.getTextSelectionEnd(), text.length());
    int[] bounds = findLineOrParagraphBounds(text, cursor);
    int start = bounds[0];
    int end = bounds[1];

    boolean targetEnd = moveToEnd;
    int target = targetEnd ? end : start;
    if (isCursorAt(node, target) && start != end) {
      targetEnd = !targetEnd;
      target = targetEnd ? end : start;
    }

    boolean moved =
        isCursorAt(node, target)
            || pipeline.returnFeedback(
                eventId, Feedback.edit(node, MOVE_CURSOR).setCursorIndex(target));
    if (moved) {
      moveToEnd = !targetEnd;
    }
    return moved;
  }

  private boolean moveWithinWholeText(
      AccessibilityNodeInfoCompat node,
      CharSequence text,
      EventId eventId,
      Pipeline.FeedbackReturner pipeline) {
    boolean targetEnd = moveToEnd;
    int edge = targetEnd ? text.length() : 0;
    if (isCursorAt(node, edge) && text.length() > 0) {
      targetEnd = !targetEnd;
      edge = targetEnd ? text.length() : 0;
    }

    if (!AccessibilityNodeInfoUtils.supportsAnyAction(
        node,
        AccessibilityNodeInfoCompat.ACTION_SET_SELECTION,
        targetEnd
            ? AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            : AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)) {
      return false;
    }

    boolean moved =
        isCursorAt(node, edge)
            || pipeline.returnFeedback(
                eventId, Feedback.edit(node, targetEnd ? CURSOR_TO_END : CURSOR_TO_BEGINNING));
    if (moved) {
      moveToEnd = !targetEnd;
    }
    return moved;
  }

  private static boolean isCursorAt(AccessibilityNodeInfoCompat node, int index) {
    return node.getTextSelectionStart() == index && node.getTextSelectionEnd() == index;
  }

  private static int clampCursor(int rawCursor, int textLength) {
    if (rawCursor < 0) {
      return 0;
    }
    return Math.min(rawCursor, textLength);
  }

  /** Returns {@code [start, end]} of the newline-delimited paragraph/line containing {@code pos}. */
  static int[] findLineOrParagraphBounds(CharSequence text, int pos) {
    int length = text.length();
    int clamped = clampCursor(pos, length);
    int start = clamped;
    while (start > 0 && text.charAt(start - 1) != '\n') {
      start--;
    }
    int end = clamped;
    while (end < length && text.charAt(end) != '\n') {
      end++;
    }
    if (end > start && text.charAt(end - 1) == '\r') {
      end--;
    }
    return new int[] {start, end};
  }
}
