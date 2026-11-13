package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.FORCE_LIFT_TO_TYPE_ON_IME
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseKeyTouchTest {
  private val letter = VietnameseKeyTouch.Key("a", textEntry = true)
  private val shift = VietnameseKeyTouch.Key("shift")
  private val send = VietnameseKeyTouch.Key("enter", editorAction = true)
  private val touch = VietnameseKeyTouch(300, 30)

  @Test
  fun usesAllSixConfirmationModes() {
    val policy = { key: VietnameseKeyTouch.Key, method: Int ->
      VietnameseKeyTouch.confirmsWithDoubleTap(key, method)
    }
    assertTrue(listOf(letter, shift, send).all { policy(it, DOUBLE_TAP) })
    assertFalse(policy(letter, LIFT_TO_TYPE))
    assertTrue(policy(shift, LIFT_TO_TYPE))
    assertTrue(policy(send, LIFT_TO_TYPE))
    assertFalse(policy(letter, LIFT_TO_TYPE_EXCEPT_ACTION_KEY))
    assertFalse(policy(shift, LIFT_TO_TYPE_EXCEPT_ACTION_KEY))
    assertTrue(policy(send, LIFT_TO_TYPE_EXCEPT_ACTION_KEY))
    for (method in listOf(FORCE_LIFT_TO_TYPE_ON_IME, SINGLE_TAP_TO_TYPE, DIRECT_SILENT_TYPING)) {
      assertFalse(listOf(letter, shift, send).any { policy(it, method) })
    }
  }

  @Test
  fun exploreAndDoubleTapAnywhereInKeyboardConfirmsSelectedKeyExactlyOnce() {
    touch.start(letter, DOUBLE_TAP)
    touch.explore(send)
    assertNull(touch.release(send, DOUBLE_TAP, 10f, 10f, 100, false))
    touch.start(shift, DOUBLE_TAP)
    assertNull(touch.release(shift, DOUBLE_TAP, 50f, 50f, 160, true))
    touch.start(shift, DOUBLE_TAP)
    assertEquals(send, touch.release(shift, DOUBLE_TAP, 50f, 50f, 240, true))
    assertNull(touch.selected)
  }

  @Test
  fun sharedFirstTapMapLetsInterleavedKeysConfirmIndependently() {
    val firstTaps = LinkedHashMap<VietnameseKeyTouch.Key, VietnameseKeyTouch.Tap>()
    val first = VietnameseKeyTouch(300, 30, firstTaps)
    val second = VietnameseKeyTouch(300, 30, firstTaps)
    first.start(letter, DOUBLE_TAP)
    assertNull(first.release(letter, DOUBLE_TAP, 10f, 10f, 100, true))
    second.start(shift, DOUBLE_TAP)
    assertNull(second.release(shift, DOUBLE_TAP, 50f, 50f, 160, true))
    val third = VietnameseKeyTouch(300, 30, firstTaps)
    third.start(letter, DOUBLE_TAP)
    assertEquals(letter, third.release(letter, DOUBLE_TAP, 10f, 10f, 240, true))
    val fourth = VietnameseKeyTouch(300, 30, firstTaps)
    fourth.start(shift, DOUBLE_TAP)
    assertEquals(shift, fourth.release(shift, DOUBLE_TAP, 50f, 50f, 300, true))
  }

  @Test
  fun exploreDoesNotDiscardAnotherTouchPendingFirstTap() {
    val firstTaps = LinkedHashMap<VietnameseKeyTouch.Key, VietnameseKeyTouch.Tap>()
    val first = VietnameseKeyTouch(300, 30, firstTaps)
    val second = VietnameseKeyTouch(300, 30, firstTaps)
    first.start(letter, DOUBLE_TAP)
    assertNull(first.release(letter, DOUBLE_TAP, 10f, 10f, 100, true))
    second.start(shift, DOUBLE_TAP)
    second.explore(letter)
    val third = VietnameseKeyTouch(300, 30, firstTaps)
    third.start(letter, DOUBLE_TAP)
    assertEquals(letter, third.release(letter, DOUBLE_TAP, 10f, 10f, 240, true))
  }

  @Test
  fun liftingSecondTapOutsideDiscardsPendingFirstTap() {
    touch.start(letter, DOUBLE_TAP)
    assertNull(touch.release(letter, DOUBLE_TAP, 10f, 10f, 100, true))
    touch.start(letter, DOUBLE_TAP)
    assertNull(touch.release(null, DOUBLE_TAP, 0f, 0f, 160, false))
    touch.start(letter, DOUBLE_TAP)
    assertNull(touch.release(letter, DOUBLE_TAP, 10f, 10f, 240, true))
  }

  @Test
  fun cancellationAndLiftingOutsideDiscardTheKey() {
    touch.start(letter, FORCE_LIFT_TO_TYPE_ON_IME)
    assertNull(touch.release(null, FORCE_LIFT_TO_TYPE_ON_IME, 0f, 0f, 100, false))
    touch.start(send, DOUBLE_TAP)
    assertNull(touch.release(send, DOUBLE_TAP, 0f, 0f, 100, true))
    touch.cancel()
    touch.start(shift, DOUBLE_TAP)
    assertNull(touch.release(shift, DOUBLE_TAP, 0f, 0f, 160, true))
  }
}
