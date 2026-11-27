package com.google.android.accessibility.utils;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LatencyExperiments {
  public static final String STATIONARY_TOUCH_DELAY_KEY = "pref_latency_stationary_touch_delay_ms";
  private static final String OVERHAUL_MIGRATION_KEY = "pref_latency_overhaul_v1_migrated";
  private static final String OVERHAUL_MIGRATION_V2_KEY = "pref_latency_overhaul_v2_migrated";
  private static final String OVERHAUL_MIGRATION_V3_KEY = "pref_latency_overhaul_migrated_v3";
  private static final String[] LEGACY_LATENCY_PREF_KEYS = {
    "pref_fast_navigator_key",
    "pref_fast_nav_cheap_pivot_key",
    "pref_fast_nav_fire_first_key",
    "pref_fast_nav_skip_target_refresh_key",
    "pref_background_order_build_key",
    "pref_coalesce_listener_builds_key",
    "pref_complete_swipe_early_key",
    "pref_defer_builds_during_burst_key",
    "pref_event_driven_scroll_landing_key",
    "pref_focus_order_build_key",
    "pref_focus_priority_lane_key",
    "pref_keep_preparation_on_focus_change",
    "pref_keep_prepared_across_unrelated_key",
    "pref_keep_unused_prepared_key",
    "pref_lean_order_builder_key",
    "pref_prepare_ahead_key",
    "pref_prepare_on_settle_key",
    "pref_prepare_wider_key",
    "pref_presynthesize_prepared_key",
    "pref_reduce_swipe_start_delay_key",
    "pref_serve_stale_order_key",
    "pref_settle_order_build_key",
    "pref_small_audio_buffer_key",
    "pref_speculative_speech_key",
    "pref_speech_pipeline_chain_key",
    "pref_speech_pipeline_key",
    "pref_speech_wait_cap_key",
    "pref_split_speech_key",
    "pref_touch_order_build_key",
    "pref_trust_untouched_nodes_key",
    "pref_yielding_prepare_key",
    "pref_latency_touch_explore",
    "pref_latency_window_immediate",
    "pref_latency_window_settle"
  };

  public enum Feature {
    TRACE("trace"),
    RESET_SESSION("reset_session", true),
    STALL_CAPTURE("stall_capture"),
    LIGHT_LOGGING("light_logging", true),
    NONBLOCKING_PREFETCH("nonblocking_prefetch", true),
    ASYNC_ORDER("async_order", true),
    SCOPED_CACHE("scoped_cache", true),
    INPUT_PRIORITY("input_priority", true),
    TOAST_FILTER("toast_filter", true),
    EARLY_CONTROLS("early_controls", true),
    CACHED_TEXT("cached_text", true),
    FRESH_PREPARED("fresh_prepared"),
    PCM_ADJACENT("pcm_adjacent", true),
    PCM_SECOND("pcm_second"),
    IMMEDIATE_SPEECH("immediate_speech", true),
    FAST_DOUBLE_TAP("simulated_double_tap", true),
    FAST_SINGLE_FINGER_SWIPE("fast_single_finger_swipe", true),
    EARLY_TOUCH_SPEECH("touch_speech_with_rotation", true),
    FAST_WINDOW_TRANSITIONS("fast_window_transitions", true),
    LIVE_EDGE_REUSE("live_edge_reuse", true),
    AUDIO_RESAMPLE("audio_resample", true),
    AUDIO_KEEP_WARM("audio_keep_warm", true),
    WEB_LOCAL_NAVIGATION("web_local_navigation");

    public final String key;
    public final boolean defaultEnabled;
    private volatile boolean enabled;

    Feature(String suffix) {
      this(suffix, false);
    }

    Feature(String suffix, boolean defaultEnabled) {
      key = "pref_latency_" + suffix;
      this.defaultEnabled = defaultEnabled;
    }

    public boolean enabled() {
      return enabled;
    }
  }

  private static volatile long lastInput;
  private static volatile boolean touching;
  private static volatile boolean touchControllerReleased;
  private static volatile boolean preparationAllowed;
  private static volatile Runnable inputListener;
  private static volatile int stationaryTouchDelayMs;

  private LatencyExperiments() {}

  public static void load(SharedPreferences preferences) {
    migrateLegacyPreferences(preferences);
    for (Feature feature : Feature.values()) {
      feature.enabled = preferences.getBoolean(feature.key, feature.defaultEnabled);
    }
    loadStationaryTouchDelay(preferences);
  }

  private static void migrateLegacyPreferences(SharedPreferences preferences) {
    boolean v1Done = preferences.getBoolean(OVERHAUL_MIGRATION_KEY, false);
    boolean v2Done = preferences.getBoolean(OVERHAUL_MIGRATION_V2_KEY, false);
    boolean v3Done = preferences.getBoolean(OVERHAUL_MIGRATION_V3_KEY, false);
    if (v1Done && v2Done && v3Done) {
      return;
    }
    SharedPreferences.Editor editor = preferences.edit();
    if (!v1Done) {
      for (String legacyKey : LEGACY_LATENCY_PREF_KEYS) {
        editor.remove(legacyKey);
      }
      editor.putBoolean("pref_prepare_second_ahead_speech", false);
      editor.putBoolean(Feature.PCM_SECOND.key, false);
      editor.putBoolean(Feature.FRESH_PREPARED.key, false);
      editor.putBoolean(OVERHAUL_MIGRATION_KEY, true);
    }
    if (!v2Done) {
      editor.putBoolean("pref_prepare_adjacent_speech", false);
      editor.putBoolean(OVERHAUL_MIGRATION_V2_KEY, true);
    }
    if (!v3Done) {
      editor.remove("pref_latency_fast_scroll");
      editor.putBoolean("pref_reduce_window_delay_key", true);
      editor.putBoolean(OVERHAUL_MIGRATION_V3_KEY, true);
    }
    editor.apply();
  }

  public static void loadStationaryTouchDelay(SharedPreferences preferences) {
    stationaryTouchDelayMs = stationaryTouchDelayMs(preferences);
  }

  public static int defaultStationaryTouchDelayMs() {
    return 100;
  }

  public static int minimumStationaryTouchDelayMs() {
    return 100;
  }

  public static int maximumStationaryTouchDelayMs() {
    return Math.max(defaultStationaryTouchDelayMs(), 500);
  }

  public static int stationaryTouchDelayMs() {
    int delayMs = stationaryTouchDelayMs;
    return delayMs == 0 ? defaultStationaryTouchDelayMs() : delayMs;
  }

  public static int stationaryTouchDelayMs(SharedPreferences preferences) {
    int defaultMs = defaultStationaryTouchDelayMs();
    try {
      int selectedMs = preferences.getInt(STATIONARY_TOUCH_DELAY_KEY, defaultMs);
      return Math.max(minimumStationaryTouchDelayMs(), Math.min(selectedMs, maximumStationaryTouchDelayMs()));
    } catch (ClassCastException error) {
      return defaultMs;
    }
  }

  public static void setEnabled(Feature feature, boolean enabled) {
    feature.enabled = enabled;
  }

  public static void setPreparationAllowed(boolean allowed) {
    preparationAllowed = allowed;
  }

  public static boolean preparationAllowed() {
    return preparationAllowed;
  }

  public static boolean preparePcm() {
    return preparationAllowed && (Feature.PCM_ADJACENT.enabled() || Feature.PCM_SECOND.enabled());
  }

  public static void setInputListener(Runnable listener) {
    inputListener = listener;
  }

  public static void onInput() {
    lastInput = SystemClock.uptimeMillis();
    Runnable listener = inputListener;
    if (listener != null) listener.run();
  }

  public static void onTouch(boolean active) {
    touching = active;
    onInput();
  }

  public static void onTouchControllerContact(boolean active) {
    touchControllerReleased = !active;
    onInput();
  }

  public static void resetTouchControllerContact() {
    touchControllerReleased = false;
  }

  public static boolean preparationTouchActive(boolean touchInteractionActive) {
    return touchInteractionActive && !touchControllerReleased;
  }

  public static boolean preparationInputIsActive(long quietMillis) {
    return preparationTouchActive(touching) || inputQuietDelay(quietMillis) > 0;
  }

  public static boolean inputIsActive(long quietMillis) {
    return touching || inputQuietDelay(quietMillis) > 0;
  }

  public static long inputQuietDelay(long quietMillis) {
    return Math.max(0, quietMillis - (SystemClock.uptimeMillis() - lastInput));
  }

  public static Map<String, Boolean> snapshot() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (Feature feature : Feature.values()) result.put(feature.key, feature.enabled);
    return result;
  }
}
