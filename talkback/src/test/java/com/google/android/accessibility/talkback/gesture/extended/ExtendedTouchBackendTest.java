package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.graphics.Path;
import android.os.Looper;
import android.view.ViewConfiguration;
import android.view.Display;
import android.view.MotionEvent;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.focusmanagement.FocusFeedbackMapper;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.gestures.GestureManifold;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi", shadows = ExtendedTouchBackendTest.TouchShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ExtendedTouchBackendTest {
  public static class TestService extends TalkBackService {
    final List<Integer> gestures = new ArrayList<>();
    final List<Long> continuedTouches = new ArrayList<>();
    final List<String> actions = new ArrayList<>();
    final List<Path> scrolls = new ArrayList<>();
    final List<Path> secondScrolls = new ArrayList<>();
    boolean replayScrolls = true;
    @Override public boolean performTwoFingerScroll(Path path, long duration) {
      scrolls.add(path);
      return true;
    }
    @Override public boolean performTwoFingerScroll(Path firstPath, Path secondPath, long duration) {
      scrolls.add(firstPath);
      secondScrolls.add(secondPath);
      return replayScrolls;
    }
    @Override public void performCatalogAction(String action) { actions.add(action); }
    @Override public void onCreate() {}
    @Override public void onContinuedGestureTouchDown(long eventTime) {
      continuedTouches.add(eventTime);
    }
    @Override public boolean onGesture(AccessibilityGestureEvent event) {
      gestures.add(event.getGestureId());
      return true;
    }
  }

  @Implements(TouchInteractionController.class)
  public static class TouchShadow {
    Executor executor;
    TouchInteractionController.Callback callback;
    int state = TouchInteractionController.STATE_TOUCH_INTERACTING;
    int clicks;
    int explorationRequests;
    boolean failClick;
    @Implementation protected void performClick() {
      if (failClick) throw new IllegalStateException();
      clicks++;
    }
    final List<Integer> draggedPointers = new ArrayList<>();
    @Implementation protected void registerCallback(Executor executor, TouchInteractionController.Callback callback) {
      this.executor = executor;
      this.callback = callback;
    }
    @Implementation protected boolean unregisterCallback(TouchInteractionController.Callback callback) {
      this.callback = null;
      return true;
    }
    @Implementation protected int getState() { return state; }
    @Implementation protected void requestDragging(int pointerId) { draggedPointers.add(pointerId); }
    @Implementation protected void requestTouchExploration() { explorationRequests++; }
  }

  private TestService service;
  private ExtendedTouchBackend backend;
  private TouchShadow touch;
  private boolean touchSpeechWasEnabled;

  @Before public void setUp() {
    touchSpeechWasEnabled = FocusFeedbackMapper.isTouchStopsSpeechEnabled();
    service = Robolectric.buildService(TestService.class).create().get();
    touch = Shadow.extract(service.getTouchInteractionController(Display.DEFAULT_DISPLAY));
    backend = new ExtendedTouchBackend(service, () -> { throw new AssertionError("Gesture backend failed"); });
    backend.start();
  }

  @After public void tearDown() {
    backend.stop();
    FocusFeedbackMapper.setTouchStopsSpeech(touchSpeechWasEnabled);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.EARLY_TOUCH_SPEECH, false);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_SINGLE_FINGER_SWIPE, false);
    service.getSharedPreferences("touch-timing-test", 0).edit().clear().commit();
    LatencyExperiments.loadStationaryTouchDelay(service.getSharedPreferences("touch-timing-test", 0));
  }

  @Test public void stationaryFingerRequestsExplorationAfterTheReducedDelay() {
    setStationaryTouchDelay(100);
    motion(MotionEvent.ACTION_DOWN, 700);

    Shadows.shadowOf(Looper.getMainLooper()).idleFor(99, TimeUnit.MILLISECONDS);
    assertEquals(0, touch.explorationRequests);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
    assertEquals(1, touch.explorationRequests);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS);
    assertEquals(1, touch.explorationRequests);
  }

  @Test public void touchExplorationCannotStartWhenAndroidIsClear() {
    touch.state = TouchInteractionController.STATE_CLEAR;
    touch.callback.onStateChanged(touch.state);
    backend.onGestureCompleted(new AccessibilityGestureEvent(
        GestureManifold.GESTURE_TOUCH_EXPLORE, Display.DEFAULT_DISPLAY, List.of()));

    assertEquals(0, touch.explorationRequests);
    touch.state = TouchInteractionController.STATE_TOUCH_INTERACTING;
    touch.callback.onStateChanged(touch.state);
    swipeUp();
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP), service.gestures);
  }

  @Test public void aFullMotionQueueDoesNotStopGestureHandling() {
    requestExplorationWithoutStateCallback();
    for (int i = 0; i <= 128; i++) {
      motion(MotionEvent.ACTION_MOVE, 700);
    }
    motion(MotionEvent.ACTION_UP, 700);
    assertNextSwipeWorks();
  }

  @Test public void aMissingStateCallbackTimesOutWithoutDisablingGestures() {
    requestExplorationWithoutStateCallback();
    motion(MotionEvent.ACTION_MOVE, 700);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(
        ViewConfiguration.getDoubleTapTimeout(), TimeUnit.MILLISECONDS);
    motion(MotionEvent.ACTION_UP, 700);
    assertNextSwipeWorks();
  }

  private void requestExplorationWithoutStateCallback() {
    setStationaryTouchDelay(100);
    motion(MotionEvent.ACTION_DOWN, 700);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
    assertEquals(1, touch.explorationRequests);
  }

  private void assertNextSwipeWorks() {
    assertNotNull(touch.callback);
    swipeUp();
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP), service.gestures);
  }

  @Test public void liftingBeforeTheStationaryDelayKeepsDoubleTapAvailable() {
    setStationaryTouchDelay(200);
    motion(MotionEvent.ACTION_DOWN, 700);
    motion(MotionEvent.ACTION_UP, 700);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(99, TimeUnit.MILLISECONDS);
    assertEquals(0, touch.explorationRequests);
    time += 100;
    motion(MotionEvent.ACTION_DOWN, 700);
    motion(MotionEvent.ACTION_UP, 700);
    assertEquals(1, touch.clicks);
  }

  @Test public void fastDoubleTapIsDispatchedOnSecondUpWithOrWithoutRotation() {
    for (boolean rotation : new boolean[] {false, true}) {
      startFastDoubleTapMode(rotation);
      service.gestures.clear();
      motion(MotionEvent.ACTION_DOWN, 700);
      motion(MotionEvent.ACTION_UP, 700);
      motion(MotionEvent.ACTION_DOWN, 700);
      assertTrue(service.gestures.isEmpty());
      motion(MotionEvent.ACTION_UP, 700);
      assertEquals(List.of(AccessibilityService.GESTURE_DOUBLE_TAP), service.gestures);
      assertEquals(0, touch.clicks);
    }
  }

  private void startFastDoubleTapMode(boolean rotation) {
    backend.stop();
    backend = new ExtendedTouchBackend(
        service, () -> { throw new AssertionError("Gesture backend failed"); }, rotation, false, true);
    backend.start();
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
  }

  @Test public void movingFartherThanTouchSlopCancelsOnlyTheEarlyExplorationRequest() {
    setStationaryTouchDelay(200);
    int slop = ViewConfiguration.get(service).getScaledTouchSlop();
    motion(MotionEvent.ACTION_DOWN, 700);
    motion(MotionEvent.ACTION_MOVE, 700 - slop - 1);

    Shadows.shadowOf(Looper.getMainLooper()).idleFor(99, TimeUnit.MILLISECONDS);
    assertEquals(0, touch.explorationRequests);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
    assertEquals(1, touch.explorationRequests);
  }

  @Test public void stationaryFingerWaitsForTheSelectedDelayEvenWithTinyMoves() {
    int initialRequests = touch.explorationRequests;
    for (int selectedMs : new int[] {200, 350}) {
      touch.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
      setStationaryTouchDelay(selectedMs);
      motion(MotionEvent.ACTION_DOWN, 700);

      time += 155;
      motion(MotionEvent.ACTION_MOVE, 700);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(selectedMs - 1, TimeUnit.MILLISECONDS);
      assertEquals(initialRequests, touch.explorationRequests);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
      assertEquals(++initialRequests, touch.explorationRequests);
    }
  }

  @Test public void missingStationaryDelayKeepsTheExistingTimer() {
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
    int baseline = 100;
    motion(MotionEvent.ACTION_DOWN, 700);

    Shadows.shadowOf(Looper.getMainLooper()).idleFor(baseline - 1, TimeUnit.MILLISECONDS);
    assertEquals(0, touch.explorationRequests);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
    assertEquals(1, touch.explorationRequests);
  }

  private void setStationaryTouchDelay(int delayMs) {
    var preferences = service.getSharedPreferences("touch-timing-test", 0);
    preferences.edit().putInt(LatencyExperiments.STATIONARY_TOUCH_DELAY_KEY, delayMs).commit();
    LatencyExperiments.loadStationaryTouchDelay(preferences);
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
  }

  @Test public void touchClickUsesControllerAndReportsUnavailableStates() {
    assertTrue(backend.performClick());
    assertEquals(1, touch.clicks);
    touch.failClick = true;
    assertFalse(backend.performClick());
    touch.failClick = false;
    touch.state = TouchInteractionController.STATE_DELEGATING;
    assertFalse(backend.performClick());
    touch.state = TouchInteractionController.STATE_TOUCH_INTERACTING;
    backend.stop();
    assertFalse(backend.performClick());
    assertEquals(1, touch.clicks);
  }

  @Test public void callbackExecutorDoesNotPostAnotherMainThreadTask() {
    assertNotNull(touch.executor);
    List<Integer> calls = new ArrayList<>();
    touch.executor.execute(() -> calls.add(1));
    assertEquals(List.of(1), calls);
  }

  @Test public void oneFingerSwipeDispatchesWhenTheFingerLiftsWithoutAnExtraQueue() {
    motion(MotionEvent.ACTION_DOWN, 700);
    for (int step = 1; step <= 6; step++) motion(MotionEvent.ACTION_MOVE, 700 - step * 30);
    assertTrue(service.gestures.isEmpty());
    motion(MotionEvent.ACTION_UP, 520);
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP), service.gestures);
  }

  @Test public void continuedSwipeRequestsSpeechInterruptOnlyWhenBothSettingsAreOn() {
    backend.stop();
    backend = new ExtendedTouchBackend(
        service, () -> { throw new AssertionError("Rotation backend failed"); }, true, false);
    backend.start();
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
    FocusFeedbackMapper.setTouchStopsSpeech(true);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.EARLY_TOUCH_SPEECH, true);

    swipeUp();
    assertTrue(service.continuedTouches.isEmpty());
    motion(MotionEvent.ACTION_DOWN, 700);
    long continuedTouchTime = time;
    assertEquals(List.of(continuedTouchTime), service.continuedTouches);
    assertEquals(1, service.gestures.size());
    finishSwipeUp();

    FocusFeedbackMapper.setTouchStopsSpeech(false);
    swipeUp();
    FocusFeedbackMapper.setTouchStopsSpeech(true);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.EARLY_TOUCH_SPEECH, false);
    swipeUp();
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.EARLY_TOUCH_SPEECH, true);
    touch.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
    swipeUp();

    assertEquals(List.of(continuedTouchTime), service.continuedTouches);
    assertEquals(5, service.gestures.size());
  }

  @Test public void continuedSwipeInterruptsOnFingerDownWithoutRotation() {
    FocusFeedbackMapper.setTouchStopsSpeech(true);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.EARLY_TOUCH_SPEECH, true);
    for (boolean fastDoubleTap : new boolean[] {false, true}) {
      backend.stop();
      backend = new ExtendedTouchBackend(service,
          () -> { throw new AssertionError("Gesture backend failed"); },
          false, !fastDoubleTap, fastDoubleTap);
      backend.start();
      touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
      service.gestures.clear();
      service.continuedTouches.clear();

      swipeUp();
      assertTrue(service.continuedTouches.isEmpty());
      motion(MotionEvent.ACTION_DOWN, 700);
      assertEquals(List.of(time), service.continuedTouches);
      assertEquals(1, service.gestures.size());
      finishSwipeUp();
      assertEquals(2, service.gestures.size());
    }
  }

  private void swipeUp() {
    motion(MotionEvent.ACTION_DOWN, 700);
    finishSwipeUp();
  }

  private void finishSwipeUp() {
    for (int step = 1; step <= 6; step++) {
      motion(MotionEvent.ACTION_MOVE, 700 - step * 30);
    }
    motion(MotionEvent.ACTION_UP, 520);
  }

  @Test public void simpleOneFingerSwipesCompleteInAllDirectionsWhenExtendedGesturesAreOn() {
    verifySimpleOneFingerSwipes();
  }

  @Test public void fastOneFingerSwipesWithRotationKeepAllDirections() {
    backend.stop();
    backend = new ExtendedTouchBackend(
        service, () -> { throw new AssertionError("Rotation backend failed"); }, true, false);
    backend.start();
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_SINGLE_FINGER_SWIPE, true);

    verifySimpleOneFingerSwipes();
  }

  private void verifySimpleOneFingerSwipes() {
    int[] ids = {
      AccessibilityService.GESTURE_SWIPE_UP,
      AccessibilityService.GESTURE_SWIPE_DOWN,
      AccessibilityService.GESTURE_SWIPE_LEFT,
      AccessibilityService.GESTURE_SWIPE_RIGHT
    };
    float[][] destinations = {{400, 520}, {400, 880}, {220, 700}, {580, 700}};
    for (int index = 0; index < ids.length; index++) {
      touch.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
      touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
      motionAt(MotionEvent.ACTION_DOWN, 400, 700);
      for (int step = 1; step <= 6; step++) {
        motionAt(
            MotionEvent.ACTION_MOVE,
            400 + (destinations[index][0] - 400) * step / 6,
            700 + (destinations[index][1] - 700) * step / 6);
      }
      assertEquals(index, service.gestures.size());
      motionAt(MotionEvent.ACTION_UP, destinations[index][0], destinations[index][1]);
      assertEquals(ids[index], service.gestures.get(index).intValue());
    }
    assertEquals(4, service.gestures.size());
  }

  @Test public void oneFingerTwoSegmentGestureStillUsesFullRecognizer() {
    motionAt(MotionEvent.ACTION_DOWN, 400, 700);
    for (int step = 1; step <= 6; step++) {
      motionAt(MotionEvent.ACTION_MOVE, 400, 700 - 30 * step);
    }
    for (int step = 1; step <= 6; step++) {
      motionAt(MotionEvent.ACTION_MOVE, 400, 520 + 30 * step);
    }
    motionAt(MotionEvent.ACTION_UP, 400, 700);
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN), service.gestures);
  }

  @Test public void rotationDispatchesConfigurableGestureWhenEnabled() {
    backend.stop();
    backend = new ExtendedTouchBackend(
        service, () -> { throw new AssertionError("Rotation backend failed"); }, true, false);
    backend.start();
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_SINGLE_FINGER_SWIPE, true);
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
    multiMotion(MotionEvent.ACTION_DOWN, 0, 1);
    multiMotion(MotionEvent.ACTION_MOVE, 0, 1);
    multiMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0, 2);
    multiMotion(MotionEvent.ACTION_MOVE, 25, 2);
    assertEquals(List.of(
        com.google.android.accessibility.talkback.gesture.GestureShortcutMapping.ROTATE_CLOCKWISE),
        service.gestures);
    assertTrue(service.actions.isEmpty());
  }

  private void multiMotion(int action, double degrees, int count) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    double radians = Math.toRadians(degrees);
    float dx = (float) (50 * Math.cos(radians));
    float dy = (float) (50 * Math.sin(radians));
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = i == 0 ? 150 - dx : 150 + dx;
      coords[i].y = i == 0 ? 100 - dy : 100 + dy;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(
        100, time += 20, action, count, properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
    try {
      touch.callback.onMotionEvent(event);
    } finally {
      event.recycle();
    }
  }

  @Test public void stoppedBackendCannotDispatchAnotherGesture() {
    backend.stop();
    backend.onGestureCompleted(new AccessibilityGestureEvent(
      AccessibilityService.GESTURE_SWIPE_UP, Display.DEFAULT_DISPLAY, List.of()));
    assertTrue(service.gestures.isEmpty());
  }

  @Test public void ordinaryTwoFingerSwipesUseNativeDraggingInEveryDirection() {
    float[][] ends = {{400, 300}, {400, 700}, {200, 500}, {600, 500}};
    for (int i = 0; i < ends.length; i++) {
      touch.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
      touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
      dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
      dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
      dragMotion(MotionEvent.ACTION_MOVE, 2, ends[i][0], ends[i][1]);
      assertEquals(i + 1, touch.draggedPointers.size());
      assertEquals(0, touch.draggedPointers.get(i).intValue());
    }
    assertTrue(service.gestures.isEmpty());
    assertTrue(service.scrolls.isEmpty());
  }

  @Test public void normalTwoFingerSwipeCannotDispatchAsAnExtendedGesture() {
    dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
    dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 300);
    backend.onGestureCompleted(new AccessibilityGestureEvent(
        ExtendedGestures.FIRST_ID, Display.DEFAULT_DISPLAY, List.of()));
    assertEquals(List.of(0), touch.draggedPointers);
    assertTrue(service.gestures.isEmpty());
  }

  @Test public void holdingTwoFingersBeforeTurningDispatchesExtendedGestureWithoutDragging() {
    dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
    dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(
        android.view.ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 400);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 500);
    dragMotion(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
    dragMotion(MotionEvent.ACTION_UP, 1, 400, 500);
    assertEquals(List.of(ExtendedGestures.FIRST_ID), service.gestures);
    assertTrue(touch.draggedPointers.isEmpty());
  }

  @Test public void movingBeforeHoldTimeoutStillDragsWhenTheHoldTimerExpires() {
    dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
    dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 480);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(
        android.view.ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 420);
    assertEquals(List.of(0), touch.draggedPointers);
    assertTrue(service.gestures.isEmpty());
  }

  private void dragMotion(int action, int count, float x, float y) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = x + i * 60;
      coords[i].y = y;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, count, properties, coords,
        0, 0, 1, 1, 0, 0, 0, 0);
    try { touch.callback.onMotionEvent(event); } finally { event.recycle(); }
  }

  private long time = 100;
  private void motion(int action, float y) {
    motionAt(action, 400, y);
  }

  private void motionAt(int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, x, y, 0);
    try { touch.callback.onMotionEvent(event); } finally { event.recycle(); }
  }
}
