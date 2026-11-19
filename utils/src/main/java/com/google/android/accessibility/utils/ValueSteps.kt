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

package com.google.android.accessibility.utils

/**
 * Steps through a list of values, such as the values of a reading control. A step goes around from
 * the last value to the first and back. Main thread only.
 */
object ValueSteps {
  /**
   * The index of the value after, or before, [index] among [size] values. At the last value going
   * forward, or the first going back, it is the first or last value. An [index] outside the list,
   * such as -1 for a value not among them, goes around as `Math.floorMod` does.
   */
  @JvmStatic
  fun step(index: Int, size: Int, forward: Boolean): Int {
    return Math.floorMod(index + if (forward) 1 else -1, size)
  }
}
