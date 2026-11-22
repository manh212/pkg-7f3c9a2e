package com.google.android.accessibility.talkback.gesture.extended;

import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture.Direction;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture.Edge;

final class EdgeGestureTracker {
  record Area(float left, float top, float right, float bottom, float sideBottom,
      float width, float distance) {}

  private static final float DIRECTION_RATIO = 1.5f;
  private static final long STEP_INTERVAL_MS = 65;
  private static final float CONTINUOUS_STEP_FACTOR = 0.65f;

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
    boolean inBottom = y >= area.sideBottom - area.width;
    boolean inLeft = x - area.left <= area.width;
    boolean inRight = area.right() - x <= area.width;
    if (!inBottom && !inLeft && !inRight) {
      return null;
    }
    if (inBottom && (inLeft || inRight)) {
      float distBottom = Math.max(0f, area.bottom - y);
      float distSide = inLeft ? Math.max(0f, x - area.left) : Math.max(0f, area.right - x);
      if (distSide < distBottom) {
        int sideZone = zone(y, area.top, area.sideBottom);
        return new EdgeGestureTracker(area, inLeft ? Edge.LEFT : Edge.RIGHT, sideZone, x, y);
      }
      return new EdgeGestureTracker(area, Edge.BOTTOM, zone(x, area.left, area.right), x, y);
    }
    if (inBottom) {
      return new EdgeGestureTracker(area, Edge.BOTTOM, zone(x, area.left, area.right), x, y);
    }
    int sideZone = zone(y, area.top, area.sideBottom);
    return new EdgeGestureTracker(area, inLeft ? Edge.LEFT : Edge.RIGHT, sideZone, x, y);
  }

  static int zone(float position, float start, float end) {
    if (end <= start) {
      return 1;
    }
    int computed = (int) ((position - start) * 3 / (end - start));
    return Math.max(0, Math.min(2, computed));
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
    if (gesture.isTap()) {
      return false;
    }
    if (gesture.isLongSwipe()) {
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
    float axisDistance;
    float offAxis;
    switch (gesture.direction) {
      case UP -> {
        axisDistance = startY - y;
        offAxis = Math.abs(x - startX);
      }
      case DOWN -> {
        axisDistance = y - startY;
        offAxis = Math.abs(x - startX);
      }
      case IN -> {
        axisDistance = gesture.edge == Edge.LEFT ? x - startX : startX - x;
        offAxis = Math.abs(y - startY);
      }
      default -> {
        return false;
      }
    }
    float stepSize = Math.max(12f, area.distance * CONTINUOUS_STEP_FACTOR);
    float needed = area.distance + steps * stepSize;
    if (offAxis > area.distance * 1.5f
        || axisDistance < needed
        || (steps > 0 && !ending && eventTime - lastStepAt < STEP_INTERVAL_MS)) {
      return false;
    }
    steps++;
    lastStepAt = eventTime;
    return true;
  }
}
