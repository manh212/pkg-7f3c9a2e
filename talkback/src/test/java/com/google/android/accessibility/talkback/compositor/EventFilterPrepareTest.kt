/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.compositor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.eventprocessor.ProcessorPhoneticLetters
import com.google.android.accessibility.talkback.focusmanagement.AsyncTraversalOrder
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.talkback.imagecaption.ImageContents
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.monitor.CollectionState
import com.google.android.accessibility.utils.monitor.TouchMonitor
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Q3 ("chuẩn bị cả mục đang chọn") and Q5 ("chuẩn bị lại ngay khi xong") in
 * [EventFilter.prepareSwipeTargets].
 *
 * The tests drive the private [EventFilter.onFocusMoved] by reflection, so the preparation path
 * runs without the full speak pipeline. PCM audio preparation is off (both PCM features
 * disabled), so the tests cover the prepared text announcements only — the same pipeline,
 * without a speaker or TTS engine.
 *
 * The fake predictor has two modes. Order-aware mode returns a neighbour only while a saved
 * order exists, modelling production `predictTarget` without the Q7 local fallback; local mode
 * returns the neighbour regardless, modelling the Q7 fallback. The Q5 test uses order-aware
 * mode, so the swipe target can only be prepared once the order build completes; the wiring
 * test uses local mode with the async order feature off, so the target can only be prepared
 * by the pass reaching the predictor with no order at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class EventFilterPrepareTest {

  /** Minimal service for Robolectric to instantiate the collaborators against. */
  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
  }

  private lateinit var context: Context
  private lateinit var globalVariables: GlobalVariables
  private lateinit var eventFilter: EventFilter
  private lateinit var root: AccessibilityNodeInfoCompat
  private lateinit var focused: AccessibilityNodeInfoCompat
  private lateinit var next: AccessibilityNodeInfoCompat
  /** A second, separate root in the same window, whose order holds neither [focused] nor [next]. */
  private lateinit var otherRoot: AccessibilityNodeInfoCompat
  private var nextId = 1L

  /** Returns [next] forward from [focused], as the predictor mode allows. */
  private inner class Predictor(private val local: Boolean) : EventFilter.TargetPredictor {
    override fun predictTarget(
      pivot: AccessibilityNodeInfoCompat,
      searchDirection: Int,
    ): AccessibilityNodeInfoCompat? =
      predictTarget(pivot, searchDirection, NavigationTarget.TARGET_DEFAULT)

    override fun predictTarget(
      pivot: AccessibilityNodeInfoCompat,
      searchDirection: Int,
      targetType: Int,
    ): AccessibilityNodeInfoCompat? {
      val hasOrder = TraversalTreeCache.orderForPrediction(pivot) != null
      if (!local && !hasOrder) {
        return null
      }
      if (searchDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD && pivot == focused) {
        return next
      }
      if (searchDirection == TraversalStrategy.SEARCH_FOCUS_BACKWARD && pivot == next) {
        return focused
      }
      return null
    }

    override fun currentTargetType(): Int = NavigationTarget.TARGET_DEFAULT
  }

  /** Counts predictions and always returns null, modelling a pivot no search can resolve. */
  private inner class CountingNullPredictor(private val defaultForwardTarget: Boolean = false) :
    EventFilter.TargetPredictor {
    val invocations = mutableListOf<Pair<Int, Int>>()

    override fun predictTarget(
      pivot: AccessibilityNodeInfoCompat,
      searchDirection: Int,
    ): AccessibilityNodeInfoCompat? =
      predictTarget(pivot, searchDirection, NavigationTarget.TARGET_DEFAULT)

    override fun predictTarget(
      pivot: AccessibilityNodeInfoCompat,
      searchDirection: Int,
      targetType: Int,
    ): AccessibilityNodeInfoCompat? {
      invocations += targetType to searchDirection
      return if (defaultForwardTarget &&
        targetType == NavigationTarget.TARGET_DEFAULT &&
        searchDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD &&
        pivot == focused) next else null
    }

    override fun currentTargetType(): Int = NavigationTarget.TARGET_HEADING
  }

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    val service = Robolectric.setupService(TestService::class.java)
    globalVariables = GlobalVariables(service, null, CollectionState(), null)
    val compositor =
      Compositor(
        context,
        null,
        ImageContents(null, null),
        globalVariables,
        // The service is only used when phonetic-letter events are processed, which this
        // test never triggers; the constructor only stores it. A real TalkBackService is
        // far too heavy to stand up here, and the parameter carries no nullability
        // annotation, so null compiles.
        ProcessorPhoneticLetters(null, globalVariables),
        Compositor.FLAVOR_NONE,
      )
    eventFilter = EventFilter(context, compositor, TouchMonitor(), globalVariables, EarlyFocusSpeech())
    EventFilter.setLatencyExtrasEnabled(true)
    EventFilter.setPreparedSpeechOptions(true, false)
    LatencyExperiments.setPreparationAllowed(true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_ADJACENT, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_SECOND, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.CACHED_TEXT, true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, true)
    buildTree()
    // The focused node is in window 1, so the current window must be 1 too, or the focused
    // node is ineligible for preparation.
    globalVariables.updateStateFromFocusedNode(focused)
  }

  @After
  fun tearDown() {
    AsyncTraversalOrder.cancel()
    PreparedFocusSpeech.clear()
    TraversalTreeCache.clear("test")
    TraversalTreeCache.enabled = true
    EventFilter.setLatencyExtrasEnabled(true)
    EventFilter.setPreparedSpeechOptions(false, false)
    LatencyExperiments.setPreparationAllowed(false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_ADJACENT, true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_SECOND, true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, true)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.CACHED_TEXT, true)
  }

  /** Q3: the focused item is prepared on focus move, as well as the swipe targets. */
  @Test
  fun focusedItemIsPreparedOnFocusMove() {
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    eventFilter.setTargetPredictor(Predictor(local = false))
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(focused, NavigationTarget.TARGET_DEFAULT))
    assertNotNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  /**
   * Q5: with no order, preparation runs again when the order build completes — not only on
   * the old fixed timer. The predictor returns nothing while there is no order, so the swipe
   * target can only be prepared by the re-run after the build. The focused item needs no
   * order, so it is already prepared before the build completes (Q3).
   */
  @Test
  fun prepareRunsAgainWhenTheOrderBuildCompletes() {
    eventFilter.setTargetPredictor(Predictor(local = false))
    moveFocusTo(focused)
    runMainQueueFor(35)
    assertNotNull(PreparedFocusSpeech.peek(focused, NavigationTarget.TARGET_DEFAULT))
    assertTrue(awaitPrepared(next))
  }

  /**
   * Q7 wiring: the preparation pass reaches the predictor even with no order and no build,
   * so a predictor with a local fallback can find the swipe target at once. The async order
   * feature is off, so no build ever runs and no order ever appears.
   */
  @Test
  fun prepareReachesThePredictorWithoutAnOrder() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, false)
    eventFilter.setTargetPredictor(Predictor(local = true))
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  /**
   * Q1 (round 3): an order is saved but holds neither the focused item nor the swipe target,
   * so the old code vetoed both as stale. The refresh fallback re-reads them instead, and the
   * focused item is never vetoed at all. The async order feature is off, so no build can save
   * a real order meanwhile and mask the fallback.
   */
  @Test
  fun refreshFallbackPreparesNodeTheSavedOrderDoesNotHold() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, false)
    TraversalTreeCache.put(otherRoot, OrderedTraversalStrategy(otherRoot))
    eventFilter.setTargetPredictor(Predictor(local = true))
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(focused, NavigationTarget.TARGET_DEFAULT))
    assertNotNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  /**
   * Q1 (round 3): a bounds-less background text change poisons the grace flag, so the saved
   * order no longer holds the swipe target current — the old code vetoed it. The refresh
   * fallback re-reads it instead.
   */
  @Test
  fun refreshFallbackPreparesNodeUnderAGracedChange() {
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    // The deferred event must carry the saved window's id: with no window (-1)
    // noteDeferredEvent cannot blame any window and poisons nothing, so the fallback
    // would never be exercised and the test would pass on the old veto code too.
    shadowOf(event).setWindowId(WINDOW_ID)
    TraversalTreeCache.noteDeferredEvent(event)
    eventFilter.setTargetPredictor(Predictor(local = false))
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  /**
   * Q1 (round 3): the refresh fallback keeps the veto when the re-read itself fails — only
   * then is the node stale. The focused item is still prepared: it is never vetoed.
   */
  @Test
  fun failedRefreshStillVetoesTheNode() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, false)
    TraversalTreeCache.put(otherRoot, OrderedTraversalStrategy(otherRoot))
    shadowOf(next.unwrap()).setRefreshReturnValue(false)
    eventFilter.setTargetPredictor(Predictor(local = true))
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(focused, NavigationTarget.TARGET_DEFAULT))
    assertNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  @Test
  fun failedPredictionIsNotRepeatedForTheSameTargetTypeAndDirection() {
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    EventFilter.setPreparedSpeechOptions(true, true)
    val predictor = CountingNullPredictor()
    eventFilter.setTargetPredictor(predictor)
    moveFocusTo(focused)
    runMainQueueFor(200)
    val expected = listOf(NavigationTarget.TARGET_HEADING, NavigationTarget.TARGET_DEFAULT)
      .flatMap { type ->
        listOf(TraversalStrategy.SEARCH_FOCUS_FORWARD, TraversalStrategy.SEARCH_FOCUS_BACKWARD)
          .map { direction -> type to direction }
      }
    assertEquals(expected.toSet(), predictor.invocations.toSet())
    assertEquals(expected.size, predictor.invocations.size)
    assertNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
  }

  @Test
  fun failedHeadingPredictionDoesNotSuppressDefaultTarget() {
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    val predictor = CountingNullPredictor(defaultForwardTarget = true)
    eventFilter.setTargetPredictor(predictor)
    moveFocusTo(focused)
    runMainQueueFor(100)
    assertNotNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT))
    assertNull(PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_HEADING))
  }

  /** Moves focus to [node], driving the private `onFocusMoved` by reflection. */
  private fun moveFocusTo(node: AccessibilityNodeInfoCompat) {
    ReflectionHelpers.callInstanceMethod<Any>(
      eventFilter,
      "onFocusMoved",
      ReflectionHelpers.ClassParameter.from(AccessibilityNodeInfoCompat::class.java, node),
    )
  }

  private fun runMainQueueFor(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
  }

  /** Idles the main looper until [node] has a prepared announcement, or the 3 s budget ends. */
  private fun awaitPrepared(node: AccessibilityNodeInfoCompat): Boolean {
    val end = System.nanoTime() + 3_000_000_000L
    while (System.nanoTime() < end) {
      shadowOf(Looper.getMainLooper()).idle()
      if (PreparedFocusSpeech.peek(node, NavigationTarget.TARGET_DEFAULT) != null) {
        return true
      }
      Thread.sleep(5)
    }
    return false
  }

  /** A window with a root and two buttons, [focused] followed by [next]. */
  private fun buildTree() {
    val window = AccessibilityWindowInfo()
    shadowOf(window).apply {
      setId(WINDOW_ID)
      setType(AccessibilityWindowInfo.TYPE_APPLICATION)
      setActive(true)
      setFocused(true)
      setBoundsInScreen(Rect(0, 0, 1080, 2340))
    }
    val rootInfo = AccessibilityNodeInfo.obtain(View(context)).apply {
      packageName = APP_PACKAGE
      className = "android.widget.LinearLayout"
      isVisibleToUser = true
      setBoundsInScreen(Rect(0, 0, 1080, 2340))
      ReflectionHelpers.setField(this, "mSourceNodeId", nextId++)
      ReflectionHelpers.setField(this, "mWindowId", WINDOW_ID)
      shadowOf(this).setAccessibilityWindowInfo(window)
    }
    shadowOf(window).setRoot(rootInfo)
    root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    focused = child(window, rootInfo, "Focused item", Rect(0, 0, 1080, 200))
    next = child(window, rootInfo, "Next item", Rect(0, 200, 1080, 400))
    // A second root in the same window, with no children: an order built from it holds
    // neither the focused item nor the next one.
    val otherRootInfo = AccessibilityNodeInfo.obtain(View(context)).apply {
      packageName = APP_PACKAGE
      className = "android.widget.LinearLayout"
      isVisibleToUser = true
      setBoundsInScreen(Rect(0, 0, 1080, 2340))
      ReflectionHelpers.setField(this, "mSourceNodeId", nextId++)
      ReflectionHelpers.setField(this, "mWindowId", WINDOW_ID)
      shadowOf(this).setAccessibilityWindowInfo(window)
    }
    otherRoot = AccessibilityNodeInfoCompat.wrap(otherRootInfo)
  }

  private fun child(
    window: AccessibilityWindowInfo,
    parent: AccessibilityNodeInfo,
    text: String,
    bounds: Rect,
  ): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo.obtain(View(context)).apply {
      packageName = APP_PACKAGE
      className = "android.widget.Button"
      this.text = text
      isVisibleToUser = true
      isEnabled = true
      isClickable = true
      isFocusable = true
      setBoundsInScreen(bounds)
      ReflectionHelpers.setField(this, "mSourceNodeId", nextId++)
      ReflectionHelpers.setField(this, "mWindowId", WINDOW_ID)
      shadowOf(this).setAccessibilityWindowInfo(window)
      shadowOf(this).setRefreshReturnValue(true)
    }
    shadowOf(parent).addChild(info)
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private companion object {
    const val WINDOW_ID = 1
    const val APP_PACKAGE = "com.example.app"
  }
}
