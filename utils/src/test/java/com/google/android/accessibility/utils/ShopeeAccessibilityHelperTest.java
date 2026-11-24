package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShopeeAccessibilityHelperTest {

  @Test
  public void isShopeePackage_matchesRegionalShopeePackagesOnly() {
    assertTrue(ShopeeAccessibilityHelper.isShopeePackage("com.shopee.vn"));
    assertTrue(ShopeeAccessibilityHelper.isShopeePackage("com.shopee.id"));
    assertFalse(ShopeeAccessibilityHelper.isShopeePackage("com.android.chrome"));
    assertFalse(ShopeeAccessibilityHelper.isShopeePackage(null));
  }

  @Test
  public void isTechnicalDescription_detectsShopeeTestIdsAndPreservesRealText() {
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("dd_module_product_card_1"));
    assertTrue(
        ShopeeAccessibilityHelper.isTechnicalDescription(
            "dd_module_item_24185936976_title_3"));
    assertTrue(
        ShopeeAccessibilityHelper.isTechnicalDescription(
            "home_square_icon_shopeefood\n-129.000đ"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("tab_bar_button_notification"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("mini_live_streaming_item_card_0"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("mini_video_item_card_1"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("shopee_live_see_more"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("img-overlay"));

    assertFalse(
        ShopeeAccessibilityHelper.isTechnicalDescription("Loa di động Bose SoundLink Flex 2"));
    assertFalse(ShopeeAccessibilityHelper.isTechnicalDescription("Shopee Mall"));
    assertFalse(ShopeeAccessibilityHelper.isTechnicalDescription("Thông báo"));
    assertFalse(ShopeeAccessibilityHelper.isTechnicalDescription(""));
  }

  @Test
  public void cleanPlaceholderBangs_stripsShopeeIconPlaceholdersWithoutHarmingNormalText() {
    assertEquals(
        "4.9, 27,7k đánh giá",
        ShopeeAccessibilityHelper.cleanPlaceholderBangs("!!!!4.9!27,7k đánh giá!").toString());
    assertEquals(
        "Combo Độc Quyền: Điện Thoại Samsung Galaxy S25…",
        ShopeeAccessibilityHelper.cleanPlaceholderBangs(
                "!!!!Combo Độc Quyền: Điện Thoại Samsung Galaxy S25…")
            .toString());
    assertEquals(
        "Giảm giá cực sốc!",
        ShopeeAccessibilityHelper.cleanPlaceholderBangs("Giảm giá cực sốc!").toString());
  }
}
