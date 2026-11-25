package com.google.android.accessibility.talkback.focusmanagement.interpreter;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.TouchInteractionMonitor;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.TikTokAccessibilityHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves touch-exploration hits underneath full-screen obscuring video overlays (such as TikTok's
 * {@code com.ss.android.ugc.trill:id/long_press_layout}) whose Z-order sits above sibling action
 * columns (Like, Comment, Favorite, Share, Author, Caption).
 *
 * <p>Supports two paths:
 *
 * <ul>
 *   <li><b>Virtual overlay exploration (primary on Android 13+ with {@code ExtendedTouchBackend})</b>:
 *       stays in {@code STATE_TOUCH_INTERACTING} where the framework keeps delivering {@code
 *       ACTION_MOVE} coordinates, snapshots the active window's visible focusable targets once, and
 *       performs zero-IPC {@link Rect#contains(int, int)} hit-testing as the finger touches or
 *       glides across buttons.
 *   <li><b>Hover-enter fallback</b>: when framework touch exploration hits the obscuring overlay
 *       directly, uses the last recorded touch coordinates to resolve the underlying sibling
 *       button.
 * </ul>
 */
final class OverlayTouchTargetResolver {

  private static final int MAX_SIBLING_NODES_SCANNED = 400;
  private static final int MAX_WINDOW_NODES_SCANNED = 500;
  private static final long MAX_TOUCH_COORD_AGE_MS = 3_000L;
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
  private final List<CachedTarget> cachedSystemTargets = new ArrayList<>();

  /** Clears cached overlay state at touch start/end or when hovering a normal view. */
  void reset() {
    activeOverlayNode = null;
    lastResolvedTarget = null;
    cachedTargets.clear();
    cachedSystemTargets.clear();
  }

  /** Returns whether touch exploration is currently active over an obscuring full-screen overlay. */
  boolean isActive() {
    return activeOverlayNode != null && (!cachedTargets.isEmpty() || !cachedSystemTargets.isEmpty());
  }

  /**
   * Finds a visible full-screen obscuring video overlay in {@code root} (such as TikTok's {@code
   * long_press_layout}) so virtual touch exploration can bypass its Z-order interception.
   */
  @Nullable
  static AccessibilityNodeInfoCompat findVisibleObscuringOverlay(
      @Nullable AccessibilityNodeInfoCompat root) {
    if (root == null) {
      return null;
    }
    CharSequence pkg = root.getPackageName();
    if (!TikTokAccessibilityHelper.isTikTokPackage(pkg)) {
      return null;
    }
    List<AccessibilityNodeInfoCompat> matches =
        root.findAccessibilityNodeInfosByViewId(pkg + ":id/long_press_layout");
    if (matches == null || matches.isEmpty()) {
      return null;
    }
    Rect rootBounds = new Rect();
    root.getBoundsInScreen(rootBounds);
    for (int i = 0; i < matches.size(); i++) {
      AccessibilityNodeInfoCompat candidate = matches.get(i);
      if (candidate == null || !candidate.isVisibleToUser()) {
        continue;
      }
      Rect bounds = new Rect();
      candidate.getBoundsInScreen(bounds);
      if (bounds.isEmpty()) {
        continue;
      }
      if (rootBounds.isEmpty()
          || (bounds.width() >= rootBounds.width() * OVERLAY_MIN_COVERAGE_RATIO
              && bounds.height() >= rootBounds.height() * OVERLAY_MIN_COVERAGE_RATIO)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Starts a virtual touch-exploration session over {@code overlayNode} by snapshotting all visible
   * focusable targets in {@code root} and hit-testing {@code (x, y)}.
   */
  AccessibilityNodeInfoCompat startVirtualExploration(
      AccessibilityNodeInfoCompat root,
      AccessibilityNodeInfoCompat overlayNode,
      int x,
      int y) {
    return startVirtualExploration(root, overlayNode, /* systemRoots= */ null, x, y);
  }

  /**
   * Starts a virtual touch-exploration session over {@code overlayNode} by snapshotting all visible
   * focusable targets in {@code root} plus any visible system bar windows (status bar / nav bar)
   * so gliding to the top edge still reaches battery, Wi-Fi, mobile data, and clock indicators.
   */
  AccessibilityNodeInfoCompat startVirtualExploration(
      AccessibilityNodeInfoCompat root,
      AccessibilityNodeInfoCompat overlayNode,
      @Nullable List<AccessibilityNodeInfoCompat> systemRoots,
      int x,
      int y) {
    reset();
    activeOverlayNode = overlayNode;
    collectTargetsFromSubtree(
        root, overlayNode, MAX_WINDOW_NODES_SCANNED, /* requireIntersect= */ false, cachedTargets);
    if (systemRoots != null) {
      for (int i = 0; i < systemRoots.size(); i++) {
        AccessibilityNodeInfoCompat sysRoot = systemRoots.get(i);
        if (sysRoot != null) {
          collectTargetsFromSubtree(
              sysRoot,
              /* overlayNode= */ null,
              MAX_SIBLING_NODES_SCANNED,
              /* requireIntersect= */ false,
              cachedSystemTargets);
        }
      }
    }
    AccessibilityNodeInfoCompat hit = hitTest(x, y);
    AccessibilityNodeInfoCompat resolved = (hit != null) ? hit : overlayNode;
    lastResolvedTarget = resolved;
    return resolved;
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
    if (activeOverlayNode == null || (cachedTargets.isEmpty() && cachedSystemTargets.isEmpty())) {
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
    AccessibilityNodeInfoCompat systemHit = hitTestList(cachedSystemTargets, x, y);
    if (systemHit != null) {
      return systemHit;
    }
    return hitTestList(cachedTargets, x, y);
  }

  @Nullable
  private static AccessibilityNodeInfoCompat hitTestList(
      List<CachedTarget> targets, int x, int y) {
    CachedTarget best = null;
    for (int i = 0; i < targets.size(); i++) {
      CachedTarget candidate = targets.get(i);
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
    collectTargetsFromSubtree(
        parent, overlayNode, MAX_SIBLING_NODES_SCANNED, /* requireIntersect= */ true, cachedTargets);
  }

  private void collectTargetsFromSubtree(
      AccessibilityNodeInfoCompat root,
      @Nullable AccessibilityNodeInfoCompat overlayNode,
      int maxNodes,
      boolean requireIntersect,
      List<CachedTarget> outTargets) {
    Rect overlayBounds = new Rect();
    if (overlayNode != null) {
      overlayNode.getBoundsInScreen(overlayBounds);
    }

    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    int rootChildCount = root.getChildCount();
    for (int i = 0; i < rootChildCount; i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null || child.equals(overlayNode) || !child.isVisibleToUser()) {
        continue;
      }
      queue.add(child);
    }

    int scanned = 0;
    while (!queue.isEmpty() && scanned < maxNodes) {
      AccessibilityNodeInfoCompat node = queue.poll();
      scanned++;
      if (!node.isVisibleToUser() || node.equals(overlayNode)) {
        continue;
      }

      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      if (requireIntersect && !overlayBounds.isEmpty() && !bounds.isEmpty()
          && !Rect.intersects(bounds, overlayBounds)) {
        continue;
      }

      int nestedCount = node.getChildCount();
      if (AccessibilityNodeInfoUtils.shouldFocusNode(node)) {
        boolean coversOverlay =
            overlayNode != null
                && !overlayBounds.isEmpty()
                && bounds.width() >= overlayBounds.width() * 0.85f
                && bounds.height() >= overlayBounds.height() * 0.85f;
        if (!bounds.isEmpty() && !coversOverlay) {
          outTargets.add(new CachedTarget(node, bounds));
        }
        if (nestedCount == 0) {
          continue;
        }
      }

      for (int i = 0; i < nestedCount && scanned < maxNodes; i++) {
        AccessibilityNodeInfoCompat child = node.getChild(i);
        if (child != null && child.isVisibleToUser() && !child.equals(overlayNode)) {
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
