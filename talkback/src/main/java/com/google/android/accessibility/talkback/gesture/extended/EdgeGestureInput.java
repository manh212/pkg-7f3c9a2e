package com.google.android.accessibility.talkback.gesture.extended;

import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.EdgeGestureActions;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.EnumMap;
import java.util.Map;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class EdgeGestureInput {
  private final TalkBackService service;
  private final Map<EdgeGesture, String> assignments = new EnumMap<>(EdgeGesture.class);
  private @Nullable EdgeGestureTracker.Area area;
  private @Nullable EdgeGestureTracker tracker;
  private @Nullable EdgeGesture claimed;
  private boolean canUseLongSwipe;

  EdgeGestureInput(TalkBackService service) {
    this.service = service;
  }

  void reload() {
    assignments.clear();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    String unassigned = service.getString(R.string.shortcut_value_unassigned);
    for (EdgeGesture gesture : EdgeGesture.values()) {
      String action = gesture.action(prefs, unassigned);
      if (!action.isEmpty() && !unassigned.equals(action)) {
        assignments.put(gesture, action);
      }
    }
    refreshArea();
  }

  void refreshArea() {
    reset();
    area = assignments.isEmpty() ? null : readArea();
  }

  void reset() {
    tracker = null;
    claimed = null;
    canUseLongSwipe = false;
  }

  void down(MotionEvent event) {
    reset();
    if (assignments.isEmpty()) {
      return;
    }
    if (area == null) {
      return;
    }
    EdgeGestureTracker candidate = EdgeGestureTracker.start(area, event.getX(), event.getY());
    if (candidate == null) {
      return;
    }
    for (EdgeGesture gesture : assignments.keySet()) {
      if (gesture.edge == candidate.edge
          && (gesture.zone == candidate.zone || gesture.isLongSwipe())) {
        tracker = candidate;
        canUseLongSwipe |= gesture.isLongSwipe();
      }
    }
  }

  boolean moveOrUp(MotionEvent event, Runnable claimInteraction) {
    if (tracker == null) {
      return false;
    }
    boolean ending = event.getActionMasked() == MotionEvent.ACTION_UP;
    float x = event.getX();
    float y = event.getY();
    if (event.getHistorySize() > 0) {
      for (int i = 0; i < event.getHistorySize(); i++) {
        tracker.observe(event.getHistoricalX(i), event.getHistoricalY(i));
      }
    }
    tracker.observe(x, y);
    if (!ending && claimed == null && !canUseLongSwipe) {
      return false;
    }
    EdgeGesture gesture = claimed == null ? tracker.match(x, y) : claimed;
    String action = gesture == null ? null : assignments.get(gesture);
    if (gesture != null && action != null && gesture.isLongSwipe()
        && EdgeGestureActions.repeats(service, action)) {
      if (tracker.takeStep(gesture, x, y, event.getEventTime(), ending)
          && (claimed != null || EdgeGestureActions.isAvailable(service, action))) {
        if (claimed == null) {
          claimInteraction.run();
          claimed = gesture;
        }
        service.onEdgeGesture(gesture.id(), ending ? event.getEventTime() : -1);
      }
    } else if (ending && gesture != null && action != null
        && EdgeGestureActions.isAvailable(service, action)) {
      claimInteraction.run();
      claimed = gesture;
      service.onEdgeGesture(gesture.id(), event.getEventTime());
    }
    boolean handled = claimed != null;
    if (ending) {
      reset();
    }
    return handled;
  }

  private @Nullable EdgeGestureTracker.Area readArea() {
    try {
      WindowManager manager = service.getSystemService(WindowManager.class);
      if (manager == null) {
        return null;
      }
      WindowMetrics metrics = manager.getMaximumWindowMetrics();
      Rect bounds = metrics.getBounds();
      Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
          WindowInsets.Type.navigationBars()
              | WindowInsets.Type.statusBars()
              | WindowInsets.Type.displayCutout()
              | WindowInsets.Type.mandatorySystemGestures());
      SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
      float density = service.getResources().getDisplayMetrics().density;
      int width = Math.max(12, Math.min(48, prefs.getInt(EdgeGesture.WIDTH, EdgeGesture.DEFAULT_WIDTH_DP)));
      int distance = Math.max(16, Math.min(72,
          prefs.getInt(EdgeGesture.DISTANCE, EdgeGesture.DEFAULT_DISTANCE_DP)));
      return new EdgeGestureTracker.Area(
          bounds.left + safe.left, bounds.top + safe.top,
          bounds.right - safe.right, bounds.bottom, bounds.bottom - safe.bottom,
          width * density, distance * density);
    } catch (RuntimeException error) {
      LogUtils.w("EdgeGestureInput", "Could not determine gesture area: %s", error);
      return null;
    }
  }
}
