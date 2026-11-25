package com.google.android.accessibility.talkback.gesture;

import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_BEGINNING;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_END;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.MOVE_CURSOR;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.utils.input.CursorGranularity;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditBoxDoubleTapTest {
  @Test
  public void firstTapKeepsCursorThenAlternatesEndAndBeginningUntilFocusLeaves() {
    EditBoxDoubleTap activation = new EditBoxDoubleTap();
    AccessibilityNodeInfoCompat node = createEditText("Một đoạn", 1);
    List<Feedback.EditText.Action> moves = new ArrayList<>();
    List<Feedback.Focus.Action> clicks = new ArrayList<>();
    Pipeline.FeedbackReturner pipeline =
        feedback -> {
          Feedback.Part part = feedback.failovers().get(0);
          if (part.edit() != null) {
            moves.add(part.edit().action());
            if (part.edit().action() == CURSOR_TO_END) {
              node.setTextSelection(8, 8);
            } else if (part.edit().action() == CURSOR_TO_BEGINNING) {
              node.setTextSelection(0, 0);
            }
          }
          if (part.focus() != null) clicks.add(part.focus().action());
          return true;
        };

    // Tap 1: enter edit box without moving cursor.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.DEFAULT));
    // Tap 2: move to end.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.DEFAULT));
    // Tap 3: move to beginning.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.DEFAULT));
    // Tap 4: move to end again.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.DEFAULT));
    // Focus leaves and returns -> next tap enters without moving cursor.
    activation.onAccessibilityFocusChanged(null);
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.DEFAULT));

    assertEquals(List.of(CURSOR_TO_END, CURSOR_TO_BEGINNING, CURSOR_TO_END), moves);
    assertEquals(List.of(Feedback.Focus.Action.CLICK_NODE, Feedback.Focus.Action.CLICK_NODE), clicks);
  }

  @Test
  public void paragraphGranularityAlternatesCurrentParagraphEndAndStart() {
    EditBoxDoubleTap activation = new EditBoxDoubleTap();
    // "Đoạn 1\nĐoạn hai\nĐoạn 3" -> "Đoạn hai" spans [7, 15].
    AccessibilityNodeInfoCompat node = createEditText("Đoạn 1\nĐoạn hai\nĐoạn 3", 10);
    List<Integer> cursorTargets = new ArrayList<>();
    Pipeline.FeedbackReturner pipeline =
        feedback -> {
          Feedback.Part part = feedback.failovers().get(0);
          if (part.edit() != null && part.edit().action() == MOVE_CURSOR) {
            int idx = part.edit().cursorIndex();
            cursorTargets.add(idx);
            node.setTextSelection(idx, idx);
          }
          return true;
        };

    // Tap 1: enter and stay at index 10.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.PARAGRAPH));
    // Tap 2: end of current paragraph (15).
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.PARAGRAPH));
    // Tap 3: start of current paragraph (7).
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.PARAGRAPH));
    // Tap 4: end of current paragraph again (15).
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.PARAGRAPH));

    assertEquals(List.of(15, 7, 15), cursorTargets);
  }

  @Test
  public void lineGranularityUsesNativeLineActionWhenSupported() {
    EditBoxDoubleTap activation = new EditBoxDoubleTap();
    AccessibilityNodeInfoCompat node = createEditText("Dòng một dài\nDòng hai", 4);
    node.setMovementGranularities(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE);
    node.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
    node.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
    List<Integer> nodeActions = new ArrayList<>();
    Pipeline.FeedbackReturner pipeline =
        feedback -> {
          Feedback.Part part = feedback.failovers().get(0);
          if (part.nodeAction() != null) {
            nodeActions.add(part.nodeAction().actionId());
          }
          return true;
        };

    // Tap 1: enter only.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.LINE));
    // Tap 2: end of line.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.LINE));
    // Tap 3: start of line.
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline, CursorGranularity.LINE));

    assertEquals(
        List.of(
            AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY),
        nodeActions);
  }

  @Test
  public void ordinaryControlsRetainNormalActivation() {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain();
    node.setClassName("android.widget.Button");
    node.setEnabled(true);
    assertFalse(EditBoxDoubleTap.handles(node));
  }

  private static AccessibilityNodeInfoCompat createEditText(String text, int cursor) {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain();
    node.setClassName("android.widget.EditText");
    node.setEnabled(true);
    node.setEditable(true);
    node.setFocused(true);
    node.setText(text);
    node.setTextSelection(cursor, cursor);
    node.addAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION);
    return node;
  }
}
