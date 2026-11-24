package com.google.android.accessibility.talkback.focusmanagement;

import static com.google.android.accessibility.utils.Role.ROLE_FLOATING_ACTION_BUTTON;

import android.content.Context;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.actor.search.UniversalSearchActor;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.WindowUtils;
import java.util.Set;

/** Filters target window when performing directional navigation across windows. */
@VisibleForTesting
class DirectionalNavigationWindowFilter extends Filter<AccessibilityWindowInfo> {
  final Context context;
  final UniversalSearchActor.State searchState;
  final Set<Integer> pictureInPictureOverlayIds;

  DirectionalNavigationWindowFilter(
      Context context,
      UniversalSearchActor.State searchState,
      Set<Integer> pictureInPictureOverlayIds) {
    this.context = context;
    this.searchState = searchState;
    this.pictureInPictureOverlayIds = pictureInPictureOverlayIds;
  }

  @Override
  public boolean accept(AccessibilityWindowInfo window) {
    if (window == null || pictureInPictureOverlayIds.contains(window.getId())) {
      return false;
    }
    int type = AccessibilityWindowInfoUtils.getType(window);
    if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
      return false;
    }
    if (searchState.isUiVisible()) {
      return (isSearchOverlay(context, window)
          || (type == AccessibilityWindowInfo.TYPE_SYSTEM
              && (!WindowUtils.isSystemBar(context, window)
                  || !SettingsUtils.allowLinksOutOfSettings(
                      context))) // System bar in SUW is navigable.
          || (type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
          || (Role.getRole(window.getRoot()) == ROLE_FLOATING_ACTION_BUTTON));
    } else {
      boolean focusContainedInApplicationWindow =
          FormFactorUtils.isAndroidPc()
              && FeatureFlagReader.focusContainedInApplicationWindow(context);
      // When the focus should be contained in the application window,
      // application window type is filtered out.
      return ((!focusContainedInApplicationWindow
              && type == AccessibilityWindowInfo.TYPE_APPLICATION)
          || (type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER)
          || (type == AccessibilityWindowInfo.TYPE_SYSTEM
              && (!WindowUtils.isSystemBar(context, window)
                  || !SettingsUtils.allowLinksOutOfSettings(
                      context))) // System bar in SUW is navigable.
          || (type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
          || (Role.getRole(window.getRoot()) == ROLE_FLOATING_ACTION_BUTTON));
    }
  }

  static boolean isSearchOverlay(Context context, AccessibilityWindowInfo window) {
    return (AccessibilityWindowInfoUtils.getType(window)
            == AccessibilityWindowInfoCompat.TYPE_ACCESSIBILITY_OVERLAY)
        && TextUtils.equals(window.getTitle(), context.getString(R.string.title_screen_search));
  }
}
