package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Rect;
import android.view.ViewConfiguration;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SimulatedClickTimingTest {
  private final EventId doubleTap =
      new EventId(100, Performance.EVENT_TYPE_GESTURE, AccessibilityService.GESTURE_DOUBLE_TAP);

  @After public void reset() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_DOUBLE_TAP, false);
  }

  @Test public void fastDoubleTapShortensTheFallbackAndStartsImmediately() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_DOUBLE_TAP, true);
    checkGesture(doubleTap, Math.min(40, ViewConfiguration.getTapTimeout()));
  }

  @Test public void fastModeKeepsOrdinaryClickTiming() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_DOUBLE_TAP, true);
    int baseline = ViewConfiguration.getTapTimeout();
    checkGesture(
        new EventId(100, Performance.EVENT_TYPE_GESTURE, AccessibilityService.GESTURE_SWIPE_UP),
        baseline);
    checkGesture(null, baseline);
  }

  @Test public void fastModeOffKeepsCurrentSystemTapDuration() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FAST_DOUBLE_TAP, false);
    checkGesture(doubleTap, ViewConfiguration.getTapTimeout());
  }

  private void checkGesture(EventId origin, int durationMs) {
    GestureDescription gesture =
        FocusActor.buildFallbackClickGesture(new Rect(0, 0, 100, 100), origin);
    assertEquals(2, gesture.getStrokeCount());
    assertEquals(0, gesture.getStroke(0).getStartTime());
    assertEquals(durationMs, gesture.getStroke(0).getDuration());
    assertEquals(durationMs + 40, gesture.getStroke(1).getStartTime());
    assertEquals(durationMs, gesture.getStroke(1).getDuration());
  }
}
