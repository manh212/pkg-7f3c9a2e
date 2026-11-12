/*
 * Copyright 2026 Backtalk contributors
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

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/** The sound sub-screen holding audio ducking and muting by covering the proximity sensor. */
public class SoundDuckingFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, getString(R.string.pref_use_audio_focus_key))) {
          SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
              getActivity(),
              R.string.pref_use_audio_focus_key,
              R.bool.pref_use_audio_focus_default);
        }
      };

  public SoundDuckingFragment() {
    super(R.xml.sound_ducking_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_sound_ducking);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
        getActivity(), R.string.pref_use_audio_focus_key, R.bool.pref_use_audio_focus_default);
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }
}
