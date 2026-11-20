package com.google.android.libraries.accessibility.utils.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class DiagnosticLogCategoryTest {
  @After public void reset() {
    LogUtils.setEnabled(false);
    LogUtils.setSelectedCategory("all");
  }

  @Test public void selectionRoundTrips() {
    for (DiagnosticLogCategory category : DiagnosticLogCategory.values())
      assertEquals(category, DiagnosticLogCategory.fromValue(category.value));
    assertEquals(DiagnosticLogCategory.ALL, DiagnosticLogCategory.fromValue("unknown"));
  }

  @Test public void componentsMapToTheirDiagnosticCategories() {
    assertEquals(DiagnosticLogCategory.FOCUS, DiagnosticLogCategory.forTag("FocusDiagnostics"));
    assertEquals(DiagnosticLogCategory.READING_ORDER, DiagnosticLogCategory.forTag("TraversalTreeCache"));
    assertEquals(DiagnosticLogCategory.READING_ORDER,
        DiagnosticLogCategory.forTag("FocusProcessorForLogicalNavigation"));
    assertEquals(DiagnosticLogCategory.PREPARATION, DiagnosticLogCategory.forTag("NavigationPcmCache"));
    assertEquals(DiagnosticLogCategory.PERFORMANCE, DiagnosticLogCategory.forTag("LatencyDiagnostics"));
    assertEquals(DiagnosticLogCategory.SPEECH, DiagnosticLogCategory.forTag("SpeechControllerImpl"));
    assertEquals(DiagnosticLogCategory.RECOGNITION, DiagnosticLogCategory.forTag("RamblerLive"));
    assertEquals(DiagnosticLogCategory.INPUT, DiagnosticLogCategory.forTag("InputMethodMonitor"));
    assertEquals(DiagnosticLogCategory.SYSTEM, DiagnosticLogCategory.forTag("TalkBackService"));
  }

  @Test public void offModeDoesNotRecordAndAllAcceptsEveryCategory() {
    LogUtils.setSelectedCategory("focus");
    LogUtils.setEnabled(false);
    assertFalse(LogUtils.isCategoryEnabled("focus"));
    LogUtils.setEnabled(true);
    assertTrue(LogUtils.isCategoryEnabled("focus"));
    assertFalse(LogUtils.isCategoryEnabled("performance"));
    LogUtils.setSelectedCategory("all");
    assertTrue(LogUtils.isCategoryEnabled("performance"));
  }
}
