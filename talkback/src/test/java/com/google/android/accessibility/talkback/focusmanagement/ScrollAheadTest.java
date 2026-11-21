/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Checks the pure functions behind scrolling ahead of the focused item: whether a page leaves
 * enough of it on screen, whether a measured page can be trusted, and where the next item comes
 * after the focused one scrolled off the list. Mirrors the ScrollAhead cases of SandFly's
 * JumpToNextRowTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ScrollAheadTest {

  @Test
  public void itemStaysAfterAMeasuredPage() {
    Rect list = new Rect(0, 0, 1080, 2520);
    // Cut off at the bottom: 2520 - 2200 = 320 px stay.
    assertTrue(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 361, 1080, 2520), list, 2200, 130, true));
    // A page as tall as the list leaves nothing.
    assertFalse(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 361, 1080, 2520), list, 2500, 130, true));
    // An item ending high up leaves with the page.
    assertFalse(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 1800, 1080, 2300), list, 2200, 130, true));
  }

  @Test
  public void itemStaysAfterAPageBack() {
    Rect list = new Rect(0, 0, 1080, 2520);
    // Cut off at the top: its top moves down by the page and stays above the bottom.
    assertTrue(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 0, 1080, 2000), list, 2200, 130, false));
    assertFalse(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 400, 1080, 2000), list, 2200, 130, false));
  }

  @Test
  public void itemAtTheEdgeLeavesWithAPageNotMeasured() {
    // SandFly#72: a settings row ending at the bottom of a list 2112 px tall, whose page scrolled
    // 2120 px.
    Rect list = new Rect(0, 364, 1220, 2476);
    assertFalse(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 2280, 1220, 2476), list, 0, 183, true));
    assertFalse(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 364, 1220, 560), list, 0, 183, false));
    // Reported past the edge by more than a page and the margin.
    assertTrue(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, 1000, 1220, 2900), list, 0, 183, true));
    assertTrue(
        FocusProcessorForLogicalNavigation.staysAfterPage(
            new Rect(0, -60, 1220, 1000), list, 0, 183, false));
  }

  @Test
  public void onlyASingleQuietPageNoTallerThanTheListIsLearned() {
    assertTrue(FocusProcessorForLogicalNavigation.isReliablePage(2200, 2520, true, false));
    // Overlapping scrolls added up past the list's height.
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(2884, 2520, true, false));
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(2200, 2520, false, false));
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(2200, 2520, true, true));
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(0, 2520, true, false));
  }

  @Test
  public void pageShorterThanHalfTheListIsNotLearned() {
    // FastSM's Compose list: deltas of 1 and 2 for pages of a 2030 px list.
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(1, 2030, true, false));
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(2, 2030, true, false));
    assertFalse(FocusProcessorForLogicalNavigation.isReliablePage(1014, 2030, true, false));
    assertTrue(FocusProcessorForLogicalNavigation.isReliablePage(1015, 2030, true, false));
  }

  @Test
  public void learnedPageNeedsTheSameListHeight() {
    assertTrue(FocusProcessorForLogicalNavigation.sameHeight(2000, 2000));
    assertTrue(FocusProcessorForLogicalNavigation.sameHeight(2000, 1950));
    // The keyboard came up or went away.
    assertFalse(FocusProcessorForLogicalNavigation.sameHeight(1100, 2000));
    assertFalse(FocusProcessorForLogicalNavigation.sameHeight(null, 2000));
  }

  @Test
  public void otherWindowsScrollEventsAreLeftOutWhileAListIsFollowed() {
    assertTrue(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, 3, 1000, 1500));
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(3, 3, 1000, 1500));
    // Long after the scroll, or with no list followed, every event counts.
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, 3, 1000, 3000));
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, -1, 1000, 1500));
  }

  @Test
  public void nextItemFollowsTheScrolledAwayItem() {
    // The row at 2280-2476 scrolled 2120 px up: the next row starts at 356.
    assertEquals(356, TraversalTreeCache.placeAfterScroll(2280, 2476, 2120, true));
    // A row at the top scrolled 2000 px down: the previous row ends at 2364.
    assertEquals(2364, TraversalTreeCache.placeAfterScroll(364, 560, -2000, false));
  }

  @Test
  public void scrolledAwayItemLeftTheList() {
    // FastSM's Compose list 2030 px tall: its scroll deltas added up to 2 for a page that moved
    // the item at 2198-2486 off the top, so the next item starts at the list's top.
    Rect list = new Rect(0, 456, 1080, 2486);
    int shift = TraversalTreeCache.shiftOffList(2, new Rect(0, 2198, 1080, 2486), list, true);
    assertEquals(2030, shift);
    assertEquals(456, TraversalTreeCache.placeAfterScroll(2198, 2486, shift, true));
    // Back: the item at the top went below the list's bottom.
    assertEquals(-2028, TraversalTreeCache.shiftOffList(-1, new Rect(0, 458, 1080, 697), list, false));
    // Deltas in pixels already move the item off.
    Rect rows = new Rect(0, 364, 1220, 2476);
    assertEquals(2120, TraversalTreeCache.shiftOffList(2120, new Rect(0, 2280, 1220, 2476), rows, true));
    assertEquals(-2120, TraversalTreeCache.shiftOffList(-2120, new Rect(0, 364, 1220, 560), rows, false));
  }

  @Test
  public void swipeGoesOnInTheListOfTheScrolledAwayItem() {
    // FastSM's order: the window, the list, its items, then the title above the list.
    Rect list = new Rect(0, 456, 1080, 2486);
    List<Rect> bounds =
        Arrays.asList(
            new Rect(0, 0, 1080, 2520),
            list,
            new Rect(0, 456, 1080, 700),
            new Rect(0, 700, 1080, 1900),
            new Rect(0, 1900, 1080, 2486),
            new Rect(36, 145, 185, 202));
    List<Boolean> focusable = Arrays.asList(false, false, true, true, true, true);
    // Forward: the item at the list's top, not the title before it on screen.
    assertEquals(2, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 456, true));
    // Back: the item at the list's bottom, though the title comes after it in the order.
    assertEquals(4, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 2486, false));
    // A place between items: the next one starts below it, the previous one ends above it.
    assertEquals(3, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 650, true));
    assertEquals(3, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 2000, false));
    // No list known, or nothing of the list past the place.
    assertEquals(
        -1, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, new Rect(), 456, true));
    assertEquals(-1, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 2500, true));
  }

  @Test
  public void lastOfItemsEndingTogetherIsReachedFirstGoingBack() {
    Rect list = new Rect(0, 0, 1080, 2000);
    List<Rect> bounds =
        Arrays.asList(new Rect(0, 1500, 1080, 2000), new Rect(900, 1800, 1080, 2000));
    List<Boolean> focusable = Arrays.asList(true, true);
    assertEquals(1, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 2000, false));
    assertEquals(0, TraversalTreeCache.itemAfterScrolledAway(bounds, focusable, list, 1500, true));
  }

  @Test
  public void itemLeftOnScreenByAPageWasDrawnAgain() {
    Rect list = new Rect(0, 300, 1080, 2100);
    // Moved up 1300 px to 400..700: still in the list.
    assertTrue(ScrollAhead.stillOnScreenAfter(new Rect(0, 1700, 1080, 2000), 1300, list));
    // Moved up 1500 px to 200..500: partly above the list, so it may have gone on further.
    assertFalse(ScrollAhead.stillOnScreenAfter(new Rect(0, 1700, 1080, 2000), 1500, list));
    // Moved up 2000 px: gone above the list.
    assertFalse(ScrollAhead.stillOnScreenAfter(new Rect(0, 1700, 1080, 2000), 2000, list));
    // Compose deltas tell nothing.
    assertFalse(ScrollAhead.stillOnScreenAfter(new Rect(0, 1700, 1080, 2000), 1, list));
    assertFalse(ScrollAhead.stillOnScreenAfter(new Rect(0, 1700, 1080, 2000), 1500, null));
  }

  @Test
  public void scrollAheadTakesOverFromItsOwnPage() {
    // The list moves from a page scrolled ahead 100 ms ago, after the swipe's own scroll.
    assertTrue(ScrollAhead.isOwnPage(1900, 1000, 2000));
    // A swipe's scroll after the page: wait for it.
    assertFalse(ScrollAhead.isOwnPage(1000, 1900, 2000));
    assertFalse(ScrollAhead.isOwnPage(0, 0, 2000));
  }

  @Test
  public void scrollAheadWaitsForTheListToStop() {
    assertFalse(ScrollAhead.isQuiet(1149, 1000));
    assertTrue(ScrollAhead.isQuiet(1150, 1000));
  }

  @Test
  public void maxPagesIsBoundedForFocusStability() {
    assertTrue(ScrollAhead.MAX_PAGES >= 1 && ScrollAhead.MAX_PAGES <= 3);
  }
}
