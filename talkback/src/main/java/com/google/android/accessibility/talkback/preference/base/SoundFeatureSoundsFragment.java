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
import com.google.android.accessibility.talkback.timer.TimerSounds;
import com.google.android.accessibility.talkback.timer.TimerSpeechSettings;
import com.google.android.accessibility.talkback.translation.TranslationSettings;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;

/**
 * The sound sub-screen listing the sound each feature plays with. Each row names the sound,
 * whether it vibrates and which channel it plays on, and opens the screen that setting
 * belongs to; nothing is moved from its own screen.
 */
public class SoundFeatureSoundsFragment extends TalkBackPreferenceFragment {

  private static final String CLOCK_SOUND_KEY = "pref_notification_clock_sound";
  private static final String CLOCK_VIBRATION_KEY = "pref_notification_clock_vibration";
  private static final String STOPWATCH_SOUND_KEY = "pref_stopwatch_sound";
  private static final String STOPWATCH_VIBRATION_KEY = "pref_stopwatch_vibration";
  private static final String COUNTDOWN_SOUND_KEY = "pref_countdown_alarm_sound";
  private static final String COUNTDOWN_VIBRATION_KEY = "pref_countdown_alarm_vibration";
  private static final String NOTIFICATION_SOUND_BEFORE_KEY = "pref_notification_sound_before";

  private SharedPreferences prefs;

  public SoundFeatureSoundsFragment() {
    super(R.xml.sound_feature_sounds_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_sound_feature_sounds);
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
    setSummary(
        R.string.pref_sound_feature_clock_key,
        timerSummary(
            context,
            TimerSounds.Slot.CLOCK,
            CLOCK_SOUND_KEY,
            true,
            CLOCK_VIBRATION_KEY,
            false,
            prefs.getString(TimerSpeechSettings.KEY_STREAM, "accessibility")));
    setSummary(
        R.string.pref_sound_feature_stopwatch_key,
        timerSummary(
            context,
            TimerSounds.Slot.STOPWATCH,
            STOPWATCH_SOUND_KEY,
            true,
            STOPWATCH_VIBRATION_KEY,
            true,
            TimerSpeechSettings.INSTANCE.stopwatchStreamValue(prefs)));
    setSummary(
        R.string.pref_sound_feature_alarm_key,
        timerSummary(
            context,
            TimerSounds.Slot.COUNTDOWN,
            COUNTDOWN_SOUND_KEY,
            true,
            COUNTDOWN_VIBRATION_KEY,
            false,
            TimerSpeechSettings.INSTANCE.countdownStreamValue(prefs)));
    setSummary(
        R.string.pref_sound_feature_notification_key,
        getString(
            prefs.getBoolean(NOTIFICATION_SOUND_BEFORE_KEY, false)
                ? R.string.sound_feature_on
                : R.string.sound_feature_off));
    String bilingualStream = TranslationSettings.INSTANCE.bilingualStream(prefs);
    setSummary(
        R.string.pref_sound_feature_bilingual_key,
        bilingualStream.isEmpty()
            ? getString(R.string.translation_bilingual_stream_default)
            : streamName(bilingualStream));
  }

  /** The timer's sound, vibration and channel, as its row shows them. */
  private String timerSummary(
      Context context,
      TimerSounds.Slot slot,
      String soundKey,
      boolean soundDefault,
      String vibrationKey,
      boolean vibrationDefault,
      String streamValue) {
    String sound;
    if (!prefs.getBoolean(soundKey, soundDefault)) {
      sound = getString(R.string.sound_feature_no_sound);
    } else {
      String label = TimerSounds.INSTANCE.label(context, prefs, slot);
      sound = (label == null) ? getString(R.string.theme_sound_summary_default) : label;
    }
    String vibration =
        getString(
            prefs.getBoolean(vibrationKey, vibrationDefault)
                ? R.string.sound_feature_vibration_on
                : R.string.sound_feature_vibration_off);
    return sound + " • " + vibration + " • " + streamName(streamValue);
  }

  /** The channel's name, or the accessibility channel for anything unknown. */
  private String streamName(String value) {
    if (FailoverTextToSpeech.STREAM_VALUE_MEDIA.equals(value)) {
      return getString(R.string.timer_stream_media);
    } else if (FailoverTextToSpeech.STREAM_VALUE_RING.equals(value)) {
      return getString(R.string.timer_stream_ring);
    }
    return getString(R.string.timer_stream_accessibility);
  }

  private void setSummary(@StringRes int keyResId, CharSequence summary) {
    Preference preference = findPreferenceByResId(keyResId);
    if (preference != null) {
      preference.setSummary(summary);
    }
  }
}
