/*
 * Copyright 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.TouchInteractionMonitor;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionRecord;
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.input.WindowEventInterpreter;
import com.google.android.accessibility.utils.output.LowLatencyAudio;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The focus-history record is posted off the swipe path instead of being built inside it, so
 * these tests pin the two halves of that bargain: the listener that starts speech is still told
 * synchronously, and by the time the main queue runs — ahead of the app's focus event, which
 * still has to come back through the event queue — the record is in the history, matching that
 * event exactly as the synchronous record did.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = FocusManagerInternalTest.TouchShadow.class)
public class FocusManagerInternalTest {

  /** Minimal service for Robolectric to instantiate the collaborators against. */
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

  private TestService service;
  private ScreenStateMonitor screenStateMonitor;
  private AccessibilityFocusActionHistory history;
  private FocusManagerInternal focusManager;
  private final AtomicInteger listenerCalls = new AtomicInteger();

  @Before
  public void setUp() {
    service = Robolectric.setupService(TestService.class);
    FormFactorUtils.initialize(service);
    history = new AccessibilityFocusActionHistory(service);
    FocusFinder focusFinder = new FocusFinder(service);
    screenStateMonitor =
        new ScreenStateMonitor(service, new InputMethodMonitor(service));
    focusManager =
        new FocusManagerInternal(
            service,
            focusFinder,
            screenStateMonitor.state,
            history,
            new AccessibilityFocusMonitor(service, focusFinder, history.reader));
    focusManager.setPipeline(feedback -> true);
    listenerCalls.set(0);
    focusManager.setFocusSetListener(
        (node, info, eventId, actionTime) -> listenerCalls.incrementAndGet());
  }

  /**
   * Drives the screen state stable with a window change that started long ago, so {@link
   * FocusManagerInternal#ensureAccessibilityFocusOnScreen} reaches its focus checks instead of
   * returning on the not-stable-yet guard.
   */
  private void driveStableScreenState() {
    WindowEventInterpreter.EventInterpretation interpretation =
        new WindowEventInterpreter.EventInterpretation();
    interpretation.setMainWindowsChanged(true);
    interpretation.setWindowsStable(true);
    interpretation.setEventStartTime(SystemClock.uptimeMillis() - 10_000);
    screenStateMonitor.handle(interpretation, null);
  }

  private static AccessibilityNodeInfoCompat node() {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName("com.example.chat");
    // A node reports the id of the window it is in; there is no public setter for it.
    AccessibilityWindowInfo window = new AccessibilityWindowInfo();
    shadowOf(window).setId(7);
    shadowOf(info).setAccessibilityWindowInfo(window);
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  private static FocusActionInfo actionInfo() {
    return FocusActionInfo.builder()
        .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
        .build();
  }

  @Test
  public void focusAction_listenerIsToldAtOnce_recordFollowsOnTheQueue() {
    AccessibilityNodeInfoCompat node = node();
    long actionTime = SystemClock.uptimeMillis();

    boolean result = focusManager.performAccessibilityFocusActionInternal(node, actionInfo(), null);

    assertTrue(result);
    assertEquals(1, listenerCalls.get());
    assertNull(history.getLastFocusActionRecord());

    shadowOf(Looper.getMainLooper()).idle();

    FocusActionRecord record = history.getLastFocusActionRecord();
    assertNotNull(record);
    assertEquals(actionTime, record.getActionTime());
    assertEquals(7, record.getFocusedNode().getWindowId());
    assertEquals("com.example.chat", record.getFocusedNode().getPackageName());
  }

  @Test
  public void focusAction_recordMatchesTheAppsFocusEventBeforeAndAfterQueueRuns() {
    AccessibilityNodeInfoCompat node = node();

    focusManager.performAccessibilityFocusActionInternal(node, actionInfo(), null);

    AccessibilityEvent focusedEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    shadowOf(focusedEvent).setSourceNode(node.unwrap());
    // The fast record bridges the gap before the queued full history record exists.
    FocusActionRecord immediate = history.matchFocusActionRecordFromEvent(focusedEvent);
    assertNotNull(immediate);
    assertEquals(FocusActionInfo.LOGICAL_NAVIGATION, immediate.getExtraInfo().sourceAction);
    assertNull(history.getLastFocusActionRecord());

    shadowOf(Looper.getMainLooper()).idle();

    assertNotNull(history.matchFocusActionRecordFromEvent(focusedEvent));
  }

  @Test
  public void ensureFocusOnScreen_restoresFocusWhenNothingRecordsOrHolds() {
    driveStableScreenState();

    assertTrue(focusManager.ensureAccessibilityFocusOnScreen(null));
  }

  @Test
  public void ensureFocusOnScreen_leavesFocusAloneWhileMicrophoneInUse() {
    driveStableScreenState();
    LowLatencyAudio.setMicrophoneInUse(true);
    try {
      assertFalse(focusManager.ensureAccessibilityFocusOnScreen(null));
    } finally {
      LowLatencyAudio.setMicrophoneInUse(false);
    }
  }

  @Test
  public void ensureFocusOnScreen_leavesFocusAloneWhileTouchPassedToApp() {
    driveStableScreenState();
    TouchInteractionMonitor monitor = new TouchInteractionMonitor(service);
    monitor.start();
    try {
      TouchShadow shadow =
          Shadow.extract(service.getTouchInteractionController(Display.DEFAULT_DISPLAY));
      shadow.callback.onStateChanged(TouchInteractionController.STATE_DELEGATING);
      assertFalse(focusManager.ensureAccessibilityFocusOnScreen(null));

      shadow.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
      assertTrue(focusManager.ensureAccessibilityFocusOnScreen(null));
    } finally {
      monitor.stop();
    }
  }
}
