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
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * A script gesture that is still waiting when scripts are turned off is not performed, and it
 * never clears a pass-through lock held by the person or another screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ScriptTouchTest {
  private val returner = RecordingReturner()
  private val services = FakeServices(passThroughActive = false)
  private lateinit var service: AccessibilityService
  private lateinit var feedback: ScriptFeedback
  private lateinit var touch: ScriptTouch

  @Before
  fun setUp() {
    service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
    feedback = ScriptFeedback(service, returner, Runnable {}, services)
    touch = ScriptTouch(service, feedback)
  }

  class TestAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  private class RecordingReturner : Pipeline.FeedbackReturner {
    val returned = mutableListOf<Feedback>()

    override fun returnFeedback(feedback: Feedback): Boolean {
      returned.add(feedback)
      return true
    }
  }

  private class FakeServices(var passThroughActive: Boolean) : ScriptServices {
    override fun runCommand(value: String): Boolean = false

    override fun stopSpeech() {}

    override fun isSpeaking(): Boolean = false

    override fun isPassThroughActive(): Boolean = passThroughActive

    override fun lastSpeech(): String? = null
  }

  private fun strokes() =
    JSONArray(
      "[{\"points\":[{\"x\":100,\"y\":100},{\"x\":200,\"y\":200}],\"start\":0,\"duration\":50}]"
    )

  private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

  @Test
  fun closeMarksTouchClosed() {
    touch.close()
    idleMain()

    assertTrue(ReflectionHelpers.getField<Boolean>(touch, "closed"))
  }

  @Test
  fun gestureAfterCloseIsNotPerformed() {
    touch.close()
    idleMain()

    var done: Boolean? = null
    touch.gesture(strokes()) { done = it }
    idleMain()

    assertEquals(false, done)
    assertEquals(0, ReflectionHelpers.getField<Int>(touch, "gestures"))
    assertTrue(returner.returned.isEmpty())
  }

  @Test
  fun gestureSetsPassThroughWhenNobodyHoldsIt() {
    services.passThroughActive = false

    touch.gesture(strokes()) {}
    idleMain()

    assertTrue(ReflectionHelpers.getField<Boolean>(touch, "ownsPassThrough"))
    assertEquals(1, ReflectionHelpers.getField<Int>(touch, "gestures"))
    // One feedback: pass-through on, so touch exploration doesn't take the strokes.
    assertEquals(1, returner.returned.size)
  }

  @Test
  fun gestureKeepsAPassThroughLockHeldBySomebodyElse() {
    services.passThroughActive = true

    touch.gesture(strokes()) {}
    idleMain()

    assertFalse(ReflectionHelpers.getField<Boolean>(touch, "ownsPassThrough"))
    assertEquals(1, ReflectionHelpers.getField<Int>(touch, "gestures"))
    // No feedback: the lock stays with whoever holds it, and the gesture must not clear it.
    assertTrue(returner.returned.isEmpty())
  }
}
