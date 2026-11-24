package com.google.android.accessibility.utils.input;

import com.google.android.accessibility.utils.LatencyExperiments;

/**
 * Single source of truth for window-transition timing and initial-focus flags, replacing scattered
 * static volatile fields across window-change processors.
 */
public final class WindowTransitionPolicy {
  private static final long DEFAULT_WINDOW_CHANGE_DELAY_MS = 550L;

  private static volatile boolean focusNewWindows = true;
  private static volatile boolean reduceWindowDelay = false;
  private static volatile long windowChangeDelayMs = DEFAULT_WINDOW_CHANGE_DELAY_MS;

  private WindowTransitionPolicy() {}

  public static boolean focusesNewWindows() {
    return focusNewWindows;
  }

  public static void setFocusNewWindows(boolean focus) {
    focusNewWindows = focus;
  }

  public static boolean isReduceWindowDelay() {
    return reduceWindowDelay;
  }

  public static void setReduceWindowDelay(boolean reduce) {
    reduceWindowDelay = reduce;
  }

  public static long getWindowChangeDelayMs() {
    return windowChangeDelayMs;
  }

  public static void setWindowChangeDelayMs(long delayMs) {
    windowChangeDelayMs = delayMs;
  }

  public static boolean isFastWindowTransitionsEnabled() {
    return LatencyExperiments.Feature.FAST_WINDOW_TRANSITIONS.enabled();
  }

  /** Human-readable diagnostics summary of the active window-transition policy. */
  public static String diagnosticsSummary() {
    return "focusNewWindows="
        + focusNewWindows
        + ", reduceWindowDelay="
        + reduceWindowDelay
        + ", windowChangeDelayMs="
        + windowChangeDelayMs
        + ", fastWindowTransitions="
        + isFastWindowTransitionsEnabled();
  }
}
