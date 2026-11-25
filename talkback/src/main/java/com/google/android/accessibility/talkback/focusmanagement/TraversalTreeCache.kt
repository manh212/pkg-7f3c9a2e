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

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import kotlin.math.abs
import java.util.function.IntConsumer
import java.util.concurrent.atomic.AtomicLong
import com.google.android.accessibility.utils.Statistics
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.PerformanceHintHelper
import com.google.android.accessibility.utils.WindowCachePolicy

/**
 * Keeps the reading order of the window that the user last swiped in, so that swiping through a
 * screen that has not changed does not ask the app for every node again. Any event that can change
 * that window throws the order away, the same way the framework's own node cache is kept current.
 *
 * Filter logcat by the "BacktalkTreeCache" tag in debug builds to see how often the order is reused.
 */
object TraversalTreeCache {
  private const val TAG = "BacktalkTreeCache"

  /** Events that never change the nodes or their order. */
  internal const val IGNORED_EVENT_TYPES =
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
      AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
      AccessibilityEvent.TYPE_ANNOUNCEMENT or
      AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE or
      AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY or
      AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT or
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

  /**
   * Content changes that leave the nodes and their order as they are, such as a progress bar or a
   * clock that ticks. An app that shows one sends these events all the time, which would otherwise
   * throw the order away before every swipe.
   */
  private const val NON_STRUCTURAL_CHANGES =
    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
      AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION

  private const val NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION =
    NON_STRUCTURAL_CHANGES or AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION

  private const val MAX_GRACED_CHANGE_REGIONS = 8

  /**
   * How long the order is kept after one of those changes. A text change can, rarely, make a node
   * speak that did not before, so the order is not trusted for long after one.
   */
  private const val MAX_AGE_AFTER_TEXT_CHANGE_MS = 5000L

  /** Node actions that only move focus, and so do not change the nodes or their order. */
  internal val FOCUS_ACTIONS =
    setOf(
      AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS,
    )

  /**
   * Showing a node on screen only scrolls when the node is near the edge, which on a round watch
   * screen is nearly every swipe, and a scroll sends its own event, which throws the order away if
   * it arrives. Throwing it away for the action as well rebuilt the order on every swipe there.
   */
  private val SHOW_ON_SCREEN = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id

  private const val NO_WINDOW_ID = -1

  private val revisionCounter = AtomicLong()
  @Volatile private var buildingWindowId = NO_WINDOW_ID

  @JvmStatic fun revision(): Long = revisionCounter.get()
  @JvmStatic fun setBuildingWindow(windowId: Int) { buildingWindowId = windowId }

  private var root: AccessibilityNodeInfoCompat? = null
  private var strategy: OrderedTraversalStrategy? = null

  /**
   * Q4 (c2): the order most recently thrown away by [clear], kept for prediction only. The
   * live order is replaced by a fresh build soon after a clear, but while it is being rebuilt
   * — or when the window keeps churning — prediction ([orderForPrediction]) can still use
   * this one. A stale prediction only wastes synthesis; navigation ([get]) never sees this
   * slot, so a stale order can never mis-speak. The slot always holds the newest saved
   * order: [put] refreshes it too, so the fallback can never contradict the newest build.
   * Q2: the last-good slot also serves predictions past the 1.5 s text-change age
   * (MAX_AGE_AFTER_TEXT_CHANGE_MS), which expires the live order only — the slot is
   * never read by navigation, so a stale prediction can only waste synthesis.
   */
  private var lastGoodRoot: AccessibilityNodeInfoCompat? = null
  private var lastGoodStrategy: OrderedTraversalStrategy? = null

  /**
   * Who is told the window id when an operation throws the saved order away, so that window's
   * nodes can be read in the background before the next swipe, as they are when an event throws
   * the order away. An event's own clear is reported by [onAccessibilityEvent] instead, and the
   * order expiring inside [get] is not reported either: an order is built at once there anyway.
   */
  @Volatile private var orderClearedListener: IntConsumer? = null

  private var lastClearReason = "start"
  private var clearedWindowId = NO_WINDOW_ID
  @JvmStatic fun lastClearedWindowId(): Int = clearedWindowId
  private var firstIgnoredChangeTime = 0L
  private var hits = 0
  private var misses = 0
  private var liveAnswers = 0
  private val buildTimes = Statistics()
  // Keep churn detection independent of the user-selected order cache.
  private val structuralChurn = StructuralChurnTracker()

  @JvmStatic
  var enabled: Boolean = true
    set(value) {
      if (field == value) return
      field = value
      clear("setting changed", notifyListener = false)
    }

  @JvmStatic
  fun noteBuild(durationMs: Long) {
    PerformanceHintHelper.reportWorkDurationMs(durationMs)
    if (LogUtils.shouldLog(Log.DEBUG)) buildTimes.increment(durationMs)
  }

  @JvmStatic
  fun buildTimingSummary(): String =
    synchronized(buildTimes) {
      "Order build time: count=${buildTimes.count}, mean=${buildTimes.mean} ms, " +
        "p90=${buildTimes.getPercentile(90)} ms, p99=${buildTimes.getPercentile(99)} ms"
    }

  @JvmStatic
  fun noteLiveAnswer() {
    if (LogUtils.shouldLog(Log.DEBUG)) {
      liveAnswers++
    }
  }

  @JvmStatic
  fun shouldUseLiveTraversal(windowId: Int): Boolean =
    structuralChurn.isChurning(windowId, SystemClock.uptimeMillis())

  @JvmStatic
  fun noteSlowBuild(windowId: Int, durationMs: Long, budgetExceeded: Boolean) {
    structuralChurn.noteSlowBuild(windowId, SystemClock.uptimeMillis(), durationMs, budgetExceeded)
  }

  @JvmStatic
  fun notePassiveScroll(windowId: Int) {
    structuralChurn.record(windowId, SystemClock.uptimeMillis())
  }

  @JvmStatic
  fun orderCacheReused(): Int = hits

  @JvmStatic
  fun orderCacheMissed(): Int = misses

  @JvmStatic
  fun orderCacheBuilt(): Long = synchronized(buildTimes) { buildTimes.count }

  @JvmStatic
  fun orderCacheLiveAnswers(): Int = liveAnswers

  @JvmStatic
  fun resetOrderCacheStats() {
    hits = 0
    misses = 0
    liveAnswers = 0
    buildTimes.clear()
  }

  @JvmStatic
  var regionGraceEnabled: Boolean = true
    set(value) {
      if (field == value) {
        return
      }
      field = value
      resetGracedChanges()
      firstIgnoredChangeTime = 0L
    }

  @JvmStatic
  var burstLiveAnswerEnabled: Boolean = true
    get() = field && enabled

  @Volatile private var lastNavigationMs: Long = 0L
  @Volatile private var previousNavigationMs: Long = 0L

  private const val NAVIGATION_BURST_WINDOW_MS = 300L

  private val gracedChangeRegions = ArrayList<Pair<Int, Rect>>()
  /**
   * Windows whose graced changes are unknown — a bounds-less change, or more than
   * [MAX_GRACED_CHANGE_REGIONS] regions, in that window. Q5: per window, so a
   * bounds-less background change (e.g. a notification tick in another window)
   * cannot veto preparation in the foreground window. Unknown changes still veto
   * their own window's nodes.
   */
  private val gracedChangeUnknownWindows = HashSet<Int>()

  private var rereadWindowId = NO_WINDOW_ID

  /** Where the last navigation started, so a removed node does not send the user to the top. */
  private var lastPivotTop = Int.MIN_VALUE
  private var lastPivotWindowId = NO_WINDOW_ID

  /** The sum of scroll deltas when [lastPivotTop] was read, to follow the place as lists scroll. */
  private var lastPivotDeltaSum = 0

  /**
   * Set when scrolling ahead moved the focused item off screen: its bounds and its list's before
   * the page, the sum of scroll deltas then (see
   * [FocusProcessorForLogicalNavigation.scrollDeltaSumY]) and which way the page went. The next
   * navigation goes on from the item next to it.
   */
  private var scrolledAwayBounds: Rect? = null
  private val scrolledAwayListBounds = Rect()
  private var scrolledAwayDeltaSum = 0
  private var scrolledAwayForward = true

  /** Returns the saved order of [root] if it has one and it contains [pivot]. */
  @JvmStatic
  fun get(
    root: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
  ): OrderedTraversalStrategy? {
    rememberPivot(pivot)
    if (!enabled) {
      if (LogUtils.shouldLog(Log.DEBUG)) misses++
      return null
    }
    if (
      firstIgnoredChangeTime != 0L &&
        SystemClock.uptimeMillis() - firstIgnoredChangeTime > MAX_AGE_AFTER_TEXT_CHANGE_MS
    ) {
      clear("text changes", notifyListener = false)
    }
    val saved = strategy
    val result = saved?.takeIf { root == this.root && it.containsNode(pivot) }
    if (LogUtils.shouldLog(Log.DEBUG)) {
      if (result != null) {
        hits++
      } else {
        misses++
      }
    }
    return result
  }

  /**
   * Whether any order is saved at all. [holdsCurrent] is false when there is none, which
   * must not be read as the node being stale: with no order there is nothing for the node
   * to be stale against, so callers fall back to re-reading the node instead.
   */
  @JvmStatic
  fun hasSavedOrder(): Boolean = strategy != null

  /**
   * Whether the saved order holds [node]. Nothing that could remove a node from the window has
   * happened since the order was saved, so the node is still there.
   */
  @JvmStatic
  fun holds(node: AccessibilityNodeInfoCompat): Boolean =
    enabled && strategy?.containsNode(node) == true

  /**
   * Whether the saved order holds [node] as the app has it now: nothing in its window has changed
   * since the order was read from the app, not even a text or state, so the node needs no second
   * read.
   */
  @JvmStatic
  fun holdsCurrent(node: AccessibilityNodeInfoCompat): Boolean =
    if (regionGraceEnabled) {
      holds(node) && !gracedChangesTouch(node)
    } else {
      firstIgnoredChangeTime == 0L && holds(node)
    }

  @JvmStatic
  fun orderForPrediction(pivot: AccessibilityNodeInfoCompat): OrderedTraversalStrategy? {
    if (!enabled) {
      return null
    }
    // Q2: the 1.5 s text-change age expires the LIVE order only. The last-good slot
    // below serves predictions regardless of age: a stale prediction only wastes
    // synthesis, it never moves focus. Navigation (get()) keeps its own expiry.
    val liveExpired =
      firstIgnoredChangeTime != 0L &&
        SystemClock.uptimeMillis() - firstIgnoredChangeTime > MAX_AGE_AFTER_TEXT_CHANGE_MS
    if (!liveExpired) {
      val saved = strategy?.takeIf { root?.windowId == pivot.windowId && it.containsNode(pivot) }
      if (saved != null) return saved
    }
    // Q4 (c2): the last good order, kept when the live one was cleared, is still usable for
    // prediction — a stale prediction only wastes synthesis, it never moves focus. The same
    // guards apply as for the live order: the window must match and the pivot must be in it.
    // Navigation (get(), holds(), rootFor(), pivotAfterRemoval()) never consults this slot.
    return lastGoodStrategy?.takeIf {
      lastGoodRoot?.windowId == pivot.windowId && it.containsNode(pivot)
    }
  }

  internal fun noteGracedChange(windowId: Int, sourceBounds: Rect?, packageName: String? = null) {
    if (firstIgnoredChangeTime == 0L) {
      firstIgnoredChangeTime = SystemClock.uptimeMillis()
    }
    // Q11 (worker 3 §8.3, adapted to the Q5 per-window design): log once when this window
    // becomes unknown — the false→true transition for this window — with the triggering
    // package, so round 4 can tell how much of STALE_NODE_GRACED is cross-window
    // poisoning (e.g. systemui notification ticks) vs the foreground window's own changes.
    // No counter is added; the log line is the instrument. The check is the transition
    // itself: a window already unknown logs nothing, so the deferred path logs at most
    // once per poisoning.
    if (sourceBounds != null && !sourceBounds.isEmpty &&
      gracedChangeRegions.any { it.first == windowId && it.second == sourceBounds }) {
      return
    }
    if (windowId !in gracedChangeUnknownWindows &&
      (sourceBounds == null || sourceBounds.isEmpty ||
        gracedChangeRegions.count { it.first == windowId } >= MAX_GRACED_CHANGE_REGIONS)) {
      LogUtils.d(TAG,
        "TraversalTreeCache grace poisoned: window=%d package=%s bounds=%s regions=%d",
        windowId, packageName, sourceBounds,
        gracedChangeRegions.count { it.first == windowId })
    }
    // Q5: unknown-ness is per window — a bounds-less change poisons only its own window.
    if (sourceBounds == null || sourceBounds.isEmpty || windowId in gracedChangeUnknownWindows) {
      gracedChangeUnknownWindows += windowId
      return
    }
    // Q5: the region cap is per window too — regions from other windows do not count
    // toward this window's overflow.
    if (gracedChangeRegions.count { it.first == windowId } >= MAX_GRACED_CHANGE_REGIONS) {
      gracedChangeUnknownWindows += windowId
      return
    }
    gracedChangeRegions += windowId to Rect(sourceBounds)
  }

  private fun resetGracedChanges() {
    gracedChangeRegions.clear()
    gracedChangeUnknownWindows.clear()
  }

  private fun sourceBounds(event: AccessibilityEvent): Rect? {
    val source = runCatching { event.source }.getOrNull() ?: return null
    try {
      val bounds = Rect()
      source.getBoundsInScreen(bounds)
      return bounds.takeIf { !it.isEmpty }
    } catch (e: Exception) {
      return null
    } finally {
      source.recycle()
    }
  }

  private fun gracedChangesTouch(node: AccessibilityNodeInfoCompat): Boolean {
    if (firstIgnoredChangeTime == 0L) {
      return false
    }
    // Q5: only an unknown change in the node's own window vetoes it.
    if (node.windowId in gracedChangeUnknownWindows) {
      return true
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return gracedChangeRegions.any { (windowId, region) ->
      windowId == node.windowId && Rect.intersects(region, bounds)
    }
  }

  /**
   * The root of the window that [node] is in, if the saved order holds [node]: the root the order
   * was read from, as nothing that could change the window has happened since. Asking the window
   * for its root instead always waits for the app, because Android never answers that from its
   * node cache.
   */
  @JvmStatic
  fun rootFor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? =
    root?.takeIf { it.windowId == node.windowId && holds(node) }

  /**
   * Remembers where a navigation started. When the focused node is later removed — an app
   * re-laying-out its content, such as a blocked ad slot collapsing, does this — navigation carries
   * on from the node nearest this place instead of falling back to the top of the window.
   */
  @JvmStatic
  fun rememberPivot(pivot: AccessibilityNodeInfoCompat) {
    val bounds = Rect()
    pivot.getBoundsInScreen(bounds)
    lastPivotTop = bounds.top
    lastPivotWindowId = pivot.windowId
    lastPivotDeltaSum = FocusProcessorForLogicalNavigation.scrollDeltaSumY()
    scrolledAwayBounds = null
  }

  /**
   * Records that scrolling ahead moved the focused node of window [windowId] off screen. It was at
   * [bounds] in a list at [listBounds] when the sum of scroll deltas was [deltaSum], and the page
   * went [forward] or back.
   */
  @JvmStatic
  fun rememberScrolledAway(
    windowId: Int,
    bounds: Rect,
    listBounds: Rect,
    deltaSum: Int,
    forward: Boolean,
  ) {
    lastPivotWindowId = windowId
    scrolledAwayBounds = Rect(bounds)
    scrolledAwayListBounds.set(listBounds)
    scrolledAwayDeltaSum = deltaSum
    scrolledAwayForward = forward
  }

  /** Forgets the item that scrolling ahead moved off screen, once something else has focus. */
  @JvmStatic
  fun forgetScrolledAway() {
    scrolledAwayBounds = null
  }

  /**
   * Whether scrolling ahead moved the focused node of window [windowId] off screen and nothing has
   * had focus since: the next navigation goes on from it, so focus isn't put elsewhere meanwhile.
   */
  @JvmStatic
  fun continuesAfterScrolledAway(windowId: Int): Boolean =
    scrolledAwayBounds != null && windowId == lastPivotWindowId

  /**
   * Where the next item comes after a node at [top] to [bottom] has scrolled [shift] px up off
   * screen ([shift] is negative for a scroll down): the top of the item after it, going [forward],
   * or the bottom of the item before it.
   */
  @JvmStatic
  fun placeAfterScroll(top: Int, bottom: Int, shift: Int, forward: Boolean): Int =
    if (forward) bottom - shift else top - shift

  /**
   * How far an item at [item] in a list at [list] scrolled off it, given [shift] from the list's
   * scroll deltas: at least far enough to leave the list, as the deltas of some lists, such as
   * Compose lazy lists, aren't in pixels.
   */
  @JvmStatic
  fun shiftOffList(shift: Int, item: Rect, list: Rect, forward: Boolean): Int =
    if (list.isEmpty) {
      shift
    } else if (forward) {
      maxOf(shift, item.bottom - list.top)
    } else {
      minOf(shift, item.top - list.bottom)
    }

  /**
   * The index of the item that a swipe [forward] or back goes to after the focused item scrolled
   * off the list at [list], among nodes at [bounds] in reading order of which [focusable] can take
   * focus: going forward, the item of the list nearest below [place], where the item after the one
   * scrolled away starts; going back, the item nearest above [place], where the item before it
   * ends. Ties go to the first node going forward and the last going back, as a swipe meets them.
   * -1 when no item of the list is there, or the list's bounds aren't known.
   */
  @JvmStatic
  fun itemAfterScrolledAway(
    bounds: List<Rect>,
    focusable: List<Boolean>,
    list: Rect,
    place: Int,
    forward: Boolean,
  ): Int {
    if (list.isEmpty) {
      return -1
    }
    var best = -1
    for (i in bounds.indices) {
      val item = bounds[i]
      if (!focusable[i] || item.isEmpty || !Rect.intersects(item, list)) {
        continue
      }
      if (forward) {
        if (item.top >= place && (best < 0 || item.top < bounds[best].top)) {
          best = i
        }
      } else if (item.bottom <= place && (best < 0 || item.bottom >= bounds[best].bottom)) {
        best = i
      }
    }
    return best
  }

  /** The last node of [order] above [place], or the first node if none is above it. */
  private fun lastNodeAbove(
    order: OrderedTraversalStrategy,
    place: Int,
    bounds: Rect,
  ): AccessibilityNodeInfoCompat? {
    var above: AccessibilityNodeInfoCompat? = null
    for (node in order.dumpTree()) {
      if (node.windowId != lastPivotWindowId) {
        continue
      }
      node.getBoundsInScreen(bounds)
      if (bounds.top >= place) {
        return above ?: node
      }
      above = node
    }
    return above
  }

  @JvmStatic
  @JvmOverloads
  fun noteNavigationActivity(nowMs: Long = SystemClock.uptimeMillis()) {
    previousNavigationMs = lastNavigationMs
    lastNavigationMs = nowMs
  }

  @JvmStatic
  fun lastNavigationTimeMs(): Long = lastNavigationMs

  @JvmStatic
  fun previousNavigationTimeMs(): Long = previousNavigationMs

  @JvmStatic
  fun isNavigationBurst(previousMs: Long, lastMs: Long, nowMs: Long): Boolean {
    if (previousMs <= 0L || lastMs <= previousMs) {
      return false
    }
    return lastMs - previousMs < NAVIGATION_BURST_WINDOW_MS &&
      nowMs - lastMs < NAVIGATION_BURST_WINDOW_MS
  }

  @JvmStatic
  @JvmOverloads
  fun inNavigationBurst(nowMs: Long = SystemClock.uptimeMillis()): Boolean =
    isNavigationBurst(previousNavigationMs, lastNavigationMs, nowMs)

  /**
   * The node to search on from when the focused node has been removed: the last node above the
   * place the last navigation started, so a swipe forward lands on whatever now occupies that
   * place, rather than on the top of the page. Falls back to the first node when the removal was
   * above everything. Null when nothing was remembered, or the window has changed.
   *
   * The content change that removes a node usually throws the saved order away as well — an ad slot
   * collapsing sends a content change of the undefined type, which is structural here — so a fresh
   * order is built when there is none. That build is on the swiping thread, but it happens only on
   * the rare path where the focused node disappeared, and a swipe would have built an order anyway.
   */
  @JvmStatic
  @JvmOverloads
  fun pivotAfterRemoval(
    root: AccessibilityNodeInfoCompat,
    forward: Boolean = true,
    lost: AccessibilityNodeInfoCompat? = null,
  ): AccessibilityNodeInfoCompat? {
    if (lastPivotWindowId == NO_WINDOW_ID || root.windowId != lastPivotWindowId) {
      return null
    }
    val order =
      strategy?.takeIf { root == this.root }
        ?: runCatching { OrderedTraversalStrategy(root) }.getOrNull()
        ?: return null
    val bounds = Rect()
    val away = scrolledAwayBounds
    if (away != null) {
      val shift =
        shiftOffList(
          FocusProcessorForLogicalNavigation.scrollDeltaSumY() - scrolledAwayDeltaSum,
          away,
          scrolledAwayListBounds,
          scrolledAwayForward,
        )
      val place = placeAfterScroll(away.top, away.bottom, shift, scrolledAwayForward)
      val nodes = order.dumpTree().filter { it.windowId == lastPivotWindowId }
      val target =
        itemAfterScrolledAway(
          nodes.map { node -> Rect().also { node.getBoundsInScreen(it) } },
          nodes.map { node ->
            node.isVisibleToUser &&
              AccessibilityNodeInfoUtils.shouldFocusNode(node, order.speakingNodesCache)
          },
          scrolledAwayListBounds,
          place,
          scrolledAwayForward,
        )
      if (target >= 0) {
        if (forward) {
          return nodes.getOrNull(target - 1)
        }
        return nodes.getOrNull(target + 1) ?: nodes.getOrNull(target)
      }
      if (!forward) {
        for (node in nodes) {
          node.getBoundsInScreen(bounds)
          if (bounds.top >= place) {
            return node
          }
        }
        return nodes.lastOrNull()
      }
      return lastNodeAbove(order, place, bounds)
    }
    return nodeNearPlace(
      order,
      placeAfterScrolls(lastPivotTop, FocusProcessorForLogicalNavigation.scrollDeltaSumY() - lastPivotDeltaSum),
      bounds,
      forward,
      lost,
    )
  }

  /** How far below the place a node drawn again for the lost one can start. */
  private const val SAME_PLACE_SLACK_PX = 4

  /**
   * The node to search on from for lost focus that was at [place]: the node drawn again for [lost]
   * at the place, if any; going [forward], the last node above the place, so the search lands on
   * what now occupies it; going back, the first node at the place, so the search lands on the item
   * before it. Nodes off screen, such as a pager's neighbour page, don't count.
   */
  private fun nodeNearPlace(
    order: OrderedTraversalStrategy,
    place: Int,
    bounds: Rect,
    forward: Boolean,
    lost: AccessibilityNodeInfoCompat?,
  ): AccessibilityNodeInfoCompat? {
    val lostLabel = lost?.let { it.contentDescription?.toString() ?: it.text?.toString() }
    var above: AccessibilityNodeInfoCompat? = null
    var atPlace: AccessibilityNodeInfoCompat? = null
    for (node in order.dumpTree()) {
      if (node.windowId != lastPivotWindowId || !node.isVisibleToUser) {
        continue
      }
      node.getBoundsInScreen(bounds)
      if (bounds.top < place) {
        if (atPlace == null) {
          above = node
        }
        continue
      }
      if (bounds.top > place + SAME_PLACE_SLACK_PX) {
        if (atPlace == null) {
          atPlace = node
        }
        break
      }
      if (atPlace == null) {
        atPlace = node
      }
      if (lostLabel != null && lostLabel.isNotEmpty() && node != lost &&
        node.className == lost.className &&
        lostLabel == (node.contentDescription?.toString() ?: node.text?.toString())
      ) {
        return node
      }
    }
    return if (forward) above ?: atPlace else atPlace ?: above
  }

  /** The least shift, in pixels, taken as scroll deltas in pixels rather than Compose's 1 each. */
  private const val MIN_PIXEL_SHIFT = 100

  /**
   * Where a place at [top] is after lists scrolled by [shift] pixels of deltas since it was read:
   * the content moves up as the deltas grow. Deltas too small to be pixels leave it where it was.
   */
  @JvmStatic
  fun placeAfterScrolls(top: Int, shift: Int): Int =
    if (top == Int.MIN_VALUE || abs(shift) < MIN_PIXEL_SHIFT) top else top - shift

  /** Saves the order of [root], replacing any saved order. */
  @JvmStatic
  fun put(root: AccessibilityNodeInfoCompat, strategy: OrderedTraversalStrategy) {
    if (!enabled) return
    this.root = root
    this.strategy = strategy
    // Q4 (c2): the order just saved is the newest good one; keep the last-good slot on it.
    lastGoodRoot = root
    lastGoodStrategy = strategy
    firstIgnoredChangeTime = 0L
    resetGracedChanges()
  }

  /**
   * Sets who is told when an operation throws the saved order away — a node action performed by
   * TalkBack, a resume, a focus retry — with the id of the window the order belonged to. Pass
   * null to stop being told.
   */
  @JvmStatic
  fun setOrderClearedListener(listener: IntConsumer?) {
    orderClearedListener = listener
  }

  @JvmStatic
  fun startReread(windowId: Int): Boolean {
    if (windowId == rereadWindowId) {
      return false
    }
    rereadWindowId = windowId
    return true
  }

  /** Throws away the saved order. */
  @JvmStatic
  fun clear(reason: String) {
    clear(reason, notifyListener = true)
  }

  private fun clear(reason: String, notifyListener: Boolean) {
    revisionCounter.incrementAndGet()
    rereadWindowId = NO_WINDOW_ID
    if (strategy == null) {
      return
    }
    val windowId = root?.windowId ?: NO_WINDOW_ID
    clearedWindowId = windowId
    // Q4 (c2): keep the order being thrown away as the last good one, for prediction only.
    lastGoodRoot = root
    lastGoodStrategy = strategy
    root = null
    strategy = null
    firstIgnoredChangeTime = 0L
    resetGracedChanges()
    lastClearReason = reason
    if (notifyListener) {
      orderClearedListener?.accept(windowId)
    }
  }

  /**
   * Throws away the saved order before TalkBack performs [actionId] on a node, unless the action
   * only moves focus. A scroll or click changes the screen before the app's change event reaches
   * us, and TalkBack can navigate again in between, such as right after it scrolls a list.
   */
  @JvmStatic
  fun onNodeAction(actionId: Int) {
    if (changesNodes(actionId)) {
      clear(if (LogUtils.shouldLog(Log.DEBUG)) AccessibilityNodeInfoUtils.actionToString(actionId) else "")
    }
  }

  /** Whether [actionId] can change the nodes before the app's event about it arrives. */
  @JvmStatic
  fun changesNodes(actionId: Int): Boolean = actionId !in FOCUS_ACTIONS && actionId != SHOW_ON_SCREEN

  private fun advanceRevision(event: AccessibilityEvent, cacheOnly: Boolean = false) {
    if (event.eventType and IGNORED_EVENT_TYPES != 0) return
    val trackedWindow = if (buildingWindowId != NO_WINDOW_ID) buildingWindowId
      else root?.windowId ?: NO_WINDOW_ID
    if (WindowCachePolicy.isForeignContentChange(event, trackedWindow)) return
    // Q4 (c1): a change that leaves the nodes and their order as they are — text, state
    // description or content description only — does not throw the saved order away (the
    // cache keeps serving it for up to MAX_AGE_AFTER_TEXT_CHANGE_MS), so it must not move
    // the revision either: an in-flight build of that same order would otherwise be
    // discarded as STALE for a change that changed nothing about the order. Only a change
    // that can throw the order away moves the revision — which still invalidates an
    // in-flight build when no order is saved yet, for genuinely structural changes.
    if (WindowCachePolicy.isNonStructuralContent(event)) return
    if ((!cacheOnly && !LatencyExperiments.Feature.SCOPED_CACHE.enabled()) ||
      WindowCachePolicy.affectsOrder(event, trackedWindow)) revisionCounter.incrementAndGet()
  }

  @JvmStatic
  fun noteDeferredEvent(event: AccessibilityEvent) {
    advanceRevision(event)
    val saved = root ?: return
    if (event.windowId >= 0 && event.windowId != saved.windowId) return
    // Q5: a deferred non-structural change has no bounds, so it poisons only its own
    // window — and only when the window is known; an unknown window cannot be blamed
    // on any window.
    if (event.windowId >= 0 && WindowCachePolicy.isNonStructuralContent(event)) {
      val isSeekBarStateTick =
        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
          event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
          event.className?.toString()?.contains("SeekBar") == true
      if (!isSeekBarStateTick) {
        noteGracedChange(event.windowId, null, event.packageName?.toString())
      }
    }
  }

  /** Throws away the saved order if [event] can change its window. Returns whether it did. */
  /** Forget rate observations when the early event gate is switched off. */
  @JvmStatic
  fun resetChurnTracking() = structuralChurn.clear()

  /** Runs at service entry, including for updates later coalesced by the active-event gate. */
  @JvmStatic
  fun observeIncomingEvent(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
    val changes = event.contentChangeTypes
    if (changes == 0 || changes and NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION.inv() != 0) {
      structuralChurn.record(event.windowId, SystemClock.uptimeMillis())
    }
  }

  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent): Boolean = applyEvent(event, cacheOnly = false)

  @JvmStatic
  fun onCacheOnlyEvent(event: AccessibilityEvent) {
    applyEvent(event, cacheOnly = true)
  }

  private fun applyEvent(event: AccessibilityEvent, cacheOnly: Boolean): Boolean {
    val type = event.eventType
    if (type and IGNORED_EVENT_TYPES != 0) return false
    advanceRevision(event, cacheOnly)
    if (rereadWindowId != NO_WINDOW_ID &&
      affectsWindow(event, rereadWindowId) && !isNonStructural(event)) {
      rereadWindowId = NO_WINDOW_ID
    }
    val savedRoot = root ?: return false
    val windowId = event.windowId
    if (WindowCachePolicy.isForeignContentChange(event, savedRoot.windowId)) return false
    if ((cacheOnly || LatencyExperiments.Feature.SCOPED_CACHE.enabled()) &&
      !WindowCachePolicy.affectsOrder(event, savedRoot.windowId)) return false
    if (affectsWindow(event, savedRoot.windowId)) {
      val changes = event.contentChangeTypes
      val nonStructuralChanges =
        if (regionGraceEnabled) NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION
        else NON_STRUCTURAL_CHANGES
      if (
        type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
          changes != 0 &&
          changes and nonStructuralChanges.inv() == 0
      ) {
        if (regionGraceEnabled) {
          if (windowId !in gracedChangeUnknownWindows) {
            // Q5: the region cap is per window — regions from other windows do not
            // force this window's change to be recorded as unknown.
            val bounds = if (!cacheOnly &&
              gracedChangeRegions.count { it.first == windowId } < MAX_GRACED_CHANGE_REGIONS)
              sourceBounds(event) else null
            noteGracedChange(windowId, bounds, event.packageName?.toString())
          }
        } else if (firstIgnoredChangeTime == 0L) {
          firstIgnoredChangeTime = SystemClock.uptimeMillis()
        }
        return false
      }
      clear(
        if (LogUtils.shouldLog(Log.DEBUG)) {
          AccessibilityEvent.eventTypeToString(type) +
            " 0x" +
            Integer.toHexString(event.contentChangeTypes)
        } else {
          ""
        },
        notifyListener = false,
      )
      return true
    }
    return false
  }

  private fun affectsWindow(event: AccessibilityEvent, windowId: Int): Boolean {
    val type = event.eventType
    return type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
      type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.windowId == NO_WINDOW_ID ||
      event.windowId == windowId
  }

  private fun isNonStructural(event: AccessibilityEvent): Boolean {
    val changes = event.contentChangeTypes
    return event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
      changes != 0 &&
      changes and NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION.inv() == 0
  }
}
