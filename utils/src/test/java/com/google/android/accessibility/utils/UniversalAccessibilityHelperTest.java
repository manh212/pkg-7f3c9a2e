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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Kiểm thử đơn vị cho {@link UniversalAccessibilityHelper}. */
public final class UniversalAccessibilityHelperTest {

  @After
  public void tearDown() {
    UniversalAccessibilityHelper.setFocusFilterEnabled(true);
    UniversalAccessibilityHelper.clearSnapshotCache();
  }

  @Test
  public void focusFilterToggle_defaultsOnAndCanBeDisabled() {
    assertTrue(UniversalAccessibilityHelper.isFocusFilterEnabled());
    UniversalAccessibilityHelper.setFocusFilterEnabled(false);
    assertFalse(UniversalAccessibilityHelper.isFocusFilterEnabled());
    assertFalse(UniversalAccessibilityHelper.shouldHideNode(null));
  }

  @Test
  public void isRawResourceIdDescription_detectsOnlyAndroidIds() {
    assertTrue(UniversalAccessibilityHelper.isRawResourceIdDescription("@2131829637"));
    assertTrue(UniversalAccessibilityHelper.isRawResourceIdDescription(" @2131843423 "));
    assertFalse(UniversalAccessibilityHelper.isRawResourceIdDescription("@manhmanh414"));
    assertFalse(UniversalAccessibilityHelper.isRawResourceIdDescription("Tìm kiếm"));
    assertFalse(UniversalAccessibilityHelper.isRawResourceIdDescription(null));
  }

  @Test
  public void isTechnicalSnakeCaseDescription_detectsInternalTestIdsOnly() {
    assertTrue(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("dd_module_item"));
    assertTrue(
        UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("wallet_bar_cell_left"));
    assertTrue(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("popup_banner_image"));
    assertFalse(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("Mua ngay"));
    assertFalse(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("Giỏ hàng"));
    assertFalse(UniversalAccessibilityHelper.isTechnicalSnakeCaseDescription("two_parts"));
  }

  @Test
  public void cleanControlAndPlaceholderChars_stripsObjectReplacementAndNull() {
    assertEquals(
        "Áo khoác mùa đông",
        UniversalAccessibilityHelper.cleanControlAndPlaceholderChars(
            "\uFFFC  Áo khoác\u0000  mùa đông \uFFFC"));
    assertEquals(
        "Bình thường",
        UniversalAccessibilityHelper.cleanControlAndPlaceholderChars("Bình thường"));
  }
}
