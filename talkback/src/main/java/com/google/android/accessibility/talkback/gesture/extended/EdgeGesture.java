package com.google.android.accessibility.talkback.gesture.extended;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.R;
import java.util.Locale;

public enum EdgeGesture {
  LEFT_TOP_IN(Edge.LEFT, 0, Direction.IN),
  LEFT_TOP_UP(Edge.LEFT, 0, Direction.UP),
  LEFT_TOP_DOWN(Edge.LEFT, 0, Direction.DOWN),
  LEFT_MIDDLE_IN(Edge.LEFT, 1, Direction.IN),
  LEFT_MIDDLE_UP(Edge.LEFT, 1, Direction.UP),
  LEFT_MIDDLE_DOWN(Edge.LEFT, 1, Direction.DOWN),
  LEFT_BOTTOM_IN(Edge.LEFT, 2, Direction.IN),
  LEFT_BOTTOM_UP(Edge.LEFT, 2, Direction.UP),
  LEFT_BOTTOM_DOWN(Edge.LEFT, 2, Direction.DOWN),
  RIGHT_TOP_IN(Edge.RIGHT, 0, Direction.IN),
  RIGHT_TOP_UP(Edge.RIGHT, 0, Direction.UP),
  RIGHT_TOP_DOWN(Edge.RIGHT, 0, Direction.DOWN),
  RIGHT_MIDDLE_IN(Edge.RIGHT, 1, Direction.IN),
  RIGHT_MIDDLE_UP(Edge.RIGHT, 1, Direction.UP),
  RIGHT_MIDDLE_DOWN(Edge.RIGHT, 1, Direction.DOWN),
  RIGHT_BOTTOM_IN(Edge.RIGHT, 2, Direction.IN),
  RIGHT_BOTTOM_UP(Edge.RIGHT, 2, Direction.UP),
  RIGHT_BOTTOM_DOWN(Edge.RIGHT, 2, Direction.DOWN),
  BOTTOM_LEFT_UP(Edge.BOTTOM, 0, Direction.UP),
  BOTTOM_MIDDLE_UP(Edge.BOTTOM, 1, Direction.UP),
  BOTTOM_RIGHT_UP(Edge.BOTTOM, 2, Direction.UP),
  BOTTOM_SWIPE_RIGHT(Edge.BOTTOM, -1, Direction.RIGHT),
  BOTTOM_SWIPE_LEFT(Edge.BOTTOM, -1, Direction.LEFT);

  public enum Edge {
    LEFT(R.string.edge_gestures_left),
    RIGHT(R.string.edge_gestures_right),
    BOTTOM(R.string.edge_gestures_bottom);

    public final int title;

    Edge(int title) {
      this.title = title;
    }
  }

  enum Direction {
    IN(R.string.edge_gesture_in),
    UP(R.string.edge_gesture_up),
    DOWN(R.string.edge_gesture_down),
    RIGHT(R.string.edge_gesture_swipe_right),
    LEFT(R.string.edge_gesture_swipe_left);

    final int title;

    Direction(int title) {
      this.title = title;
    }
  }

  public static final String ENABLED = "pref_edge_gestures_enabled";
  public static final String WIDTH = "pref_edge_gestures_width";
  public static final String DISTANCE = "pref_edge_gestures_distance";
  public static final int DEFAULT_WIDTH_DP = 24;
  public static final int DEFAULT_DISTANCE_DP = 32;

  private static final int FIRST_ID = 12001;
  private static final String KEY_PREFIX = "pref_edge_gesture_";
  private static final EdgeGesture[] GESTURES = values();

  public final Edge edge;
  final int zone;
  final Direction direction;
  public final String key;

  EdgeGesture(Edge edge, int zone, Direction direction) {
    this.edge = edge;
    this.zone = zone;
    this.direction = direction;
    key = KEY_PREFIX + name().toLowerCase(Locale.ROOT);
  }

  public int id() {
    return FIRST_ID + ordinal();
  }

  public boolean isLongSwipe() {
    return zone == -1;
  }

  public static @Nullable EdgeGesture fromId(int id) {
    int index = id - FIRST_ID;
    return index >= 0 && index < GESTURES.length ? GESTURES[index] : null;
  }

  public static @Nullable EdgeGesture fromKey(@Nullable String key) {
    if (key == null || !key.startsWith(KEY_PREFIX)) {
      return null;
    }
    for (EdgeGesture gesture : GESTURES) {
      if (gesture.key.equals(key)) {
        return gesture;
      }
    }
    return null;
  }

  static @Nullable EdgeGesture find(Edge edge, int zone, Direction direction) {
    for (EdgeGesture gesture : GESTURES) {
      if (gesture.edge == edge && gesture.zone == zone && gesture.direction == direction) {
        return gesture;
      }
    }
    return null;
  }

  public String title(Context context) {
    if (isLongSwipe()) {
      return context.getString(direction.title);
    }
    int position;
    if (edge == Edge.BOTTOM) {
      position = switch (zone) {
        case 0 -> R.string.edge_gesture_zone_left;
        case 1 -> R.string.edge_gesture_zone_middle;
        default -> R.string.edge_gesture_zone_right;
      };
    } else {
      position = switch (zone) {
        case 0 -> R.string.edge_gesture_zone_top;
        case 1 -> R.string.edge_gesture_zone_middle;
        default -> R.string.edge_gesture_zone_bottom;
      };
    }
    return context.getString(
        R.string.edge_gesture_zone_and_direction,
        context.getString(position),
        context.getString(direction.title));
  }

  public static boolean isEnabled(SharedPreferences prefs) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && prefs.getBoolean(ENABLED, false);
  }

  public static boolean hasAssignments(Context context, SharedPreferences prefs) {
    if (!isEnabled(prefs)) {
      return false;
    }
    String unassigned = context.getString(R.string.shortcut_value_unassigned);
    for (EdgeGesture gesture : GESTURES) {
      String action = gesture.action(prefs, unassigned);
      if (!action.isEmpty() && !unassigned.equals(action)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isPreference(@Nullable String key) {
    return ENABLED.equals(key) || WIDTH.equals(key) || DISTANCE.equals(key) || fromKey(key) != null;
  }

  public String action(SharedPreferences prefs, String unassigned) {
    return prefs.getString(key, unassigned);
  }
}
