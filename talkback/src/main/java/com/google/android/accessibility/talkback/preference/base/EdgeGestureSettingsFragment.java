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
      AccessibilitySuitePreferenceCategory group =
          new AccessibilitySuitePreferenceCategory(requireContext());
      group.setKey(switch (edge) {
        case LEFT -> "edge_gestures_left";
        case RIGHT -> "edge_gestures_right";
        case BOTTOM -> "edge_gestures_bottom";
      });
      group.setTitle(edge.title);
      getPreferenceScreen().addPreference(group);
      for (EdgeGesture gesture : EdgeGesture.values()) {
        if (gesture.edge == edge) {
          group.addPreference(new GestureListPreference(
              requireContext(), gesture.key, gesture.title(requireContext()), unassigned));
        }
      }
    }
    collapseCategories();
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
