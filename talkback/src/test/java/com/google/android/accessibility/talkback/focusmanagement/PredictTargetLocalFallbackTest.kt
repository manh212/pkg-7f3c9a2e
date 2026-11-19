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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_BACKWARD
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_FORWARD
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Q7 ("đoán mục kề khi chưa có thứ tự"): when there is no saved traversal order yet,
 * [FocusProcessorForLogicalNavigation.predictTarget] guesses the neighbour locally with a
 * [com.google.android.accessibility.utils.traversal.SimpleTraversalStrategy] instead of giving
 * up, so the swipe's speech can still be prepared. The saved order still wins when one exists,
 * and a prediction is only a guess for speech preparation: it writes nothing and focuses
 * nothing, so it can never steer a real swipe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictTargetLocalFallbackTest {

  private lateinit var service: TalkBackService
  private lateinit var processor: FocusProcessorForLogicalNavigation
  private var nextId = 1L

  @Before
  fun setUp() {
    service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    TraversalTreeCache.clear("test")
    processor =
      FocusProcessorForLogicalNavigation(
        service,
        null,
        null,
        null,
        null,
        Compositor.TextComposer { _, _, _ -> null },
        null,
      )
  }

  @After
  fun tearDown() {
    TraversalTreeCache.clear("test")
  }

  private fun window(): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
    shadowOf(window).setId(1)
    return window
  }

  /** A visible, focusable, clickable button with its own source id, in [window]. */
  private fun item(
    window: AccessibilityWindowInfo,
    text: String,
    bounds: Rect,
  ): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    ReflectionHelpers.setField(node, "mSourceNodeId", nextId++)
    node.className = "android.widget.Button"
    node.text = text
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.setBoundsInScreen(bounds)
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    return node
  }

  private fun root(window: AccessibilityWindowInfo): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    ReflectionHelpers.setField(node, "mSourceNodeId", nextId++)
    node.className = "android.view.ViewGroup"
    node.isVisibleToUser = true
    node.setBoundsInScreen(Rect(0, 0, 200, 200))
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    return node
  }

  /** A root with two buttons, "First" above "Second"; returns the root and both children. */
  private fun twoItemTree():
    Triple<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val appWindow = window()
    val rootInfo = root(appWindow)
    val firstInfo = item(appWindow, "First", Rect(0, 0, 200, 100))
    val secondInfo = item(appWindow, "Second", Rect(0, 100, 200, 200))
    shadowOf(rootInfo).addChild(firstInfo)
    shadowOf(rootInfo).addChild(secondInfo)
    val rootCompat = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val first = checkNotNull(rootCompat.getChild(0))
    val second = checkNotNull(rootCompat.getChild(1))
    return Triple(rootCompat, first, second)
  }

  @Test
  fun noOrder_predictsLocalNeighbourForwardAndBack() {
    val (_, first, second) = twoItemTree()
    assertNull(TraversalTreeCache.orderForPrediction(first))

    assertEquals(second, processor.predictTarget(first, SEARCH_FOCUS_FORWARD))
    assertEquals(first, processor.predictTarget(second, SEARCH_FOCUS_BACKWARD))
  }

  @Test
  fun noOrder_lastItemHasNoNeighbourToPredict() {
    val (_, first, second) = twoItemTree()
    assertNull(TraversalTreeCache.orderForPrediction(second))

    assertNull(processor.predictTarget(second, SEARCH_FOCUS_FORWARD))
    assertNull(processor.predictTarget(first, SEARCH_FOCUS_BACKWARD))
  }

  @Test
  fun savedOrder_predictsTheOrderBasedTarget() {
    val (root, first, second) = twoItemTree()
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    val order = checkNotNull(TraversalTreeCache.orderForPrediction(first))

    val expected =
      TraversalStrategyUtils.searchFocus(
        order,
        first,
        SEARCH_FOCUS_FORWARD,
        processor.composeNodeFilter(
          NavigationTarget.TARGET_DEFAULT, order.getSpeakingNodesCache(),
        ),
      )
    assertEquals(second, expected)
    assertEquals(expected, processor.predictTarget(first, SEARCH_FOCUS_FORWARD))
  }

  @Test
  fun localPrediction_writesNothingAndFocusesNothing() {
    val (root, first, second) = twoItemTree()

    assertEquals(second, processor.predictTarget(first, SEARCH_FOCUS_FORWARD))
    // Nothing was cached for navigation to read: no order, no root.
    assertNull(TraversalTreeCache.orderForPrediction(first))
    assertNull(TraversalTreeCache.rootFor(first))
    // Nothing was focused by the prediction, neither the pivot nor the guess.
    assertFalse(first.isAccessibilityFocused)
    assertFalse(second.isAccessibilityFocused)
    // The pivot is untouched, and predicting again gives the same guess: the prediction
    // consumes no state, so the real swipe that follows still derives its own target.
    assertEquals("First", first.text.toString())
    assertEquals(second, processor.predictTarget(first, SEARCH_FOCUS_FORWARD))
    assertNull(TraversalTreeCache.orderForPrediction(first))
    // The root was never cached either.
    assertNull(TraversalTreeCache.rootFor(root))
  }
}
