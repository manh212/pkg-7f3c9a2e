package com.google.android.accessibility.talkback.gesture;

import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_BEGINNING;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.CURSOR_TO_END;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
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
  public void opensAtTheEndThenAlternatesUntilTheFocusLeaves() {
    EditBoxDoubleTap activation = new EditBoxDoubleTap();
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain();
    node.setClassName("android.widget.EditText");
    node.setEnabled(true);
    node.setEditable(true);
    node.setFocused(true);
    node.setText("Một đoạn");
    node.setTextSelection(1, 1);
    node.addAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION);
    List<Feedback.EditText.Action> moves = new ArrayList<>();
    List<Feedback.Focus.Action> clicks = new ArrayList<>();
    Pipeline.FeedbackReturner pipeline = feedback -> {
      Feedback.Part part = feedback.failovers().get(0);
      if (part.edit() != null) moves.add(part.edit().action());
      if (part.focus() != null) clicks.add(part.focus().action());
      return true;
    };

    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline));
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline));
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline));
    activation.onAccessibilityFocusChanged(null);
    assertTrue(activation.activate(node, EVENT_ID_UNTRACKED, pipeline));

    assertEquals(List.of(CURSOR_TO_END, CURSOR_TO_BEGINNING, CURSOR_TO_END, CURSOR_TO_END), moves);
    assertEquals(List.of(Feedback.Focus.Action.CLICK_NODE, Feedback.Focus.Action.CLICK_NODE), clicks);
  }

  @Test
  public void ordinaryControlsRetainNormalActivation() {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain();
    node.setClassName("android.widget.Button");
    node.setEnabled(true);
    assertFalse(EditBoxDoubleTap.handles(node));
  }
}
