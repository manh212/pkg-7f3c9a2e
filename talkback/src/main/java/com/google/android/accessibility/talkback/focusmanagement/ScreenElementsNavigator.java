package com.google.android.accessibility.talkback.focusmanagement;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.recognition.ScreenElements;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SearchDirection;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handles swipe navigation through recognized {@link ScreenElements}, keeping overlay-element
 * ordering and anchor handoff decoupled from {@link FocusProcessorForLogicalNavigation}.
 */
final class ScreenElementsNavigator {
  private static final int MAX_ORDERED_NODES = 2000;

  interface Callback {
    boolean focusNode(
        AccessibilityNodeInfoCompat target, NavigationAction navigationAction, EventId eventId);

    boolean continueFromAnchor(
        AccessibilityNodeInfoCompat anchor,
        boolean ignoreDescendantsOfPivot,
        NavigationAction navigationAction,
        EventId eventId);
  }

  private final AccessibilityService service;
  private final FocusFinder focusFinder;
  private @Nullable AccessibilityNodeInfoCompat leavingScreenElementsOf;

  ScreenElementsNavigator(AccessibilityService service, FocusFinder focusFinder) {
    this.service = service;
    this.focusFinder = focusFinder;
  }

  /**
   * Moves through recognised screen elements, which sit in the reading order after the app node
   * each is anchored to. Returns null when the swipe is the app's own.
   */
  @Nullable Boolean navigateAroundScreenElements(
      AccessibilityNodeInfoCompat pivot,
      boolean ignoreDescendantsOfPivot,
      NavigationAction navigationAction,
      @SearchDirection int logicalDirection,
      EventId eventId,
      Callback callback) {
    if (ScreenElements.getNeedsOrder()) {
      orderScreenElements();
    }
    boolean forward = logicalDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD;
    int index = ScreenElements.indexOf(pivot);
    if (index >= 0) {
      @Nullable AccessibilityNodeInfoCompat next = ScreenElements.neighbour(index, forward);
      if (next != null) {
        return callback.focusNode(next, navigationAction, eventId);
      }
      @Nullable AccessibilityNodeInfoCompat anchor = ScreenElements.anchor(index);
      if (anchor != null && ScreenElements.isCoveredHost(anchor)) {
        // A view the elements cover is passed over, also a WebView, which would otherwise take the
        // swipe into its own web navigation.
        @Nullable AccessibilityNodeInfoCompat past = ScreenElements.pastCoveredHost(anchor, forward);
        // At the window's edge, the swipe goes on as SandFly's own: to the next window, or around.
        return past == null ? null : callback.focusNode(past, navigationAction, eventId);
      }
      if (!forward) {
        return anchor != null && callback.focusNode(anchor, navigationAction, eventId);
      }
      if (anchor == null) {
        @Nullable AccessibilityNodeInfoCompat first = ScreenElements.firstAppNodeOrNull();
        return first != null && callback.focusNode(first, navigationAction, eventId);
      }
      // Carry on from the anchor as if the swipe had started there.
      leavingScreenElementsOf = anchor;
      try {
        return callback.continueFromAnchor(
            anchor, ignoreDescendantsOfPivot, navigationAction, eventId);
      } finally {
        leavingScreenElementsOf = null;
      }
    }
    if (pivot.getWindowId() != ScreenElements.getHostWindow()) {
      return null;
    }
    if (forward) {
      if (pivot.equals(leavingScreenElementsOf)) {
        return null;
      }
      @Nullable AccessibilityNodeInfoCompat element = ScreenElements.firstAfter(pivot);
      return element == null ? null : callback.focusNode(element, navigationAction, eventId);
    }
    if (ScreenElements.isFirstAppNode(pivot)) {
      @Nullable AccessibilityNodeInfoCompat element = ScreenElements.lastBefore(null);
      if (element != null) {
        return callback.focusNode(element, navigationAction, eventId);
      }
    }
    return null;
  }

  /** Gives each recognised screen element its anchor among the host window's nodes. */
  void orderScreenElements() {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    @Nullable AccessibilityWindowInfo host = null;
    for (AccessibilityWindowInfo window : AccessibilityServiceCompatUtils.getWindows(service)) {
      if (window.getId() == ScreenElements.getHostWindow()) {
        host = window;
        break;
      }
    }
    @Nullable AccessibilityNodeInfoCompat root =
        host == null ? null : AccessibilityWindowInfoUtils.getRootCompat(host);
    if (root != null) {
      TraversalStrategy strategy =
          TraversalStrategyUtils.getTraversalStrategy(
              root, focusFinder, TraversalStrategy.SEARCH_FOCUS_FORWARD);
      Filter<AccessibilityNodeInfoCompat> filter =
          NavigationTarget.createNodeFilter(
              NavigationTarget.TARGET_DEFAULT, strategy.getSpeakingNodesCache());
      @Nullable AccessibilityNodeInfoCompat node =
          filter.accept(root)
              ? root
              : TraversalStrategyUtils.searchFocus(
                  strategy, root, TraversalStrategy.SEARCH_FOCUS_FORWARD, filter);
      while (node != null && nodes.size() < MAX_ORDERED_NODES) {
        if (nodes.contains(node)) {
          break;
        }
        nodes.add(node);
        node =
            TraversalStrategyUtils.searchFocus(
                strategy, node, TraversalStrategy.SEARCH_FOCUS_FORWARD, filter);
      }
    }
    ScreenElements.setOrder(nodes);
  }
}
