package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class OffscreenContentTest {
  @Test
  public void findsNearestOffscreenNodeInBothDirections() {
    AccessibilityNodeInfo root = node(null, true);
    AccessibilityNodeInfo before = node("before", false);
    AccessibilityNodeInfo group = node(null, true);
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo after = node("after", false);
    AccessibilityNodeInfo later = node("later", false);
    addChildren(group, pivot, after);
    addChildren(root, before, group, later);
    seal(root, before, group, pivot, after, later);

    assertEquals(
        new OffscreenContent.Ahead(wrap(after), wrap(after)),
        OffscreenContent.findAhead(wrap(root), wrap(pivot), true));
    assertEquals(
        new OffscreenContent.Ahead(wrap(before), wrap(before)),
        OffscreenContent.findAhead(wrap(root), wrap(pivot), false));
  }

  @Test
  public void skipsDescendantsOfPivotAndNestedScrollableContainers() {
    AccessibilityNodeInfo root = node(null, true);
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo pivotChild = node("pivot child", false);
    AccessibilityNodeInfo nested = node(null, true);
    nested.setScrollable(true);
    AccessibilityNodeInfo nestedChild = node("nested child", false);
    AccessibilityNodeInfo next = node("next", false);
    addChildren(pivot, pivotChild);
    addChildren(nested, nestedChild);
    addChildren(root, pivot, nested, next);
    seal(root, pivot, pivotChild, nested, nestedChild, next);

    assertEquals(
        new OffscreenContent.Ahead(wrap(next), wrap(next)),
        OffscreenContent.findAhead(wrap(root), wrap(pivot), true));
  }

  @Test
  public void pageEndIsAboutAScreenFurtherOn() {
    AccessibilityNodeInfo root = scrollView();
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo firstOnScreen = node("first", true);
    AccessibilityNodeInfo secondOnScreen = node("second", true);
    AccessibilityNodeInfo firstOff = node("first off", false);
    AccessibilityNodeInfo secondOff = node("second off", false);
    AccessibilityNodeInfo thirdOff = node("third off", false);
    AccessibilityNodeInfo fourthOff = node("fourth off", false);
    addChildren(
        root, pivot, firstOnScreen, secondOnScreen, firstOff, secondOff, thirdOff, fourthOff);
    seal(
        root, pivot, firstOnScreen, secondOnScreen, firstOff, secondOff, thirdOff, fourthOff);

    // Three items are on screen, so the page end is the third item off screen.
    assertEquals(
        new OffscreenContent.Ahead(wrap(firstOff), wrap(thirdOff)),
        OffscreenContent.findAhead(wrap(root), wrap(pivot), true));
  }

  @Test
  public void oneLongText_hasNothingAheadEitherWay() {
    AccessibilityNodeInfo text = longText();
    AccessibilityNodeInfo filler = node(null, true);
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, filler, text);
    seal(scroll, filler, text);

    assertTrue(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), true, false));
    assertTrue(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), false, false));
  }

  @Test
  public void indexedScrollView_keepsScrollingAtBothEdgesOfTheLoadedItems() {
    AccessibilityNodeInfo first = node("First loaded item", true);
    AccessibilityNodeInfo last = node("Last loaded item", true);
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, first, last);
    wrap(scroll).addAction(AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION);
    seal(scroll, first, last);

    // A collection can report a ScrollView role without CollectionInfo and load more items later.
    assertTrue(OffscreenContent.keepsAllContent(wrap(scroll)));
    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(last), true, false));
    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(first), false, false));
  }

  @Test
  public void offscreenButtonAfterText_isAheadForwardOnly() {
    AccessibilityNodeInfo text = longText();
    AccessibilityNodeInfo button = offscreenButton();
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, text, button);
    seal(scroll, text, button);

    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), true, false));
    assertTrue(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), false, false));
  }

  @Test
  public void offscreenButtonBeforeText_isAheadBackwardOnly() {
    AccessibilityNodeInfo text = longText();
    AccessibilityNodeInfo button = offscreenButton();
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, button, text);
    seal(scroll, button, text);

    assertTrue(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), true, false));
    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), false, false));
  }

  @Test
  public void nestedScrollingContainerAfterText_mayHoldMore() {
    AccessibilityNodeInfo message = longText();
    AccessibilityNodeInfo nested = node(null, true);
    nested.setClassName("android.widget.HorizontalScrollView");
    nested.setScrollable(true);
    AccessibilityNodeInfo next = node("Next", false);
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(nested, next);
    addChildren(scroll, message, nested);
    seal(scroll, message, nested, next);

    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(message), true, false));
  }

  @Test
  public void shadeLeavesRowsPastTheEdgeOutOfTheTree_scrollsOnToThem() {
    // The notification shade's list says it is a scroll view, but rows past its edge aren't in
    // the tree, so nothing in the tree reaches past the edge.
    AccessibilityNodeInfo first = row("Message 1", 0, 450);
    AccessibilityNodeInfo last = row("Message 2", 450, 900);
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, first, last);
    seal(scroll, first, last);

    assertTrue(OffscreenContent.keepsAllContent(wrap(scroll)));
    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(last), true, false));
    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(first), false, false));
  }

  @Test
  public void shadeRowCutOffAtTheEdge_scrollsOnToTheRowsPastIt() {
    // The last row shown is cut off by the shade's bottom edge; the rows past it aren't in the
    // tree.
    AccessibilityNodeInfo first = row("Message 1", 0, 600);
    AccessibilityNodeInfo last = row("Message 2", 600, 1000);
    last.setBoundsInParent(new Rect(0, 0, 900, 450));
    AccessibilityNodeInfo scroll = scrollView();
    scroll.setPackageName("com.android.systemui");
    addChildren(scroll, first, last);
    seal(scroll, first, last);

    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(last), true, false));
  }

  @Test
  public void silentContentPastTheEdge_hasNothingAhead() {
    AccessibilityNodeInfo text = row("Changes", 0, 900);
    AccessibilityNodeInfo spacer = node(null, false);
    spacer.setBoundsInScreen(new Rect());
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, text, spacer);
    seal(scroll, text, spacer);

    assertTrue(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), true, false));
  }

  @Test
  public void pivotIsTheScrollView_scrollsAsBefore() {
    AccessibilityNodeInfo text = longText();
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, text);
    seal(scroll, text);

    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(scroll), true, false));
  }

  @Test
  public void pivotOutsideTheScrollView_scrollsAsBefore() {
    AccessibilityNodeInfo inTree = longText();
    AccessibilityNodeInfo text = longText();
    AccessibilityNodeInfo scroll = scrollView();
    addChildren(scroll, inTree);
    seal(scroll, inTree, text);

    assertFalse(OffscreenContent.hasNothingAhead(wrap(scroll), wrap(text), true, false));
  }

  @Test
  public void notificationShadeList_doesNotKeepAllContent() {
    AccessibilityNodeInfo scroll = scrollView();
    scroll.setViewIdResourceName("com.android.systemui:id/notification_stack_scroller");
    seal(scroll);

    assertFalse(OffscreenContent.keepsAllContent(wrap(scroll)));
  }

  private static AccessibilityNodeInfo node(String text, boolean visible) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setText(text);
    node.setVisibleToUser(visible);
    node.setScreenReaderFocusable(false);
    node.setBoundsInParent(new Rect(0, 0, 100, 40));
    node.setBoundsInScreen(new Rect(0, 0, 100, 40));
    return node;
  }

  /** A scroll view 1000 px tall on screen. */
  private static AccessibilityNodeInfo scrollView() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.ScrollView");
    node.setScrollable(true);
    node.setVisibleToUser(true);
    node.setScreenReaderFocusable(false);
    node.setBoundsInParent(new Rect(0, 0, 900, 1000));
    node.setBoundsInScreen(new Rect(0, 0, 900, 1000));
    return node;
  }

  /** A text 3000 px tall, of which the top 1000 px are on screen. */
  private static AccessibilityNodeInfo longText() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.TextView");
    node.setText("Changes");
    node.setVisibleToUser(true);
    node.setBoundsInParent(new Rect(0, 0, 900, 3000));
    node.setBoundsInScreen(new Rect(0, 0, 900, 1000));
    return node;
  }

  /** A button scrolled out of view. */
  private static AccessibilityNodeInfo offscreenButton() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.Button");
    node.setText("OK");
    node.setFocusable(true);
    node.setClickable(true);
    node.setVisibleToUser(false);
    node.setBoundsInParent(new Rect(0, 0, 900, 100));
    node.setBoundsInScreen(new Rect());
    return node;
  }

  /** A notification row of the shade, fully on screen from {@code top} to {@code bottom}. */
  private static AccessibilityNodeInfo row(String title, int top, int bottom) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.FrameLayout");
    node.setText(title);
    node.setFocusable(true);
    node.setVisibleToUser(true);
    node.setBoundsInParent(new Rect(0, 0, 900, bottom - top));
    node.setBoundsInScreen(new Rect(0, top, 900, bottom));
    return node;
  }

  private static void addChildren(
      AccessibilityNodeInfo parent, AccessibilityNodeInfo... children) {
    for (AccessibilityNodeInfo child : children) {
      shadowOf(parent).addChild(child);
    }
  }

  private static void seal(AccessibilityNodeInfo... nodes) {
    for (AccessibilityNodeInfo node : nodes) {
      ReflectionHelpers.callInstanceMethod(
          node, "setSealed", ClassParameter.from(boolean.class, true));
    }
  }

  private static AccessibilityNodeInfoCompat wrap(AccessibilityNodeInfo node) {
    return AccessibilityNodeInfoCompat.wrap(node);
  }
}
