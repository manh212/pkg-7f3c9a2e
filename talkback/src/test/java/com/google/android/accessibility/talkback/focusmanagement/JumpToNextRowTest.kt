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

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Checks when a swipe jumps straight to the next row of a list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JumpToNextRowTest {
  @Test
  fun itemClearOfTheListEdgesNeedsNoScroll() {
    val list = Rect(0, 0, 1080, 2520)
    assertTrue(FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(0, 900, 1080, 1400), list))
    assertFalse(FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(0, 2000, 1080, 2520), list))
    assertFalse(FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(0, 0, 1080, 400), list))
    assertFalse(FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(), list))
  }

  @Test
  fun lastWholeRowAboveAPartlyShownRowNeedsTheEdgeCheck() {
    // #76: Xiaomi Control Panel app list. The next row shows 24 px below the focused row, and
    // a show-on-screen scroll of the focused row reveals it.
    val list = Rect(0, 713, 1220, 2656)
    assertFalse(
      FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(44, 2374, 1176, 2632), list)
    )
    // The row above it has a whole row's room below it.
    assertTrue(
      FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(44, 2116, 1176, 2374), list)
    )
  }

  @Test
  fun cardBesideAPartlyShownCardNeedsTheEdgeCheck() {
    val carousel = Rect(0, 500, 1080, 900)
    // A card at the start of a horizontal list, the next card partly shown after it.
    assertFalse(
      FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(40, 520, 860, 880), carousel)
    )
    // A tile with a whole tile's room on each side.
    assertTrue(
      FocusProcessorForLogicalNavigation.isClearOfEdges(Rect(400, 520, 680, 880), carousel)
    )
  }

  @Test
  fun gridTargetAtOrPastTheSuggestedCellIsTheNextItem() {
    // File picker: suggested row 11 holds a section break; the next file is row 12.
    assertTrue(FocusProcessorForLogicalNavigation.isAtOrPastCell(12, 0, 11, 0, true))
    assertTrue(FocusProcessorForLogicalNavigation.isAtOrPastCell(11, 0, 11, 0, true))
    assertFalse(FocusProcessorForLogicalNavigation.isAtOrPastCell(10, 2, 11, 0, true))
    // Going back, the cell before the suggested one is past it.
    assertTrue(FocusProcessorForLogicalNavigation.isAtOrPastCell(9, 3, 10, 3, false))
    assertFalse(FocusProcessorForLogicalNavigation.isAtOrPastCell(11, 0, 10, 3, false))
  }

  @Test
  fun targetBeforeOrHoldingThePivotGoesBack() {
    // Facebook: the Remove button of a person, inside that person's card, after a page scroll.
    val remove = Rect(666, 684, 1053, 765)
    val card = Rect(0, 538, 1080, 783)
    val nextCard = Rect(0, 783, 1080, 1028)
    assertTrue(FocusProcessorForLogicalNavigation.goesBackFromPivot(card, remove, true))
    assertTrue(
      FocusProcessorForLogicalNavigation.goesBackFromPivot(Rect(0, 293, 1080, 538), remove, true)
    )
    assertFalse(FocusProcessorForLogicalNavigation.goesBackFromPivot(nextCard, remove, true))
    // The Add Friend button left of Remove on its row comes before it.
    assertTrue(
      FocusProcessorForLogicalNavigation.goesBackFromPivot(Rect(23, 684, 640, 765), remove, true)
    )
    // In a right-to-left layout the button right of the pivot comes before it.
    assertFalse(
      FocusProcessorForLogicalNavigation.goesBackFromPivot(
        Rect(23, 684, 640, 765), remove, true, true
      )
    )
    assertTrue(
      FocusProcessorForLogicalNavigation.goesBackFromPivot(
        Rect(1060, 684, 1080, 765), remove, true, true
      )
    )
    // Going up, the card before the button is the way on.
    assertFalse(FocusProcessorForLogicalNavigation.goesBackFromPivot(card, remove, false))
    assertTrue(FocusProcessorForLogicalNavigation.goesBackFromPivot(nextCard, remove, false))
    assertFalse(FocusProcessorForLogicalNavigation.goesBackFromPivot(Rect(), remove, true))
  }

  @Test
  fun cardHoldsItsButton() {
    val remove = Rect(666, 684, 1053, 765)
    assertTrue(FocusProcessorForLogicalNavigation.holdsPivot(Rect(0, 538, 1080, 783), remove))
    assertFalse(FocusProcessorForLogicalNavigation.holdsPivot(Rect(remove), remove))
    assertFalse(FocusProcessorForLogicalNavigation.holdsPivot(Rect(0, 783, 1080, 1028), remove))
  }

  @Test
  fun otherWindowsScrollEventsAreLeftOutWhileAListIsFollowed() {
    assertTrue(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, 3, 1000, 1500))
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(3, 3, 1000, 1500))
    // Long after the scroll, or with no list followed, every event counts.
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, 3, 1000, 3000))
    assertFalse(FocusProcessorForLogicalNavigation.isOtherWindowScroll(7, -1, 1000, 1500))
  }

  @Test
  fun learnedPageNeedsTheSameListHeight() {
    assertTrue(FocusProcessorForLogicalNavigation.sameHeight(2000, 2000))
    assertTrue(FocusProcessorForLogicalNavigation.sameHeight(2000, 1950))
    // The keyboard came up or went away.
    assertFalse(FocusProcessorForLogicalNavigation.sameHeight(1100, 2000))
    assertFalse(FocusProcessorForLogicalNavigation.sameHeight(null, 2000))
  }

  @Test
  fun rememberedPlaceFollowsPixelScrolls() {
    assertEquals(500, TraversalTreeCache.placeAfterScrolls(2000, 1500))
    // Compose deltas of 1 per page aren't pixels.
    assertEquals(2000, TraversalTreeCache.placeAfterScrolls(2000, 16))
    assertEquals(Int.MIN_VALUE, TraversalTreeCache.placeAfterScrolls(Int.MIN_VALUE, 1500))
  }

  @Test
  fun itemLeftOnScreenByAPageWasDrawnAgain() {
    val list = Rect(0, 300, 1080, 2100)
    // Moved up 1500 px to 200..500: still in the list.
    assertTrue(ScrollAhead.stillOnScreenAfter(Rect(0, 1700, 1080, 2000), 1500, list))
    // Moved up 2000 px: gone above the list.
    assertFalse(ScrollAhead.stillOnScreenAfter(Rect(0, 1700, 1080, 2000), 2000, list))
    // Compose deltas tell nothing.
    assertFalse(ScrollAhead.stillOnScreenAfter(Rect(0, 1700, 1080, 2000), 1, list))
    assertFalse(ScrollAhead.stillOnScreenAfter(Rect(0, 1700, 1080, 2000), 1500, null))
  }

  @Test
  fun scrollViewItemsAreInItsOnlyChild() {
    // Nothing Phone (1) Settings home: a ScrollView whose one child lays out the rows.
    val scrollView = node()
    val layout = node(scrollView)
    val row = node(layout)
    node(layout)
    assertEquals(layout, FocusProcessorForLogicalNavigation.itemParentOf(scrollView, row))
  }

  @Test
  fun listWithManyChildrenHoldsItsItems() {
    val list = node()
    val row = node(list)
    node(list)
    assertEquals(list, FocusProcessorForLogicalNavigation.itemParentOf(list, row))
  }

  @Test
  fun onlyChildThatTakesFocusIsAnItem() {
    val scrollView = node()
    val row = node(scrollView)
    node(row)
    node(row)
    assertEquals(scrollView, FocusProcessorForLogicalNavigation.itemParentOf(scrollView, row))
    val other = node()
    val clickable = node(other)
    clickable.unwrap().isClickable = true
    node(clickable)
    node(clickable)
    assertEquals(other, FocusProcessorForLogicalNavigation.itemParentOf(other, node()))
  }

  private fun node(parent: AccessibilityNodeInfoCompat? = null): AccessibilityNodeInfoCompat {
    val raw = AccessibilityNodeInfo.obtain()
    // Distinct sources keep the nodes unequal.
    raw.setSource(View(RuntimeEnvironment.getApplication()))
    raw.className = "android.view.View"
    raw.isVisibleToUser = true
    raw.setBoundsInScreen(Rect(0, 0, 100, 100))
    val node = AccessibilityNodeInfoCompat.wrap(raw)
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(raw)
    }
    return node
  }
}
