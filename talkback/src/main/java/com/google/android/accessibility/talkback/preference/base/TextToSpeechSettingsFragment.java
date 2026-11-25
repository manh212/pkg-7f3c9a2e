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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.accessibility.utils.output.VoiceProfiles.VoiceProfile;

/**
 * The voice settings screen. Per-voice parameters (TTS engine, language, voice, volume, speed,
 * pitch, phrase chunking, equalizer and compressor) live inside {@link VoiceProfilesFragment},
 * while global reading options live directly in the general section below.
 */
public class TextToSpeechSettingsFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, VoiceProfiles.PREF_ACTIVE)) {
          // Switched by a gesture, keyboard shortcut or the Meridian menu.
          updateVoiceProfileSummary();
        } else if (TextUtils.equals(key, FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY)) {
          // Switched by the reading control.
          updateSpeechStreamValue();
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
    setUpSystemTtsSettingsPreference();
    updateVoiceProfileSummary();
    updateSpeechStreamValue();
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updateVoiceProfileSummary();
    updateSpeechStreamValue();
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

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

  private void updateSpeechStreamValue() {
    if (findPreferenceByResId(R.string.pref_speech_stream_key) instanceof ListPreference list) {
      list.setValue(FailoverTextToSpeech.chosenSpeechStream(prefs));
    }
  }

  private void setUpSystemTtsSettingsPreference() {
    Preference preference = findPreferenceByResId(R.string.pref_system_tts_settings_key);
    if (preference == null) {
      return;
    }
    Intent intent = new Intent(TalkBackService.INTENT_TTS_SETTINGS);
    if (FormFactorUtils.isAndroidTv()
        || !SettingsUtils.allowLinksOutOfSettings(requireContext())
        || !PreferenceSettingsUtils.canHandleIntent(requireContext(), intent)) {
      getPreferenceScreen().removePreferenceRecursively(preference.getKey());
      return;
    }
    preference.setIntent(intent);
  }

  static int toPercent(float multiplier) {
    return Math.round(multiplier * 100);
  }

  static String fromPercent(int percent) {
    return Float.toString(percent / 100f);
  }
}
