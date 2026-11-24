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
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("wallet_bar_cell_wallet"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("wallet_bar_cell_coin"));
    assertTrue(ShopeeAccessibilityHelper.isTechnicalDescription("wallet_bar_cell_voucher"));

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

  @Test
  public void cleanLeadingBadgeZero_stripsMallPlaceholderOnly() {
    assertEquals(
        "Điện thoại thông minh Xiaomi POCO X8s",
        ShopeeAccessibilityHelper.cleanLeadingBadgeZero("0Điện thoại thông minh Xiaomi POCO X8s")
            .toString());
    assertEquals(
        "[Chính hãng] Tai nghe Bluetooth",
        ShopeeAccessibilityHelper.cleanLeadingBadgeZero("0[Chính hãng] Tai nghe Bluetooth")
            .toString());
    assertEquals(
        "0500ml",
        ShopeeAccessibilityHelper.cleanLeadingBadgeZero("0500ml").toString());
  }

  @Test
  public void resolveDirectIdLabel_mapsProductAndChatButtons() {
    assertEquals("Chat ngay", ShopeeAccessibilityHelper.resolveDirectIdLabel("buttonProductChatNow"));
    assertEquals(
        "Thêm vào giỏ hàng",
        ShopeeAccessibilityHelper.resolveDirectIdLabel("buttonProductAddCart"));
    assertEquals(
        "Thêm vào giỏ hàng", ShopeeAccessibilityHelper.resolveDirectIdLabel("new_atc_btn"));
    assertEquals(
        "Video sản phẩm", ShopeeAccessibilityHelper.resolveDirectIdLabel("viewPDPVideoPlayer"));
    assertEquals(
        "Video trực tiếp",
        ShopeeAccessibilityHelper.resolveDirectIdLabel("LiveStreamingVideoPlayerView.Touchable"));
    assertEquals(
        "Ảnh bìa sản phẩm",
        ShopeeAccessibilityHelper.resolveDirectIdLabel("imageCover_vn-11134207-81ztc"));
    assertEquals("Quay lại", ShopeeAccessibilityHelper.resolveDirectIdLabel("home_btn"));
    assertEquals(
        "Ảnh đại diện cửa hàng",
        ShopeeAccessibilityHelper.resolveDirectIdLabel("chat_action_bar_avatar"));
    assertEquals("Thêm tiện ích", ShopeeAccessibilityHelper.resolveDirectIdLabel("more_btn"));
    assertEquals(
        "Chọn sản phẩm", ShopeeAccessibilityHelper.resolveDirectIdLabel("product_selection_btn"));
    assertEquals(
        "Biểu tượng cảm xúc và nhãn dán",
        ShopeeAccessibilityHelper.resolveDirectIdLabel("sticker_btn"));
    assertEquals("Gửi tin nhắn", ShopeeAccessibilityHelper.resolveDirectIdLabel("send_btn"));
  }
}
