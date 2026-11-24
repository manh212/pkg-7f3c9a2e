package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TikTokAccessibilityHelperTest {

  @Test
  public void isTikTokPackage_matchesOfficialTikTokPackagesOnly() {
    assertTrue(TikTokAccessibilityHelper.isTikTokPackage("com.ss.android.ugc.trill"));
    assertTrue(TikTokAccessibilityHelper.isTikTokPackage("com.zhiliaoapp.musically"));
    assertTrue(TikTokAccessibilityHelper.isTikTokPackage("com.ss.android.ugc.aweme"));
    assertFalse(TikTokAccessibilityHelper.isTikTokPackage("com.zing.zalo"));
    assertFalse(TikTokAccessibilityHelper.isTikTokPackage(null));
  }

  @Test
  public void isTechnicalDescription_detectsStoryRingAndBadgeNoise() {
    assertTrue(
        TikTokAccessibilityHelper.isTechnicalDescription("storyringhas_unconsumed_story_false"));
    assertTrue(TikTokAccessibilityHelper.isTechnicalDescription("storybadgenone_trueicon"));
    assertFalse(TikTokAccessibilityHelper.isTechnicalDescription("Hộp thư"));
    assertFalse(TikTokAccessibilityHelper.isTechnicalDescription(""));
  }

  @Test
  public void isRawResourceIdDescription_detectsAndroidResourceIds() {
    assertTrue(TikTokAccessibilityHelper.isRawResourceIdDescription("@2131829637"));
    assertTrue(TikTokAccessibilityHelper.isRawResourceIdDescription("@2131843423"));
    assertFalse(TikTokAccessibilityHelper.isRawResourceIdDescription("@manhmanh414"));
    assertFalse(TikTokAccessibilityHelper.isRawResourceIdDescription("Tìm kiếm"));
  }

  @Test
  public void cleanTikTokText_removesObjectReplacementChars() {
    assertEquals(
        "8.575₫ x 12 tháng với PayLater Tối đa 30 triệu ₫",
        TikTokAccessibilityHelper.cleanTikTokText(
                "8.575₫ x 12 tháng với PayLater\uFFFC\uFFFC\uFFFCTối đa 30 triệu ₫ ")
            .toString());
    assertEquals(
        "Đảm bảo giao vào 26 tháng 11",
        TikTokAccessibilityHelper.cleanTikTokText("\uFFFC Đảm bảo giao vào 26 tháng 11 ")
            .toString());
  }

  @Test
  public void cleanTikTokState_translatesLayoutStateAndStripsBrokenFavoriteZero() {
    assertEquals("tắt", TikTokAccessibilityHelper.cleanTikTokState("layoutOff").toString());
    assertEquals(
        "Chưa thêm video vào mục Yêu thích của bạn.",
        TikTokAccessibilityHelper.cleanTikTokState(
                "Chưa thêm video vào mục Yêu thích của bạn. Có Lưu người đã thêm video này vào mục Yêu thích.")
            .toString());
    assertNull(TikTokAccessibilityHelper.cleanTikTokState("Đã tạm dừng"));
  }
}
