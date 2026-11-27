/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter;
import com.google.android.accessibility.talkback.quickactions.SettingsQuickActions;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.preference.BasePreferencesFragment;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Panel holding a set of base fragment for preferences. */
public abstract class TalkbackBaseFragment extends BasePreferencesFragment {
  private static final int INVALID_VALUE = -1;
  private static final String KEY_RETURN_FOCUS = "meridian.settings.return_focus";
  private static final String KEY_INITIAL_FOCUS = "meridian.settings.initial_focus";
  private boolean initialFocusPending = true;
  private @Nullable String returnFocusKey;

  private final int xmlResId;

  private final SharedPreferences.OnSharedPreferenceChangeListener dependencyVisibilityListener =
      (sharedPreferences, key) -> syncDependentPreferencesVisibility();

  public TalkbackBaseFragment() {
    xmlResId = INVALID_VALUE;
  }

  public TalkbackBaseFragment(int xmlResId) {
    this.xmlResId = xmlResId;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    ViewCompat.setAccessibilityPaneTitle(view, getTitle());
    if (savedInstanceState != null) {
      initialFocusPending = savedInstanceState.getBoolean(KEY_INITIAL_FOCUS, initialFocusPending);
      if (returnFocusKey == null) {
        returnFocusKey = savedInstanceState.getString(KEY_RETURN_FOCUS);
      }
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(KEY_RETURN_FOCUS, returnFocusKey);
    outState.putBoolean(KEY_INITIAL_FOCUS, initialFocusPending);
  }

  public void rememberReturnFocus(@Nullable String key) {
    returnFocusKey = key;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (getContext() != null) {
      SharedPreferencesUtils.getSharedPreferences(getContext())
          .registerOnSharedPreferenceChangeListener(dependencyVisibilityListener);
    }
    syncDependentPreferencesVisibility();
    if (getContext() != null) com.google.android.accessibility.talkback.licensing.SupportClient.opened(getContext());
    View view = getView();
    if (view != null) {
      view.post(() -> {
        if (!isResumed() || getView() != view) {
          return;
        }
        boolean focused = SettingsSearchNavigation.focusTarget(this)
            || (returnFocusKey != null
                && SettingsSearchNavigation.focusPreference(this, returnFocusKey));
        if (!focused && (initialFocusPending || returnFocusKey != null)) {
          SettingsSearchNavigation.focusFirstPreference(this);
        }
        initialFocusPending = false;
        returnFocusKey = null;
      });
    }
  }

  @Override
  public void onPause() {
    if (getContext() != null) {
      SharedPreferencesUtils.getSharedPreferences(getContext())
          .unregisterOnSharedPreferenceChangeListener(dependencyVisibilityListener);
    }
    SettingsQuickActions.setCurrentSection(null);
    super.onPause();
  }

  /**
   * This function is used to get the sub title which the fragment likes to show on app bar. The
   * child class implements this function and will show sub title on app bar. If the child class
   * doesn't implement this function, sub title will not show anything.
   *
   * @return The sub title of the fragment will show on app bar.
   */
  @Override
  public @Nullable CharSequence getSubTitle() {
    return null;
  }

  @Override
  public int getXmlResId() {
    return xmlResId;
  }

  /** Preferences managed by this fragment. */
  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    if (xmlResId != INVALID_VALUE) {
      super.onCreatePreferences(savedInstanceState, rootKey);

      final Context context = getContext();
      if (context == null) {
        return;
      }
      TalkBackPreferenceFilter talkBackPreferenceFilter = new TalkBackPreferenceFilter(context);
      talkBackPreferenceFilter.filterPreferences(getPreferenceScreen());
      syncDependentPreferencesVisibility();
    }
  }

  /**
   * Hides preferences whose android:dependency points to an unchecked switch/checkbox on this
   * screen so screen-reader users do not swipe through unavailable disabled controls.
   */
  protected void syncDependentPreferencesVisibility() {
    PreferenceScreen screen = getPreferenceScreen();
    if (screen == null) {
      return;
    }
    syncGroupDependencyVisibility(screen);
  }

  private void syncGroupDependencyVisibility(PreferenceGroup group) {
    for (int i = 0; i < group.getPreferenceCount(); i++) {
      Preference pref = group.getPreference(i);
      String depKey = pref.getDependency();
      if (depKey != null && !depKey.isEmpty()) {
        Preference parent = findPreference(depKey);
        if (parent instanceof TwoStatePreference) {
          boolean parentChecked = ((TwoStatePreference) parent).isChecked();
          if (pref.isVisible() != parentChecked) {
            pref.setVisible(parentChecked);
          }
        }
      }
      if (pref instanceof PreferenceGroup) {
        syncGroupDependencyVisibility((PreferenceGroup) pref);
      }
    }
  }

  /**
   * Collapses every category of the screen. Call after the preferences have their final
   * visibility.
   */
  protected void collapseCategories() {
    PreferenceScreen screen = getPreferenceScreen();
    Context context = getContext();
    if (screen == null || context == null) {
      return;
    }
    for (int i = 0; i < screen.getPreferenceCount(); i++) {
      Preference preference = screen.getPreference(i);
      if (preference instanceof AccessibilitySuitePreferenceCategory) {
        ((AccessibilitySuitePreferenceCategory) preference)
            .setCollapsible(
                context.getString(R.string.category_collapsed),
                context.getString(R.string.category_expanded));
      }
    }
  }

  public boolean createNewItem() {
    return CreateNewShortcut.perform(this);
  }

  /** Turns a preference on or off, selecting the preference by key-resource-ID. */
  public void setEnabled(Context context, int preferenceKeyResId, boolean enable) {
    Preference preference = findPreference(context.getString(preferenceKeyResId));
    if (preference != null) {
      preference.setEnabled(enable);
    }
  }

  /** Returns the preference associated with the specified resource identifier. */
  public Preference findPreferenceByResId(@StringRes int resId) {
    return findPreference(getString(resId));
  }
}

