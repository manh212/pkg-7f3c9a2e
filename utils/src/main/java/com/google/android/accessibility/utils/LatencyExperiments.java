package com.google.android.accessibility.utils;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.ViewConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LatencyExperiments {
  public static final String DOUBLE_TAP_CLICK_DURATION_KEY =
      "pref_latency_double_tap_click_duration_ms";
  public static final String STATIONARY_TOUCH_DELAY_KEY = "pref_latency_stationary_touch_delay_ms";
  public static final int SIMULATED_CLICK_GAP_MS = 40;

  public enum Feature {
    TRACE("trace"),
    RESET_SESSION("reset_session"),
    STALL_CAPTURE("stall_capture"),
    LIGHT_LOGGING("light_logging"),
    NONBLOCKING_PREFETCH("nonblocking_prefetch"),
    ASYNC_ORDER("async_order"),
    SCOPED_CACHE("scoped_cache"),
    INPUT_PRIORITY("input_priority"),
    TOAST_FILTER("toast_filter"),
    EARLY_CONTROLS("early_controls"),
    CACHED_TEXT("cached_text"),
    FRESH_PREPARED("fresh_prepared"),
    PCM_ADJACENT("pcm_adjacent"),
    PCM_SECOND("pcm_second"),
    IMMEDIATE_SPEECH("immediate_speech"),
    FAST_DOUBLE_TAP("simulated_double_tap"),
    FAST_SINGLE_FINGER_SWIPE("fast_single_finger_swipe"),
    TOUCH_SPEECH_WITH_ROTATION("touch_speech_with_rotation"),
    FAST_WINDOW_TRANSITIONS("fast_window_transitions"),
    FAST_SCROLL("fast_scroll"),
    LIVE_EDGE_REUSE("live_edge_reuse"),
    AUDIO_RESAMPLE("audio_resample"),
    AUDIO_KEEP_WARM("audio_keep_warm");

    public final String key;
    private volatile boolean enabled;

    Feature(String suffix) {
      key = "pref_latency_" + suffix;
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
  private static volatile int doubleTapClickDurationMs;
  private static volatile int stationaryTouchDelayMs;

  private LatencyExperiments() {}

  public static void load(SharedPreferences preferences) {
    for (Feature feature : Feature.values()) {
      feature.enabled = preferences.getBoolean(feature.key, false);
    }
    loadTouchTiming(preferences);
  }

  public static void loadTouchTiming(SharedPreferences preferences) {
    doubleTapClickDurationMs = doubleTapClickDurationMs(preferences);
    stationaryTouchDelayMs = stationaryTouchDelayMs(preferences);
  }

  private static int readTouchTiming(
      SharedPreferences preferences, String key, int defaultMs, int minMs, int maxMs) {
    try {
      return Math.max(minMs, Math.min(preferences.getInt(key, defaultMs), maxMs));
    } catch (ClassCastException error) {
      return defaultMs;
    }
  }

  public static int defaultDoubleTapClickDurationMs() {
    return 2 * ViewConfiguration.getTapTimeout() + SIMULATED_CLICK_GAP_MS;
  }

  public static int minimumDoubleTapClickDurationMs() {
    return 2 * Math.min(ViewConfiguration.getTapTimeout(), 40) + SIMULATED_CLICK_GAP_MS;
  }

  public static int maximumDoubleTapClickDurationMs() {
    return Math.max(defaultDoubleTapClickDurationMs(), 500);
  }

  public static int doubleTapClickDurationMs() {
    int durationMs = doubleTapClickDurationMs;
    return durationMs == 0 ? defaultDoubleTapClickDurationMs() : durationMs;
  }

  public static int doubleTapClickDurationMs(SharedPreferences preferences) {
    return readTouchTiming(preferences, DOUBLE_TAP_CLICK_DURATION_KEY, defaultDoubleTapClickDurationMs(),
        minimumDoubleTapClickDurationMs(), maximumDoubleTapClickDurationMs());
  }

  public static int defaultStationaryTouchDelayMs() {
    return Math.max(150, ViewConfiguration.getDoubleTapTimeout() - 50);
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
    return readTouchTiming(preferences, STATIONARY_TOUCH_DELAY_KEY, defaultStationaryTouchDelayMs(),
        minimumStationaryTouchDelayMs(), maximumStationaryTouchDelayMs());
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
