package com.google.android.accessibility.talkback.focusmanagement;

import static com.google.android.accessibility.utils.Role.ROLE_FLOATING_ACTION_BUTTON;

import android.content.Context;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.talkback.actor.search.UniversalSearchActor;
import com.google.android.accessibility.talkback.recognition.ScreenElements;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.Role;

/** Filters target windows during window-granularity navigation. */
class WindowNavigationFilter extends Filter<AccessibilityWindowInfo> {
  final Context context;
  final UniversalSearchActor.State searchState;

  WindowNavigationFilter(Context context, UniversalSearchActor.State searchState) {
    this.context = context;
    this.searchState = searchState;
  }

  @Override
  public boolean accept(AccessibilityWindowInfo window) {
    if (window == null) {
      return false;
    }

    int type = AccessibilityWindowInfoUtils.getType(window);
    if (searchState.isUiVisible()) {
      return DirectionalNavigationWindowFilter.isSearchOverlay(context, window)
          || (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
          || (type == AccessibilityWindowInfo.TYPE_SYSTEM)
          || (type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
          || (Role.getRole(window.getRoot()) == ROLE_FLOATING_ACTION_BUTTON);
    } else {
      return (type == AccessibilityWindowInfo.TYPE_APPLICATION)
          || (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
          || (type == AccessibilityWindowInfo.TYPE_SYSTEM)
          || (type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
          || isScreenElementWindow(window)
          || (Role.getRole(window.getRoot()) == ROLE_FLOATING_ACTION_BUTTON);
    }
  }

  private static boolean isScreenElementWindow(AccessibilityWindowInfo window) {
    return (AccessibilityWindowInfoUtils.getType(window)
            == AccessibilityWindowInfoCompat.TYPE_ACCESSIBILITY_OVERLAY)
        && ScreenElements.isElementWindow(window.getId());
  }
}
