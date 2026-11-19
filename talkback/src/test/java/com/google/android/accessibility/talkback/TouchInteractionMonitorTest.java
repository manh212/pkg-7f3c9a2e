package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
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
import org.robolectric.shadow.api.Shadow;

/**
 * The touch-passed-to-app state ported from SandFly (commit c4929e605) is the only thing {@link
 * TouchInteractionMonitor} tracks: the framework reports the touch as delegating while the user
 * holds a control, and the flag clears as soon as the touch leaves that state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = TouchInteractionMonitorTest.TouchShadow.class)
public class TouchInteractionMonitorTest {

  /** Minimal service for Robolectric to instantiate the monitor against. */
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  /** Stands in for the framework controller, capturing the callback the monitor registers. */
  @Implements(TouchInteractionController.class)
  public static class TouchShadow {
    TouchInteractionController.Callback callback;

    @Implementation
    protected void registerCallback(
        Executor executor, TouchInteractionController.Callback callback) {
      this.callback = callback;
    }

    @Implementation
    protected boolean unregisterCallback(TouchInteractionController.Callback callback) {
      this.callback = null;
      return true;
    }
  }

  private TouchInteractionMonitor monitor;
  private TouchShadow touch;

  @Before
  public void setUp() {
    TestService service = Robolectric.setupService(TestService.class);
    touch = Shadow.extract(service.getTouchInteractionController(Display.DEFAULT_DISPLAY));
    monitor = new TouchInteractionMonitor(service);
    monitor.start();
  }

  @After
  public void tearDown() {
    monitor.stop();
  }

  @Test
  public void delegatingTouchIsPassedToApp() {
    touch.callback.onStateChanged(TouchInteractionController.STATE_DELEGATING);

    assertTrue(TouchInteractionMonitor.isTouchPassedToApp());
  }

  @Test
  public void touchExploringTouchIsNotPassedToApp() {
    touch.callback.onStateChanged(TouchInteractionController.STATE_DELEGATING);
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_EXPLORING);

    assertFalse(TouchInteractionMonitor.isTouchPassedToApp());
  }

  @Test
  public void stopClearsPassedToAppAndUnregisters() {
    touch.callback.onStateChanged(TouchInteractionController.STATE_DELEGATING);
    assertTrue(TouchInteractionMonitor.isTouchPassedToApp());

    monitor.stop();

    assertFalse(TouchInteractionMonitor.isTouchPassedToApp());
    assertNull(touch.callback);
  }
}
