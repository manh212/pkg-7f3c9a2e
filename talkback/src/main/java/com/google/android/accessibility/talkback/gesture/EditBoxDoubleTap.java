package com.google.android.accessibility.talkback.gesture;

import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_BEGINNING;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_END;

import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;

public final class EditBoxDoubleTap {
  private AccessibilityNodeInfoCompat previous;
  private boolean moveToEnd = true;

  public static boolean handles(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && node.isEnabled() && node.isEditable()
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
      @Nullable AccessibilityNodeInfoCompat node, EventId eventId, Pipeline.FeedbackReturner pipeline) {
    if (node == null || !handles(node)) return false;
    onAccessibilityFocusChanged(node);
    boolean opening = previous == null || !node.isFocused();
    boolean opened = false;
    if (opening) {
      moveToEnd = true;
      opened = pipeline.returnFeedback(eventId, Feedback.focus(Feedback.Focus.Action.CLICK_NODE)
          .setTarget(node));
      if (!opened) return false;
      node.refresh();
    }
    CharSequence text = node.getText();
    if (node.isShowingHintText() || text == null || text.length() == 0) {
      if (previous == null) previous = AccessibilityNodeInfoCompat.obtain(node);
      moveToEnd = false;
      return true;
    }
    if (!AccessibilityNodeInfoUtils.supportsAnyAction(node,
            AccessibilityNodeInfoCompat.ACTION_SET_SELECTION,
            moveToEnd ? AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                : AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)) {
      return opened;
    }
    if (!node.isFocused()) {
      node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS);
      node.refresh();
    }
    int edge = moveToEnd ? text.length() : 0;
    boolean atEdge = node.getTextSelectionStart() == edge && node.getTextSelectionEnd() == edge;
    boolean moved = atEdge || pipeline.returnFeedback(eventId,
        Feedback.edit(node, moveToEnd ? CURSOR_TO_END : CURSOR_TO_BEGINNING));
    if (moved) {
      if (previous == null) {
        previous = AccessibilityNodeInfoCompat.obtain(node);
      }
      moveToEnd = !moveToEnd;
    }
    return moved || opened;
  }
}
