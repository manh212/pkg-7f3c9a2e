package com.google.android.accessibility.talkback.focusmanagement;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.RideAppAccessibility;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SearchDirection;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Extension point for app-specific directional navigation overrides, keeping per-app rules out of
 * {@link FocusProcessorForLogicalNavigation}.
 */
public interface AppNavigationHook {
  /**
   * Returns a specific target node to focus instead of standard traversal, or {@code null} to let
   * standard navigation proceed.
   */
  @Nullable AccessibilityNodeInfoCompat findOverrideTarget(
      @NonNull AccessibilityNodeInfoCompat pivot,
      boolean ignoreDescendantsOfPivot,
      @NonNull NavigationAction navigationAction,
      @SearchDirection int logicalDirection);

  /** Registry of built-in app navigation hooks. */
  final class Registry {
    private static final List<AppNavigationHook> HOOKS = List.of(new GrabBookingNavigationHook());

    private Registry() {}

    public static @Nullable AccessibilityNodeInfoCompat findOverrideTarget(
        @NonNull AccessibilityNodeInfoCompat pivot,
        boolean ignoreDescendantsOfPivot,
        @NonNull NavigationAction navigationAction,
        @SearchDirection int logicalDirection) {
      for (AppNavigationHook hook : HOOKS) {
        AccessibilityNodeInfoCompat target =
            hook.findOverrideTarget(pivot, ignoreDescendantsOfPivot, navigationAction, logicalDirection);
        if (target != null && !target.equals(pivot)) {
          return target;
        }
      }
      return null;
    }
  }

  /**
   * Grab exposes its genuine transportBookButton without an accessible label. When normal swipe
   * navigation would skip that nested native control, focus the original Android view itself from
   * the preceding booking controls.
   */
  final class GrabBookingNavigationHook implements AppNavigationHook {
    @Override
    public @Nullable AccessibilityNodeInfoCompat findOverrideTarget(
        @NonNull AccessibilityNodeInfoCompat pivot,
        boolean ignoreDescendantsOfPivot,
        @NonNull NavigationAction navigationAction,
        @SearchDirection int logicalDirection) {
      if (ignoreDescendantsOfPivot
          || navigationAction.targetType != NavigationTarget.TARGET_DEFAULT
          || logicalDirection != TraversalStrategy.SEARCH_FOCUS_FORWARD
          || !RideAppAccessibility.precedesGrabBookingButton(pivot)) {
        return null;
      }
      AccessibilityNodeInfoCompat root = TraversalTreeCache.rootFor(pivot);
      if (root == null) {
        root = AccessibilityNodeInfoUtils.getRoot(pivot);
      }
      return RideAppAccessibility.findGrabBookingButton(root);
    }
  }
}
