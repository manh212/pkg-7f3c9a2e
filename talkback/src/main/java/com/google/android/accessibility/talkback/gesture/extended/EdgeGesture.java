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
  BOTTOM_SWIPE_LEFT(Edge.BOTTOM, -1, Direction.LEFT),

  LEFT_TOP_TAP_1(Edge.LEFT, 0, 1, 1),
  LEFT_TOP_TAP_2(Edge.LEFT, 0, 1, 2),
  LEFT_TOP_TAP_3(Edge.LEFT, 0, 1, 3),
  LEFT_MIDDLE_TAP_1(Edge.LEFT, 1, 1, 1),
  LEFT_MIDDLE_TAP_2(Edge.LEFT, 1, 1, 2),
  LEFT_MIDDLE_TAP_3(Edge.LEFT, 1, 1, 3),
  LEFT_BOTTOM_TAP_1(Edge.LEFT, 2, 1, 1),
  LEFT_BOTTOM_TAP_2(Edge.LEFT, 2, 1, 2),
  LEFT_BOTTOM_TAP_3(Edge.LEFT, 2, 1, 3),
  RIGHT_TOP_TAP_1(Edge.RIGHT, 0, 1, 1),
  RIGHT_TOP_TAP_2(Edge.RIGHT, 0, 1, 2),
  RIGHT_TOP_TAP_3(Edge.RIGHT, 0, 1, 3),
  RIGHT_MIDDLE_TAP_1(Edge.RIGHT, 1, 1, 1),
  RIGHT_MIDDLE_TAP_2(Edge.RIGHT, 1, 1, 2),
  RIGHT_MIDDLE_TAP_3(Edge.RIGHT, 1, 1, 3),
  RIGHT_BOTTOM_TAP_1(Edge.RIGHT, 2, 1, 1),
  RIGHT_BOTTOM_TAP_2(Edge.RIGHT, 2, 1, 2),
  RIGHT_BOTTOM_TAP_3(Edge.RIGHT, 2, 1, 3),
  BOTTOM_LEFT_TAP_1(Edge.BOTTOM, 0, 1, 1),
  BOTTOM_LEFT_TAP_2(Edge.BOTTOM, 0, 1, 2),
  BOTTOM_LEFT_TAP_3(Edge.BOTTOM, 0, 1, 3),
  BOTTOM_MIDDLE_TAP_1(Edge.BOTTOM, 1, 1, 1),
  BOTTOM_MIDDLE_TAP_2(Edge.BOTTOM, 1, 1, 2),
  BOTTOM_MIDDLE_TAP_3(Edge.BOTTOM, 1, 1, 3),
  BOTTOM_RIGHT_TAP_1(Edge.BOTTOM, 2, 1, 1),
  BOTTOM_RIGHT_TAP_2(Edge.BOTTOM, 2, 1, 2),
  BOTTOM_RIGHT_TAP_3(Edge.BOTTOM, 2, 1, 3),

  LEFT_2F_TAP_1(Edge.LEFT, -1, 2, 1),
  LEFT_2F_TAP_2(Edge.LEFT, -1, 2, 2),
  LEFT_2F_TAP_3(Edge.LEFT, -1, 2, 3),
  RIGHT_2F_TAP_1(Edge.RIGHT, -1, 2, 1),
  RIGHT_2F_TAP_2(Edge.RIGHT, -1, 2, 2),
  RIGHT_2F_TAP_3(Edge.RIGHT, -1, 2, 3),
  BOTTOM_2F_TAP_1(Edge.BOTTOM, -1, 2, 1),
  BOTTOM_2F_TAP_2(Edge.BOTTOM, -1, 2, 2),
  BOTTOM_2F_TAP_3(Edge.BOTTOM, -1, 2, 3),

  LEFT_3F_TAP_1(Edge.LEFT, -1, 3, 1),
  LEFT_3F_TAP_2(Edge.LEFT, -1, 3, 2),
  LEFT_3F_TAP_3(Edge.LEFT, -1, 3, 3),
  RIGHT_3F_TAP_1(Edge.RIGHT, -1, 3, 1),
  RIGHT_3F_TAP_2(Edge.RIGHT, -1, 3, 2),
  RIGHT_3F_TAP_3(Edge.RIGHT, -1, 3, 3),
  BOTTOM_3F_TAP_1(Edge.BOTTOM, -1, 3, 1),
  BOTTOM_3F_TAP_2(Edge.BOTTOM, -1, 3, 2),
  BOTTOM_3F_TAP_3(Edge.BOTTOM, -1, 3, 3);

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
    LEFT(R.string.edge_gesture_swipe_left),
    TAP(R.string.edge_gesture_tap_1);

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
  public final int fingers;
  public final int taps;
  public final String key;

  EdgeGesture(Edge edge, int zone, Direction direction) {
    this.edge = edge;
    this.zone = zone;
    this.direction = direction;
    this.fingers = 1;
    this.taps = 0;
    key = KEY_PREFIX + name().toLowerCase(Locale.ROOT);
  }

  EdgeGesture(Edge edge, int zone, int fingers, int taps) {
    this.edge = edge;
    this.zone = zone;
    this.direction = Direction.TAP;
    this.fingers = fingers;
    this.taps = taps;
    key = KEY_PREFIX + name().toLowerCase(Locale.ROOT);
  }

  public int id() {
    return FIRST_ID + ordinal();
  }

  public boolean isTap() {
    return taps > 0;
  }

  public boolean isSwipe() {
    return taps == 0;
  }

  public boolean isLongSwipe() {
    return isSwipe() && zone == -1;
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
      if (gesture.isSwipe()
          && gesture.edge == edge
          && gesture.zone == zone
          && gesture.direction == direction) {
        return gesture;
      }
    }
    return null;
  }

  static @Nullable EdgeGesture findTap(Edge edge, int zone, int fingers, int taps) {
    for (EdgeGesture gesture : GESTURES) {
      if (gesture.isTap()
          && gesture.edge == edge
          && gesture.zone == zone
          && gesture.fingers == fingers
          && gesture.taps == taps) {
        return gesture;
      }
    }
    return null;
  }

  public String title(Context context) {
    if (isTap()) {
      int tapRes = switch (taps) {
        case 1 -> R.string.edge_gesture_tap_1;
        case 2 -> R.string.edge_gesture_tap_2;
        default -> R.string.edge_gesture_tap_3;
      };
      if (fingers > 1) {
        int fingerRes = fingers == 2 ? R.string.edge_gesture_2_fingers : R.string.edge_gesture_3_fingers;
        return context.getString(
            R.string.edge_gesture_zone_and_direction,
            context.getString(fingerRes),
            context.getString(tapRes));
      }
      return context.getString(
          R.string.edge_gesture_zone_and_direction,
          context.getString(zoneTitleRes()),
          context.getString(tapRes));
    }
    if (isLongSwipe()) {
      return context.getString(direction.title);
    }
    return context.getString(
        R.string.edge_gesture_zone_and_direction,
        context.getString(zoneTitleRes()),
        context.getString(direction.title));
  }

  private int zoneTitleRes() {
    if (edge == Edge.BOTTOM) {
      return switch (zone) {
        case 0 -> R.string.edge_gesture_zone_left;
        case 1 -> R.string.edge_gesture_zone_middle;
        default -> R.string.edge_gesture_zone_right;
      };
    }
    return switch (zone) {
      case 0 -> R.string.edge_gesture_zone_top;
      case 1 -> R.string.edge_gesture_zone_middle;
      default -> R.string.edge_gesture_zone_bottom;
    };
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
