/*
 * Copyright 2026 SandFly contributors
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
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.SpeechCompressor;
import com.google.android.accessibility.utils.output.SpeechEqualizer;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The speech adjustment entry: one row for the speech equalizer and one for the multiband
 * compressor, each with its own settings, for one voice profile or for the default voice.
 */
public class SpeechAdjustmentFragment extends TalkbackBaseFragment {

  static final String ARG_PROFILE_ID = "profile_id";

  private SharedPreferences prefs;
  private String id = "";
  private @Nullable Preference equalizer;
  private @Nullable Preference compressor;

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_speech_adjustment);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    Context context = requireContext();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    Bundle args = getArguments();
    id = (args == null) ? "" : args.getString(ARG_PROFILE_ID, "");
    getPreferenceManager().setStorageDeviceProtected();
    PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
    setPreferenceScreen(screen);
    if (!id.isEmpty() && !VoiceProfiles.ids(prefs).contains(id)) {
      return;
    }

    equalizer = new AccessibilitySuitePreference(context);
    equalizer.setTitle(R.string.title_pref_speech_equalizer);
    equalizer.setPersistent(false);
    equalizer.setIconSpaceReserved(false);
    SpeechEqualizerFragment.setUp(equalizer, id);
    screen.addPreference(equalizer);

    compressor = new AccessibilitySuitePreference(context);
    compressor.setTitle(R.string.title_pref_speech_compressor);
    compressor.setPersistent(false);
    compressor.setIconSpaceReserved(false);
    SpeechCompressorFragment.setUp(compressor, id);
    screen.addPreference(compressor);
  }

  @Override
  public void onResume() {
    super.onResume();
    if (equalizer != null) {
      equalizer.setSummary(SpeechEqualizerFragment.summary(requireContext(), prefs, id));
    }
    if (compressor != null) {
      compressor.setSummary(SpeechCompressorFragment.summary(requireContext(), prefs, id));
    }
  }

  static String summary(Context context, SharedPreferences prefs, String profileId) {
    if (!prefs.getBoolean(
        FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
        FailoverTextToSpeech.lowLatencyAudioDefault(context))) {
      return context.getString(R.string.summary_speech_equalizer_needs_low_latency);
    }
    boolean equalizerOn = SpeechEqualizer.isOn(prefs, profileId);
    boolean compressorOn = SpeechCompressor.isOn(prefs, profileId);
    if (!equalizerOn && !compressorOn) {
      return context.getString(R.string.speech_equalizer_off);
    }
    if (equalizerOn && compressorOn) {
      return context.getString(
          R.string.speech_adjustment_two_summaries,
          SpeechEqualizerFragment.summary(context, prefs, profileId),
          SpeechCompressorFragment.summary(context, prefs, profileId));
    }
    return equalizerOn
        ? SpeechEqualizerFragment.summary(context, prefs, profileId)
        : SpeechCompressorFragment.summary(context, prefs, profileId);
  }

  static void setUp(Preference preference, String profileId) {
    preference.setFragment(SpeechAdjustmentFragment.class.getName());
    preference.getExtras().putString(ARG_PROFILE_ID, profileId);
  }
}
