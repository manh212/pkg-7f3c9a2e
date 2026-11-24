package com.google.android.accessibility.talkback.focusmanagement.interpreter;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.TouchInteractionMonitor;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves touch-exploration hits underneath full-screen obscuring video overlays (such as TikTok's
 * {@code com.ss.android.ugc.trill:id/long_press_layout}) whose Z-order sits above sibling action
 * columns (Like, Comment, Favorite, Share, Author, Caption).
 *
 * <p>When touch exploration hovers on such an overlay, Android's hit-test stops at the overlay and
 * never emits {@code TYPE_VIEW_HOVER_ENTER} for the buttons underneath — even while the finger
 * glides across them. This resolver snapshots visible focusable targets from the overlay's siblings
 * once per touch interaction and performs zero-IPC {@link Rect#contains(int, int)} hit-testing on
 * both the initial hover and subsequent finger moves.
 */
final class OverlayTouchTargetResolver {

  private static final int MAX_SIBLING_NODES_SCANNED = 160;
  private static final long MAX_TOUCH_COORD_AGE_MS = 500L;
  private static final float OVERLAY_MIN_COVERAGE_RATIO = 0.70f;

  private static final class CachedTarget {
    final AccessibilityNodeInfoCompat node;
    final Rect bounds;
    final long area;

    CachedTarget(AccessibilityNodeInfoCompat node, Rect bounds) {
      this.node = node;
      this.bounds = bounds;
      this.area = (long) bounds.width() * (long) bounds.height();
    }
  }

  @Nullable private AccessibilityNodeInfoCompat activeOverlayNode;
  @Nullable private AccessibilityNodeInfoCompat lastResolvedTarget;
  private final List<CachedTarget> cachedTargets = new ArrayList<>();

  /** Clears cached overlay state at touch start/end or when hovering a normal view. */
  void reset() {
    activeOverlayNode = null;
    lastResolvedTarget = null;
    cachedTargets.clear();
  }

  /** Returns whether touch exploration is currently hovering on an obscuring full-screen overlay. */
  boolean isActive() {
    return activeOverlayNode != null && !cachedTargets.isEmpty();
  }

  /**
   * Checks if {@code touchedFocusableNode} is a full-screen overlay covering interactive siblings.
   * If so, snapshots the visible sibling targets and returns the target under the current touch
   * coordinates (or {@code touchedFocusableNode} itself if touching empty video space).
   */
  AccessibilityNodeInfoCompat resolveOnHoverEnter(
      AccessibilityNodeInfoCompat touchedFocusableNode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        || !isObscuringOverlay(touchedFocusableNode)) {
      reset();
      return touchedFocusableNode;
    }

    if (!touchedFocusableNode.equals(activeOverlayNode)) {
      reset();
      activeOverlayNode = touchedFocusableNode;
      collectSiblingTargets(touchedFocusableNode);
    }

    if (cachedTargets.isEmpty()) {
      lastResolvedTarget = touchedFocusableNode;
      return touchedFocusableNode;
    }

    int x = TouchInteractionMonitor.getLastTouchX();
    int y = TouchInteractionMonitor.getLastTouchY();
    long ageMs = SystemClock.uptimeMillis() - TouchInteractionMonitor.getLastTouchUptimeMs();
    if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || ageMs < 0 || ageMs > MAX_TOUCH_COORD_AGE_MS) {
      lastResolvedTarget = touchedFocusableNode;
      return touchedFocusableNode;
    }

    AccessibilityNodeInfoCompat hit = hitTest(x, y);
    AccessibilityNodeInfoCompat resolved = (hit != null) ? hit : touchedFocusableNode;
    lastResolvedTarget = resolved;
    return resolved;
  }

  /**
   * Hit-tests cached targets as the finger glides across an active full-screen overlay. Returns
   * the newly entered target if it differs from the previous target, or {@code null} if unchanged.
   */
  @Nullable
  AccessibilityNodeInfoCompat resolveOnTouchMove(int x, int y) {
    if (activeOverlayNode == null || cachedTargets.isEmpty()) {
      return null;
    }
    AccessibilityNodeInfoCompat hit = hitTest(x, y);
    AccessibilityNodeInfoCompat resolved = (hit != null) ? hit : activeOverlayNode;
    if (resolved.equals(lastResolvedTarget)) {
      return null;
    }
    lastResolvedTarget = resolved;
    return resolved;
  }

  @Nullable
  private AccessibilityNodeInfoCompat hitTest(int x, int y) {
    CachedTarget best = null;
    for (int i = 0; i < cachedTargets.size(); i++) {
      CachedTarget candidate = cachedTargets.get(i);
      if (candidate.bounds.contains(x, y)) {
        if (best == null || candidate.area < best.area) {
          best = candidate;
        }
      }
    }
    return (best != null) ? best.node : null;
  }

  private void collectSiblingTargets(AccessibilityNodeInfoCompat overlayNode) {
    AccessibilityNodeInfoCompat parent = overlayNode.getParent();
    if (parent == null) {
      return;
    }
    int childCount = parent.getChildCount();
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    for (int i = 0; i < childCount; i++) {
      AccessibilityNodeInfoCompat sibling = parent.getChild(i);
      if (sibling == null || sibling.equals(overlayNode) || !sibling.isVisibleToUser()) {
        continue;
      }
      queue.add(sibling);
    }

    Rect overlayBounds = new Rect();
    overlayNode.getBoundsInScreen(overlayBounds);
    int scanned = 0;
    while (!queue.isEmpty() && scanned < MAX_SIBLING_NODES_SCANNED) {
      AccessibilityNodeInfoCompat node = queue.poll();
      scanned++;
      if (!node.isVisibleToUser()) {
        continue;
      }

      int nestedCount = node.getChildCount();
      if (AccessibilityNodeInfoUtils.shouldFocusNode(node)) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean coversOverlay =
            !overlayBounds.isEmpty()
                && bounds.width() >= overlayBounds.width() * 0.85f
                && bounds.height() >= overlayBounds.height() * 0.85f;
        if (!bounds.isEmpty() && !coversOverlay) {
          cachedTargets.add(new CachedTarget(node, bounds));
        }
        if (nestedCount == 0) {
          continue;
        }
      }

      for (int i = 0; i < nestedCount && scanned < MAX_SIBLING_NODES_SCANNED; i++) {
        AccessibilityNodeInfoCompat child = node.getChild(i);
        if (child != null && child.isVisibleToUser()) {
          queue.add(child);
        }
      }
    }
  }

  private static boolean isObscuringOverlay(AccessibilityNodeInfoCompat node) {
    if (node.getChildCount() != 0 || !node.isVisibleToUser()) {
      return false;
    }
    String viewId = node.getViewIdResourceName();
    if (viewId != null && viewId.endsWith(":id/long_press_layout")) {
      return true;
    }
    CharSequence className = node.getClassName();
    if (className == null || !"android.view.View".equals(className.toString())) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || parent.getChildCount() <= 1) {
      return false;
    }
    int parentRole = Role.getRole(parent);
    if (parentRole != Role.ROLE_PAGER) {
      return false;
    }
    Rect nodeBounds = new Rect();
    Rect parentBounds = new Rect();
    node.getBoundsInScreen(nodeBounds);
    parent.getBoundsInScreen(parentBounds);
    if (nodeBounds.isEmpty() || parentBounds.isEmpty()) {
      return false;
    }
    return nodeBounds.width() >= parentBounds.width() * OVERLAY_MIN_COVERAGE_RATIO
        && nodeBounds.height() >= parentBounds.height() * OVERLAY_MIN_COVERAGE_RATIO;
  }
}
