/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PerformanceHintHelperTest {

  @Test
  fun targetNanosForRefreshRate_scalesFor144Hz120Hz90HzAnd60Hz() {
    assertEquals(
      PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS,
      PerformanceHintHelper.targetNanosForRefreshRate(144f),
    )
    assertEquals(
      PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS,
      PerformanceHintHelper.targetNanosForRefreshRate(120f),
    )
    assertEquals(
      PerformanceHintHelper.TARGET_90HZ_NANOS,
      PerformanceHintHelper.targetNanosForRefreshRate(90f),
    )
    assertEquals(
      PerformanceHintHelper.TARGET_WORK_DURATION_NANOS,
      PerformanceHintHelper.targetNanosForRefreshRate(60f),
    )
  }

  @Test
  fun boostNanosForTarget_exceedsTargetByHalf() {
    assertEquals(
      10_500_000L,
      PerformanceHintHelper.boostNanosForTarget(PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS),
    )
    assertEquals(
      18_000_000L,
      PerformanceHintHelper.boostNanosForTarget(PerformanceHintHelper.TARGET_WORK_DURATION_NANOS),
    )
  }
}
