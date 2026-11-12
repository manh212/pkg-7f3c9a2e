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
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.talkback.timer.TimerSpeechSettings;
import com.google.android.accessibility.talkback.translation.TranslationSettings;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;

/**
 * The voice sub-screen listing the voice each feature reads with. Each row names the profile
 * in use and opens the screen that setting belongs to; nothing is moved from its own screen.
 */
public class VoiceFeatureVoicesFragment extends TalkBackPreferenceFragment {

  private static final String NOTIFICATION_VOICE_KEY = "pref_notification_voice_profile";

  private SharedPreferences prefs;

  public VoiceFeatureVoicesFragment() {
    super(R.xml.voice_feature_voices_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_voice_feature_voices);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext());
  }

  @Override
  public void onResume() {
    super.onResume();
    updateSummaries();
  }

  private void updateSummaries() {
    Context context = requireContext();
    setSummary(R.string.pref_voice_feature_notification_key, notificationVoiceName(context));
    setSummary(
        R.string.pref_voice_feature_clock_key,
        profileName(context, TimerSpeechSettings.profileId(prefs)));
    setSummary(
        R.string.pref_voice_feature_stopwatch_key,
        profileName(context, TimerSpeechSettings.stopwatchProfileId(prefs)));
    setSummary(
        R.string.pref_voice_feature_alarm_key,
        profileName(context, TimerSpeechSettings.countdownProfileId(prefs)));
    setSummary(
        R.string.pref_voice_feature_bilingual_original_key,
        profileName(context, TranslationSettings.INSTANCE.originalProfile(prefs)));
    setSummary(
        R.string.pref_voice_feature_bilingual_translated_key,
        profileName(context, TranslationSettings.INSTANCE.translatedProfile(prefs)));
  }

  /** The voice notifications read with, or Meridian's own voice when none is chosen. */
  private String notificationVoiceName(Context context) {
    String id = prefs.getString(NOTIFICATION_VOICE_KEY, "");
    if (id == null || id.isEmpty() || !VoiceProfiles.ids(prefs).contains(id)) {
      return context.getString(R.string.notification_voice_sandfly);
    }
    return VoiceProfileNames.nameOf(context, prefs, id);
  }

  /** The profile's name, or Meridian's default when none is chosen. */
  private String profileName(Context context, String id) {
    if (id == null || id.isEmpty()) {
      return context.getString(R.string.voice_profile_default);
    }
    return VoiceProfileNames.nameOf(context, prefs, id);
  }

  private void setSummary(@StringRes int keyResId, CharSequence summary) {
    Preference preference = findPreferenceByResId(keyResId);
    if (preference != null) {
      preference.setSummary(summary);
    }
  }
}
