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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ZaloAccessibilityHelper}. */
@RunWith(JUnit4.class)
public final class ZaloAccessibilityHelperTest {

  @Test
  public void isZaloPackage_matchesOnlyZalo() {
    assertThat(ZaloAccessibilityHelper.isZaloPackage("com.zing.zalo")).isTrue();
    assertThat(ZaloAccessibilityHelper.isZaloPackage("com.shopee.vn")).isFalse();
    assertThat(ZaloAccessibilityHelper.isZaloPackage(null)).isFalse();
  }

  @Test
  public void sanitizeZaloText_stripsNullCharAndEdgeNewlines() {
    assertThat(ZaloAccessibilityHelper.sanitizeZaloText("Cậu\0: Tối ngày 15\n"))
        .isEqualTo("Cậu: Tối ngày 15");
    assertThat(ZaloAccessibilityHelper.sanitizeZaloText("\noki oki\n")).isEqualTo("oki oki");
  }

  @Test
  public void formatMainTabLabel_handlesBadgesAndCounts() {
    assertThat(ZaloAccessibilityHelper.formatMainTabLabel("Tường nhà", false, "N"))
        .isEqualTo("Tường nhà, có tin mới");
    assertThat(ZaloAccessibilityHelper.formatMainTabLabel("Tin nhắn", false, "3"))
        .isEqualTo("Tin nhắn, 3 thông báo mới");
    assertThat(ZaloAccessibilityHelper.formatMainTabLabel("Khám phá", true, null))
        .isEqualTo("Khám phá, có thông báo mới");
    assertThat(ZaloAccessibilityHelper.formatMainTabLabel("Danh bạ", false, null))
        .isEqualTo("Danh bạ");
  }

  @Test
  public void formatNotificationButtonLabel_handlesCount() {
    assertThat(ZaloAccessibilityHelper.formatNotificationButtonLabel("4"))
        .isEqualTo("Thông báo, 4 thông báo mới");
    assertThat(ZaloAccessibilityHelper.formatNotificationButtonLabel(null))
        .isEqualTo("Thông báo");
  }

  @Test
  public void formatCallActionLabel_distinguishesActiveAndIncomingCalls() {
    assertThat(ZaloAccessibilityHelper.formatCallActionLabel("Change speaker", "Loa"))
        .isEqualTo("Loa ngoài");
    assertThat(ZaloAccessibilityHelper.formatCallActionLabel("Mute record", "Mic"))
        .isEqualTo("Bật hoặc tắt mic");
    assertThat(ZaloAccessibilityHelper.formatCallActionLabel("Accept call", "Trả lời"))
        .isEqualTo("Trả lời cuộc gọi");
    assertThat(ZaloAccessibilityHelper.formatCallActionLabel("End call", "Từ chối"))
        .isEqualTo("Từ chối cuộc gọi");
    assertThat(ZaloAccessibilityHelper.formatCallActionLabel("End call", "Kết thúc"))
        .isEqualTo("Kết thúc cuộc gọi");
  }
}
