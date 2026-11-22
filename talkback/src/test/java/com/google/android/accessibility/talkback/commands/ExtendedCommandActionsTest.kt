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

package com.google.android.accessibility.talkback.commands

import com.google.android.accessibility.talkback.quicksettings.QuickControl
import com.google.android.accessibility.talkback.recognition.RecognitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtendedCommandActionsTest {

  @Test
  fun liveRecognitionModeActionsRoundTripAll12Modes() {
    assertEquals(12, RecognitionMode.entries.size)
    for (mode in RecognitionMode.entries) {
      val action = ExtendedCommandActions.liveRecognitionModeAction(mode)
      assertEquals(mode, ExtendedCommandActions.parseLiveRecognitionMode(action))
    }
    assertNull(ExtendedCommandActions.parseLiveRecognitionMode("UNKNOWN"))
  }

  @Test
  fun quickDeviceControlActionsRoundTripAllCuratedControls() {
    assertEquals(9, ExtendedCommandActions.QUICK_DEVICE_CONTROLS.size)
    for (control in ExtendedCommandActions.QUICK_DEVICE_CONTROLS) {
      val action = ExtendedCommandActions.quickControlAction(control)
      assertEquals(control, ExtendedCommandActions.parseQuickControl(action))
    }
    assertNull(ExtendedCommandActions.parseQuickControl("EXTENDED_QUICK_CONTROL:UNKNOWN"))
    assertNull(ExtendedCommandActions.parseQuickControl(ExtendedCommandActions.ACTION_TOGGLE_DIRECT_TOUCH))
  }
}
