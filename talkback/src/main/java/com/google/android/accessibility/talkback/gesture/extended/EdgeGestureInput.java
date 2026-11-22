package com.google.android.accessibility.talkback.gesture.extended;

import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
  private final Handler handler;
  private final EdgeHaptics haptics;
  private final EdgeTapTracker tapTracker;
  private final Map<EdgeGesture, String> assignments = new EnumMap<>(EdgeGesture.class);
  private @Nullable EdgeGestureTracker.Area area;
  private @Nullable EdgeGestureTracker tracker;
  private @Nullable EdgeGesture claimed;
  private boolean hasThreeFingerBottomTap;
  private long currentDownTime;
  private final Runnable commitPendingTap = this::commitPendingTap;

  EdgeGestureInput(TalkBackService service) {
    this.service = service;
    this.handler = new Handler(Looper.getMainLooper());
    this.haptics = new EdgeHaptics(service);
    this.tapTracker = new EdgeTapTracker(ViewConfiguration.get(service).getScaledTouchSlop());
  }

  void reload() {
    assignments.clear();
    hasThreeFingerBottomTap = false;
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    String unassigned = service.getString(R.string.shortcut_value_unassigned);
    for (EdgeGesture gesture : EdgeGesture.values()) {
      String action = gesture.action(prefs, unassigned);
      if (!action.isEmpty() && !unassigned.equals(action)) {
        assignments.put(gesture, action);
        if (gesture.isTap() && gesture.edge == EdgeGesture.Edge.BOTTOM && gesture.fingers == 3) {
          hasThreeFingerBottomTap = true;
        }
      }
    }
    refreshArea();
  }

  void refreshArea() {
    reset();
    area = assignments.isEmpty() ? null : readArea();
  }

  void reset() {
    handler.removeCallbacks(commitPendingTap);
    tracker = null;
    claimed = null;
    tapTracker.resetAll();
  }

  void clearStroke() {
    tracker = null;
    claimed = null;
    tapTracker.resetStroke();
  }

  boolean isTrackingBottomThreeFingerTap() {
    return hasThreeFingerBottomTap && tapTracker.isBottomThreeFingerStroke();
  }

  boolean isTrackingTap() {
    return tapTracker.isStrokeActive();
  }

  void down(MotionEvent event) {
    currentDownTime = event.getEventTime();
    tracker = null;
    claimed = null;
    if (assignments.isEmpty() || area == null) {
      tapTracker.resetAll();
      return;
    }
    if (tapTracker.onDown(area, event, assignments.keySet())
        && tapTracker.canContinueSequence(event)) {
      handler.removeCallbacks(commitPendingTap);
      handler.postAtTime(commitPendingTap, event.getEventTime() + EdgeTapTracker.MAX_TAP_HOLD_MS + 16);
    }

    EdgeGestureTracker candidate = EdgeGestureTracker.start(area, event.getX(), event.getY());
    if (candidate == null) {
      return;
    }
    for (EdgeGesture gesture : assignments.keySet()) {
      if (gesture.isSwipe()
          && gesture.edge == candidate.edge
          && (gesture.zone == candidate.zone || gesture.isLongSwipe())) {
        tracker = candidate;
        break;
      }
    }
  }

  boolean onPointerDown(MotionEvent event) {
    tracker = null;
    claimed = null;
    if (area == null || assignments.isEmpty()) {
      tapTracker.resetAll();
      return false;
    }
    return tapTracker.onPointerDown(area, event, assignments.keySet());
  }

  boolean moveOrUp(MotionEvent event, Runnable claimInteraction) {
    int actionMasked = event.getActionMasked();
    boolean ending = actionMasked == MotionEvent.ACTION_UP;

    if (tapTracker.isStrokeActive()) {
      if (actionMasked == MotionEvent.ACTION_MOVE) {
        tapTracker.onMove(event);
      } else if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
        tapTracker.onPointerUp(event);
      } else if (ending) {
        EdgeTapTracker.Stroke stroke = tapTracker.onUp(event);
        if (stroke != null) {
          return handleCompletedTapStroke(stroke, event, claimInteraction);
        }
      }
    }

    if (tracker == null || event.getPointerCount() > 1) {
      return false;
    }
    float x = event.getX();
    float y = event.getY();
    if (event.getHistorySize() > 0) {
      for (int i = 0; i < event.getHistorySize(); i++) {
        tracker.observe(event.getHistoricalX(i), event.getHistoricalY(i));
      }
    }
    tracker.observe(x, y);

    EdgeGesture gesture = claimed == null ? tracker.match(x, y) : claimed;
    String action = gesture == null ? null : assignments.get(gesture);

    if (!ending && claimed == null && gesture != null && action != null
        && EdgeGestureActions.isAvailable(service, action)) {
      if (EdgeGestureActions.repeats(service, action)) {
        if (tracker.takeStep(gesture, x, y, event.getEventTime(), /* ending= */ false)) {
          claimInteraction.run();
          claimed = gesture;
          haptics.onSwipeStep();
          service.onEdgeGesture(gesture.id(), -1);
          return true;
        }
      } else {
        claimInteraction.run();
        claimed = gesture;
        haptics.onSwipeConfirm();
        return true;
      }
    }

    if (gesture != null && action != null && EdgeGestureActions.repeats(service, action)) {
      if (tracker.takeStep(gesture, x, y, event.getEventTime(), ending)
          && (claimed != null || EdgeGestureActions.isAvailable(service, action))) {
        if (claimed == null) {
          claimInteraction.run();
          claimed = gesture;
        }
        haptics.onSwipeStep();
        service.onEdgeGesture(gesture.id(), ending ? event.getEventTime() : -1);
      }
    } else if (ending && gesture != null && action != null
        && EdgeGestureActions.isAvailable(service, action)) {
      boolean newlyClaimed = claimed == null;
      if (newlyClaimed) {
        claimInteraction.run();
        claimed = gesture;
        haptics.onSwipeConfirm();
      }
      service.onEdgeGesture(gesture.id(), event.getEventTime());
    }

    boolean handled = claimed != null;
    if (ending) {
      tracker = null;
      claimed = null;
    }
    return handled;
  }

  private boolean handleCompletedTapStroke(
      EdgeTapTracker.Stroke stroke, MotionEvent event, Runnable claimInteraction) {
    handler.removeCallbacks(commitPendingTap);
    if (!tapTracker.matchesPendingStroke(stroke, currentDownTime)) {
      commitPendingTap();
    }
    long eventTime = event.getEventTime();
    int count = tapTracker.recordTap(stroke, eventTime);
    EdgeGesture exact = EdgeGesture.findTap(stroke.edge(), stroke.zone(), stroke.fingers(), count);
    boolean exactAssigned = exact != null && assignments.containsKey(exact);
    boolean higherAssigned = tapTracker.hasHigherTapAssigned(stroke, count, assignments.keySet());

    if (!exactAssigned && !higherAssigned) {
      tapTracker.resetAll();
      return false;
    }

    haptics.onEdgeTapBeat();
    claimInteraction.run();
    tracker = null;
    claimed = null;

    if (!higherAssigned) {
      EdgeGesture confirmed = tapTracker.consumePending(assignments.keySet());
      if (confirmed != null) {
        String action = assignments.get(confirmed);
        if (action != null && EdgeGestureActions.isAvailable(service, action)) {
          haptics.onTapConfirm(confirmed.taps);
          service.onEdgeGesture(confirmed.id(), eventTime);
        }
      }
      return true;
    }

    handler.removeCallbacks(commitPendingTap);
    handler.postDelayed(commitPendingTap, EdgeTapTracker.MULTI_TAP_TIMEOUT_MS);
    return true;
  }

  private void commitPendingTap() {
    EdgeGesture confirmed = tapTracker.consumePending(assignments.keySet());
    if (confirmed == null) {
      return;
    }
    String action = assignments.get(confirmed);
    if (action == null || !EdgeGestureActions.isAvailable(service, action)) {
      return;
    }
    haptics.onTapConfirm(confirmed.taps);
    service.onEdgeGesture(confirmed.id(), SystemClock.uptimeMillis());
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
