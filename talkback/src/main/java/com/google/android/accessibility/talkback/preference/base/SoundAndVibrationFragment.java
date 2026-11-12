/*
 * Copyright (C) 2020 The Android Open Source Project
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

import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The sound and vibration settings: a landing page over the sound sub-screens. */
public class SoundAndVibrationFragment extends TalkbackBaseFragment {

  public SoundAndVibrationFragment() {
    super(R.xml.sound_and_vibration_preferences);
  }

  @Override
  public CharSequence getTitle() {
    // Changes title by supporting vibration or not. Preference Vibration Feedback will be removed
    // by TalkBackPreferenceFilter if this device doesn't support vibration.
    int titleResid =
        FeatureSupport.isVibratorSupported(getContext())
            ? R.string.title_pref_sound_and_vibration
            : R.string.title_pref_sound;
    return getText(titleResid);
  }

  /**
   * Updates the status of a switch after another screen changed it while this one is visible.
   */
  public static void updateTwoStatePreferenceStatus(
      FragmentActivity activity, int preferenceKeyResId, int preferenceDefaultKeyResId) {
    @Nullable Preference preference =
        PreferenceSettingsUtils.findPreference(activity, activity.getString(preferenceKeyResId));
    if (preference instanceof TwoStatePreference) {
      // Make sure that we have the latest value of preference before continuing.
      boolean enabledState =
          SharedPreferencesUtils.getBooleanPref(
              SharedPreferencesUtils.getSharedPreferences(activity.getApplicationContext()),
              activity.getResources(),
              preferenceKeyResId,
              preferenceDefaultKeyResId);

      ((TwoStatePreference) preference).setChecked(enabledState);
    }
  }
}
