package com.google.android.accessibility.utils;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LatencyExperiments {
  public static final String STATIONARY_TOUCH_DELAY_KEY = "pref_latency_stationary_touch_delay_ms";

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
    FRESH_PREPARED("fresh_prepared", true),
    PCM_ADJACENT("pcm_adjacent", true),
    PCM_SECOND("pcm_second", true),
    IMMEDIATE_SPEECH("immediate_speech", true),
    FAST_DOUBLE_TAP("simulated_double_tap", true),
    FAST_SINGLE_FINGER_SWIPE("fast_single_finger_swipe", true),
    EARLY_TOUCH_SPEECH("touch_speech_with_rotation", true),
    FAST_WINDOW_TRANSITIONS("fast_window_transitions", true),
    FAST_SCROLL("fast_scroll", true),
    LIVE_EDGE_REUSE("live_edge_reuse", true),
    AUDIO_RESAMPLE("audio_resample", true),
    AUDIO_KEEP_WARM("audio_keep_warm", true);

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
    for (Feature feature : Feature.values()) {
      feature.enabled = preferences.getBoolean(feature.key, feature.defaultEnabled);
    }
    loadStationaryTouchDelay(preferences);
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
