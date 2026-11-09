/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The service info applied when the service connects is computed again at locked boot
 * completion, so the framework re-registers the service's requests (such as touch
 * exploration) once the keyguard is up. A service that was never connected cannot read
 * its info back from the framework in a unit test, so these tests cover the flag
 * computation that both applications share.
 */
@RunWith(JUnit4.class)
public class TalkBackServiceLockedBootTest {

  private static int flags(
      boolean multiFingerGestureSupported,
      boolean supportsTouchScreen,
      boolean exploreByTouch,
      boolean competingScreenReaderEnabled) {
    return TalkBackService.computeServiceFlags(
        0,
        0,
        multiFingerGestureSupported,
        false,
        supportsTouchScreen,
        exploreByTouch,
        competingScreenReaderEnabled,
        false);
  }

  @Test
  public void computeServiceFlags_requestsTouchExplorationWhenPreferenceEnabled() {
    int flags = flags(true, true, true, false);

    assertTrue(
        (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
    assertTrue((flags & AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES) != 0);
    assertTrue((flags & AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH) != 0);
  }

  @Test
  public void computeServiceFlags_clearsTouchExplorationWhenPreferenceDisabled() {
    int flags =
        TalkBackService.computeServiceFlags(
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE,
            0,
            true,
            false,
            true,
            false,
            false,
            false);

    assertFalse(
        (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }

  @Test
  public void computeServiceFlags_clearsTouchExplorationWhenCompetingReaderEnabled() {
    int flags = flags(true, true, true, true);

    assertFalse(
        (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }

  @Test
  public void computeServiceFlags_clearsTouchExplorationWithoutTouchScreen() {
    int flags = flags(true, false, true, false);

    assertFalse(
        (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }

  @Test
  public void computeServiceFlags_clearsMultiFingerFlagsWhenUnsupported() {
    int flags = flags(false, true, true, false);

    assertFalse((flags & AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES) != 0);
    assertFalse((flags & AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH) != 0);
    assertTrue(
        (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }

  @Test
  public void computeServiceFlags_keepsAdditionalAndReadUrlFlags() {
    int flags =
        TalkBackService.computeServiceFlags(
            0, 0x40, false, false, true, true, false, true);

    assertEquals(0x40, flags & 0x40);
    assertTrue((flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0);
  }
}
