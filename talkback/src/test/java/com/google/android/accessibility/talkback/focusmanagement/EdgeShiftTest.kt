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

package com.google.android.accessibility.talkback.focusmanagement

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeShiftTest {

  private val listTop = 200
  private val listBottom = 2240

  @Test
  fun itemWithBothEdgesOnScreenUsesTop() {
    assertEquals(-300, FocusProcessorForLogicalNavigation.edgeShift(500, 800, 900, 1200))
    assertEquals(300, FocusProcessorForLogicalNavigation.edgeShift(800, 500, 1200, 900))
  }

  @Test
  fun tallItemCutOffAtTopMovingDownUsesBottom() {
    assertEquals(
      -4,
      FocusProcessorForLogicalNavigation.edgeShift(listTop, listTop, listBottom - 4, listBottom),
    )
  }

  @Test
  fun tallItemCutOffAtTopMovingUpUsesBottom() {
    assertEquals(
      600,
      FocusProcessorForLogicalNavigation.edgeShift(listTop, listTop, listBottom, listBottom - 600),
    )
  }

  @Test
  fun tallItemCutOffAtBottomMovingDownUsesTop() {
    assertEquals(
      -700,
      FocusProcessorForLogicalNavigation.edgeShift(listTop, listTop + 700, listBottom, listBottom),
    )
  }

  @Test
  fun unchangedBoundsGiveNoShift() {
    assertEquals(
      0,
      FocusProcessorForLogicalNavigation.edgeShift(listTop, listTop, listBottom, listBottom),
    )
  }
}
