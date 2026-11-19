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
import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Turning scripts off marks the screen closed, so no recognition starts after that. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ScriptScreenTest {
  private lateinit var screen: ScriptScreen

  class TestAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  @Before
  fun setUp() {
    val service =
      Robolectric.buildService(TestAccessibilityService::class.java).create().get()
    screen = ScriptScreen(service)
  }

  @Test
  fun closeMarksScreenClosed() {
    screen.close()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(ReflectionHelpers.getField<Boolean>(screen, "closed"))
  }
}
