package com.google.android.accessibility.talkback.interpreters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AutoScrollSettleTest {
  @Test
  public void quietScrollKeepsItsNormalSettleDelay() {
    assertEquals(110, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1000, 110));
  }

  @Test
  public void repeatedEventsCannotExtendTheWaitPastTheDeadline() {
    assertEquals(110, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1180, 110));
    assertEquals(50, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1300, 110));
    assertEquals(0, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1350, 110));
  }

  @Test
  public void rangeMustReferToRealVisibleItemsBeforeSkippingSettleDelay() {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
    event.setItemCount(20);
    event.setFromIndex(5);
    event.setToIndex(9);
    assertTrue(AutoScrollInterpreter.hasVisibleItemRange(event));
    event.setToIndex(20);
    assertFalse(AutoScrollInterpreter.hasVisibleItemRange(event));
    event.setToIndex(3);
    assertFalse(AutoScrollInterpreter.hasVisibleItemRange(event));
    event.recycle();
  }
}
