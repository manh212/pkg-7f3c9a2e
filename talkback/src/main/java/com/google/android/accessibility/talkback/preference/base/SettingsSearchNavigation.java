package com.google.android.accessibility.talkback.preference.base;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;

public final class SettingsSearchNavigation {
  public static final String TARGET_KEY = "settings_search_target_key";

  public static boolean focusTarget(TalkbackBaseFragment fragment) {
    Bundle arguments = fragment.getArguments();
    if (arguments == null) {
      return false;
    }
    String key = arguments.getString(TARGET_KEY, "");
    if (key.isEmpty()) {
      return false;
    }
    arguments.remove(TARGET_KEY);
    return focusPreference(fragment, key);
  }

  static boolean focusPreference(TalkbackBaseFragment fragment, String key) {
    Preference preference = fragment.findPreference(key);
    if (preference == null || !preference.isVisible()) {
      return false;
    }
    if (preference instanceof AccessibilitySuitePreferenceCategory category) {
      category.setCollapsed(false);
    }
    for (PreferenceGroup parent = preference.getParent(); parent != null; parent = parent.getParent()) {
      if (parent instanceof AccessibilitySuitePreferenceCategory category) {
        category.setCollapsed(false);
      }
    }
    fragment.scrollToPreference(preference);
    RecyclerView list = fragment.getListView();
    if (list != null) {
      list.post(() -> focusRow(fragment, list, preference, 0, true));
    }
    return list != null;
  }

  @SuppressLint("RestrictedApi")
  static void focusFirstPreference(TalkbackBaseFragment fragment) {
    RecyclerView list = fragment.getListView();
    if (list == null || !(list.getAdapter() instanceof PreferenceGroupAdapter adapter)) {
      return;
    }
    for (int i = 0; i < adapter.getItemCount(); i++) {
      Preference preference = adapter.getItem(i);
      if (preference != null && preference.isSelectable() && preference.isEnabled()) {
        if (i > 0 && list.findViewHolderForAdapterPosition(i) == null) {
          list.scrollToPosition(i);
        }
        list.post(() -> focusRow(fragment, list, preference, 0, false));
        return;
      }
    }
  }

  @SuppressLint("RestrictedApi")
  private static void focusRow(
      TalkbackBaseFragment fragment,
      RecyclerView list,
      Preference preference,
      int attempt,
      boolean restoreInputFocus) {
    if (!fragment.isResumed() || !list.isAttachedToWindow() || fragment.getListView() != list) {
      return;
    }
    if (list.getAdapter() instanceof PreferenceGroupAdapter adapter) {
      int position = adapter.getPreferenceAdapterPosition(preference);
      RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
      if (holder != null) {
        View row = holder.itemView;
        if (restoreInputFocus) {
          row.requestFocus();
        }
        row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        return;
      }
    }
    if (attempt < 3) {
      list.postDelayed(
          () -> focusRow(fragment, list, preference, attempt + 1, restoreInputFocus), 100);
    }
  }

  private SettingsSearchNavigation() {}
}
