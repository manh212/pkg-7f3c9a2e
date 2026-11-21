package com.google.android.accessibility.talkback.focusmanagement.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import org.junit.Test;

/**
 * Kiểm thử đơn vị cho chuẩn hóa nhãn động và chính sách lọc nguồn ý định người dùng của Focus
 * Memory v2.
 */
public final class FocusMemoryV2Test {

  @Test
  public void normalizeDynamicLabel_stripsVietnameseChatTimestampsAndDeliveryStates() {
    String before = "Đức Vương, chào bà nhé, Đã nhận lúc 08:28";
    String after = "Đức Vương, chào bà nhé, Đã xem 09:15";

    String normBefore = FocusTargetFingerprint.normalizeDynamicLabel(before);
    String normAfter = FocusTargetFingerprint.normalizeDynamicLabel(after);

    assertEquals(normBefore, normAfter);
    assertFalse(normBefore.contains("08:28"));
    assertFalse(normAfter.contains("09:15"));
    assertTrue(normBefore.contains("đức vương"));
  }

  @Test
  public void normalizeDynamicLabel_stripsSentTimeAndDeliveryState() {
    String msg = "Kệ anh Đã gửi lúc 08:26, Đã xem";
    String norm = FocusTargetFingerprint.normalizeDynamicLabel(msg);

    assertTrue(norm.contains("kệ anh"));
    assertFalse(norm.contains("08:26"));
    assertFalse(norm.contains("đã gửi"));
    assertFalse(norm.contains("đã xem"));
  }

  @Test
  public void isUserIntentionalSource_acceptsNavigationAndTouchRejectsAutoRestore() {
    FocusActionInfo logicalNav =
        new FocusActionInfo.Builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build();
    FocusActionInfo touchExplore =
        new FocusActionInfo.Builder().setSourceAction(FocusActionInfo.TOUCH_EXPLORATION).build();
    FocusActionInfo screenStateChange =
        new FocusActionInfo.Builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build();
    FocusActionInfo ensureOnScreen =
        new FocusActionInfo.Builder().setSourceAction(FocusActionInfo.ENSURE_ON_SCREEN).build();

    assertTrue(ScreenFocusMemoryStore.isUserIntentionalSource(logicalNav));
    assertTrue(ScreenFocusMemoryStore.isUserIntentionalSource(touchExplore));
    assertFalse(ScreenFocusMemoryStore.isUserIntentionalSource(screenStateChange));
    assertFalse(ScreenFocusMemoryStore.isUserIntentionalSource(ensureOnScreen));
    assertFalse(ScreenFocusMemoryStore.isUserIntentionalSource(null));
  }

  @Test
  public void coordinator_cancelsPendingRestoreOnUserInteraction() {
    ScreenFocusMemoryStore store = new ScreenFocusMemoryStore();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(store, 450L);

    assertFalse(coordinator.hasPendingRestore());
    coordinator.cancelOnUserInteraction();
    assertFalse(coordinator.hasPendingRestore());
  }
}
