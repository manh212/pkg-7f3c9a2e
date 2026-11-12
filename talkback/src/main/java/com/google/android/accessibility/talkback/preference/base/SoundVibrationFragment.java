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
import android.os.Vibrator;
import android.text.TextUtils;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VibrationIntensity;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The sound sub-screen holding vibration feedback and its strength. */
public class SoundVibrationFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;
  private @Nullable AccessibilitySuiteListPreference fallbackVibrationLevel;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, getString(R.string.pref_vibration_key))) {
          if (FeatureSupport.isVibratorSupported(getContext())) {
            SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
                getActivity(), R.string.pref_vibration_key, R.bool.pref_vibration_default);
          }
        } else if (TextUtils.equals(key, getString(R.string.pref_vibration_strength_key))) {
          updateFallbackVibrationLevel();
        }
      };

  public SoundVibrationFragment() {
    super(R.xml.sound_vibration_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_sound_vibration);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
    setUpPercentSlider(R.string.pref_vibration_strength_key);
    Vibrator vibrator = getContext().getSystemService(Vibrator.class);
    if (vibrator == null || !vibrator.hasVibrator()) {
      PreferenceSettingsUtils.hidePreference(
          getContext(), getPreferenceScreen(), R.string.pref_vibration_strength_key);
    } else if (!vibrator.hasAmplitudeControl()) {
      showFallbackVibrationLevel();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updateFallbackVibrationLevel();
    if (FeatureSupport.isVibratorSupported(getContext())) {
      SoundAndVibrationFragment.updateTwoStatePreferenceStatus(
          getActivity(), R.string.pref_vibration_key, R.bool.pref_vibration_default);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  private void showFallbackVibrationLevel() {
    Preference slider = findPreference(getString(R.string.pref_vibration_strength_key));
    if (slider == null) return;
    int order = slider.getOrder();
    getPreferenceScreen().removePreference(slider);
    AccessibilitySuiteListPreference level = new AccessibilitySuiteListPreference(requireContext());
    level.setKey("pref_vibration_strength_duration_level");
    level.setPersistent(false);
    level.setTitle(R.string.title_pref_vibration_strength);
    level.setDialogTitle(R.string.title_pref_vibration_strength);
    level.setIconSpaceReserved(false);
    level.setOrder(order);
    level.setEntries(new CharSequence[] {
      getString(R.string.vibration_level_very_light),
      getString(R.string.vibration_level_light),
      getString(R.string.vibration_level_strong),
      getString(R.string.vibration_level_very_strong)
    });
    level.setEntryValues(new CharSequence[] {"15", "35", "65", "100"});
    level.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          prefs.edit()
              .putInt(getString(R.string.pref_vibration_strength_key), Integer.parseInt((String) newValue))
              .apply();
          return true;
        });
    fallbackVibrationLevel = level;
    getPreferenceScreen().addPreference(level);
    updateFallbackVibrationLevel();
  }

  private void updateFallbackVibrationLevel() {
    if (fallbackVibrationLevel == null) return;
    int strength = prefs.getInt(
        getString(R.string.pref_vibration_strength_key),
        getResources().getInteger(R.integer.pref_vibration_strength_default));
    int level = VibrationIntensity.levelForPercentage(strength);
    fallbackVibrationLevel.setValue(Integer.toString(VibrationIntensity.percentageForLevel(level)));
    fallbackVibrationLevel.setSummary(fallbackVibrationLevel.getEntries()[level]);
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
