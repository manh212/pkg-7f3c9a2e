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

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodesTest {
  @Test
  fun childOrNull_returnsTheChildInRangeAndNullPastTheLastOne() {
    val parent = AccessibilityNodeInfo.obtain()
    val child = AccessibilityNodeInfo.obtain().apply { text = "A" }
    shadowOf(parent).addChild(child)
    val compat = AccessibilityNodeInfoCompat.wrap(parent)

    assertEquals(AccessibilityNodeInfoCompat.wrap(child), compat.childOrNull(0))
    assertNull(compat.childOrNull(1))
    assertNull(compat.childOrNull(-1))
  }

  @Test
  fun children_listsEveryChild() {
    val parent = AccessibilityNodeInfo.obtain()
    val first = AccessibilityNodeInfo.obtain().apply { text = "A" }
    val second = AccessibilityNodeInfo.obtain().apply { text = "B" }
    shadowOf(parent).addChild(first)
    shadowOf(parent).addChild(second)
    val compat = AccessibilityNodeInfoCompat.wrap(parent)

    assertEquals(
      listOf(AccessibilityNodeInfoCompat.wrap(first), AccessibilityNodeInfoCompat.wrap(second)),
      compat.children(),
    )
  }
}
