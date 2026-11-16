package com.google.android.accessibility.utils.gestures;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class AnchoredGesture extends GestureMatcher {
  public static final int UP = 0;
  public static final int DOWN = 1;
  public static final int LEFT = 2;
  public static final int RIGHT = 3;
  public static final int TAP = 4;

  public static int directionFor(int id) {
    return switch (id) {
      case 11005 -> UP;
      case 11006 -> DOWN;
      case 11007 -> LEFT;
      case 11008 -> RIGHT;
      case 11009 -> TAP;
      default -> throw new IllegalArgumentException("Unknown anchored gesture id: " + id);
    };
  }

  private final int direction;
  private final float minimum;
  private final float slop;
  private final float touchSlop;
  private final long longPressTimeout;
  private final int[] ids = {-1, -1};
  private float anchorX;
  private float anchorY;
  private float secondX;
  private float secondY;
  private long pairedAt;
  private boolean paired;
  private boolean armed;

  public AnchoredGesture(Context context, int id, int direction, StateChangeListener listener,
      AnalyticsEventLogger logger) {
    super(id, new Handler(context.getMainLooper()), listener, logger);
    this.direction = direction;
    minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 6f,
        context.getResources().getDisplayMetrics());
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    slop = Math.max(touchSlop, minimum / 4f);
    longPressTimeout = ViewConfiguration.getLongPressTimeout();
    clear();
  }

  @Override public void clear() {
    ids[0] = ids[1] = -1;
    paired = armed = false;
    pairedAt = 0;
    super.clear();
  }

  @Override protected void onDown(EventId eventId, MotionEvent event) {
    if (event.getPointerCount() != 1 || ids[0] != -1) { cancelGesture(event); return; }
    ids[0] = event.getPointerId(0);
    anchorX = event.getX();
    anchorY = event.getY();
  }

  @Override protected void onPointerDown(EventId eventId, MotionEvent event) {
    if (paired || event.getPointerCount() != 2 || ids[0] < 0) { cancelGesture(event); return; }
    int firstIndex = event.findPointerIndex(ids[0]);
    int added = event.getActionIndex();
    if (firstIndex < 0 || added == firstIndex) { cancelGesture(event); return; }
    ids[1] = event.getPointerId(added);
    anchorX = event.getX(firstIndex);
    anchorY = event.getY(firstIndex);
    secondX = event.getX(added);
    secondY = event.getY(added);
    pairedAt = event.getEventTime();
    paired = true;
  }

  @Override protected void onMove(EventId eventId, MotionEvent event) {
    if (!paired) return;
    if (event.getPointerCount() > 2) { cancelGesture(event); return; }
    int a = event.findPointerIndex(ids[0]);
    int b = event.findPointerIndex(ids[1]);
    if (a < 0 || b < 0) { cancelGesture(event); return; }
    for (int history = 0; history < event.getHistorySize(); history++) {
      if (!checkPositions(event.getHistoricalX(a, history), event.getHistoricalY(a, history),
          event.getHistoricalX(b, history), event.getHistoricalY(b, history),
          event.getEventTime())) { cancelGesture(event); return; }
    }
    if (!checkPositions(event.getX(a), event.getY(a), event.getX(b), event.getY(b),
        event.getEventTime())) { cancelGesture(event); return; }
    if (direction != TAP
        && along(event.getX(b) - secondX, event.getY(b) - secondY, direction) >= minimum) {
      completeGesture(eventId, event);
    }
  }

  @Override protected void onPointerUp(EventId eventId, MotionEvent event) {
    if (!paired || event.getPointerCount() != 2) { cancelGesture(event); return; }
    int lifted = event.getPointerId(event.getActionIndex());
    if (lifted != ids[1]) { cancelGesture(event); return; }
    int a = event.findPointerIndex(ids[0]);
    int b = event.findPointerIndex(ids[1]);
    if (a < 0 || b < 0) { cancelGesture(event); return; }
    if (!armed && event.getEventTime() - pairedAt >= longPressTimeout) armed = true;
    if (!armed) { cancelGesture(event); return; }
    if (Math.hypot(event.getX(a) - anchorX, event.getY(a) - anchorY) >= touchSlop) {
      cancelGesture(event); return;
    }
    float dx = event.getX(b) - secondX;
    float dy = event.getY(b) - secondY;
    if (direction == TAP) {
      if (Math.hypot(dx, dy) < touchSlop) completeGesture(eventId, event);
      else cancelGesture(event);
      return;
    }
    float forward = along(dx, dy, direction);
    if (forward >= minimum
        && across(dx, dy, direction) <= Math.max(slop, forward * .55f)) {
      completeGesture(eventId, event);
    } else {
      cancelGesture(event);
    }
  }

  @Override protected void onUp(EventId eventId, MotionEvent event) {
    cancelGesture(event);
  }

  private boolean checkPositions(float ax, float ay, float bx, float by, long eventTime) {
    if (!armed && eventTime - pairedAt >= longPressTimeout) armed = true;
    if (!armed) {
      return Math.hypot(ax - anchorX, ay - anchorY) < touchSlop
          && Math.hypot(bx - secondX, by - secondY) < touchSlop;
    }
    return Math.hypot(ax - anchorX, ay - anchorY) < touchSlop
        && secondOk(bx - secondX, by - secondY);
  }

  private boolean secondOk(float dx, float dy) {
    if (direction == TAP) return Math.hypot(dx, dy) < minimum;
    float forward = along(dx, dy, direction);
    return forward >= -slop
        && across(dx, dy, direction) <= Math.max(slop, forward * .55f);
  }

  private static float along(float dx, float dy, int direction) {
    return switch (direction) { case UP -> -dy; case DOWN -> dy; case LEFT -> -dx; default -> dx; };
  }

  private static float across(float dx, float dy, int direction) {
    return direction == UP || direction == DOWN ? Math.abs(dx) : Math.abs(dy);
  }

  @Override protected String getGestureName() { return "AnchoredGesture:" + direction; }
}
