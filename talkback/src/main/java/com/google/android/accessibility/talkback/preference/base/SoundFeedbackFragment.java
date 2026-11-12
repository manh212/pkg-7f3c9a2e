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
import androidx.preference.SeekBarPreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/** The sound sub-screen holding feedback sounds, their volume and when they stay silent. */
public class SoundFeedbackFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, getString(R.string.pref_soundback_key))) {
          SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
              getActivity(), R.string.pref_soundback_key, R.bool.pref_soundback_default);
        }
      };

  public SoundFeedbackFragment() {
    super(R.xml.sound_feedback_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_sound_feedback);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
    setUpPercentSlider(R.string.pref_soundback_volume_seekbar_key_int);
    if (findPreference(getString(R.string.pref_soundback_volume_seekbar_key_int))
        instanceof SeekBarPreference volume) {
      volume.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            prefs
                .edit()
                .putString(getString(R.string.pref_soundback_volume_key), newValue.toString())
                .apply();
            return true;
          });
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updateSoundVolumeSlider();
    SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
        getActivity(), R.string.pref_soundback_key, R.bool.pref_soundback_default);
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  private void updateSoundVolumeSlider() {
    if (findPreference(getString(R.string.pref_soundback_volume_seekbar_key_int))
        instanceof SeekBarPreference volume) {
      volume.setValue(
          SharedPreferencesUtils.getIntFromStringPref(
              prefs,
              getResources(),
              R.string.pref_soundback_volume_key,
              R.string.pref_soundback_volume_default));
    }
  }

  /** Sets a slider to go from 5 to 100 percent in steps of 5. */
  private void setUpPercentSlider(int keyResId) {
    if (findPreference(getString(keyResId)) instanceof SeekBarPreference slider) {
      slider.setMin(5);
      slider.setMax(100);
      slider.setSeekBarIncrement(5);
    }
  }
}
