/*
 * Copyright (C) 2026 The SandFly Authors
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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/**
 * [TraversalTreeCache.onAccessibilityEvent] reports whether it threw the saved order away, which
 * is what makes TalkBackService start a background prefetch of the window's nodes at that moment.
 * The same contract is what makes pruning queued window-content-changed events safe: the cache is
 * cleared synchronously from the event itself, so the order is gone even when the queued copy of
 * the event is later dropped by the queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraversalTreeCacheTest {

  @After
  fun tearDown() {
    TraversalTreeCache.setOrderClearedListener(null)
    TraversalTreeCache.enabled = true
    TraversalTreeCache.regionGraceEnabled = true
    TraversalTreeCache.burstLiveAnswerEnabled = true
    TraversalTreeCache.clear("test")
    TraversalTreeCache.resetOrderCacheStats()
    LogUtils.setEnabled(false)
  }

  /** Registers a listener that records the windows it is told about, and returns the record. */
  private fun recordingListener(): MutableList<Int> {
    val reported = mutableListOf<Int>()
    TraversalTreeCache.setOrderClearedListener { reported.add(it) }
    return reported
  }

  @Test
  fun disabledCache_dropsSavedOrderAndDoesNotKeepNewOrders() {
    val (root, child) = savedOrder()
    TraversalTreeCache.enabled = false
    assertNull(TraversalTreeCache.get(root, child))
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.rootFor(child))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    assertNull(TraversalTreeCache.get(root, child))
    TraversalTreeCache.enabled = true
    assertNull(TraversalTreeCache.get(root, child))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    assertNotNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun buildCount_isSeparateFromLookupMissesAndLiveAnswers() {
    LogUtils.setEnabled(true)
    TraversalTreeCache.resetOrderCacheStats()
    val root = AccessibilityNodeInfoCompat.wrap(node(null))
    TraversalTreeCache.get(root, root)
    TraversalTreeCache.noteLiveAnswer()
    assertEquals(0L, TraversalTreeCache.orderCacheBuilt())
    TraversalTreeCache.noteBuild(37)
    assertEquals(1L, TraversalTreeCache.orderCacheBuilt())
    assertTrue(TraversalTreeCache.buildTimingSummary().contains("mean=37 ms"))
  }

  private fun node(parent: AccessibilityNodeInfo?): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = "android.widget.Button"
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.setBoundsInScreen(Rect(0, 0, 100, 100))
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  /** Saves the order of a root with one child, and returns the root and that child. */
  private fun savedOrder(): Pair<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val rootInfo = node(null)
    node(rootInfo)
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val child = checkNotNull(root.getChild(0))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    return root to child
  }

  private fun event(type: Int, contentChangeTypes: Int = 0): AccessibilityEvent {
    val event = AccessibilityEvent.obtain(type)
    event.contentChangeTypes = contentChangeTypes
    return event
  }

  /** A text-only content change in [windowId], which starts the 1.5 s order age. */
  private fun textChangeEvent(windowId: Int): AccessibilityEvent {
    val change =
      event(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
      )
    shadowOf(change).setWindowId(windowId)
    return change
  }

  /**
   * Saves the order of a root with one child in window [windowId], and returns the root and
   * that child. The window id is pinned on the nodes themselves (see EventFilterPrepareTest),
   * so a deferred event can address this window exactly.
   */
  private fun savedOrderInWindow(
    windowId: Int
  ): Pair<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(windowId)
    val rootInfo = node(null)
    ReflectionHelpers.setField(rootInfo, "mWindowId", windowId)
    shadowOf(rootInfo).setAccessibilityWindowInfo(window)
    val childInfo = node(rootInfo)
    ReflectionHelpers.setField(childInfo, "mWindowId", windowId)
    shadowOf(childInfo).setAccessibilityWindowInfo(window)
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val child = checkNotNull(root.getChild(0))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    return root to child
  }

  @Test
  fun structuralEvent_clearsSavedOrder_andReportsTheClear() {
    val (root, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun windowContentChanged_undefinedChange_clearsSavedOrder_andReportsTheClear() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, contentChangeTypes = 0)
      )

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_textOnlyChange_keepsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
        )
      )

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun cacheOnlyTextKeepsOrderButRequiresFreshNodeAndSubtreeClearsOrder() {
    val (_, child) = savedOrder()
    assertTrue(TraversalTreeCache.holdsCurrent(child))

    TraversalTreeCache.onCacheOnlyEvent(event(
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT))
    assertTrue(TraversalTreeCache.holds(child))
    assertFalse(TraversalTreeCache.holdsCurrent(child))

    TraversalTreeCache.onCacheOnlyEvent(event(
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE))
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun ignoredEventType_keepsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared = TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_ANNOUNCEMENT))

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun structuralEvent_withNoSavedOrder_reportsNoClear() {
    savedOrder()
    TraversalTreeCache.clear("test")

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertFalse(cleared)
  }

  @Test
  fun nodeActionClear_reportsTheWindowToTheListener() {
    val (root, child) = savedOrder()
    val reported = recordingListener()

    TraversalTreeCache.onNodeAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)

    assertFalse(TraversalTreeCache.holds(child))
    assertEquals(listOf(root.windowId), reported)
  }

  @Test
  fun focusOnlyNodeAction_keepsTheOrder_andReportsNothing() {
    val (_, child) = savedOrder()
    val reported = recordingListener()

    TraversalTreeCache.onNodeAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)

    assertTrue(TraversalTreeCache.holds(child))
    assertTrue(reported.isEmpty())
  }

  @Test
  fun operationClear_reportsTheWindowToTheListener() {
    val (root, _) = savedOrder()
    val reported = recordingListener()

    // The clear a resume and a focus retry perform: a direct call, not an event.
    TraversalTreeCache.clear("resume")

    assertEquals(listOf(root.windowId), reported)
  }

  @Test
  fun operationClear_withNoSavedOrder_reportsNothing() {
    val reported = recordingListener()

    TraversalTreeCache.clear("resume")

    assertTrue(reported.isEmpty())
  }

  @Test
  fun eventClear_doesNotReportToTheListener() {
    savedOrder()
    val reported = recordingListener()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertTrue(cleared)
    // The service warms the window from the event itself; the listener is only for the
    // clears no event reports.
    assertTrue(reported.isEmpty())
  }

  @Test
  fun orderExpiredAfterTextChange_doesNotReportToTheListener() {
    val (root, child) = savedOrder()
    val reported = recordingListener()
    TraversalTreeCache.onAccessibilityEvent(
      event(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
      )
    )

    ShadowSystemClock.advanceBy(Duration.ofMillis(5_001))
    assertNull(TraversalTreeCache.get(root, child))

    assertTrue(reported.isEmpty())
  }

  @Test
  fun windowContentChanged_contentDescriptionChange_keepsSavedOrderWhenGraceEnabled() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
        )
      )

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_contentDescriptionChange_clearsSavedOrderWhenGraceDisabled() {
    val (_, child) = savedOrder()
    TraversalTreeCache.regionGraceEnabled = false

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
        )
      )

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun gracedChange_regionNotContainingNode_holdsCurrentStaysTrue() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionContainingNode_holdsCurrentIsFalse() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(0, 0, 200, 200))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionInsideNode_holdsCurrentIsFalse() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(10, 10, 50, 50))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionNotContainingNode_holdsCurrentIsFalseWhenGraceDisabled() {
    val (_, child) = savedOrder()
    TraversalTreeCache.regionGraceEnabled = false

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun burst_singleNavigation_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(100000L)

    assertFalse(TraversalTreeCache.inNavigationBurst(100100L))
  }

  @Test
  fun burst_twoCloseNavigations_isBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)

    assertTrue(TraversalTreeCache.inNavigationBurst(1200L))
  }

  @Test
  fun burst_firstGapTooLong_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1400L)

    assertFalse(TraversalTreeCache.inNavigationBurst(1500L))
  }

  @Test
  fun burst_secondGapTooLong_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)

    assertFalse(TraversalTreeCache.inNavigationBurst(1500L))
  }

  @Test
  fun burst_disabledFlag_doesNotChangeBurstSignal() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)
    TraversalTreeCache.burstLiveAnswerEnabled = false

    assertTrue(TraversalTreeCache.inNavigationBurst(1200L))
  }

  @Test
  fun orderCacheStats_hitCountsAsReused() {
    LogUtils.setEnabled(true)
    val (root, child) = savedOrder()
    TraversalTreeCache.resetOrderCacheStats()

    assertNotNull(TraversalTreeCache.get(root, child))
    assertEquals(1, TraversalTreeCache.orderCacheReused())
    assertEquals(0, TraversalTreeCache.orderCacheMissed())
    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())
  }

  @Test
  fun orderCacheStats_missCountsAsMissed() {
    LogUtils.setEnabled(true)
    TraversalTreeCache.resetOrderCacheStats()
    val root = AccessibilityNodeInfoCompat.wrap(node(null))
    val pivot = AccessibilityNodeInfoCompat.wrap(node(null))

    assertNull(TraversalTreeCache.get(root, pivot))
    assertEquals(0, TraversalTreeCache.orderCacheReused())
    assertEquals(1, TraversalTreeCache.orderCacheMissed())
  }

  @Test
  fun orderCacheStats_liveAnswerCountsOnlyWhenLogging() {
    TraversalTreeCache.resetOrderCacheStats()
    TraversalTreeCache.noteLiveAnswer()

    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())

    LogUtils.setEnabled(true)
    TraversalTreeCache.noteLiveAnswer()

    assertEquals(1, TraversalTreeCache.orderCacheLiveAnswers())

    TraversalTreeCache.resetOrderCacheStats()
    assertEquals(0, TraversalTreeCache.orderCacheReused())
    assertEquals(0, TraversalTreeCache.orderCacheMissed())
    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())
  }

  // Q4 (c2): the order thrown away by clear() is kept for prediction only — navigation
  // never sees it, so a stale prediction can only waste synthesis, never mis-speak.
  @Test
  fun clearedOrder_isKeptForPredictionButNotForNavigation() {
    val (root, child) = savedOrder()
    val strategy = checkNotNull(TraversalTreeCache.orderForPrediction(child))

    TraversalTreeCache.clear("test")

    assertNull(TraversalTreeCache.get(root, child))
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.rootFor(child))
    assertSame(strategy, TraversalTreeCache.orderForPrediction(child))
  }

  // Q4 (c2): the last-good slot always holds the newest saved order, so the prediction
  // fallback can never contradict the newest build.
  @Test
  fun clearedOrder_predictionFollowsTheNewestSavedOrder() {
    val (root, child) = savedOrder()
    TraversalTreeCache.clear("test")
    val newer = OrderedTraversalStrategy(root)
    TraversalTreeCache.put(root, newer)
    TraversalTreeCache.clear("test")

    assertSame(newer, TraversalTreeCache.orderForPrediction(child))
  }

  // Q4 (c2): prediction from the last good order still needs the pivot to be in it.
  @Test
  fun clearedOrder_predictionReturnsNullForNodeOutsideTheOrder() {
    val (_, child) = savedOrder()
    val outsider = AccessibilityNodeInfoCompat.wrap(node(null))
    TraversalTreeCache.clear("test")

    assertNotNull(TraversalTreeCache.orderForPrediction(child))
    assertNull(TraversalTreeCache.orderForPrediction(outsider))
  }

  // Q4 (c1): a same-window text-only change keeps the saved order and does not move the
  // revision — an in-flight build of that order is no longer discarded as STALE.
  @Test
  fun textOnlyChange_doesNotMoveRevision() {
    val (root, child) = savedOrder()
    val before = TraversalTreeCache.revision()
    val change =
      event(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
      )
    shadowOf(change).setWindowId(root.windowId)

    val cleared = TraversalTreeCache.onAccessibilityEvent(change)

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
    assertEquals(before, TraversalTreeCache.revision())
  }

  // Q2: the 5 s text-change age expires the live order only — the last-good slot
  // still serves predictions past it.
  @Test
  fun prediction_lastGoodServesAfterTextChangeAge() {
    val (_, child) = savedOrder()
    val strategy = checkNotNull(TraversalTreeCache.orderForPrediction(child))
    assertFalse(TraversalTreeCache.onAccessibilityEvent(textChangeEvent(child.windowId)))

    ShadowSystemClock.advanceBy(Duration.ofMillis(5_001))

    assertSame(strategy, TraversalTreeCache.orderForPrediction(child))
  }

  // Q2: past the 5 s text-change age the live order is gone — navigation finds
  // nothing — yet prediction still serves the last-good order, which can only come
  // from the last-good slot once the live order has been cleared.
  @Test
  fun prediction_liveOrderExpiredAfterTextChangeAge() {
    val (root, child) = savedOrder()
    val strategy = checkNotNull(TraversalTreeCache.orderForPrediction(child))
    TraversalTreeCache.onAccessibilityEvent(textChangeEvent(child.windowId))

    ShadowSystemClock.advanceBy(Duration.ofMillis(5_001))

    assertNull(TraversalTreeCache.get(root, child))
    assertFalse(TraversalTreeCache.holds(child))
    assertSame(strategy, TraversalTreeCache.orderForPrediction(child))
  }

  // Q2: navigation keeps the 5 s text-change expiry — only prediction may use the
  // last-good order past it. Inside the age the order still serves navigation.
  @Test
  fun navigation_getStillExpiresAfterTextChangeAge() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(textChangeEvent(child.windowId))

    ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
    assertNotNull(TraversalTreeCache.get(root, child))

    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    assertNull(TraversalTreeCache.get(root, child))
  }

  // Q5: a bounds-less graced change in another window must not veto this window's nodes.
  @Test
  fun gracedChange_unknownChangeInOtherWindow_doesNotVetoThisWindow() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId + 1, null)

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: the same bounds-less change still vetoes its own window's nodes.
  @Test
  fun gracedChange_unknownChangeInOwnWindow_vetoesItsNodes() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, null)

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: the >8-regions overflow is per window — overflowing another window leaves
  // this window's nodes current.
  @Test
  fun gracedChange_regionOverflowInOtherWindow_doesNotVetoThisWindow() {
    val (_, child) = savedOrder()
    val otherWindowId = child.windowId + 1
    repeat(9) { i ->
      TraversalTreeCache.noteGracedChange(otherWindowId, Rect(i * 10, 0, i * 10 + 5, 5))
    }

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: overflowing this window's own regions still vetoes its nodes, even where no
  // region touches the node — the window is unknown, not just partly changed.
  @Test
  fun gracedChange_regionOverflowInOwnWindow_vetoesItsNodes() {
    val (_, child) = savedOrder()
    repeat(9) { i ->
      TraversalTreeCache.noteGracedChange(child.windowId, Rect(i * 10, 200, i * 10 + 5, 205))
    }

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: regions in another window do not count toward this window's overflow — with
  // the old global count, the ninth region overall would have poisoned this window.
  @Test
  fun gracedChange_regionsInOtherWindow_doNotCountTowardThisWindowOverflow() {
    val (_, child) = savedOrder()
    val otherWindowId = child.windowId + 1
    repeat(8) { i ->
      TraversalTreeCache.noteGracedChange(otherWindowId, Rect(i * 10, 0, i * 10 + 5, 5))
    }

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: a deferred bounds-less change poisons only its own window, not the saved one.
  @Test
  fun deferredGracedChange_inOtherWindow_doesNotVetoThisWindow() {
    val (_, child) = savedOrderInWindow(7)

    TraversalTreeCache.noteDeferredEvent(textChangeEvent(8))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: a deferred bounds-less change in the saved window vetoes that window's nodes.
  @Test
  fun deferredGracedChange_inOwnWindow_vetoesItsNodes() {
    val (_, child) = savedOrderInWindow(7)

    TraversalTreeCache.noteDeferredEvent(textChangeEvent(7))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  // Q5: a deferred change from an unknown window cannot be blamed on any window.
  @Test
  fun deferredGracedChange_fromUnknownWindow_vetoesNoWindow() {
    val (_, child) = savedOrderInWindow(7)

    TraversalTreeCache.noteDeferredEvent(textChangeEvent(-1))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }
}
