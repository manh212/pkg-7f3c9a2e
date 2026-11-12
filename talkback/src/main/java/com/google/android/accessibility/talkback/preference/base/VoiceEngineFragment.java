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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import java.util.List;

/** The voice sub-screen holding the reader, the speech stream and the system TTS settings. */
public class VoiceEngineFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY)) {
          // Switched by the reading control.
          updateSpeechStreamValue();
        }
      };

  public VoiceEngineFragment() {
    super(R.xml.voice_engine_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_voice_engine_stream);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext());

    setUpEnginePreference();
    setUpSystemTtsSettingsPreference();
    updateSpeechStreamValue();
  }

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updateSpeechStreamValue();
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  /**
   * Shows the volume speech uses. Before the list, a switch chose between the accessibility and
   * media volumes, and its choice is shown until another is made.
   */
  private void updateSpeechStreamValue() {
    if (findPreferenceByResId(R.string.pref_speech_stream_key) instanceof ListPreference list) {
      list.setValue(FailoverTextToSpeech.chosenSpeechStream(prefs));
    }
  }

  private void setUpEnginePreference() {
    if (!(findPreferenceByResId(R.string.pref_tts_engine_key) instanceof ListPreference enginePref)) {
      return;
    }

    Context context = requireContext();
    List<String> engines = FailoverTextToSpeech.getInstalledTtsEngines(context.getPackageManager());
    CharSequence[] entries = new CharSequence[engines.size() + 1];
    CharSequence[] values = new CharSequence[engines.size() + 1];
    entries[0] = getString(R.string.summary_pref_tts_engine_default);
    values[0] = "";
    for (int i = 0; i < engines.size(); i++) {
      entries[i + 1] = FailoverTextToSpeech.getEngineDisplayName(context, engines.get(i));
      values[i + 1] = engines.get(i);
    }
    enginePref.setEntries(entries);
    enginePref.setEntryValues(values);

    enginePref.setSummaryProvider(
        preference -> {
          CharSequence entry = ((ListPreference) preference).getEntry();
          return (entry == null) ? entries[0] : entry;
        });
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
      getPreferenceScreen().removePreference(preference);
      return;
    }
    preference.setIntent(intent);
  }
}
