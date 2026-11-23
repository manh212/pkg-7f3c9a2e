package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import org.junit.Test;

public class EdgeGestureTrackerTest {
  private static final EdgeGestureTracker.Area AREA =
      new EdgeGestureTracker.Area(0, 24, 360, 800, 736, 24, 32);

  @Test
  public void detectsAllTwentyThreeGestures() {
    EnumSet<EdgeGesture> matches = EnumSet.noneOf(EdgeGesture.class);
    for (int y : new int[] {140, 380, 620}) {
      add(matches, 4, y, 44, y);
      add(matches, 4, y, 4, y - 40);
      add(matches, 4, y, 4, y + 40);
      add(matches, 356, y, 316, y);
      add(matches, 356, y, 356, y - 40);
      add(matches, 356, y, 356, y + 40);
    }
    for (int x : new int[] {60, 180, 300}) {
      add(matches, x, 728, x, 688);
    }
    add(matches, 60, 728, 138, 728);
    add(matches, 300, 728, 222, 728);
    EnumSet<EdgeGesture> allSwipes = EnumSet.noneOf(EdgeGesture.class);
    for (EdgeGesture g : EdgeGesture.values()) {
      if (g.isSwipe()) allSwipes.add(g);
    }
    assertEquals(23, allSwipes.size());
    assertEquals(allSwipes, matches);
  }

  @Test
  public void definesAllFiftyFourEdgeTapGesturesWithStableIds() {
    int taps = 0;
    for (EdgeGesture g : EdgeGesture.values()) {
      assertEquals(g, EdgeGesture.fromId(g.id()));
      assertEquals(g, EdgeGesture.fromKey(g.key));
      if (g.isTap()) {
        taps++;
        assertEquals(g, EdgeGesture.findTap(g.edge, g.zone, g.fingers, g.taps));
      }
    }
    assertEquals(54, taps);
    assertEquals(77, EdgeGesture.values().length);
    assertEquals(12001, EdgeGesture.LEFT_TOP_IN.id());
    assertEquals(12068, EdgeGesture.BOTTOM_3F_TAP_3.id());
  }

  @Test
  public void cornerResolutionPicksCloserEdgeAndClampsZones() {
    EdgeGestureTracker leftCorner = EdgeGestureTracker.start(AREA, 3, 725);
    assertNotNull(leftCorner);
    assertEquals(EdgeGesture.Edge.LEFT, leftCorner.edge);
    assertEquals(2, leftCorner.zone);
    assertEquals(0, EdgeGestureTracker.zone(-10, 0, 100));
    assertEquals(2, EdgeGestureTracker.zone(200, 0, 100));
    assertEquals(1, EdgeGestureTracker.zone(50, 100, 100));
  }

  @Test
  public void continuousVerticalEdgeSwipeStepsRepeatedlyForVolumeOrRate() {
    EdgeGestureTracker tracker = EdgeGestureTracker.start(AREA, 356, 620);
    assertNotNull(tracker);
    EdgeGesture gesture = EdgeGesture.RIGHT_BOTTOM_UP;
    assertFalse(tracker.takeStep(gesture, 356, 595, 100, false));
    assertTrue(tracker.takeStep(gesture, 356, 588, 110, false));
    assertFalse(tracker.takeStep(gesture, 356, 560, 130, false));
    assertTrue(tracker.takeStep(gesture, 356, 560, 180, false));
    assertTrue(tracker.takeStep(gesture, 356, 535, 250, false));
  }

  @Test
  public void outsideInsetsAndOrdinaryTouchesDoNotMatch() {
    assertNull(EdgeGestureTracker.start(AREA, 90, 800));
    assertNull(EdgeGestureTracker.start(AREA, 90, 12));
    assertNull(EdgeGestureTracker.start(AREA, 180, 420));
    assertNull(match(4, 140, 25, 140));
    assertNull(match(4, 140, 68, 200));
    assertNull(match(90, 728, 150, 728));
  }

  @Test
  public void detectsBottomSwipesFromTheLastScreenRow() {
    assertEquals(EdgeGesture.BOTTOM_SWIPE_RIGHT, match(60, 799, 138, 799));
    assertEquals(EdgeGesture.BOTTOM_MIDDLE_UP, match(180, 799, 180, 759));
  }

  @Test
  public void angledPathsDoNotBecomeStraightWhenTheyReturnToTheStartAxis() {
    EdgeGestureTracker tracker = EdgeGestureTracker.start(AREA, 60, 728);
    assertNotNull(tracker);
    tracker.observe(82, 680);
    tracker.observe(160, 728);
    assertNull(tracker.match(160, 728));
  }

  @Test
  public void continuousSwipeStepsGrowWithDistanceAndDoNotRepeatOnRelease() {
    EdgeGestureTracker tracker = EdgeGestureTracker.start(AREA, 60, 728);
    assertNotNull(tracker);
    EdgeGesture gesture = EdgeGesture.BOTTOM_SWIPE_RIGHT;
    assertFalse(tracker.takeStep(gesture, 123, 728, 100, false));
    assertTrue(tracker.takeStep(gesture, 124, 728, 110, false));
    assertFalse(tracker.takeStep(gesture, 156, 728, 125, false));
    assertTrue(tracker.takeStep(gesture, 156, 728, 180, false));
    assertFalse(tracker.takeStep(gesture, 156, 728, 210, true));
    assertTrue(tracker.takeStep(gesture, 188, 728, 220, true));
  }

  private static void add(
      EnumSet<EdgeGesture> matches, float startX, float startY, float x, float y) {
    EdgeGesture gesture = match(startX, startY, x, y);
    assertNotNull(gesture);
    assertEquals(gesture, EdgeGesture.fromId(gesture.id()));
    assertEquals(gesture, EdgeGesture.fromKey(gesture.key));
    assertTrue(matches.add(gesture));
  }

  private static EdgeGesture match(float startX, float startY, float x, float y) {
    EdgeGestureTracker tracker = EdgeGestureTracker.start(AREA, startX, startY);
    assertNotNull(tracker);
    tracker.observe(x, y);
    return tracker.match(x, y);
  }
}
