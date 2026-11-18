package com.google.android.accessibility.utils.input;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ImeEditFeedback {
  private static final long EVENT_WINDOW_MS = 400;

  private record Edit(String packageName, long time) {}

  private record DictationEdit(AccessibilityNodeInfo node, long time) {}

  private static volatile @Nullable Edit latestEdit;
  private static @Nullable DictationEdit latestDictationEdit;

  private ImeEditFeedback() {}

  public static void recordEdit(@Nullable String packageName) {
    if (packageName != null) latestEdit = new Edit(packageName, SystemClock.uptimeMillis());
  }

  public static void recordDictationEdit(AccessibilityNodeInfo node) {
    DictationEdit previous = latestDictationEdit;
    latestDictationEdit =
        new DictationEdit(AccessibilityNodeInfo.obtain(node), SystemClock.uptimeMillis());
    if (previous != null) previous.node().recycle();
  }

  static boolean ownsDictationFeedback(AccessibilityEvent event) {
    DictationEdit edit = latestDictationEdit;
    if (edit == null) return false;
    long age = event.getEventTime() - edit.time();
    if (age < 0 || age > EVENT_WINDOW_MS) return false;
    AccessibilityNodeInfo source = event.getSource();
    if (source == null) return false;
    try {
      return edit.node().equals(source);
    } finally {
      source.recycle();
    }
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
