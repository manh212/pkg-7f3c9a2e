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
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import com.google.android.accessibility.material.preference.AccessibilitySeekBarPreference;
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference;
import com.google.android.accessibility.material.preference.AccessibilitySuiteSwitchPreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.SpeechCompressor;
import com.google.android.accessibility.utils.output.SpeechCompressor.Preset;
import com.google.android.accessibility.utils.output.SpeechCompressor.Settings;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The multiband compressor's settings, for one voice profile or for the default voice. */
public class SpeechCompressorFragment extends TalkbackBaseFragment {

  static final String ARG_PROFILE_ID = "profile_id";

  private static final String CUSTOM = "custom";

  private SharedPreferences prefs;
  private String id = "";
  private @Nullable ListPreference presetPref;
  private @Nullable AccessibilitySuiteSwitchPreference onPref;
  private final List<ValuePreference> thresholds = new ArrayList<>();
  private final List<ValuePreference> ratios = new ArrayList<>();
  private @Nullable ValuePreference attackPref;
  private @Nullable ValuePreference releasePref;
  private @Nullable ValuePreference makeupPref;
  private boolean saving;

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_speech_compressor);
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

    onPref = new AccessibilitySuiteSwitchPreference(context);
    onPref.setKey(SpeechCompressor.onKey(id));
    onPref.setDefaultValue(false);
    onPref.setTitle(R.string.title_pref_speech_compressor_on);
    onPref.setIconSpaceReserved(false);
    screen.addPreference(onPref);

    presetPref = new AccessibilitySuiteListPreference(context);
    presetPref.setKey("speech_compressor_preset");
    presetPref.setPersistent(false);
    presetPref.setTitle(R.string.title_pref_speech_equalizer_preset);
    presetPref.setDialogTitle(R.string.title_pref_speech_equalizer_preset);
    presetPref.setIconSpaceReserved(false);
    presetPref.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          Preset preset = SpeechCompressor.presetFor((String) newValue);
          if (preset != null) {
            SpeechCompressor.save(prefs, id, preset.settings());
            update();
          }
          return false;
        });
    screen.addPreference(presetPref);

    for (int i = 0; i < SpeechCompressor.BANDS; i++) {
      int band = i;
      PreferenceCategory category = new PreferenceCategory(context);
      category.setTitle(bandName(context, band));
      category.setIconSpaceReserved(false);
      screen.addPreference(category);

      ValuePreference threshold = new ValuePreference(context);
      threshold.setKey("speech_compressor_threshold_" + band);
      threshold.setPersistent(false);
      threshold.setTitle(R.string.speech_compressor_threshold);
      threshold.setMin(SpeechCompressor.MIN_THRESHOLD_DB);
      threshold.setMax(0);
      threshold.setShowSeekBarValue(true);
      threshold.setIconSpaceReserved(false);
      threshold.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            Settings settings = SpeechCompressor.read(prefs, id);
            settings.thresholds[band] = (Integer) newValue;
            save(settings);
            threshold.describe(dbText(context, (Integer) newValue));
            updatePreset(settings);
            return true;
          });
      category.addPreference(threshold);
      thresholds.add(threshold);

      ValuePreference ratio = new ValuePreference(context);
      ratio.setKey("speech_compressor_ratio_" + band);
      ratio.setPersistent(false);
      ratio.setTitle(R.string.speech_compressor_ratio);
      ratio.setMin(SpeechCompressor.MIN_RATIO_X10);
      ratio.setMax(SpeechCompressor.MAX_RATIO_X10);
      ratio.setShowSeekBarValue(true);
      ratio.setIconSpaceReserved(false);
      ratio.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            Settings settings = SpeechCompressor.read(prefs, id);
            settings.ratios[band] = (Integer) newValue;
            save(settings);
            ratio.describe(ratioText(context, (Integer) newValue));
            updatePreset(settings);
            return true;
          });
      category.addPreference(ratio);
      ratios.add(ratio);
    }

    PreferenceCategory common = new PreferenceCategory(context);
    common.setTitle(R.string.speech_compressor_common);
    common.setIconSpaceReserved(false);
    screen.addPreference(common);

    attackPref = new ValuePreference(context);
    attackPref.setKey("speech_compressor_attack");
    attackPref.setPersistent(false);
    attackPref.setTitle(R.string.speech_compressor_attack);
    attackPref.setMin(SpeechCompressor.MIN_ATTACK_MS);
    attackPref.setMax(SpeechCompressor.MAX_ATTACK_MS);
    attackPref.setShowSeekBarValue(true);
    attackPref.setIconSpaceReserved(false);
    attackPref.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          Settings settings = SpeechCompressor.read(prefs, id);
          save(new Settings(settings.thresholds, settings.ratios, (Integer) newValue,
              settings.releaseMs, settings.makeupDb));
          attackPref.describe(msText(context, (Integer) newValue));
          updatePreset(SpeechCompressor.read(prefs, id));
          return true;
        });
    common.addPreference(attackPref);

    releasePref = new ValuePreference(context);
    releasePref.setKey("speech_compressor_release");
    releasePref.setPersistent(false);
    releasePref.setTitle(R.string.speech_compressor_release);
    releasePref.setMin(SpeechCompressor.MIN_RELEASE_MS);
    releasePref.setMax(SpeechCompressor.MAX_RELEASE_MS);
    releasePref.setShowSeekBarValue(true);
    releasePref.setIconSpaceReserved(false);
    releasePref.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          Settings settings = SpeechCompressor.read(prefs, id);
          save(new Settings(settings.thresholds, settings.ratios, settings.attackMs,
              (Integer) newValue, settings.makeupDb));
          releasePref.describe(msText(context, (Integer) newValue));
          updatePreset(SpeechCompressor.read(prefs, id));
          return true;
        });
    common.addPreference(releasePref);

    makeupPref = new ValuePreference(context);
    makeupPref.setKey("speech_compressor_makeup");
    makeupPref.setPersistent(false);
    makeupPref.setTitle(R.string.speech_compressor_makeup);
    makeupPref.setMin(0);
    makeupPref.setMax(SpeechCompressor.MAX_MAKEUP_DB);
    makeupPref.setShowSeekBarValue(true);
    makeupPref.setIconSpaceReserved(false);
    makeupPref.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          Settings settings = SpeechCompressor.read(prefs, id);
          save(new Settings(settings.thresholds, settings.ratios, settings.attackMs,
              settings.releaseMs, (Integer) newValue));
          makeupPref.describe(dbText(context, (Integer) newValue));
          updatePreset(SpeechCompressor.read(prefs, id));
          return true;
        });
    common.addPreference(makeupPref);
  }

  private void save(Settings settings) {
    saving = true;
    try {
      SpeechCompressor.save(prefs, id, settings);
    } finally {
      saving = false;
    }
  }

  private final OnSharedPreferenceChangeListener prefsListener =
      (sharedPrefs, key) -> {
        if (!saving
            && (TextUtils.equals(key, SpeechCompressor.key(id))
                || TextUtils.equals(key, SpeechCompressor.onKey(id)))) {
          update();
        }
      };

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    update();
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
  }

  private void update() {
    Context context = requireContext();
    if (onPref != null) {
      onPref.setChecked(SpeechCompressor.isOn(prefs, id));
    }
    Settings settings = SpeechCompressor.read(prefs, id);
    for (int i = 0; i < thresholds.size(); i++) {
      thresholds.get(i).setValue(settings.thresholds[i]);
      thresholds.get(i).describe(dbText(context, settings.thresholds[i]));
      ratios.get(i).setValue(settings.ratios[i]);
      ratios.get(i).describe(ratioText(context, settings.ratios[i]));
    }
    if (attackPref != null) {
      attackPref.setValue(settings.attackMs);
      attackPref.describe(msText(context, settings.attackMs));
    }
    if (releasePref != null) {
      releasePref.setValue(settings.releaseMs);
      releasePref.describe(msText(context, settings.releaseMs));
    }
    if (makeupPref != null) {
      makeupPref.setValue(settings.makeupDb);
      makeupPref.describe(dbText(context, settings.makeupDb));
    }
    updatePreset(settings);
  }

  private void updatePreset(Settings settings) {
    if (presetPref == null) {
      return;
    }
    Context context = requireContext();
    Preset current = SpeechCompressor.presetOf(settings);
    List<CharSequence> entries = new ArrayList<>();
    List<CharSequence> values = new ArrayList<>();
    for (Preset preset : Preset.values()) {
      entries.add(presetName(context, preset));
      values.add(preset.getValue());
    }
    if (current == null) {
      entries.add(context.getString(R.string.speech_equalizer_custom));
      values.add(CUSTOM);
    }
    presetPref.setEntries(entries.toArray(new CharSequence[0]));
    presetPref.setEntryValues(values.toArray(new CharSequence[0]));
    presetPref.setValue(current == null ? CUSTOM : current.getValue());
    presetPref.setSummary("%s");
  }

  static String summary(Context context, SharedPreferences prefs, String profileId) {
    if (!prefs.getBoolean(
        FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
        FailoverTextToSpeech.lowLatencyAudioDefault(context))) {
      return context.getString(R.string.summary_speech_equalizer_needs_low_latency);
    }
    if (!SpeechCompressor.isOn(prefs, profileId)) {
      return context.getString(R.string.speech_equalizer_off);
    }
    Preset preset = SpeechCompressor.presetOf(SpeechCompressor.read(prefs, profileId));
    return (preset == null)
        ? context.getString(R.string.speech_equalizer_custom)
        : presetName(context, preset);
  }

  static String presetName(Context context, Preset preset) {
    int resId =
        switch (preset) {
          case NONE -> R.string.speech_compressor_none;
          case GENTLE -> R.string.speech_compressor_gentle;
          case CLARITY -> R.string.speech_compressor_clarity;
          case EVEN -> R.string.speech_compressor_even;
        };
    return context.getString(resId);
  }

  private static String bandName(Context context, int band) {
    if (band == 0) {
      return context.getString(
          R.string.speech_compressor_band_below, frequencyName(context, SpeechCompressor.CROSSOVERS[0]));
    }
    if (band == SpeechCompressor.BANDS - 1) {
      return context.getString(
          R.string.speech_compressor_band_above,
          frequencyName(context, SpeechCompressor.CROSSOVERS[SpeechCompressor.BANDS - 2]));
    }
    return context.getString(
        R.string.speech_compressor_band_range,
        frequencyName(context, SpeechCompressor.CROSSOVERS[band - 1]),
        frequencyName(context, SpeechCompressor.CROSSOVERS[band]));
  }

  private static String frequencyName(Context context, int hz) {
    NumberFormat format = NumberFormat.getInstance();
    format.setMaximumFractionDigits(1);
    return (hz >= 1000)
        ? context.getString(R.string.speech_equalizer_band_khz, format.format(hz / 1000.0))
        : context.getString(R.string.speech_equalizer_band_hz, format.format(hz));
  }

  private static String dbText(Context context, int db) {
    String number = (db > 0 ? "+" : "") + NumberFormat.getIntegerInstance().format(db);
    return context.getString(R.string.speech_equalizer_gain_db, number);
  }

  private static String ratioText(Context context, int ratioX10) {
    NumberFormat format = NumberFormat.getInstance();
    format.setMaximumFractionDigits(1);
    return context.getString(R.string.speech_compressor_ratio_value, format.format(ratioX10 / 10.0));
  }

  private static String msText(Context context, int ms) {
    return context.getString(
        R.string.speech_compressor_ms, NumberFormat.getIntegerInstance().format(ms));
  }

  private static final class ValuePreference extends AccessibilitySeekBarPreference {
    private @Nullable SeekBar seekBar;
    private @Nullable String description;

    ValuePreference(@NonNull Context context) {
      super(context);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
      super.onBindViewHolder(holder);
      seekBar = (SeekBar) holder.findViewById(androidx.preference.R.id.seekbar);
      describe(description);
    }

    void describe(@Nullable String text) {
      description = text;
      if (seekBar != null && text != null) {
        ViewCompat.setStateDescription(seekBar, text);
      }
    }
  }

  static void setUp(Preference preference, String profileId) {
    preference.setFragment(SpeechCompressorFragment.class.getName());
    preference.getExtras().putString(ARG_PROFILE_ID, profileId);
  }
}
