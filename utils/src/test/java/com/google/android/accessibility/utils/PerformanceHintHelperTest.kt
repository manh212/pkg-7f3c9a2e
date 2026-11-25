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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PerformanceHintHelperTest {

  @Test
  fun targetNanosForRefreshRate_scalesFor144Hz120Hz90HzAnd60Hz() {
    assertThat(PerformanceHintHelper.targetNanosForRefreshRate(144f))
      .isEqualTo(PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS)
    assertThat(PerformanceHintHelper.targetNanosForRefreshRate(120f))
      .isEqualTo(PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS)
    assertThat(PerformanceHintHelper.targetNanosForRefreshRate(90f))
      .isEqualTo(PerformanceHintHelper.TARGET_90HZ_NANOS)
    assertThat(PerformanceHintHelper.targetNanosForRefreshRate(60f))
      .isEqualTo(PerformanceHintHelper.TARGET_WORK_DURATION_NANOS)
  }

  @Test
  fun boostNanosForTarget_exceedsTargetByHalf() {
    assertThat(
      PerformanceHintHelper.boostNanosForTarget(PerformanceHintHelper.TARGET_HIGH_REFRESH_NANOS)
    ).isEqualTo(10_500_000L)
    assertThat(
      PerformanceHintHelper.boostNanosForTarget(PerformanceHintHelper.TARGET_WORK_DURATION_NANOS)
    ).isEqualTo(18_000_000L)
  }
}
