package com.google.android.accessibility.talkback;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import androidx.annotation.Nullable;
import android.view.MotionEvent;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.talkback.gesture.SyntheticTouches;

/**
 * Tracks whether the framework is currently passing a touch to the app, such as the hold of a
 * double-tap and hold.
 *
 * <p>Ported from SandFly's {@code TouchInteractionMonitor} (commit c4929e605, "Leave focus alone
 * while an app records or has the touch"): only the touch-passed-to-app state is ported, not
 * SandFly's gesture machine — Meridian keeps its own touch-exploration path, and the only other
 * {@link TouchInteractionController} consumer here, the extended-gestures backend, runs only
 * while extended gestures are enabled. {@link
 * com.google.android.accessibility.talkback.actor.FocusManagerInternal} consults {@link
 * #isTouchPassedToApp()} before restoring focus, so a content change while the user holds a
 * control (a voice-message record button, for example) does not place focus elsewhere and speak
 * it into the recording.
 *
 * <p>A future full port of SandFly's {@code TouchInteractionMonitor} would merge into this class
 * rather than replace it: the static state and its getter are the ported contract.
 *
 * <p>One piece of SandFly's touch handling is ported here: a touch Meridian sent itself, such as
 * the swipe of pull to refresh, goes to the app as it is. {@link SyntheticTouches} marks the
 * touch Meridian is about to send, and this monitor hands the next touch down to the app and
 * leaves the rest of that touch alone, so touch exploration does not take it as a gesture.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class TouchInteractionMonitor implements TouchInteractionController.Callback {

  /** Listener notified as the finger moves during single-finger touch exploration. */
  public interface TouchMoveListener {
    void onTouchMove(int rawX, int rawY, long uptimeMs);
  }

  /** Whether a touch is passed to the app, such as the hold of a double-tap and hold. */
  private static volatile boolean touchPassedToApp;

  private static volatile int lastTouchX = Integer.MIN_VALUE;
  private static volatile int lastTouchY = Integer.MIN_VALUE;
  private static volatile long lastTouchUptimeMs = 0L;
  private static volatile boolean exploringTouch = false;
  @Nullable private static volatile TouchMoveListener touchMoveListener;

  /** Whether the touch in progress is one Meridian sent, which goes to the app as it is. */
  private volatile boolean passingSyntheticTouch;

  private final TouchInteractionController controller;

  public TouchInteractionMonitor(AccessibilityService service) {
    controller = service.getTouchInteractionController(Display.DEFAULT_DISPLAY);
    if (controller == null) {
      throw new IllegalStateException("No touch controller");
    }
  }

  /** Starts listening for touch-interaction state changes. */
  public void start() {
    controller.registerCallback(Runnable::run, this);
  }

  /** Stops listening and clears the passed-to-app state. */
  public void stop() {
    try {
      controller.unregisterCallback(this);
    } catch (RuntimeException ignored) {
      // The controller may already be gone when the service goes away.
    }
    passingSyntheticTouch = false;
    touchPassedToApp = false;
    exploringTouch = false;
  }

  public static int getLastTouchX() {
    return lastTouchX;
  }

  public static int getLastTouchY() {
    return lastTouchY;
  }

  public static long getLastTouchUptimeMs() {
    return lastTouchUptimeMs;
  }

  public static void setTouchMoveListener(@Nullable TouchMoveListener listener) {
    touchMoveListener = listener;
  }

  /**
   * Records pointer coordinates passively observed via {@link
   * AccessibilityService#onMotionEvent(MotionEvent)} (API 34+), which fires during normal touch
   * exploration even when {@code FLAG_SEND_MOTION_EVENTS} is off.
   */
  public static void recordObservedMotionEvent(MotionEvent event) {
    if (event == null || touchPassedToApp || event.getPointerCount() != 1) {
      return;
    }
    int action = event.getActionMasked();
    boolean isPositionEvent =
        action == MotionEvent.ACTION_DOWN
            || action == MotionEvent.ACTION_MOVE
            || action == MotionEvent.ACTION_HOVER_ENTER
            || action == MotionEvent.ACTION_HOVER_MOVE;
    if (!isPositionEvent) {
      return;
    }
    int x = Math.round(event.getRawX());
    int y = Math.round(event.getRawY());
    long now = SystemClock.uptimeMillis();
    lastTouchX = x;
    lastTouchY = y;
    lastTouchUptimeMs = now;
    TouchMoveListener listener = touchMoveListener;
    if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE)
        && listener != null) {
      listener.onTouchMove(x, y, now);
    }
  }

  /**
   * Returns whether a touch is passed to the app now, such as the hold of a double-tap and hold.
   */
  public static boolean isTouchPassedToApp() {
    return touchPassedToApp;
  }

  @Override
  public void onStateChanged(int state) {
    touchPassedToApp = state == TouchInteractionController.STATE_DELEGATING;
    exploringTouch = state == TouchInteractionController.STATE_TOUCH_EXPLORING;
  }

  @Override
  public void onMotionEvent(MotionEvent event) {
    // The monitor only tracks the state; motion events are not needed, except for a touch
    // Meridian sent itself, which goes to the app as it is.
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
        && SyntheticTouches.takeIfExpected()) {
      // Meridian's own touch, such as the swipe of pull to refresh: the app gets it as it is.
      passingSyntheticTouch = true;
      try {
        controller.requestDelegating();
      } catch (IllegalStateException ignored) {
        // The touch is not delegated; it is explored as usual.
      }
      return;
    }
    if (passingSyntheticTouch) {
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        passingSyntheticTouch = false;
      }
      return;
    }
    int action = event.getActionMasked();
    if (event.getPointerCount() == 1
        && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
      int x = Math.round(event.getRawX());
      int y = Math.round(event.getRawY());
      long now = SystemClock.uptimeMillis();
      lastTouchX = x;
      lastTouchY = y;
      lastTouchUptimeMs = now;
      TouchMoveListener listener = touchMoveListener;
      if (action == MotionEvent.ACTION_MOVE && exploringTouch && listener != null) {
        listener.onTouchMove(x, y, now);
      }
    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      exploringTouch = false;
    }
  }
}
