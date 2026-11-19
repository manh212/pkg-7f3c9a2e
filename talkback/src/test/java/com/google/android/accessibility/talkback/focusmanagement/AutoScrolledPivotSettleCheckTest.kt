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
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.ActorStateWritable
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_FORWARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * [FocusProcessorForLogicalNavigation.getPivotNodeForNavigationAction] after an auto-scroll: a
 * swipe continues from the native focus set by the last successful move in the same window,
 * instead of returning to Android's cached focus when that cache is missing or still on the
 * old node.
 *
 * Meridian port note (SandFly 7f98f6a02): only the native-focus continuation tests are staged
 * here. SandFly's settle-check tests (missingPivot_waitsOnTheFocusedPivotAndSearchesFromItsPlace,
 * pivotInOrder_movedSinceLastRead_waitsForTheList,
 * aSlowedListStillWaitsForItsEdgeWhenThePivotHasLeft) exercise SandFly's
 * isMissingFromListOrder/focusEdgeNodeWhenSettled settle paths, which Meridian's divergent
 * settle method does not have, and the defer tests
 * (nextSwipeWaitsWhileTheRemovedPivotsEdgeIsSettling,
 * focusMovedElsewhereCancelsThePostedScrollSearchWait) need d7de05680's defer machinery
 * (deferredNavigations/isSwipeScrollPending), which Meridian does not have. They were left out
 * rather than staged failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoScrolledPivotSettleCheckTest {
  private lateinit var processor: FocusProcessorForLogicalNavigation
  private lateinit var service: TalkBackService
  private val feedback = ArrayList<Feedback>()
  private var nextId = 1L
  private var focusedNode: AccessibilityNodeInfoCompat? = null

  /** A node whose parent and children are set by the test. */
  private inner class Node(className: String, vararg actions: AccessibilityAction) :
    AccessibilityNodeInfoCompat(info(className, actions)) {
    var parentNode: Node? = null
    val children = mutableListOf<Node>()

    /** Bounds that [refresh] reads, as the list moves; unchanged if null. */
    var boundsAfterRefresh: Rect? = null
    var refreshReads = 0

    override fun getParent(): AccessibilityNodeInfoCompat? = parentNode

    override fun refresh(): Boolean {
      refreshReads++
      boundsAfterRefresh?.let { setBoundsInScreen(it) }
      return true
    }

    override fun getChildCount(): Int = children.size

    override fun getChild(index: Int): AccessibilityNodeInfoCompat? = children[index]

    fun add(vararg nodes: Node): Node {
      nodes.forEach {
        it.parentNode = this
        children.add(it)
      }
      return this
    }
  }

  private fun info(className: String, actions: Array<out AccessibilityAction>) =
    AccessibilityNodeInfo.obtain().apply {
      ReflectionHelpers.setField(this, "mSourceNodeId", nextId++)
      this.className = className
      isVisibleToUser = true
      actions.forEach { addAction(it) }
    }

  private fun item(bounds: Rect): Node =
    Node("android.widget.TextView", AccessibilityAction.ACTION_CLICK).apply {
      setBoundsInScreen(bounds)
      isFocusable = true
      isClickable = true
      text = "Item"
    }
  @Before
  fun setUp() {
    service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    val monitor =
      object : AccessibilityFocusMonitor(service, null, null) {
        override fun getAccessibilityFocus(
          useInputFocusIfEmpty: Boolean,
          requireEditable: Boolean,
          isCurrent: Filter<AccessibilityNodeInfoCompat>?,
        ): AccessibilityNodeInfoCompat? = focusedNode
      }
    processor =
      FocusProcessorForLogicalNavigation(
          service,
          null,
          monitor,
          null,
          null,
          Compositor.TextComposer { _, _, _ -> null },
          null,
        )
        .apply {
          setPipeline(
            Pipeline.FeedbackReturner {
              feedback.add(it)
              true
            }
          )
        }
  }
  private fun action() =
    NavigationAction.Builder()
      .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setDirection(SEARCH_FOCUS_FORWARD)
      .setTarget(NavigationTarget.TARGET_DEFAULT)
      .setShouldScroll(true)
      .build()
  private fun recordNativeFocus(node: Node) {
    val history = AccessibilityFocusActionHistory(org.robolectric.RuntimeEnvironment.getApplication())
    history.onAccessibilityFocusAction(
      node, FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
      SystemClock.uptimeMillis(), null,
    )
    processor.setActorState(ActorState(ActorStateWritable(
      null, null, null, null, history.reader, null, null, null, null, null, null, null, null, null,
    )))
  }

  private fun navigationPivot(): AccessibilityNodeInfoCompat? =
    ReflectionHelpers.callInstanceMethod(
      processor, "getPivotNodeForNavigationAction",
      ReflectionHelpers.ClassParameter.from(NavigationAction::class.java, action()),
    )

  @Test
  fun aSwipeAfterEarlyFocusUsesTheNodeThatStillActuallyHasFocus() {
    val previous = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    val current = item(Rect(0, 100, 200, 200)).apply { isAccessibilityFocused = true }
    focusedNode = previous // Android's focus event is still behind the next swipe.
    recordNativeFocus(current)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())

    assertSame(current, navigationPivot())
  }

  @Test
  fun anAppMovingFocusElsewhereDoesNotRestoreTheLastNavigationFocus() {
    val current = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    val previous = item(Rect(0, 100, 200, 200)).apply { isAccessibilityFocused = false }
    focusedNode = current
    recordNativeFocus(previous)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())

    assertSame(current, navigationPivot())
  }

  @Test
  fun normalFocusThatMatchesTheLastNavigationNeedsNoAdditionalRefresh() {
    val current = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    focusedNode = current
    recordNativeFocus(current)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())
    val before = current.refreshReads

    assertSame(current, navigationPivot())
    assertEquals(before, current.refreshReads)
  }

  @Test
  fun navigationWithoutARecentAutomaticScrollKeepsTheCurrentFocus() {
    val previous = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    val current = item(Rect(0, 100, 200, 200)).apply { isAccessibilityFocused = true }
    focusedNode = previous
    recordNativeFocus(current)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", 0L)

    assertSame(previous, navigationPivot())
  }

  @Test
  fun aNavigationRecordInAnotherWindowCannotReplaceTheCurrentFocus() {
    val current = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    val otherWindow = item(Rect(0, 100, 200, 200)).apply {
      isAccessibilityFocused = true
      ReflectionHelpers.setField(unwrap(), "mWindowId", 1)
    }
    focusedNode = current
    recordNativeFocus(otherWindow)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())
    val before = otherWindow.refreshReads

    assertSame(current, navigationPivot())
    assertEquals(before, otherWindow.refreshReads)
  }

  @Test
  fun aMissingCachedFocusAfterTheScrollUsesTheStillFocusedNativeNode() {
    val root = Node("android.widget.FrameLayout")
    val current = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = true }
    focusedNode = null
    shadowOf(service).setRootInActiveWindow(root.unwrap())
    recordNativeFocus(current)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())

    assertSame(current, navigationPivot())
  }

  @Test
  fun aMissingFocusCannotRestoreANodeInADifferentWindow() {
    val root = Node("android.widget.FrameLayout")
    val otherWindow = item(Rect(0, 0, 200, 100)).apply {
      isAccessibilityFocused = true
      ReflectionHelpers.setField(unwrap(), "mWindowId", 1)
    }
    focusedNode = null
    shadowOf(service).setRootInActiveWindow(root.unwrap())
    recordNativeFocus(otherWindow)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())
    val before = otherWindow.refreshReads

    assertEquals(root, navigationPivot())
    assertEquals(before, otherWindow.refreshReads)
  }

  @Test
  fun aMissingFocusDoesNotRestoreARecordThatNoLongerHasFocus() {
    val root = Node("android.widget.FrameLayout")
    val previous = item(Rect(0, 0, 200, 100)).apply { isAccessibilityFocused = false }
    focusedNode = null
    shadowOf(service).setRootInActiveWindow(root.unwrap())
    recordNativeFocus(previous)
    ReflectionHelpers.setField(processor, "lastAutoScrollUptime", SystemClock.uptimeMillis())

    assertEquals(root, navigationPivot())
  }
}