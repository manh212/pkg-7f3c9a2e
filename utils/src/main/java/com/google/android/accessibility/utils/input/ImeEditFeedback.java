package com.google.android.accessibility.utils.input;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ImeEditFeedback {
  private static final long EVENT_WINDOW_MS = 400;

  private record Edit(String packageName, long time) {}

  private static volatile @Nullable Edit latestEdit;

  private ImeEditFeedback() {}

  public static void recordEdit(@Nullable String packageName) {
    if (packageName != null) latestEdit = new Edit(packageName, SystemClock.uptimeMillis());
  }

  public static void clear() {
    latestEdit = null;
  }

  static boolean ownsEditFeedback(AccessibilityEvent event) {
    Edit edit = latestEdit;
    if (edit == null) return false;
    long age = event.getEventTime() - edit.time();
    return age >= 0 && age <= EVENT_WINDOW_MS
        && TextUtils.equals(edit.packageName(), event.getPackageName());
  }
}
