package com.google.android.accessibility.talkback.vietnameseime

import android.view.MotionEvent
import android.view.View
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
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
class VietnameseKeyboardViewTest {
  private val context = RuntimeEnvironment.getApplication()
  private val spoken = mutableListOf<String>()
  private val typed = mutableListOf<String>()
  private var released = 0
  private val keyboard = VietnameseKeyboardView(context, { spoken.add(it) }, { typed.add(it) }) {
    released++
  }

  @Test
  fun directModeStillSpeaksExploredKeyAndCommitsOnReleaseWithoutAccessibilityFocus() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_MOVE, 60f, 60f, 140)
    assertEquals(listOf("q", "w"), spoken)
    assertEquals(emptyList<String>(), typed)
    touch(MotionEvent.ACTION_UP, 60f, 60f, 170)

    assertEquals(listOf("w"), typed)
    assertEquals(1, released)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, keyboard.importantForAccessibility)
    assertFalse(keyboard.isFocusable)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitKeyboardReachesNearScreenMidpoint() {
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 380f..390f)
  }

  @Test
  fun passwordDoesNotAnnounceDigit() {
    prepareKeyboard()
    keyboard.hideLetterSpeech = true

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 20f, 170)

    assertEquals(listOf(context.getString(R.string.vietnamese_ime_password_character)), spoken)
    assertEquals(listOf("1"), typed)
  }

  private fun prepareKeyboard() {
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)
  }

  private fun touch(action: Int, x: Float, y: Float, time: Long) {
    val event = MotionEvent.obtain(100, time, action, x, y, 0)
    try {
      keyboard.onTouchEvent(event)
    } finally {
      event.recycle()
    }
  }
}
