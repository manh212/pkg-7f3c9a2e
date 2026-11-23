package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import java.util.EnumSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EdgeTapTrackerTest {
  private static final EdgeGestureTracker.Area AREA =
      new EdgeGestureTracker.Area(0, 24, 360, 800, 736, 24, 32);

  @Test
  public void recognizesFourFingerSingleDoubleAndTripleTapsOnEveryEdge() {
    EnumSet<EdgeGesture> assigned = EnumSet.allOf(EdgeGesture.class);
    for (EdgeGesture.Edge edge : EdgeGesture.Edge.values()) {
      for (int taps = 1; taps <= 3; taps++) {
        EdgeTapTracker tracker = new EdgeTapTracker(12);
        for (int tap = 0; tap < taps; tap++) {
          long down = 1000L + 150L * tap;
          MotionEvent first = event(edge, down, down, MotionEvent.ACTION_DOWN, 1);
          assertTrue(tracker.onDown(AREA, first, assigned));
          first.recycle();
          for (int pointers = 2; pointers <= 4; pointers++) {
            MotionEvent added = event(edge, down, down + pointers * 10,
                MotionEvent.ACTION_POINTER_DOWN | ((pointers - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT), pointers);
            assertTrue(tracker.onPointerDown(AREA, added, assigned));
            added.recycle();
          }
          for (int pointers = 4; pointers >= 2; pointers--) {
            MotionEvent lifted = event(edge, down, down + 80 - pointers * 5,
                MotionEvent.ACTION_POINTER_UP | ((pointers - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT), pointers);
            tracker.onPointerUp(lifted);
            lifted.recycle();
          }
          MotionEvent last = event(edge, down, down + 100, MotionEvent.ACTION_UP, 1);
          EdgeTapTracker.Stroke stroke = tracker.onUp(last);
          last.recycle();
          assertNotNull(stroke);
          assertEquals(4, stroke.fingers());
          assertEquals(tap + 1, tracker.recordTap(stroke, down + 100));
        }
        assertEquals(EdgeGesture.findTap(edge, -1, 4, taps), tracker.consumePending(assigned));
      }
    }
  }

  private static MotionEvent event(EdgeGesture.Edge edge, long down, long time, int action, int count) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[i] = new MotionEvent.PointerCoords();
      coordinates[i].x = edge == EdgeGesture.Edge.LEFT ? 4 : edge == EdgeGesture.Edge.RIGHT ? 356 : 60 + i * 60;
      coordinates[i].y = edge == EdgeGesture.Edge.BOTTOM ? 728 : 140 + i * 60;
      coordinates[i].pressure = 1;
      coordinates[i].size = 1;
    }
    return MotionEvent.obtain(down, time, action, count, properties, coordinates,
        0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
  }
}
