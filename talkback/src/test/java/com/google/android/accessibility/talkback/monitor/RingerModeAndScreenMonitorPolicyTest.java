package com.google.android.accessibility.talkback.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RingerModeAndScreenMonitorPolicyTest {

  @Test
  public void blocksTimeAnnouncementWhenDisplayWakesInNonInteractiveAod() {
    assertFalse(
        RingerModeAndScreenMonitor.shouldAnnounceForDisplayOn(
            /* displayOn= */ true,
            /* preDisplayOn= */ false,
            /* interactive= */ false,
            /* alreadyAnnounced= */ false));
  }

  @Test
  public void announcesWhenDisplayTurnsOnWhileInteractive() {
    assertTrue(
        RingerModeAndScreenMonitor.shouldAnnounceForDisplayOn(
            /* displayOn= */ true,
            /* preDisplayOn= */ false,
            /* interactive= */ true,
            /* alreadyAnnounced= */ false));
  }

  @Test
  public void announcesOnScreenInteractiveAfterAodDisplayWasAlreadyOn() {
    assertTrue(
        RingerModeAndScreenMonitor.shouldAnnounceForScreenOn(
            /* displayOn= */ true, /* alreadyAnnounced= */ false));
    assertFalse(
        RingerModeAndScreenMonitor.shouldAnnounceForScreenOn(
            /* displayOn= */ true, /* alreadyAnnounced= */ true));
    assertFalse(
        RingerModeAndScreenMonitor.shouldAnnounceForScreenOn(
            /* displayOn= */ false, /* alreadyAnnounced= */ false));
  }

  @Test
  public void doesNotDoubleAnnounceOnceAlreadySpoken() {
    assertFalse(
        RingerModeAndScreenMonitor.shouldAnnounceForDisplayOn(
            /* displayOn= */ true,
            /* preDisplayOn= */ false,
            /* interactive= */ true,
            /* alreadyAnnounced= */ true));
  }
}
