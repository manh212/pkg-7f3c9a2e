package com.google.android.accessibility.talkback.focusmanagement.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import org.junit.Test;

/**
 * Kiểm thử đơn vị cho Focus Memory v2.1: chuẩn hóa nhãn/khóa động, tách primaryToken Nekogram/Zalo,
 * chống nhấp nháy tiêu đề Chrome, và cửa sổ chặn EditText FOCUS_SYNCHRONIZATION.
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
  public void normalizeDynamicLabel_stripsNekogramUnreadMentionsAndMutedState() {
    String first = "Meridian. Đã nhận 10:57. Nguyễn Quyết Thắng. Wow!";
    String second = "Meridian. 2 tin nhắn mới. 1 lượt nhắc. Đã nhận 11:02. Hoàng Nhuần. Ok";

    String tokenFirst =
        FocusTargetFingerprint.extractPrimaryToken(
            FocusTargetFingerprint.normalizeDynamicLabel(first));
    String tokenSecond =
        FocusTargetFingerprint.extractPrimaryToken(
            FocusTargetFingerprint.normalizeDynamicLabel(second));

    assertEquals("meridian", tokenFirst);
    assertEquals("meridian", tokenSecond);
  }

  @Test
  public void semanticScreenKey_normalizesDynamicUnreadCountersInTabLabels() {
    String before = SemanticScreenKey.normalizeTitle("💬Tất cả 21 chat chưa đọc");
    String after = SemanticScreenKey.normalizeTitle("💬Tất cả 22 chat chưa đọc");

    assertEquals(before, after);
    assertFalse(before.contains("21"));
    assertTrue(before.contains("chat chưa đọc"));
  }

  @Test
  public void semanticScreenKey_normalizesHeaderFallbackLabelAndFiltersDynamicNoise() {
    assertEquals("thanh toán", SemanticScreenKey.normalizeHeaderFallbackLabel("Thanh toán"));
    assertEquals(
        "chọn shopee voucher",
        SemanticScreenKey.normalizeHeaderFallbackLabel("Chọn Shopee Voucher"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Quay lại"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Chưa được gắn nhãn"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("150.000đ"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("00:12:34"));
  }

  @Test
  public void semanticScreenKey_detectsBrowserTitleFlickerWithSameSignature() {
    SemanticScreenKey chatMuse =
        new SemanticScreenKey(
            "com.android.chrome",
            "chrome: chat — muse",
            "",
            "hdr:bắt đầu tìm kiếm bằng giọng nói|sel:muse.ai",
            -1);
    SemanticScreenKey bareMuse =
        new SemanticScreenKey(
            "com.android.chrome",
            "chrome: muse",
            "",
            "hdr:bắt đầu tìm kiếm bằng giọng nói|sel:muse.ai",
            -1);

    assertTrue(bareMuse.isLikelyTitleFlickerOf(chatMuse));
    assertFalse(chatMuse.isLikelyTitleFlickerOf(chatMuse));
  }

  @Test
  public void coordinator_suppressesInputFocusSyncWithinGuardWindowAndClearsOnUserTouch() {
    ScreenFocusMemoryStore store = new ScreenFocusMemoryStore();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(store, 900L);
    SemanticScreenKey zaloChat =
        new SemanticScreenKey("com.zing.zalo", "zalo", "", "mode:input", -1);

    coordinator.markRestored(zaloChat, /* windowId= */ 1477, /* nowUptimeMs= */ 10_000L);

    assertTrue(coordinator.shouldSuppressInputFocusSync(1477, 10_031L));
    assertTrue(coordinator.shouldSuppressInputFocusSync(1477, 10_473L));
    assertFalse(coordinator.shouldSuppressInputFocusSync(1477, 10_800L));
    assertFalse(coordinator.shouldSuppressInputFocusSync(1493, 10_031L));

    coordinator.cancelOnUserInteraction();
    assertFalse(coordinator.shouldSuppressInputFocusSync(1477, 10_031L));
  }

  @Test
  public void coordinator_guardsMessengerEditTextHijackWithinSuppressionWindow() {
    ScreenFocusMemoryStore store = new ScreenFocusMemoryStore();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(store, 900L);
    SemanticScreenKey messengerChat =
        new SemanticScreenKey("com.facebook.orca", "messenger", "", "hdr:trung duy", -1);

    coordinator.markRestored(messengerChat, /* windowId= */ 637, /* nowUptimeMs= */ 20_000L);

    assertTrue(coordinator.isGuardedEditTextHijack(messengerChat, 637, 20_473L));
    assertFalse(coordinator.isGuardedEditTextHijack(messengerChat, 637, 20_800L));
    assertFalse(coordinator.isGuardedEditTextHijack(messengerChat, 999, 20_473L));
  }

  @Test
  public void coordinator_guardsTransientTitleFlickerRightAfterRestore() {
    ScreenFocusMemoryStore store = new ScreenFocusMemoryStore();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(store, 900L);
    SemanticScreenKey chatMuse =
        new SemanticScreenKey(
            "com.android.chrome",
            "chrome: chat — muse",
            "",
            "hdr:bắt đầu tìm kiếm bằng giọng nói|sel:muse.ai",
            -1);
    SemanticScreenKey bareMuse =
        new SemanticScreenKey(
            "com.android.chrome",
            "chrome: muse",
            "",
            "hdr:bắt đầu tìm kiếm bằng giọng nói|sel:muse.ai",
            -1);

    coordinator.markRestored(chatMuse, /* windowId= */ 1485, /* nowUptimeMs= */ 20_000L);

    assertTrue(coordinator.isGuardedTitleFlicker(bareMuse, 1485, 20_400L));
    assertFalse(coordinator.isGuardedTitleFlicker(bareMuse, 1485, 22_000L));
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
  public void semanticScreenKey_filtersVolatileChatControlsFromFallbackSignature() {
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Xuống dưới cùng"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Emoji, nhãn dán, và GIF"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Scroll to bottom"));
    assertEquals("", SemanticScreenKey.normalizeHeaderFallbackLabel("Gửi"));
    assertEquals(
        "accessible android chat",
        SemanticScreenKey.normalizeHeaderFallbackLabel("Accessible Android Chat"));
  }

  @Test
  public void fingerprint_rejectsUnlabeledGenericRootContainers() {
    assertTrue(
        FocusTargetFingerprint.isUnlabeledGenericContainer(
            "com.epi:id/root_view", "android.widget.FrameLayout", "", false));
    assertTrue(
        FocusTargetFingerprint.isUnlabeledGenericContainer(
            "", "android.widget.LinearLayout", "", false));
    assertFalse(
        FocusTargetFingerprint.isUnlabeledGenericContainer(
            "com.epi:id/root_view", "android.widget.FrameLayout", "tin nóng", false));
    assertFalse(
        FocusTargetFingerprint.isUnlabeledGenericContainer(
            "com.miui.home:id/icon_icon", "android.view.View", "", false));
    assertFalse(
        FocusTargetFingerprint.isUnlabeledGenericContainer(
            "com.zing.zalo:id/chat_input", "android.widget.EditText", "", true));
  }

  @Test
  public void coordinator_defaultRetryWindowSupportsSlowProgressiveScreens() {
    assertTrue(FocusRestoreCoordinator.DEFAULT_RETRY_WINDOW_MS >= 1500L);
  }
}
