package com.google.android.accessibility.talkback.gesture

import android.app.Application
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeGestureRecentCommandsTest {
  private val context: Application = RuntimeEnvironment.getApplication()

  @Test
  fun savedRecentCommandsRemainRecognizedAndDoNotRepeatDuringAHold() {
    assertEquals(EdgeGestureActions.Action.PREVIOUS_RECENT,
      EdgeGestureActions.find(context, "EDGE_GESTURE_PREVIOUS_RECENT"))
    assertEquals(EdgeGestureActions.Action.NEXT_RECENT,
      EdgeGestureActions.find(context, "EDGE_GESTURE_NEXT_RECENT"))
    assertFalse(EdgeGestureActions.repeats(context, "EDGE_GESTURE_PREVIOUS_RECENT"))
    assertFalse(EdgeGestureActions.repeats(context, "EDGE_GESTURE_NEXT_RECENT"))
    assertTrue(EdgeGestureActions.repeats(context, "EDGE_GESTURE_PREVIOUS_SENTENCE"))
    assertTrue(EdgeGestureActions.repeats(context, "EDGE_GESTURE_NEXT_SENTENCE"))
    assertTrue(EdgeGestureActions.repeats(context,
      context.getString(R.string.shortcut_value_increase_volume)))
  }
}
