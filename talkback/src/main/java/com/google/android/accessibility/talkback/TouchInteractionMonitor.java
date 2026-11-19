package com.google.android.accessibility.talkback;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.os.Build;
import android.view.Display;
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

  /** Whether a touch is passed to the app, such as the hold of a double-tap and hold. */
  private static volatile boolean touchPassedToApp;

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
  }
}
