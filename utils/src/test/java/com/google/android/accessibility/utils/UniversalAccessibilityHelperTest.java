/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Kiểm thử đơn vị cho {@link UniversalAccessibilityHelper}. */
@RunWith(RobolectricTestRunner.class)
public final class UniversalAccessibilityHelperTest {

  @After
  public void tearDown() {
    UniversalAccessibilityHelper.setFocusFilterEnabled(true);
    UniversalAccessibilityHelper.clearSnapshotCache();
  }

  @Test
  public void focusFilterToggle_defaultsOnAndCanBeDisabled() {
    assertThat(UniversalAccessibilityHelper.isFocusFilterEnabled()).isTrue();
    UniversalAccessibilityHelper.setFocusFilterEnabled(false);
    assertThat(UniversalAccessibilityHelper.isFocusFilterEnabled()).isFalse();
    assertThat(UniversalAccessibilityHelper.shouldHideNode(null)).isFalse();
  }

  @Test
  public void isRawResourceIdDescription_detectsOnlyAndroidIds() {
    assertThat(UniversalAccessibilityHelper.isRawResourceIdDescription("@2131829637")).isTrue();
    assertThat(UniversalAccessibilityHelper.isRawResourceIdDescription(" @2131843423 ")).isTrue();
    assertThat(UniversalAccessibilityHelper.isRawResourceIdDescription("@manhmanh414")).isFalse();
    assertThat(UniversalAccessibilityHelper.isRawResourceIdDescription("Tìm kiếm")).isFalse();
    assertThat(UniversalAccessibilityHelper.isRawResourceIdDescription(null)).isFalse();
  }

  @Test
  public void isTechnicalSnakeCaseDescription_detectsInternalTestIdsOnly() {
    assertThat(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("dd_module_item"))
        .isTrue();
    assertThat(
            UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("wallet_bar_cell_left"))
        .isTrue();
    assertThat(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("popup_banner_image"))
        .isTrue();
    assertThat(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("Mua ngay")).isFalse();
    assertThat(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("Giỏ hàng"))
        .isFalse();
    assertThat(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("two_parts"))
        .isFalse();
  }

  @Test
  public void cleanControlAndPlaceholderChars_stripsObjectReplacementAndNull() {
    assertThat(
            UniversalAccessibilityHelper.cleanControlAndPlaceholderChars(
                "\uFFFC  Áo khoác\u0000  mùa đông \uFFFC"))
        .isEqualTo("Áo khoác mùa đông");
    assertThat(UniversalAccessibilityHelper.cleanControlAndPlaceholderChars("Bình thường"))
        .isEqualTo("Bình thường");
  }
}
