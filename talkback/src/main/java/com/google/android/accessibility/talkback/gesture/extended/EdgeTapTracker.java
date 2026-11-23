package com.google.android.accessibility.talkback.gesture.extended;

import android.view.MotionEvent;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture.Edge;
import java.util.Set;

final class EdgeTapTracker {
  static final long MAX_TAP_HOLD_MS = 280L;
  static final long MULTI_TAP_TIMEOUT_MS = 290L;
  private static final float MULTI_FINGER_WIDTH_MULTIPLIER = 1.8f;

  record Stroke(Edge edge, int zone, int fingers) {}

  private final float touchSlop;
  private final float[] downX = new float[32];
  private final float[] downY = new float[32];

  private boolean strokeActive;
  private boolean strokeInvalid;
  private boolean pointerLifted;
  private long strokeDownTime;
  private int maxPointersInStroke;
  private @Nullable Edge firstEdge;
  private int firstZone;

  private @Nullable Stroke pendingStroke;
  private int tapCount;
  private long lastTapUpTime;

  EdgeTapTracker(float touchSlop) {
    this.touchSlop = touchSlop;
  }

  void resetAll() {
    resetStroke();
    pendingStroke = null;
    tapCount = 0;
    lastTapUpTime = 0L;
  }

  void resetStroke() {
    strokeActive = false;
    strokeInvalid = false;
    pointerLifted = false;
    strokeDownTime = 0L;
    maxPointersInStroke = 0;
    firstEdge = null;
    firstZone = -1;
  }

  boolean isStrokeActive() {
    return strokeActive && !strokeInvalid;
  }

  boolean canContinueSequence(MotionEvent event) {
    return isStrokeActive()
        && pendingStroke != null
        && pendingStroke.edge == firstEdge
        && (pendingStroke.fingers > 1 || pendingStroke.zone == firstZone)
        && event.getEventTime() - lastTapUpTime <= MULTI_TAP_TIMEOUT_MS;
  }

  boolean matchesPendingStroke(Stroke stroke, long downTime) {
    return pendingStroke != null
        && pendingStroke.equals(stroke)
        && downTime - lastTapUpTime <= MULTI_TAP_TIMEOUT_MS;
  }

  boolean isBottomMultiFingerStroke() {
    return isStrokeActive() && firstEdge == Edge.BOTTOM && maxPointersInStroke >= 3;
  }

  boolean onDown(EdgeGestureTracker.Area area, MotionEvent event, Set<EdgeGesture> assigned) {
    resetStroke();
    float x = event.getX();
    float y = event.getY();
    Edge edge = detectEdge(area, x, y, /* multiFinger= */ false);
    if (edge == null) {
      Edge wideEdge = detectEdge(area, x, y, /* multiFinger= */ true);
      if (wideEdge == null || !hasAnyMultiFingerTap(assigned, wideEdge)) {
        return false;
      }
      edge = wideEdge;
    }
    int zone = zoneFor(area, edge, x, y);
    if (!hasAnyTapOnEdgeOrZone(assigned, edge, zone)) {
      return false;
    }
    strokeActive = true;
    strokeDownTime = event.getEventTime();
    maxPointersInStroke = 1;
    firstEdge = edge;
    firstZone = zone;
    recordPointerDown(event, 0);
    return true;
  }

  boolean onPointerDown(
      EdgeGestureTracker.Area area, MotionEvent event, Set<EdgeGesture> assigned) {
    if (!strokeActive || strokeInvalid) {
      return false;
    }
    int count = event.getPointerCount();
    if (count > 4 || pointerLifted) {
      strokeInvalid = true;
      return false;
    }
    int idx = event.getActionIndex();
    float x = event.getX(idx);
    float y = event.getY(idx);
    Edge edge = detectEdge(area, x, y, /* multiFinger= */ true);
    if (edge != firstEdge || !hasAnyMultiFingerTap(assigned, edge)) {
      strokeInvalid = true;
      return false;
    }
    maxPointersInStroke = Math.max(maxPointersInStroke, count);
    recordPointerDown(event, idx);
    return true;
  }

  void onMove(MotionEvent event) {
    if (!strokeActive || strokeInvalid) {
      return;
    }
    for (int i = 0; i < event.getPointerCount(); i++) {
      int pid = event.getPointerId(i);
      if (pid >= 0 && pid < downX.length) {
        if (moved(event.getX(i), event.getY(i), pid)) {
          strokeInvalid = true;
          return;
        }
        for (int j = 0; j < event.getHistorySize(); j++) {
          if (moved(event.getHistoricalX(i, j), event.getHistoricalY(i, j), pid)) {
            strokeInvalid = true;
            return;
          }
        }
      }
    }
  }

  void onPointerUp(MotionEvent event) {
    onMove(event);
    pointerLifted = true;
  }

  private boolean moved(float x, float y, int pointerId) {
    return Math.hypot(x - downX[pointerId], y - downY[pointerId]) > touchSlop;
  }

  @Nullable Stroke onUp(MotionEvent event) {
    onMove(event);
    if (!strokeActive || strokeInvalid || firstEdge == null) {
      resetStroke();
      return null;
    }
    long duration = event.getEventTime() - strokeDownTime;
    if (duration > MAX_TAP_HOLD_MS) {
      resetStroke();
      return null;
    }
    int fingers = maxPointersInStroke;
    int zone = fingers == 1 ? firstZone : -1;
    Stroke completed = new Stroke(firstEdge, zone, fingers);
    resetStroke();
    return completed;
  }

  int recordTap(Stroke stroke, long upTime) {
    if (pendingStroke != null
        && pendingStroke.equals(stroke)
        && tapCount < 3) {
      tapCount++;
    } else {
      pendingStroke = stroke;
      tapCount = 1;
    }
    lastTapUpTime = upTime;
    return tapCount;
  }

  @Nullable EdgeGesture consumePending(Set<EdgeGesture> assigned) {
    Stroke stroke = pendingStroke;
    int count = tapCount;
    pendingStroke = null;
    tapCount = 0;
    if (stroke == null || count <= 0) {
      return null;
    }
    EdgeGesture gesture = EdgeGesture.findTap(stroke.edge, stroke.zone, stroke.fingers, count);
    return gesture != null && assigned.contains(gesture) ? gesture : null;
  }

  boolean hasHigherTapAssigned(Stroke stroke, int currentTaps, Set<EdgeGesture> assigned) {
    for (int next = currentTaps + 1; next <= 3; next++) {
      EdgeGesture candidate = EdgeGesture.findTap(stroke.edge, stroke.zone, stroke.fingers, next);
      if (candidate != null && assigned.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  private void recordPointerDown(MotionEvent event, int index) {
    int pid = event.getPointerId(index);
    if (pid >= 0 && pid < downX.length) {
      downX[pid] = event.getX(index);
      downY[pid] = event.getY(index);
    }
  }

  static @Nullable Edge detectEdge(
      EdgeGestureTracker.Area area, float x, float y, boolean multiFinger) {
    if (x < area.left() || x >= area.right() || y < area.top() || y >= area.bottom()) {
      return null;
    }
    float strip = multiFinger ? area.width() * MULTI_FINGER_WIDTH_MULTIPLIER : area.width();
    boolean inBottom = y >= area.sideBottom() - strip;
    boolean inLeft = x - area.left() <= strip;
    boolean inRight = area.right() - x <= strip;
    if (!inBottom && !inLeft && !inRight) {
      return null;
    }
    if (inBottom && (inLeft || inRight)) {
      float distBottom = Math.max(0f, area.bottom() - y);
      float distSide = inLeft ? Math.max(0f, x - area.left()) : Math.max(0f, area.right() - x);
      if (distSide < distBottom) {
        return inLeft ? Edge.LEFT : Edge.RIGHT;
      }
      return Edge.BOTTOM;
    }
    if (inBottom) return Edge.BOTTOM;
    return inLeft ? Edge.LEFT : Edge.RIGHT;
  }

  private static int zoneFor(EdgeGestureTracker.Area area, Edge edge, float x, float y) {
    if (edge == Edge.BOTTOM) {
      return EdgeGestureTracker.zone(x, area.left(), area.right());
    }
    return EdgeGestureTracker.zone(y, area.top(), area.sideBottom());
  }

  private static boolean hasAnyTapOnEdgeOrZone(Set<EdgeGesture> assigned, Edge edge, int zone) {
    for (EdgeGesture g : assigned) {
      if (!g.isTap() || g.edge != edge) continue;
      if (g.fingers > 1 || g.zone == zone) return true;
    }
    return false;
  }

  private static boolean hasAnyMultiFingerTap(Set<EdgeGesture> assigned, Edge edge) {
    for (EdgeGesture g : assigned) {
      if (g.isTap() && g.edge == edge && g.fingers > 1) return true;
    }
    return false;
  }
}
