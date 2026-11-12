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
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.SpeechCompressor;
import com.google.android.accessibility.utils.output.SpeechEqualizer;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.accessibility.utils.output.VoiceProfiles.VoiceProfile;

/**
 * The voice settings: a landing page over the voice sub-screens. A voice's speed, pitch and
 * volume live on its own profile's page; the adjustment row opens the adjustment screen for
 * the voice profile in use.
 */
public class TextToSpeechSettingsFragment extends TalkBackPreferenceFragment {

  private static final String ADJUSTMENT_SCREEN_KEY = "pref_speech_adjustment_screen";

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, VoiceProfiles.PREF_ACTIVE)) {
          // Switched by a gesture, keyboard shortcut or the Backtalk menu.
          setUpAdjustmentRow();
          updateVoiceProfileSummary();
        } else if (SpeechEqualizer.isKey(key)
            || SpeechCompressor.isKey(key)
            || TextUtils.equals(key, FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY)) {
          updateAdjustmentSummary();
        }
      };

  public TextToSpeechSettingsFragment() {
    super(R.xml.text_to_speech_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_voice_settings);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext());
    setUpAdjustmentRow();
    updateVoiceProfileSummary();
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    setUpAdjustmentRow();
    updateVoiceProfileSummary();
    updateAdjustmentSummary();
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  /**
   * Names the voice profile in use on the profiles row, since the rows on this screen no longer
   * carry the settings of any one profile.
   */
  private void updateVoiceProfileSummary() {
    Preference preference = findPreferenceByResId(R.string.pref_voice_profiles_screen_key);
    if (preference == null) {
      return;
    }
    VoiceProfile profile = VoiceProfiles.readActive(prefs);
    preference.setSummary(
        (profile == null)
            ? null
            : getString(
                R.string.voice_profile_in_use,
                VoiceProfileNames.nameOf(requireContext(), prefs, profile.id())));
  }

  /**
   * Points the adjustment row at the voice profile in use, so the equalizer and compressor edit
   * the voice being heard rather than Backtalk's default.
   */
  private void setUpAdjustmentRow() {
    Preference preference = findPreference(ADJUSTMENT_SCREEN_KEY);
    if (preference == null) {
      return;
    }
    if (FormFactorUtils.isAndroidWear()) {
      getPreferenceScreen().removePreferenceRecursively(ADJUSTMENT_SCREEN_KEY);
      return;
    }
    SpeechAdjustmentFragment.setUp(preference, VoiceProfiles.activeId(prefs));
  }

  private void updateAdjustmentSummary() {
    Preference preference = findPreference(ADJUSTMENT_SCREEN_KEY);
    if (preference == null) {
      return;
    }
    preference.setSummary(
        SpeechAdjustmentFragment.summary(
            requireContext(), prefs, VoiceProfiles.activeId(prefs)));
  }

  static int toPercent(float multiplier) {
    return Math.round(multiplier * 100);
  }

  static String fromPercent(int percent) {
    return Float.toString(percent / 100f);
  }
}
