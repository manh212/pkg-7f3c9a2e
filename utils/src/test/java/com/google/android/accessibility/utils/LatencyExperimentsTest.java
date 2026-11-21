package com.google.android.accessibility.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LatencyExperimentsTest {
  @After
  public void reset() {
    for (LatencyExperiments.Feature feature : LatencyExperiments.Feature.values()) {
      LatencyExperiments.setEnabled(feature, false);
    }
    LatencyExperiments.setInputListener(null);
    LatencyExperiments.onTouch(false);
    LatencyExperiments.resetTouchControllerContact();
    LatencyExperiments.setPreparationAllowed(false);
  }

  /** The features that load as on when their switch was never saved. */
  private static final Set<LatencyExperiments.Feature> DEFAULT_ON = EnumSet.of(
      LatencyExperiments.Feature.CACHED_TEXT,
      LatencyExperiments.Feature.EARLY_CONTROLS,
      LatencyExperiments.Feature.FAST_SCROLL,
      LatencyExperiments.Feature.FAST_SINGLE_FINGER_SWIPE,
      LatencyExperiments.Feature.FAST_WINDOW_TRANSITIONS,
      LatencyExperiments.Feature.IMMEDIATE_SPEECH,
      LatencyExperiments.Feature.PCM_ADJACENT,
      LatencyExperiments.Feature.RESET_SESSION,
      LatencyExperiments.Feature.FAST_DOUBLE_TAP,
      LatencyExperiments.Feature.EARLY_TOUCH_SPEECH,
      LatencyExperiments.Feature.ASYNC_ORDER,
      LatencyExperiments.Feature.AUDIO_KEEP_WARM,
      LatencyExperiments.Feature.AUDIO_RESAMPLE,
      LatencyExperiments.Feature.INPUT_PRIORITY,
      LatencyExperiments.Feature.LIGHT_LOGGING,
      LatencyExperiments.Feature.LIVE_EDGE_REUSE,
      LatencyExperiments.Feature.NONBLOCKING_PREFETCH,
      LatencyExperiments.Feature.SCOPED_CACHE,
      LatencyExperiments.Feature.TOAST_FILTER);

  @Test
  public void persistedSwitchesAreIndependentAndMissingSwitchesUseTheirDefaults() {
    SharedPreferences preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("latency-test", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    LatencyExperiments.load(preferences);
    for (LatencyExperiments.Feature feature : LatencyExperiments.Feature.values()) {
      assertEquals(DEFAULT_ON.contains(feature), feature.enabled());
    }
    preferences.edit().clear()
        .putBoolean(LatencyExperiments.Feature.TRACE.key, true)
        .putBoolean(LatencyExperiments.Feature.ASYNC_ORDER.key, false).commit();
    LatencyExperiments.load(preferences);
    assertTrue(LatencyExperiments.Feature.TRACE.enabled());
    assertFalse(LatencyExperiments.Feature.ASYNC_ORDER.enabled());
    assertTrue(LatencyExperiments.Feature.CACHED_TEXT.enabled());
    assertFalse(LatencyExperiments.Feature.STALL_CAPTURE.enabled());
    preferences.edit().clear().commit();
    LatencyExperiments.load(preferences);
    assertTrue(LatencyExperiments.Feature.ASYNC_ORDER.enabled());
    assertFalse(LatencyExperiments.Feature.TRACE.enabled());
  }

  @Test
  public void overhaulMigrationRemovesLegacyKeysAndOnlyRunsOnce() {
    SharedPreferences preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("latency-overhaul-test", Context.MODE_PRIVATE);
    preferences.edit().clear()
        .putBoolean("pref_fast_navigator_key", true)
        .putBoolean("pref_reduce_window_delay_key", true)
        .putBoolean("pref_prepare_adjacent_speech", true)
        .putBoolean("pref_prepare_second_ahead_speech", true)
        .putBoolean(LatencyExperiments.Feature.PCM_SECOND.key, true)
        .putBoolean(LatencyExperiments.Feature.FRESH_PREPARED.key, true).commit();

    LatencyExperiments.load(preferences);
    assertFalse(preferences.contains("pref_fast_navigator_key"));
    assertTrue(preferences.getBoolean("pref_reduce_window_delay_key", false));
    assertFalse(preferences.getBoolean("pref_prepare_adjacent_speech", true));
    assertFalse(preferences.getBoolean("pref_prepare_second_ahead_speech", true));
    assertFalse(LatencyExperiments.Feature.PCM_SECOND.enabled());
    assertFalse(LatencyExperiments.Feature.FRESH_PREPARED.enabled());
    assertTrue(preferences.getBoolean("pref_latency_overhaul_v1_migrated", false));
    assertTrue(preferences.getBoolean("pref_latency_overhaul_v2_migrated", false));

    preferences.edit()
        .putBoolean("pref_prepare_adjacent_speech", true)
        .putBoolean(LatencyExperiments.Feature.PCM_SECOND.key, true)
        .putBoolean(LatencyExperiments.Feature.FRESH_PREPARED.key, true).commit();
    LatencyExperiments.load(preferences);
    assertTrue(preferences.getBoolean("pref_prepare_adjacent_speech", false));
    assertTrue(LatencyExperiments.Feature.PCM_SECOND.enabled());
    assertTrue(LatencyExperiments.Feature.FRESH_PREPARED.enabled());
  }

  @Test
  public void backgroundPreparationRequiresItsMasterAndAnIndividualSwitch() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_SECOND, true);
    LatencyExperiments.setPreparationAllowed(false);
    assertFalse(LatencyExperiments.preparePcm());
    LatencyExperiments.setPreparationAllowed(true);
    assertTrue(LatencyExperiments.preparePcm());
    assertFalse(LatencyExperiments.Feature.PCM_ADJACENT.enabled());
  }

  @Test
  public void inputCancelsPreparationImmediatelyAndTouchBlocksNewWork() {
    AtomicInteger cancellations = new AtomicInteger();
    LatencyExperiments.setInputListener(cancellations::incrementAndGet);
    LatencyExperiments.onTouch(true);
    assertEquals(1, cancellations.get());
    assertTrue(LatencyExperiments.inputIsActive(0));
    LatencyExperiments.onTouch(false);
    assertEquals(2, cancellations.get());
    assertFalse(LatencyExperiments.inputIsActive(0));
  }

  @Test
  public void physicalReleaseLetsPreparationResumeWithinAFrameworkTouchInteraction() {
    LatencyExperiments.onTouch(true);
    LatencyExperiments.onTouchControllerContact(false);
    assertTrue(LatencyExperiments.inputIsActive(0));
    assertFalse(LatencyExperiments.preparationInputIsActive(0));
    LatencyExperiments.onTouchControllerContact(true);
    assertTrue(LatencyExperiments.preparationInputIsActive(0));
  }
}
