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

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The navigation PCM cache is dropped only when a changed preference can change the engine,
 * voice, locale, speech rate or speech pitch ("chỉ xóa cache khi đổi giọng đọc"). Unrelated
 * preference writes, such as the stopwatch or selector state, must leave prepared speech alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FailoverTextToSpeechCacheWipeTest {

  private Context context;
  private SharedPreferences prefs;
  private FailoverTextToSpeech engine;
  private NavigationPcmCache cache;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    LatencyExperiments.setPreparationAllowed(true);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_ADJACENT, true);
  }

  @After
  public void tearDown() {
    if (cache != null) cache.shutdown();
    prefs.edit().clear().commit();
    VoiceProfiles.setActive(null);
    for (LatencyExperiments.Feature feature : LatencyExperiments.Feature.values()) {
      LatencyExperiments.setEnabled(feature, false);
    }
    LatencyExperiments.setInputListener(null);
    LatencyExperiments.onTouch(false);
    LatencyExperiments.resetTouchControllerContact();
    LatencyExperiments.setPreparationAllowed(false);
  }

  /** Builds an engine with a navigation cache injected, so a wipe can be observed. */
  private FailoverTextToSpeech engineWithCache() {
    engine = new FailoverTextToSpeech(context, /* enableSpeechCache= */ false);
    cache = new NavigationPcmCache(context);
    ReflectionHelpers.setField(engine, "navigationPcmCache", cache);
    return engine;
  }

  /** The cache's wipe count: {@link NavigationPcmCache#invalidate} raises it by one. */
  private long wipeCount() {
    AtomicLong configuration = ReflectionHelpers.getField(cache, "configuration");
    return configuration.get();
  }

  private boolean cacheClosed() {
    return ReflectionHelpers.getField(cache, "closed");
  }

  private void readVoiceProfile() {
    ReflectionHelpers.callInstanceMethod(
        engine, "readVoiceProfile", ClassParameter.from(SharedPreferences.class, prefs));
  }

  /** A profile in use, speaking with {@code engineName}; returns its ID. */
  private String createActiveProfile(String engineName) {
    String id =
        VoiceProfiles.create(prefs, "Reading", engineName, "80", "1.5", "0.9", true);
    prefs.edit().putString(VoiceProfiles.PREF_ACTIVE, id).apply();
    return id;
  }

  @Test
  public void engineKeyStillWipes() {
    assertTrue(FailoverTextToSpeech.isVoiceRelevantKey(FailoverTextToSpeech.PREF_TTS_ENGINE_KEY));
  }

  @Test
  public void selectedEngineVoiceKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            VoiceSwitching.selectedKey("com.example.engine")));
  }

  @Test
  public void activeProfileKeyStillWipes() {
    assertTrue(FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.PREF_ACTIVE));
  }

  @Test
  public void profileEngineKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            VoiceProfiles.key("3", VoiceProfiles.ENGINE)));
  }

  @Test
  public void profileVoiceKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.VOICE)));
  }

  @Test
  public void profileLanguageKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            VoiceProfiles.key("3", VoiceProfiles.LANGUAGE)));
  }

  @Test
  public void switchLanguagesKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            FailoverTextToSpeech.PREF_SWITCH_LANGUAGES_KEY));
  }

  @Test
  public void switchDialectsKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            FailoverTextToSpeech.PREF_SWITCH_DIALECTS_KEY));
  }

  @Test
  public void preferredLocaleKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            FailoverTextToSpeech.PREF_TALKBACK_PREFER_LOCALE_KEY));
  }

  @Test
  public void speechRateKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(FailoverTextToSpeech.PREF_SPEECH_RATE_KEY));
  }

  @Test
  public void speechRateSeekbarKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            FailoverTextToSpeech.PREF_SPEECH_RATE_SEEKBAR_KEY));
  }

  @Test
  public void profileRateKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.RATE)));
  }

  @Test
  public void speechPitchKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(FailoverTextToSpeech.PREF_SPEECH_PITCH_KEY));
  }

  @Test
  public void speechPitchSeekbarKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(
            FailoverTextToSpeech.PREF_SPEECH_PITCH_SEEKBAR_KEY));
  }

  @Test
  public void profilePitchKeyStillWipes() {
    assertTrue(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.PITCH)));
  }

  @Test
  public void profileListKeyStillWipes() {
    assertTrue(FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.PREF_IDS));
  }

  @Test
  public void nullKeyStillWipes() {
    assertTrue(FailoverTextToSpeech.isVoiceRelevantKey(null));
  }

  @Test
  public void stopwatchKeyDoesNotWipe() {
    // Stopwatch.KEY_SOUND in TalkBackService's timer package.
    assertFalse(FailoverTextToSpeech.isVoiceRelevantKey("pref_stopwatch_sound"));
  }

  @Test
  public void processingLevelKeyDoesNotWipe() {
    assertFalse(FailoverTextToSpeech.isVoiceRelevantKey("pref_event_processing_level"));
  }

  @Test
  public void orderCacheKeyDoesNotWipe() {
    assertFalse(FailoverTextToSpeech.isVoiceRelevantKey("pref_order_cache"));
  }

  @Test
  public void prepareAdjacentKeyDoesNotWipe() {
    assertFalse(FailoverTextToSpeech.isVoiceRelevantKey("pref_prepare_adjacent_speech"));
  }

  @Test
  public void voiceOrderKeyDoesNotWipe() {
    // The order voices cycle in changes no voice; only the selected voice does.
    assertFalse(
        FailoverTextToSpeech.isVoiceRelevantKey(
            "pref_engine_voice_order_com.example.engine"));
  }

  @Test
  public void profileNameKeyDoesNotWipe() {
    assertFalse(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.NAME)));
  }

  @Test
  public void profileVolumeKeyDoesNotWipe() {
    assertFalse(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.VOLUME)));
  }

  @Test
  public void profilePhrasesKeyDoesNotWipe() {
    assertFalse(
        FailoverTextToSpeech.isVoiceRelevantKey(VoiceProfiles.key("3", VoiceProfiles.PHRASES)));
  }

  @Test
  public void profileEqualizerKeyDoesNotWipe() {
    assertFalse(
        FailoverTextToSpeech.isVoiceRelevantKey(
            VoiceProfiles.key("3", VoiceProfiles.EQUALIZER)));
  }

  @Test
  public void unrelatedKeyKeepsPreparedSpeech() {
    engineWithCache();
    long wipes = wipeCount();
    engine.refreshNavigationCache("pref_stopwatch_sound");
    assertEquals(wipes, wipeCount());
  }

  @Test
  public void engineKeyDropsPreparedSpeech() {
    engineWithCache();
    long wipes = wipeCount();
    engine.refreshNavigationCache(FailoverTextToSpeech.PREF_TTS_ENGINE_KEY);
    assertEquals(wipes + 1, wipeCount());
  }

  @Test
  public void profileRateKeyDropsPreparedSpeech() {
    engineWithCache();
    long wipes = wipeCount();
    engine.refreshNavigationCache(VoiceProfiles.key("3", VoiceProfiles.RATE));
    assertEquals(wipes + 1, wipeCount());
  }

  @Test
  public void reloadAllDropsPreparedSpeech() {
    engineWithCache();
    long wipes = wipeCount();
    engine.refreshNavigationCache();
    assertEquals(wipes + 1, wipeCount());
  }

  @Test
  public void unrelatedKeyStillShutsDownCacheWhenPreparationDisabled() {
    engineWithCache();
    LatencyExperiments.setPreparationAllowed(false);
    engine.refreshNavigationCache("pref_stopwatch_sound");
    assertTrue(cacheClosed());
  }

  @Test
  public void sameVoiceProfileChangeKeepsPreparedSpeech() {
    createActiveProfile("com.example.eloquence");
    engineWithCache();
    long wipes = wipeCount();
    readVoiceProfile();
    assertEquals(wipes, wipeCount());
  }

  @Test
  public void changedVoiceProfileDropsPreparedSpeech() {
    createActiveProfile("com.example.eloquence");
    engineWithCache();
    VoiceProfiles.setActive(null);
    long wipes = wipeCount();
    readVoiceProfile();
    assertEquals(wipes + 1, wipeCount());
  }
}
