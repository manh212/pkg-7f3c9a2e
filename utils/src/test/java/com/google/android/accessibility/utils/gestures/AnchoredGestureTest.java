package com.google.android.accessibility.utils.gestures;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.google.android.accessibility.utils.Performance;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AnchoredGestureTest {
  private final List<Integer> completed = new ArrayList<>();
  private GestureMatcher matcher;
  private long time;
  private float secondX;
  private float secondY;

  @Before public void setUp() { completed.clear(); time = 100; }

  private void create(int direction) {
    Context context = RuntimeEnvironment.getApplication();
    matcher = new AnchoredGesture(context, 11005 + direction, direction,
        (id, state, event) -> { if (state == GestureMatcher.STATE_GESTURE_COMPLETED) completed.add(id); }, event -> {});
  }

  @Test public void directionForMapsAllFiveIds() {
    assertEquals(AnchoredGesture.UP, AnchoredGesture.directionFor(11005));
    assertEquals(AnchoredGesture.DOWN, AnchoredGesture.directionFor(11006));
    assertEquals(AnchoredGesture.LEFT, AnchoredGesture.directionFor(11007));
    assertEquals(AnchoredGesture.RIGHT, AnchoredGesture.directionFor(11008));
    assertEquals(AnchoredGesture.TAP, AnchoredGesture.directionFor(11009));
    try {
      AnchoredGesture.directionFor(11010);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void holdThenSwipeFiresMatchingDirectionOnMove() {
    for (int direction = 0; direction < 4; direction++) {
      create(direction);
      begin();
      hold();
      swipe(direction);
      assertEquals("direction " + direction, List.of(11005 + direction), completed);
      completed.clear();
    }
  }

  @Test public void holdThenSwipeDoesNotFireOtherDirections() {
    for (int direction = 0; direction < 4; direction++) {
      for (int other = 0; other < 4; other++) {
        if (other == direction) continue;
        create(other);
        begin();
        hold();
        swipe(direction);
        assertTrue("swipe " + direction + " on matcher " + other, completed.isEmpty());
        assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
      }
    }
  }

  @Test public void swipeWithoutHoldDoesNotFire() {
    create(AnchoredGesture.RIGHT);
    begin();
    swipe(AnchoredGesture.RIGHT);
    assertTrue(completed.isEmpty());
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
  }

  @Test public void holdThenTapFiresTapOnly() {
    create(AnchoredGesture.TAP);
    begin();
    hold();
    liftSecond();
    assertEquals(List.of(11009), completed);
    event(MotionEvent.ACTION_UP, new int[] {7}, new float[] {400}, new float[] {400});
    completed.clear();
    create(AnchoredGesture.RIGHT);
    begin();
    hold();
    finish();
    assertTrue(completed.isEmpty());
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
  }

  @Test public void tapWithoutHoldDoesNotFire() {
    create(AnchoredGesture.TAP);
    begin();
    time += ViewConfiguration.getLongPressTimeout() / 2;
    finish();
    assertTrue(completed.isEmpty());
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
  }

  @Test public void twoFingerScrollIsUnaffected() {
    create(AnchoredGesture.RIGHT);
    begin();
    moveBoth(400, 425);
    moveBoth(400, 450);
    assertTrue(completed.isEmpty());
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
  }

  @Test public void anchorMovingAfterHoldCancels() {
    create(AnchoredGesture.RIGHT);
    begin();
    hold();
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {430, 490}, new float[] {400, 400});
    assertTrue(completed.isEmpty());
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
  }

  @Test public void secondFingerAddedLateStillArms() {
    create(AnchoredGesture.RIGHT);
    event(MotionEvent.ACTION_DOWN, new int[] {7}, new float[] {400}, new float[] {400});
    time += 1000;
    pointerDown();
    hold();
    swipe(AnchoredGesture.RIGHT);
    assertEquals(List.of(11008), completed);
  }

  @Test public void rejectsThirdFinger() {
    create(AnchoredGesture.RIGHT);
    begin();
    hold();
    event(MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2, 4},
        new float[] {400, 490, 550}, new float[] {400, 400, 400});
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
    assertTrue(completed.isEmpty());
  }

  private void begin() {
    event(MotionEvent.ACTION_DOWN, new int[] {7}, new float[] {400}, new float[] {400});
    pointerDown();
  }
  private void pointerDown() {
    secondX = 490;
    secondY = 400;
    event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {400, 490}, new float[] {400, 400});
  }
  private void hold() {
    time += ViewConfiguration.getLongPressTimeout();
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {400, 490}, new float[] {400, 400});
  }
  private void swipe(int direction) {
    for (int step = 1; step <= 5; step++) {
      moveSecond(490 + dx(direction) * step * 25, 400 + dy(direction) * step * 25);
    }
  }
  private void moveSecond(float x, float y) {
    secondX = x;
    secondY = y;
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {400, x}, new float[] {400, y});
  }
  private void moveBoth(float x, float y) {
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {x, x + 90}, new float[] {y, y});
  }
  private void liftSecond() {
    event(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {400, secondX}, new float[] {400, secondY});
  }
  private void finish() {
    liftSecond();
    event(MotionEvent.ACTION_UP, new int[] {7}, new float[] {400}, new float[] {400});
  }
  private void event(int action, int[] ids, float[] xs, float[] ys) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
    for (int index = 0; index < ids.length; index++) {
      properties[index] = new MotionEvent.PointerProperties();
      properties[index].id = ids[index];
      properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[index] = new MotionEvent.PointerCoords();
      coordinates[index].x = xs[index]; coordinates[index].y = ys[index]; coordinates[index].pressure = 1;
    }
    MotionEvent event = MotionEvent.obtain(100, time += 30, action, ids.length, properties, coordinates,
        0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    try { matcher.onMotionEvent(Performance.EVENT_ID_UNTRACKED, event); } finally { event.recycle(); }
  }
  private int dx(int direction) { return direction == 2 ? -1 : direction == 3 ? 1 : 0; }
  private int dy(int direction) { return direction == 0 ? -1 : direction == 1 ? 1 : 0; }
}
