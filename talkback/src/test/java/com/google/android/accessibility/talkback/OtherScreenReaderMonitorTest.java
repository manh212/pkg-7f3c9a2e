package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import org.junit.Test;

public class OtherScreenReaderMonitorTest {
  private static final String OWN = "io.github.aaron_gh.meridian";
  private static final int SPOKEN = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
  private static final int EXPLORE =
      AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;

  @Test
  public void yieldsForAnotherScreenReaderRequestingTouchExploration() {
    assertTrue(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", SPOKEN, EXPLORE, 0));
  }

  @Test
  public void yieldsForKnownScreenReaderServicesEvenWithoutActiveTouchFlag() {
    assertTrue(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "com.nirenr.talkman", "com.nirenr.talkman.TalkManAccessibilityService",
            AccessibilityServiceInfo.FEEDBACK_GENERIC, 0, 0));
    assertTrue(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "com.google.android.marvin.talkback",
            "com.google.android.marvin.talkback.TalkBackService",
            SPOKEN,
            0,
            0));
    assertTrue(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "com.samsung.android.accessibility.talkback",
            "com.samsung.android.accessibility.talkback.TalkBackService",
            SPOKEN,
            0,
            0));
  }

  @Test
  public void ignoresCompanionAccessibilityServicesAndStaticCapabilityOnly() {
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "another.reader",
            "another.reader.HelperService",
            SPOKEN,
            0,
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "com.google.android.marvin.talkback",
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService",
            SPOKEN,
            0,
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "com.google.android.marvin.talkback",
            "com.google.android.accessibility.accessibilitymenu.AccessibilityMenuService",
            SPOKEN,
            0,
            0));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN,
            "com.samsung.android.accessibility.talkback",
            "com.samsung.android.accessibility.talkback.AssistantHelperService",
            SPOKEN,
            0,
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION));
  }

  @Test
  public void ignoresOwnServiceAndUnrelatedAccessibilityServices() {
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(OWN, OWN, SPOKEN, EXPLORE, 0));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", AccessibilityServiceInfo.FEEDBACK_GENERIC, EXPLORE, 0));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", SPOKEN, 0, 0));
  }
}
