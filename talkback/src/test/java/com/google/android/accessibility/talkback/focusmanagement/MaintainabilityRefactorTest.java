package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.SystemClock;
import com.google.android.accessibility.talkback.focusmanagement.memory.FocusRestoreCoordinator;
import com.google.android.accessibility.talkback.focusmanagement.memory.ScreenFocusMemoryStore;
import com.google.android.accessibility.talkback.focusmanagement.memory.WindowFocusRestoreOrchestrator;
import com.google.android.accessibility.talkback.preference.base.DiagnosticsPreferenceActions;
import com.google.android.accessibility.utils.input.WindowEventInterpreter;
import com.google.android.accessibility.utils.input.WindowTransitionPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for the maintainability refactoring batch (policy, orchestrator, diagnostics). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MaintainabilityRefactorTest {

  @After
  public void tearDown() {
    WindowTransitionPolicy.setFocusNewWindows(true);
    WindowTransitionPolicy.setReduceWindowDelay(false);
    WindowTransitionPolicy.setWindowChangeDelayMs(550L);
    DiagnosticsPreferenceActions.setLastLogClearedUptimeMsForTesting(0L);
  }

  @Test
  public void windowTransitionPolicy_syncsAcrossProcessorsAndReportsDiagnostics() {
    FocusProcessorForScreenStateChange.setFocusNewWindows(false);
    assertFalse(WindowTransitionPolicy.focusesNewWindows());
    assertFalse(FocusProcessorForScreenStateChange.focusesNewWindows());

    WindowEventInterpreter.setReduceWindowDelay(true);
    assertTrue(WindowTransitionPolicy.isReduceWindowDelay());

    WindowTransitionPolicy.setWindowChangeDelayMs(180L);
    assertEquals(180L, WindowTransitionPolicy.getWindowChangeDelayMs());

    String summary = WindowTransitionPolicy.diagnosticsSummary();
    assertTrue(summary.contains("focusNewWindows=false"));
    assertTrue(summary.contains("reduceWindowDelay=true"));
    assertTrue(summary.contains("windowChangeDelayMs=180"));
  }

  @Test
  public void windowFocusRestoreOrchestrator_respectsArmedAndFastWindowGuards() {
    WindowFocusRestoreOrchestrator orchestrator = new WindowFocusRestoreOrchestrator();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(new ScreenFocusMemoryStore());
    AtomicInteger runs = new AtomicInteger(0);

    // Not armed -> never schedules.
    assertFalse(
        orchestrator.scheduleFastRestoreRetry(
            false, 1_000L, 1_050L, coordinator, runs::incrementAndGet));

    // Past the 150ms fast-transition window -> never schedules.
    assertFalse(
        orchestrator.scheduleFastRestoreRetry(
            true,
            1_000L,
            1_000L + WindowFocusRestoreOrchestrator.FAST_TRANSITION_WINDOW_MS,
            coordinator,
            runs::incrementAndGet));

    // Armed within window -> schedules and can be cancelled cleanly.
    assertTrue(
        orchestrator.scheduleFastRestoreRetry(
            true, 1_000L, 1_050L, coordinator, runs::incrementAndGet));
    orchestrator.cancelPendingRetry();
    assertEquals(0, runs.get());
  }

  @Test
  public void diagnosticsPreferenceActions_tracksRecentLogClearWindow() {
    DiagnosticsPreferenceActions.setLastLogClearedUptimeMsForTesting(0L);
    assertFalse(DiagnosticsPreferenceActions.wasLogClearedRecently());

    long now = SystemClock.uptimeMillis();
    DiagnosticsPreferenceActions.setLastLogClearedUptimeMsForTesting(now);
    assertTrue(DiagnosticsPreferenceActions.wasLogClearedRecently());

    DiagnosticsPreferenceActions.setLastLogClearedUptimeMsForTesting(Math.max(1L, now - 35_000L));
    if (now > 35_000L) {
      assertFalse(DiagnosticsPreferenceActions.wasLogClearedRecently());
    }
  }

  @Test
  public void navigationResult_validatesTypesAndLinkIndex() {
    NavigationResult empty = NavigationResult.create((androidx.core.view.accessibility.AccessibilityNodeInfoCompat) null);
    assertTrue(empty.isEmpty());
    assertFalse(empty.isFocusAvailable());

    NavigationResult edge = NavigationResult.create(NavigationResult.Type.REACH_EDGE);
    assertTrue(edge.shouldSkipNavigation());
    assertFalse(edge.isFocusAvailable());
    edge.setLinkIndex(3);
    assertEquals(3, edge.getLinkIndex());

    try {
      NavigationResult.create(NavigationResult.Type.NATIVE_ELEMENT);
      fail("Expected IllegalArgumentException for NATIVE_ELEMENT without node");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }
}
