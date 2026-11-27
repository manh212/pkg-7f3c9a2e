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

  @Test
  public void lockscreenClockRepeat_exactTimeWithinWindow() {
    assertTrue(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:07", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 3999L, 14, 7));
  }

  @Test
  public void lockscreenClockRepeat_outsideWindow() {
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:07", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 4001L, 14, 7));
  }

  @Test
  public void lockscreenClockRepeat_noAnnouncement() {
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:07", /* announcementUptimeMs= */ -1L, /* nowUptimeMs= */ 1000L, 14, 7));
  }

  @Test
  public void lockscreenClockRepeat_twelveHourForms() {
    assertTrue(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "7:07", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 19, 7));
    assertTrue(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "7:07 PM", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 19, 7));
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "7:07 AM", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 19, 7));
  }

  @Test
  public void lockscreenClockRepeat_minuteBoundaryOnly() {
    assertTrue(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:06", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 14, 7));
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:08", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 14, 7));
  }

  @Test
  public void lockscreenClockRepeat_nonClockText() {
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "Màn hình khoá", /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 14, 7));
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            "14:07, Tháng 11 27",
            /* announcementUptimeMs= */ 1000L,
            /* nowUptimeMs= */ 2000L,
            14,
            7));
    assertFalse(
        RingerModeAndScreenMonitor.isLockscreenClockRepeat(
            null, /* announcementUptimeMs= */ 1000L, /* nowUptimeMs= */ 2000L, 14, 7));
  }
}
