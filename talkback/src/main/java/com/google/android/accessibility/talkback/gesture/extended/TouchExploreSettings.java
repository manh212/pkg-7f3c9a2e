package com.google.android.accessibility.talkback.gesture.extended;

import android.content.SharedPreferences;
import android.os.Build;

public final class TouchExploreSettings {
  public static final String ENABLED = "pref_latency_touch_explore";
  public static final String DELAY_MS = "pref_latency_touch_explore_delay";
  public static final int MIN_DELAY_MS = 0;
  public static final int DEFAULT_DELAY_MS = 0;
  public static final int MAX_DELAY_MS = 250;

  private TouchExploreSettings() {}

  public static boolean isEnabled(SharedPreferences preferences) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && preferences.getBoolean(ENABLED, false);
  }

  public static int delayMs(SharedPreferences preferences) {
    return Math.max(
        MIN_DELAY_MS,
        Math.min(MAX_DELAY_MS, preferences.getInt(DELAY_MS, DEFAULT_DELAY_MS)));
  }
}
