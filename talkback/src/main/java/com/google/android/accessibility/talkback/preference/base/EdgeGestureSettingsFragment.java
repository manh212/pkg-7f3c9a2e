package com.google.android.accessibility.talkback.preference.base;

import android.os.Build;
import android.os.Bundle;
import androidx.preference.Preference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture;

public final class EdgeGestureSettingsFragment extends TalkBackPreferenceFragment {
  public EdgeGestureSettingsFragment() {
    super(R.xml.edge_gesture_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.edge_gestures_screen);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return;
    }
    String unassigned = getString(R.string.shortcut_value_unassigned);
    for (EdgeGesture.Edge edge : EdgeGesture.Edge.values()) {
      String edgePrefix = switch (edge) {
        case LEFT -> "edge_gestures_left";
        case RIGHT -> "edge_gestures_right";
        case BOTTOM -> "edge_gestures_bottom";
      };
      String edgeName = getString(edge.title);
      addCategory(edgePrefix + "_swipe",
          getString(R.string.edge_gesture_category_format, edgeName, getString(R.string.edge_gesture_group_swipe)),
          edge, /* tap= */ false, /* fingers= */ 1, unassigned);
      addCategory(edgePrefix + "_tap_1f",
          getString(R.string.edge_gesture_category_format, edgeName, getString(R.string.edge_gesture_group_tap_1f)),
          edge, /* tap= */ true, /* fingers= */ 1, unassigned);
      addCategory(edgePrefix + "_tap_2f",
          getString(R.string.edge_gesture_category_format, edgeName, getString(R.string.edge_gesture_group_tap_2f)),
          edge, /* tap= */ true, /* fingers= */ 2, unassigned);
      addCategory(edgePrefix + "_tap_3f",
          getString(R.string.edge_gesture_category_format, edgeName, getString(R.string.edge_gesture_group_tap_3f)),
          edge, /* tap= */ true, /* fingers= */ 3, unassigned);
    }
    collapseCategories();
  }

  private void addCategory(
      String key,
      String title,
      EdgeGesture.Edge edge,
      boolean tap,
      int fingers,
      String unassigned) {
    AccessibilitySuitePreferenceCategory group =
        new AccessibilitySuitePreferenceCategory(requireContext());
    group.setKey(key);
    group.setTitle(title);
    getPreferenceScreen().addPreference(group);
    for (EdgeGesture gesture : EdgeGesture.values()) {
      if (gesture.edge == edge && gesture.isTap() == tap && gesture.fingers == fingers) {
        group.addPreference(new GestureListPreference(
            requireContext(), gesture.key, gesture.title(requireContext()), unassigned));
      }
    }
  }

  @Override
  public void onDisplayPreferenceDialog(Preference preference) {
    if (preference instanceof GestureListPreference gesture) {
      GestureListPreference.showCommandList(this, gesture);
    } else {
      super.onDisplayPreferenceDialog(preference);
    }
  }
}
