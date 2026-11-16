package com.google.android.accessibility.talkback.gesture.extended;

import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;

public final class AnchoredGestures {
  public static final int FIRST_ID = GestureShortcutMapping.ANCHORED_SWIPE_UP;
  public static final int LAST_ID = GestureShortcutMapping.ANCHORED_TAP;
  private AnchoredGestures() {}
  public static boolean contains(int id) { return id >= FIRST_ID && id <= LAST_ID; }
}
