/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Checks the pure functions behind speaking the next item while a list scrolls: whether the list
 * still moves, whether its followed part has slowed down, whether it has stopped, whether it
 * scrolls a page in one scroll event, which item is past the pivot's, and which nodes take focus.
 * Mirrors the speak-while-scrolling cases of SandFly's JumpToNextRowTest, ScrollsSidewaysTest and
 * ListRowOrderTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SpeakWhileScrollingTest {

  @Test
  public void deepScrollOrderWaitsForStableList() {
    assertTrue(FocusProcessorForLogicalNavigation.shouldWaitForStableScrollOrder(true));
    assertFalse(FocusProcessorForLogicalNavigation.shouldWaitForStableScrollOrder(false));
  }

  @Test
  public void listWithoutScrollEventsWaitsBriefly() {
    assertTrue(FocusProcessorForLogicalNavigation.listStillMoving(1179, 1000, 0, 0, 0, 0));
    assertFalse(FocusProcessorForLogicalNavigation.listStillMoving(1180, 1000, 0, 0, 0, 0));
  }

  @Test
  public void listMovingAtSpeedKeepsWaiting() {
    // 20 px just after the scroll, then a full step: speeding up.
    assertTrue(FocusProcessorForLogicalNavigation.listStillMoving(1110, 1000, 2, 1100, 600, 20));
    // A step of at least half the one before.
    assertTrue(FocusProcessorForLogicalNavigation.listStillMoving(1210, 1000, 3, 1200, 300, 600));
    // One scroll event: nothing to compare yet.
    assertTrue(FocusProcessorForLogicalNavigation.listStillMoving(1110, 1000, 1, 1100, 5, 900));
  }

  @Test
  public void listSlowingDownCountsAsStopped() {
    assertFalse(FocusProcessorForLogicalNavigation.listStillMoving(1210, 1000, 3, 1200, 250, 600));
  }

  @Test
  public void listWithoutRecentScrollEventCountsAsStopped() {
    assertFalse(FocusProcessorForLogicalNavigation.listStillMoving(1250, 1000, 2, 1100, 600, 600));
  }

  @Test
  public void waitEndsAfterMaximum() {
    assertFalse(FocusProcessorForLogicalNavigation.listStillMoving(1270, 1000, 3, 1260, 600, 600));
  }

  @Test
  public void trackerIsTheSmallPartThatLeavesFirst() {
    Rect list = new Rect(0, 200, 1000, 2200);
    List<Rect> parts =
        Arrays.asList(
            new Rect(0, 200, 1000, 2200), // as tall as the list
            new Rect(125, 387, 1026, 472),
            new Rect(125, 1500, 1026, 1600),
            new Rect(0, 0, 0, 0), // empty
            new Rect(125, 2400, 1026, 2500)); // off screen
    assertEquals(1, FocusProcessorForLogicalNavigation.pickTracker(list, parts, true));
    assertEquals(2, FocusProcessorForLogicalNavigation.pickTracker(list, parts, false));
    assertEquals(
        -1,
        FocusProcessorForLogicalNavigation.pickTracker(
            list, Arrays.asList(new Rect(list)), true));
  }

  @Test
  public void trackerStepsSlowDownOnlyAtTheEnd() {
    // Steps of a scrolled message, every 30 ms.
    int[] steps = {542, 501, 301, 261, 165, 89, 56, 35};
    boolean moved = false;
    int previous = 0;
    for (int step : steps) {
      assertFalse(FocusProcessorForLogicalNavigation.trackerSlowed(step, previous, moved));
      moved = true;
      previous = step;
    }
    assertTrue(FocusProcessorForLogicalNavigation.trackerSlowed(3, 35, true));
    assertTrue(FocusProcessorForLogicalNavigation.trackerSlowed(100, 542, true));
  }

  @Test
  public void trackerThatHasNotMovedHasNotSlowed() {
    assertFalse(FocusProcessorForLogicalNavigation.trackerSlowed(0, 0, false));
  }

  @Test
  public void clippedTrackerTouchesTheListsEdge() {
    Rect list = new Rect(0, 0, 1080, 2520);
    assertTrue(
        FocusProcessorForLogicalNavigation.touchesEdge(new Rect(0, 0, 1080, 2000), list));
    assertTrue(
        FocusProcessorForLogicalNavigation.touchesEdge(new Rect(0, 361, 1080, 2520), list));
    // A part reported where it is, off screen.
    assertFalse(
        FocusProcessorForLogicalNavigation.touchesEdge(new Rect(125, -500, 1026, -400), list));
    assertFalse(
        FocusProcessorForLogicalNavigation.touchesEdge(new Rect(125, 900, 1026, 1000), list));
  }

  @Test
  public void watchedListStopsOnlyAfterItsScrollEvents() {
    int moving = FocusProcessorForLogicalNavigation.LIST_MOVING;
    int stopped = FocusProcessorForLogicalNavigation.LIST_STOPPED;
    int notScrolled = FocusProcessorForLogicalNavigation.LIST_NOT_SCROLLED;
    assertEquals(moving, FocusProcessorForLogicalNavigation.watchedListState(100, 0, 100));
    assertEquals(notScrolled, FocusProcessorForLogicalNavigation.watchedListState(180, 0, 180));
    assertEquals(moving, FocusProcessorForLogicalNavigation.watchedListState(300, 3, 60));
    assertEquals(stopped, FocusProcessorForLogicalNavigation.watchedListState(500, 5, 150));
    assertEquals(stopped, FocusProcessorForLogicalNavigation.watchedListState(1500, 9, 10));
  }

  @Test
  public void oneEventListStopsAFrameAfterItsEvent() {
    int moving = FocusProcessorForLogicalNavigation.LIST_MOVING;
    int stopped = FocusProcessorForLogicalNavigation.LIST_STOPPED;
    int notScrolled = FocusProcessorForLogicalNavigation.LIST_NOT_SCROLLED;
    assertEquals(moving, FocusProcessorForLogicalNavigation.watchedListState(30, 1, 10, true));
    assertEquals(stopped, FocusProcessorForLogicalNavigation.watchedListState(40, 1, 16, true));
    assertEquals(moving, FocusProcessorForLogicalNavigation.watchedListState(40, 1, 16, false));
    // No event yet: waits for it as any list does.
    assertEquals(moving, FocusProcessorForLogicalNavigation.watchedListState(100, 0, 100, true));
    assertEquals(
        notScrolled, FocusProcessorForLogicalNavigation.watchedListState(180, 0, 180, true));
    assertFalse(
        FocusProcessorForLogicalNavigation.listStillMoving(1040, 1000, 1, 1020, 1, 1, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.listStillMoving(1040, 1000, 1, 1020, 1, 1, false));
    assertTrue(
        FocusProcessorForLogicalNavigation.listStillMoving(1030, 1000, 1, 1020, 1, 1, true));
    assertTrue(FocusProcessorForLogicalNavigation.listStillMoving(1100, 1000, 0, 0, 0, 0, true));
  }

  @Test
  public void oneEventListNeedsTwoOneEventScrollsInARow() {
    assertEquals(1, FocusProcessorForLogicalNavigation.nextOneEventScrolls(0, true));
    assertEquals(2, FocusProcessorForLogicalNavigation.nextOneEventScrolls(1, true));
    assertEquals(2, FocusProcessorForLogicalNavigation.nextOneEventScrolls(2, true));
    assertEquals(0, FocusProcessorForLogicalNavigation.nextOneEventScrolls(2, false));
    assertEquals(0, FocusProcessorForLogicalNavigation.nextOneEventScrolls(1, false));
  }

  @Test
  public void oneEventScrollIsToldAfterTheQuietTime() {
    assertEquals(Boolean.TRUE, FocusProcessorForLogicalNavigation.oneEventScroll(1, 150));
    assertNull(FocusProcessorForLogicalNavigation.oneEventScroll(1, 16));
    assertNull(FocusProcessorForLogicalNavigation.oneEventScroll(0, 200));
    assertEquals(Boolean.FALSE, FocusProcessorForLogicalNavigation.oneEventScroll(2, 16));
  }

  @Test
  public void nextItemMeetsThePivotsItem() {
    Rect item = new Rect(0, 1000, 1080, 1300);
    assertTrue(
        FocusProcessorForLogicalNavigation.meetsItem(
            new Rect(0, 1300, 1080, 1600), item, true, 2000));
    // A gap of a whole row: another item may lie between.
    assertFalse(
        FocusProcessorForLogicalNavigation.meetsItem(
            new Rect(0, 1600, 1080, 1900), item, true, 2000));
    assertTrue(
        FocusProcessorForLogicalNavigation.meetsItem(
            new Rect(0, 700, 1080, 1000), item, false, 2000));
    assertFalse(
        FocusProcessorForLogicalNavigation.meetsItem(
            new Rect(0, 400, 1080, 700), item, false, 2000));
  }

  @Test
  public void targetBeforeOrHoldingThePivotGoesBack() {
    // Facebook: the Remove button of a person, inside that person's card, after a page scroll.
    Rect remove = new Rect(666, 684, 1053, 765);
    Rect card = new Rect(0, 538, 1080, 783);
    Rect nextCard = new Rect(0, 783, 1080, 1028);
    assertTrue(FocusProcessorForLogicalNavigation.goesBackFromPivot(card, remove, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.goesBackFromPivot(
            new Rect(0, 293, 1080, 538), remove, true));
    assertFalse(FocusProcessorForLogicalNavigation.goesBackFromPivot(nextCard, remove, true));
    // The Add Friend button left of Remove on its row comes before it.
    assertTrue(
        FocusProcessorForLogicalNavigation.goesBackFromPivot(
            new Rect(23, 684, 640, 765), remove, true));
    // In a right-to-left layout the button right of the pivot comes before it.
    assertFalse(
        FocusProcessorForLogicalNavigation.goesBackFromPivot(
            new Rect(23, 684, 640, 765), remove, true, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.goesBackFromPivot(
            new Rect(1060, 684, 1080, 765), remove, true, true));
    // Going up, the card before the button is the way on.
    assertFalse(FocusProcessorForLogicalNavigation.goesBackFromPivot(card, remove, false));
    assertTrue(FocusProcessorForLogicalNavigation.goesBackFromPivot(nextCard, remove, false));
    assertFalse(
        FocusProcessorForLogicalNavigation.goesBackFromPivot(new Rect(), remove, true));
  }

  @Test
  public void cardHoldsItsButton() {
    Rect remove = new Rect(666, 684, 1053, 765);
    assertTrue(
        FocusProcessorForLogicalNavigation.holdsPivot(new Rect(0, 538, 1080, 783), remove));
    assertFalse(FocusProcessorForLogicalNavigation.holdsPivot(new Rect(remove), remove));
    assertFalse(
        FocusProcessorForLogicalNavigation.holdsPivot(new Rect(0, 783, 1080, 1028), remove));
  }

  @Test
  public void itemPastThePivotsItem() {
    Rect pivotItem = new Rect(0, 200, 1080, 1500);
    assertTrue(
        FocusProcessorForLogicalNavigation.isPastItem(
            new Rect(0, 1500, 1080, 2520), pivotItem, true));
    assertFalse(
        FocusProcessorForLogicalNavigation.isPastItem(
            new Rect(0, 0, 1080, 200), pivotItem, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.isPastItem(
            new Rect(0, 0, 1080, 200), pivotItem, false));
    // The pivot's item has left the screen: every item on screen is past it.
    assertTrue(
        FocusProcessorForLogicalNavigation.isPastItem(new Rect(0, 300, 1080, 600), null, true));
    assertFalse(FocusProcessorForLogicalNavigation.isPastItem(new Rect(), null, true));
  }

  @Test
  public void pivotsItemLeavesRoomForTheNextItem() {
    Rect list = new Rect(0, 0, 1080, 2520);
    // Going back, the item above appears once the pivot's item moves down from the top.
    assertFalse(
        FocusProcessorForLogicalNavigation.leavesRoomPast(
            new Rect(0, 0, 1080, 2280), list, false));
    assertTrue(
        FocusProcessorForLogicalNavigation.leavesRoomPast(
            new Rect(0, 400, 1080, 2520), list, false));
    assertFalse(
        FocusProcessorForLogicalNavigation.leavesRoomPast(
            new Rect(0, 400, 1080, 2520), list, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.leavesRoomPast(
            new Rect(0, 0, 1080, 2000), list, true));
    assertTrue(FocusProcessorForLogicalNavigation.leavesRoomPast(null, list, true));
  }

  @Test
  public void cutOffItemIsShownAfterTheScroll() {
    Rect list = new Rect(0, 200, 1080, 2520);
    assertTrue(
        FocusProcessorForLogicalNavigation.isCutOff(new Rect(0, 2300, 1080, 2520), list));
    assertTrue(
        FocusProcessorForLogicalNavigation.isCutOff(new Rect(0, 200, 1080, 400), list));
    assertFalse(
        FocusProcessorForLogicalNavigation.isCutOff(new Rect(0, 600, 1080, 900), list));
    // An item as tall as the list can't be shown whole.
    assertFalse(
        FocusProcessorForLogicalNavigation.isCutOff(new Rect(0, 200, 1080, 2520), list));
  }

  @Test
  public void moveFromTheLastRowGoesPastTheList() {
    assertTrue(FocusProcessorForLogicalNavigation.isPastLastRow(11, 11));
    assertTrue(FocusProcessorForLogicalNavigation.isPastLastRow(-1, 11));
    assertFalse(FocusProcessorForLogicalNavigation.isPastLastRow(10, 11));
    // Row count not known.
    assertFalse(FocusProcessorForLogicalNavigation.isPastLastRow(11, -1));
    assertFalse(FocusProcessorForLogicalNavigation.isPastLastRow(-1, 0));
  }

  @Test
  public void itemShownWholeOnlyWhenNotCutOff() {
    Rect list = new Rect(0, 320, 1080, 2378);
    // A last message, 293 px, ending at the list's bottom.
    assertTrue(
        FocusProcessorForLogicalNavigation.isShownWhole(
            new Rect(0, 0, 1080, 293), new Rect(0, 2085, 1080, 2378), list));
    // Taller than shown: cut off at the bottom.
    assertFalse(
        FocusProcessorForLogicalNavigation.isShownWhole(
            new Rect(0, 0, 1080, 900), new Rect(0, 2085, 1080, 2378), list));
    assertFalse(
        FocusProcessorForLogicalNavigation.isShownWhole(
            new Rect(0, 0, 1080, 293), new Rect(0, 0, 0, 0), list));
  }

  @Test
  public void verticalScrollViewDoesNotScrollSideways() {
    assertFalse(
        FocusProcessorForLogicalNavigation.scrollsSideways(
            scrollNode(
                "android.widget.ScrollView",
                AccessibilityAction.ACTION_SCROLL_FORWARD,
                AccessibilityAction.ACTION_SCROLL_DOWN)));
  }

  @Test
  public void verticalListDoesNotScrollSideways() {
    assertFalse(
        FocusProcessorForLogicalNavigation.scrollsSideways(
            scrollNode(
                "androidx.recyclerview.widget.RecyclerView",
                new AccessibilityNodeInfo.CollectionInfo(20, 1, false),
                AccessibilityAction.ACTION_SCROLL_FORWARD)));
  }

  @Test
  public void pagerWithPageActionsScrollsSideways() {
    assertTrue(
        FocusProcessorForLogicalNavigation.scrollsSideways(
            scrollNode(
                "androidx.viewpager.widget.ViewPager",
                AccessibilityAction.ACTION_SCROLL_FORWARD,
                AccessibilityAction.ACTION_PAGE_RIGHT)));
  }

  @Test
  public void horizontalScrollViewScrollsSideways() {
    assertTrue(
        FocusProcessorForLogicalNavigation.scrollsSideways(
            scrollNode(
                "android.widget.HorizontalScrollView",
                AccessibilityAction.ACTION_SCROLL_FORWARD)));
  }

  @Test
  public void oneRowListScrollsSideways() {
    assertTrue(
        FocusProcessorForLogicalNavigation.scrollsSideways(
            scrollNode(
                "androidx.recyclerview.widget.RecyclerView",
                new AccessibilityNodeInfo.CollectionInfo(1, 8, false),
                AccessibilityAction.ACTION_SCROLL_FORWARD)));
  }

  @Test
  public void plainItemHasNoPartsTakingFocus() {
    AccessibilityNodeInfoCompat item = plainNode(null);
    plainNode(item);
    plainNode(item);
    assertFalse(FocusProcessorForLogicalNavigation.hasPartsTakingFocus(item, item));
  }

  @Test
  public void clickableDescendantTakesFocus() {
    AccessibilityNodeInfoCompat item = plainNode(null);
    AccessibilityNodeInfoCompat layout = plainNode(item);
    plainNode(layout).unwrap().setClickable(true);
    assertTrue(FocusProcessorForLogicalNavigation.hasPartsTakingFocus(item, item));
  }

  @Test
  public void adjustableSliderTakesFocus() {
    AccessibilityNodeInfoCompat item = plainNode(null);
    plainNode(item).unwrap().setClassName("android.widget.SeekBar");
    assertTrue(FocusProcessorForLogicalNavigation.hasPartsTakingFocus(item, item));
  }

  @Test
  public void pivotInsideItemDoesNotCount() {
    AccessibilityNodeInfoCompat item = plainNode(null);
    AccessibilityNodeInfoCompat pivot = plainNode(item);
    pivot.unwrap().setFocusable(true);
    assertFalse(FocusProcessorForLogicalNavigation.hasPartsTakingFocus(item, pivot));
  }

  @Test
  public void tooManyNodesCountAsPartsTakingFocus() {
    AccessibilityNodeInfoCompat item = plainNode(null);
    for (int i = 0; i < 101; i++) {
      plainNode(item);
    }
    assertTrue(FocusProcessorForLogicalNavigation.hasPartsTakingFocus(item, item));
  }

  @Test
  public void rowOfAnItemAndOfAPartInsideIt() {
    AccessibilityNodeInfoCompat list = list(1);
    AccessibilityNodeInfoCompat item = row(list, 7, 0);
    AccessibilityNodeInfoCompat part = textNode("part", item);
    assertEquals(7, FocusProcessorForLogicalNavigation.rowOf(item).row());
    assertEquals(7, FocusProcessorForLogicalNavigation.rowOf(part).row());
    assertEquals(1, FocusProcessorForLogicalNavigation.rowOf(part).span());
  }

  @Test
  public void noRowOutsideAOneColumnList() {
    assertNull(FocusProcessorForLogicalNavigation.rowOf(textNode("loose", null)));
    AccessibilityNodeInfoCompat grid = list(3);
    assertNull(FocusProcessorForLogicalNavigation.rowOf(row(grid, 2, 0)));
  }

  @Test
  public void nextRowFollowsTheWayIndexesRun() {
    FocusProcessorForLogicalNavigation.ListRow from =
        new FocusProcessorForLogicalNavigation.ListRow(list(1), 10, 2);
    assertEquals(12, FocusProcessorForLogicalNavigation.rowAfterMove(from, true, false));
    assertEquals(9, FocusProcessorForLogicalNavigation.rowAfterMove(from, false, false));
    // A chat laid out from the bottom: the next item down has the lower index.
    assertEquals(9, FocusProcessorForLogicalNavigation.rowAfterMove(from, true, true));
    assertEquals(12, FocusProcessorForLogicalNavigation.rowAfterMove(from, false, true));
  }

  @Test
  public void searchFromAReusedPivotLiesPastTheNextRow() {
    // After a page scroll from row 12: rows 13 to 21 on screen, and the search from the pivot,
    // whose view now shows row 19, found row 20.
    AccessibilityNodeInfoCompat list = list(1);
    List<AccessibilityNodeInfoCompat> rows = new ArrayList<>();
    for (int i = 13; i <= 21; i++) {
      rows.add(row(list, i, (i - 13) * 200));
    }
    assertTrue(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(rows.get(7)), list, 12, 13));
    assertFalse(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(rows.get(0)), list, 12, 13));
    // Going back from row 22: row 21 is next, and row 13 lies past it.
    assertTrue(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(rows.get(0)), list, 22, 21));
    assertFalse(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(rows.get(8)), list, 22, 21));
  }

  @Test
  public void nodeOfAnotherListDoesNotLiePastTheRow() {
    AccessibilityNodeInfoCompat list = list(1);
    row(list, 13, 0);
    AccessibilityNodeInfoCompat other = list(1);
    AccessibilityNodeInfoCompat elsewhere = row(other, 40, 0);
    assertFalse(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(elsewhere), list, 12, 13));
    assertFalse(
        FocusProcessorForLogicalNavigation.liesPastRow(
            FocusProcessorForLogicalNavigation.rowOf(textNode("loose", null)), list, 12, 13));
  }

  @Test
  public void itemAtRowIsTheVisibleChildWithThatRow() {
    AccessibilityNodeInfoCompat list = list(1);
    row(list, 13, 0);
    row(list, 14, 200);
    row(list, 15, 400).unwrap().setVisibleToUser(false);
    assertEquals(
        "row 14", FocusProcessorForLogicalNavigation.itemAtRow(list, 14).getText().toString());
    assertNull(FocusProcessorForLogicalNavigation.itemAtRow(list, 15));
    assertNull(FocusProcessorForLogicalNavigation.itemAtRow(list, 30));
  }

  @Test
  public void scrollViewItemsAreInItsOnlyChild() {
    // A ScrollView whose one child lays out the rows.
    AccessibilityNodeInfoCompat scrollView = plainNode(null);
    AccessibilityNodeInfoCompat layout = plainNode(scrollView);
    AccessibilityNodeInfoCompat row = plainNode(layout);
    plainNode(layout);
    assertEquals(layout, FocusProcessorForLogicalNavigation.itemParentOf(scrollView, row));
  }

  @Test
  public void listWithManyChildrenHoldsItsItems() {
    AccessibilityNodeInfoCompat list = plainNode(null);
    AccessibilityNodeInfoCompat row = plainNode(list);
    plainNode(list);
    assertEquals(list, FocusProcessorForLogicalNavigation.itemParentOf(list, row));
  }

  @Test
  public void onlyChildThatTakesFocusIsAnItem() {
    AccessibilityNodeInfoCompat scrollView = plainNode(null);
    AccessibilityNodeInfoCompat row = plainNode(scrollView);
    plainNode(row);
    plainNode(row);
    assertEquals(scrollView, FocusProcessorForLogicalNavigation.itemParentOf(scrollView, row));
    AccessibilityNodeInfoCompat other = plainNode(null);
    AccessibilityNodeInfoCompat clickable = plainNode(other);
    clickable.unwrap().setClickable(true);
    plainNode(clickable);
    plainNode(clickable);
    assertEquals(other, FocusProcessorForLogicalNavigation.itemParentOf(other, plainNode(null)));
  }

  private static AccessibilityNodeInfoCompat scrollNode(
      String className,
      AccessibilityNodeInfo.CollectionInfo collection,
      AccessibilityAction... actions) {
    AccessibilityNodeInfo node = new AccessibilityNodeInfo();
    node.setClassName(className);
    node.setScrollable(true);
    for (AccessibilityAction action : actions) {
      node.addAction(action);
    }
    node.setCollectionInfo(collection);
    return AccessibilityNodeInfoCompat.wrap(node);
  }

  private static AccessibilityNodeInfoCompat scrollNode(
      String className, AccessibilityAction... actions) {
    return scrollNode(className, null, actions);
  }

  private static AccessibilityNodeInfoCompat list(int columns) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    raw.setClassName("androidx.recyclerview.widget.RecyclerView");
    raw.setScrollable(true);
    raw.setVisibleToUser(true);
    raw.setBoundsInScreen(new Rect(0, 0, 1080, 2000));
    raw.setCollectionInfo(new AccessibilityNodeInfo.CollectionInfo(50, columns, false));
    return AccessibilityNodeInfoCompat.wrap(raw);
  }

  private static AccessibilityNodeInfoCompat row(
      AccessibilityNodeInfoCompat list, int index, int top) {
    AccessibilityNodeInfoCompat item = textNode("row " + index, list);
    item.unwrap().setBoundsInScreen(new Rect(0, top, 1080, top + 200));
    item.unwrap()
        .setCollectionItemInfo(
            new AccessibilityNodeInfo.CollectionItemInfo(index, 1, 0, 1, false));
    return item;
  }

  private static AccessibilityNodeInfoCompat textNode(
      String text, AccessibilityNodeInfoCompat parent) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    raw.setText(text);
    raw.setClassName("android.widget.TextView");
    raw.setFocusable(true);
    raw.setVisibleToUser(true);
    raw.setBoundsInScreen(new Rect(0, 0, 100, 100));
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(raw);
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(raw);
    }
    return node;
  }

  private static AccessibilityNodeInfoCompat plainNode(AccessibilityNodeInfoCompat parent) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    // Distinct sources keep the nodes unequal.
    raw.setSource(new View(RuntimeEnvironment.getApplication()));
    raw.setClassName("android.view.View");
    raw.setVisibleToUser(true);
    raw.setBoundsInScreen(new Rect(0, 0, 100, 100));
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(raw);
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(raw);
    }
    return node;
  }
}
