package com.google.android.accessibility.talkback.gesture.extended;

import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture.Direction;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture.Edge;

final class EdgeGestureTracker {
  record Area(float left, float top, float right, float bottom, float sideBottom,
      float width, float distance) {}

  private static final float DIRECTION_RATIO = 1.5f;
  private static final long STEP_INTERVAL_MS = 65;

  private final Area area;
  final Edge edge;
  final int zone;
  private final float startX;
  private final float startY;
  private float horizontalTravel;
  private float verticalTravel;
  private int steps;
  private long lastStepAt;

  private EdgeGestureTracker(Area area, Edge edge, int zone, float startX, float startY) {
    this.area = area;
    this.edge = edge;
    this.zone = zone;
    this.startX = startX;
    this.startY = startY;
  }

  static @Nullable EdgeGestureTracker start(Area area, float x, float y) {
    if (x < area.left || x >= area.right || y < area.top || y >= area.bottom) {
      return null;
    }
    if (y >= area.sideBottom - area.width) {
      return new EdgeGestureTracker(area, Edge.BOTTOM, zone(x, area.left, area.right), x, y);
    }
    int zone = zone(y, area.top, area.sideBottom);
    if (x - area.left <= area.width) {
      return new EdgeGestureTracker(area, Edge.LEFT, zone, x, y);
    }
    if (area.right - x <= area.width) {
      return new EdgeGestureTracker(area, Edge.RIGHT, zone, x, y);
    }
    return null;
  }

  private static int zone(float position, float start, float end) {
    return Math.min(2, (int) ((position - start) * 3 / (end - start)));
  }

  void observe(float x, float y) {
    horizontalTravel = Math.max(horizontalTravel, Math.abs(x - startX));
    verticalTravel = Math.max(verticalTravel, Math.abs(y - startY));
  }

  @Nullable EdgeGesture match(float x, float y) {
    float dx = x - startX;
    float dy = y - startY;
    float horizontal = Math.abs(dx);
    float vertical = Math.abs(dy);
    if (edge == Edge.BOTTOM) {
      if (-dy >= area.distance && vertical >= horizontalTravel * DIRECTION_RATIO) {
        return EdgeGesture.find(edge, zone, Direction.UP);
      }
      if (horizontal >= area.distance * 2
          && verticalTravel <= area.distance / 2) {
        return EdgeGesture.find(edge, -1, dx > 0 ? Direction.RIGHT : Direction.LEFT);
      }
    } else if (vertical >= area.distance && vertical >= horizontalTravel * DIRECTION_RATIO) {
      return EdgeGesture.find(edge, zone, dy < 0 ? Direction.UP : Direction.DOWN);
    } else if (horizontal >= area.distance && horizontal >= verticalTravel * DIRECTION_RATIO
        && (edge == Edge.LEFT ? dx > 0 : dx < 0)) {
      return EdgeGesture.find(edge, zone, Direction.IN);
    }
    return null;
  }

  boolean takeStep(EdgeGesture gesture, float x, float y, long eventTime, boolean ending) {
    float distance = gesture.direction == Direction.RIGHT ? x - startX : startX - x;
    if (Math.abs(y - startY) > area.distance / 2
        || distance < area.distance * (2 + steps)
        || (steps > 0 && !ending && eventTime - lastStepAt < STEP_INTERVAL_MS)) {
      return false;
    }
    steps++;
    lastStepAt = eventTime;
    return true;
  }
}
