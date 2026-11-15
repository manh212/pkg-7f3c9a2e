package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.ViewConfiguration;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SimulatedClickTimingTest {
  private final SharedPreferences preferences = RuntimeEnvironment.getApplication()
      .getSharedPreferences("simulated-click-test", Context.MODE_PRIVATE);
  private final EventId doubleTap =
      new EventId(100, Performance.EVENT_TYPE_GESTURE, AccessibilityService.GESTURE_DOUBLE_TAP);

  @After public void reset() {
    preferences.edit().clear().commit();
    LatencyExperiments.loadTouchTiming(preferences);
  }

  @Test public void shorterSettingShortensBothStrokesAtTheExistingGap() {
    preferences.edit().putInt(LatencyExperiments.DOUBLE_TAP_CLICK_DURATION_KEY, 170).commit();
    LatencyExperiments.loadTouchTiming(preferences);
    checkGesture(doubleTap, 0, 65);
  }

  @Test public void longerSettingDelaysTheFirstStrokeAndKeepsBothStrokesValid() {
    int baseline = LatencyExperiments.defaultDoubleTapClickDurationMs();
    preferences.edit().putInt(LatencyExperiments.DOUBLE_TAP_CLICK_DURATION_KEY, baseline + 70).commit();
    LatencyExperiments.loadTouchTiming(preferences);
    checkGesture(doubleTap, 70, ViewConfiguration.getTapTimeout());
  }

  @Test public void settingPreservesOrdinaryClickTimingAndBoundsStoredValues() {
    preferences.edit().putInt(LatencyExperiments.DOUBLE_TAP_CLICK_DURATION_KEY, 999).commit();
    LatencyExperiments.loadTouchTiming(preferences);
    int baseline = ViewConfiguration.getTapTimeout();
    checkGesture(doubleTap,
        LatencyExperiments.maximumDoubleTapClickDurationMs()
            - LatencyExperiments.defaultDoubleTapClickDurationMs(), baseline);
    checkGesture(
        new EventId(100, Performance.EVENT_TYPE_GESTURE, AccessibilityService.GESTURE_SWIPE_UP),
        0,
        baseline);
    checkGesture(null, 0, baseline);
  }

  @Test public void missingDurationKeepsCurrentSystemTapDuration() {
    checkGesture(doubleTap, 0, ViewConfiguration.getTapTimeout());
  }

  private void checkGesture(EventId origin, int startMs, int durationMs) {
    GestureDescription gesture =
        FocusActor.buildFallbackClickGesture(new Rect(0, 0, 100, 100), origin);
    assertEquals(2, gesture.getStrokeCount());
    assertEquals(startMs, gesture.getStroke(0).getStartTime());
    assertEquals(durationMs, gesture.getStroke(0).getDuration());
    assertEquals(startMs + durationMs + 40, gesture.getStroke(1).getStartTime());
    assertEquals(durationMs, gesture.getStroke(1).getDuration());
  }
}
