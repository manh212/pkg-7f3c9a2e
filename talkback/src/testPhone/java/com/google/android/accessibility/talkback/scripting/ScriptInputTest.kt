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

package com.google.android.accessibility.talkback.scripting

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `twoFingerTapAndHold` is a gesture name scripts can use, like its neighbours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptInputTest {
  @Test
  fun twoFingerTapAndHoldIsAGestureName() {
    assertTrue(ScriptInput.isGesture("twoFingerTapAndHold"))
    assertTrue("twoFingerTapAndHold" in ScriptInput.gestureNames)
    assertEquals(
      "twoFingerTapAndHold",
      ScriptInput.gestureName(AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP_AND_HOLD),
    )
  }

  @Test
  fun twoFingerTapAndHoldDoesNotCollideWithItsNeighbours() {
    assertEquals(
      "twoFingerDoubleTapAndHold",
      ScriptInput.gestureName(AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD),
    )
    assertEquals(
      "twoFingerTripleTapAndHold",
      ScriptInput.gestureName(AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD),
    )
    assertEquals(
      "twoFingerTap",
      ScriptInput.gestureName(AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP),
    )
  }
}
