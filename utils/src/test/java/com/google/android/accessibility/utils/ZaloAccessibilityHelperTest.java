/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ZaloAccessibilityHelper}. */
@RunWith(JUnit4.class)
public final class ZaloAccessibilityHelperTest {

  @Test
  public void isZaloPackage_matchesOnlyZalo() {
    assertTrue(ZaloAccessibilityHelper.isZaloPackage("com.zing.zalo"));
    assertFalse(ZaloAccessibilityHelper.isZaloPackage("com.shopee.vn"));
    assertFalse(ZaloAccessibilityHelper.isZaloPackage(null));
  }

  @Test
  public void sanitizeZaloText_stripsNullCharAndEdgeNewlines() {
    assertEquals(
        "Cậu: Tối ngày 15",
        ZaloAccessibilityHelper.sanitizeZaloText("Cậu\0: Tối ngày 15\n"));
    assertEquals("oki oki", ZaloAccessibilityHelper.sanitizeZaloText("\noki oki\n"));
  }

  @Test
  public void formatMainTabLabel_handlesBadgesAndCounts() {
    assertEquals(
        "Tường nhà, có tin mới",
        ZaloAccessibilityHelper.formatMainTabLabel("Tường nhà", false, "N"));
    assertEquals(
        "Tin nhắn, 3 thông báo mới",
        ZaloAccessibilityHelper.formatMainTabLabel("Tin nhắn", false, "3"));
    assertEquals(
        "Khám phá, có thông báo mới",
        ZaloAccessibilityHelper.formatMainTabLabel("Khám phá", true, null));
    assertEquals("Danh bạ", ZaloAccessibilityHelper.formatMainTabLabel("Danh bạ", false, null));
  }

  @Test
  public void formatNotificationButtonLabel_handlesCount() {
    assertEquals("Thông báo, 4 thông báo mới", ZaloAccessibilityHelper.formatNotificationButtonLabel("4"));
    assertEquals("Thông báo", ZaloAccessibilityHelper.formatNotificationButtonLabel(null));
  }

  @Test
  public void formatCallActionLabel_distinguishesActiveAndIncomingCalls() {
    assertEquals("Loa ngoài", ZaloAccessibilityHelper.formatCallActionLabel("Change speaker", "Loa"));
    assertEquals("Bật hoặc tắt mic", ZaloAccessibilityHelper.formatCallActionLabel("Mute record", "Mic"));
    assertEquals("Trả lời cuộc gọi", ZaloAccessibilityHelper.formatCallActionLabel("Accept call", "Trả lời"));
    assertEquals("Từ chối cuộc gọi", ZaloAccessibilityHelper.formatCallActionLabel("End call", "Từ chối"));
    assertEquals("Kết thúc cuộc gọi", ZaloAccessibilityHelper.formatCallActionLabel("End call", "Kết thúc"));
  }

  @Test
  public void formatStickerLabel_includesPackNameAndIndex() {
    assertEquals(
        "Nhãn dán 3, Củ hành", ZaloAccessibilityHelper.formatStickerLabel("Củ hành", 3));
    assertEquals("Nhãn dán 2", ZaloAccessibilityHelper.formatStickerLabel(null, 2));
  }
}
