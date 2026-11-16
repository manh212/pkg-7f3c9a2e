/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.android.accessibility.talkback.preference.search;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.provider.Settings;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.preference.base.SettingsSearchIndex;
import com.google.android.accessibility.talkback.preference.base.SettingsSearchNavigation;
import com.google.android.accessibility.utils.preference.BasePreferencesActivity;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Translates an intent from the system Settings search contract into the fragment extras that
 * {@link com.google.android.accessibility.talkback.preference.base.TalkBackPreferencesActivity}
 * understands, so a Meridian setting found through system Settings search opens on the right
 * screen with the setting focused.
 */
public final class TalkBackSearchIndexablesProvider {

  private TalkBackSearchIndexablesProvider() {}

  /**
   * Fills in the target fragment and arguments on an intent from system Settings search. A bare
   * preference key is resolved to its containing fragment through the settings search index; the
   * key itself is kept in the fragment arguments so the target row is focused on arrival.
   */
  public static void assignToFragmentFromSearch(Intent intent) {
    if (intent == null) {
      return;
    }
    String key = intent.getStringExtra(Settings.EXTRA_FRAGMENT_ARG_KEY);
    String fragment = intent.getStringExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT);
    SettingsSearchIndex.Entry entry = null;
    if ((fragment == null || fragment.isEmpty()) && key != null && !key.isEmpty()) {
      entry = entryForKey(key);
      if (entry != null) {
        fragment = entry.fragment;
      }
    }
    if (fragment != null && !fragment.isEmpty()) {
      intent.putExtra(BasePreferencesActivity.FRAGMENT_NAME, fragment);
    }
    if (key != null && !key.isEmpty()) {
      Bundle args = intent.getBundleExtra(BasePreferencesActivity.FRAGMENT_ARGS);
      if (args == null) {
        args = new Bundle();
      }
      if (entry != null) {
        args.putAll(entry.arguments);
      }
      args.putString(SettingsSearchNavigation.TARGET_KEY, key);
      intent.putExtra(BasePreferencesActivity.FRAGMENT_ARGS, args);
    }
  }

  /** Returns true when the intent carries the system Settings search contract extras. */
  public static boolean isFromSearchIndexablesContract(Intent intent) {
    return intent != null
        && (intent.hasExtra(Settings.EXTRA_FRAGMENT_ARG_KEY)
            || intent.hasExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT));
  }

  @Nullable
  private static SettingsSearchIndex.Entry entryForKey(String key) {
    TalkBackService service = TalkBackService.getInstance();
    if (service == null) {
      return null;
    }
    SettingsSearchIndex.Builder builder = new SettingsSearchIndex.Builder(service);
    while (builder.step()) {
    }
    for (SettingsSearchIndex.Entry entry : builder.entries()) {
      if (key.equals(entry.key)) {
        return entry;
      }
    }
    return null;
  }
}
