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
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter;
import com.google.android.accessibility.talkback.audio.SpeakerDevices;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The sound sub-screen holding the audio output, the phone speaker and low-latency audio. */
public class SoundOutputFragment extends TalkBackPreferenceFragment {

  private SharedPreferences prefs;

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (TextUtils.equals(key, getString(R.string.pref_audio_output_device_key))) {
          updateAudioOutputPreference();
        } else if (TextUtils.equals(key, getString(R.string.pref_speaker_devices_key))) {
          updateSpeakerDevicesPreference();
        }
      };

  private final AudioDeviceCallback audioDeviceCallback =
      new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
          updateAudioOutputPreference();
          updateSpeakerDevicesPreference();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
          updateAudioOutputPreference();
          updateSpeakerDevicesPreference();
        }
      };

  public SoundOutputFragment() {
    super(R.xml.sound_output_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_sound_output);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
  }

  @Override
  public void onResume() {
    super.onResume();
    FragmentActivity activity = getActivity();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updateAudioOutputPreference();
    updateSpeakerDevicesPreference();
    Preference audioPref =
        PreferenceSettingsUtils.findPreference(
            activity, getString(R.string.pref_audio_output_device_key));
    if (audioPref instanceof ListPreference) {
      ListPreference listPref = (ListPreference) audioPref;
      listPref.setOnPreferenceClickListener(
          p -> {
            AudioDeviceRouter.updatePreference(listPref, activity);
            return false;
          });
      listPref.setOnPreferenceChangeListener(
          (p, newValue) -> {
            listPref.setValue((String) newValue);
            AudioDeviceRouter.updatePreference(listPref, activity);
            AudioDeviceRouter router = AudioDeviceRouter.getInstance();
            if (router != null) {
              router.setPreferredDevice((String) newValue);
            }
            return true;
          });
    }

    AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager != null) {
      audioManager.registerAudioDeviceCallback(
          audioDeviceCallback, new Handler(Looper.getMainLooper()));
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);

    FragmentActivity activity = getActivity();
    if (activity != null) {
      AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
      if (audioManager != null) {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
      }
    }
  }

  private void updateAudioOutputPreference() {
    FragmentActivity activity = getActivity();
    if (activity == null) {
      return;
    }
    Preference preference =
        PreferenceSettingsUtils.findPreference(
            activity, getString(R.string.pref_audio_output_device_key));
    if (preference instanceof ListPreference) {
      ListPreference listPref = (ListPreference) preference;
      AudioDeviceRouter.updatePreference(listPref, activity);
    }
  }

  /** Fills the phone speaker devices with the outputs connected now and those seen before. */
  private void updateSpeakerDevicesPreference() {
    FragmentActivity activity = getActivity();
    if (activity == null) {
      return;
    }
    Preference preference = findPreference(getString(R.string.pref_speaker_devices_key));
    if (!(preference instanceof MultiSelectListPreference)) {
      return;
    }
    MultiSelectListPreference devicesPref = (MultiSelectListPreference) preference;
    List<String> connected = new ArrayList<>();
    AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager != null) {
      for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        if (AudioDeviceRouter.Companion.isExternalDevice(device)) {
          connected.add(AudioDeviceRouter.Companion.getDeviceDisplayName(device, activity));
        }
      }
    }
    Set<String> chosen = SpeakerDevices.chosen(prefs, activity);
    List<String> choices =
        SpeakerDevices.choices(connected, SpeakerDevices.seen(prefs, activity), chosen);
    CharSequence[] names = choices.toArray(new CharSequence[0]);
    devicesPref.setEntries(names);
    devicesPref.setEntryValues(names);
    devicesPref.setEnabled(!choices.isEmpty());
    if (choices.isEmpty()) {
      devicesPref.setSummary(R.string.summary_pref_speaker_devices_no_devices);
    } else if (chosen.isEmpty()) {
      devicesPref.setSummary(R.string.summary_pref_speaker_devices_none);
    } else {
      devicesPref.setSummary(TextUtils.join(", ", new TreeSet<>(chosen)));
    }
  }
}
