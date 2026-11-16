/*
 * Copyright 2026 The SandFly Authors
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FocusFinderTest {

  @Test
  public void findAccessibilityFocus_returnsRefreshedNodeOutsideCache() {
    TestService service = Robolectric.setupService(TestService.class);
    AccessibilityNodeInfo focusedNode = AccessibilityNodeInfo.obtain();
    focusedNode.setVisibleToUser(true);
    shadowOf(focusedNode).setRefreshReturnValue(true);
    service.focusedNode = focusedNode;

    AccessibilityNodeInfoCompat result =
        new FocusFinder(service).findAccessibilityFocus(Filter.node(node -> false));

    assertNotNull(result);
    assertSame(focusedNode, result.unwrap());
  }

  public static class TestService extends AccessibilityService {
    private AccessibilityNodeInfo focusedNode;

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
      return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ? focusedNode : null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }
}
