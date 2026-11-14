package com.google.android.accessibility.talkback.vietnameseime

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.liftsToType
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeyboardViewTest {
  private val context = RuntimeEnvironment.getApplication()
  private val spoken = mutableListOf<String>()
  private val typed = mutableListOf<String>()
  private var released = 0
  private var micStops = 0
  private val keyboard = VietnameseKeyboardView(
    context, { spoken.add(it) }, { id, _ -> typed.add(id) }, { released++ },
    micStop = { micStops++ },
  )

  @Test
  fun touchscreenHoverSpeaksOnlyWhenEnabledAndDoesNotType() {
    prepareKeyboard()
    val hover = MotionEvent.obtain(100, 100, MotionEvent.ACTION_HOVER_ENTER, 20f, 60f, 0)
    hover.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
      keyboard.dispatchHoverEvent(hover)
      assertTrue(spoken.isEmpty())

      keyboard.speakHoveredKeys = true
      keyboard.dispatchHoverEvent(hover)
      hover.action = MotionEvent.ACTION_HOVER_MOVE
      keyboard.dispatchHoverEvent(hover)
      hover.action = MotionEvent.ACTION_HOVER_EXIT
      keyboard.dispatchHoverEvent(hover)

      assertEquals(listOf("q"), spoken)
      assertEquals(1, released)
      assertTrue(typed.isEmpty())
    } finally {
      hover.recycle()
    }
  }

  @Test
  fun micKeyAppearsOnlyWhenEnabled() {
    prepareKeyboard()
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    assertEquals(45, host.childCount)

    keyboard.micEnabled = true
    val micHost = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    assertEquals(46, micHost.childCount)
    val labels = (0 until micHost.childCount).mapNotNull {
      provider.createAccessibilityNodeInfo(it)?.text?.toString()
    }
    assertTrue(labels.contains(context.getString(R.string.vietnamese_ime_voice_input)))
  }

  @Test
  fun micShortHoldDoesNothing() {
    keyboard.micEnabled = true
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 294f, 225f, 100)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
    touch(MotionEvent.ACTION_UP, 294f, 225f, 200)

    assertEquals(listOf(context.getString(R.string.vietnamese_ime_voice_input)), spoken)
    assertTrue(typed.isEmpty())
    assertEquals(0, micStops)
  }

  @Test
  fun micHoldStartsListeningAfterOneSecond() {
    keyboard.micEnabled = true
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 294f, 225f, 100)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_voice_input)), spoken)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
    assertTrue(typed.isEmpty())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(listOf("mic"), typed)
    touch(MotionEvent.ACTION_UP, 294f, 225f, 200)

    assertEquals(1, micStops)
    assertEquals(1, released)
  }

  @Test
  fun micHoldInLiftToTypeStartsWithoutDoubleTap() {
    keyboard.micEnabled = true
    keyboard.typingMethod = LIFT_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 294f, 225f, 100)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_voice_input)), spoken)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
    assertEquals(listOf("mic"), typed)
    touch(MotionEvent.ACTION_UP, 294f, 225f, 200)

    assertEquals(1, micStops)
  }

  @Test
  fun directSilentModeCommitsDownKeyOnDownWithoutAccessibilityFocus() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    assertEquals(listOf("q"), spoken)
    assertEquals(listOf("q"), typed)
    assertEquals(0, released)
    touch(MotionEvent.ACTION_MOVE, 60f, 60f, 140)
    assertEquals(listOf("q"), spoken)
    assertEquals(listOf("q"), typed)
    touch(MotionEvent.ACTION_UP, 60f, 60f, 170)

    assertEquals(listOf("q"), typed)
    assertEquals(1, released)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, keyboard.importantForAccessibility)
    assertFalse(keyboard.isFocusable)
  }

  @Test
  fun directSilentModeLiftOffKeyboardDoesNotSilenceDownKey() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    assertEquals(listOf("q"), typed)
    touch(MotionEvent.ACTION_MOVE, 60f, 300f, 140)
    touch(MotionEvent.ACTION_UP, 60f, 300f, 170)

    assertEquals(listOf("q"), typed)
    assertEquals(listOf("q"), spoken)
    assertEquals(0, released)
  }

  @Test
  fun singleTapDoesNotActivateOnDown() {
    keyboard.typingMethod = SINGLE_TAP_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)

    assertEquals(emptyList<String>(), typed)
  }

  @Test
  fun directInputAnnouncesStationaryCenterImmediatelyAndCommitsOnlyAfterLift() {
    keyboard.typingMethod = SINGLE_TAP_TO_TYPE
    keyboard.timing = VietnameseKeyboardTiming(directInputMs = 100)
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 200f, 125f, 100)
    assertEquals(listOf("g"), spoken)
    assertTrue(typed.isEmpty())
    touch(MotionEvent.ACTION_UP, 200f, 125f, 150)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(99))
    assertTrue(typed.isEmpty())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(listOf("g"), typed)
  }

  @Test
  fun virtualKeysExposeBoundsAndClickThroughAndroidWithoutRawTouch() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setContentView(keyboard)
    prepareKeyboard()

    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    assertEquals(45, host.childCount)
    val letter = checkNotNull(provider.createAccessibilityNodeInfo(24))
    assertEquals("g", letter.text)
    assertTrue(letter.isTextEntryKey)
    assertTrue(letter.isClickable)
    val bounds = Rect()
    letter.getBoundsInScreen(bounds)
    val location = IntArray(2)
    keyboard.getLocationOnScreen(location)
    assertEquals(Rect(178, 100, 222, 150).apply { offset(location[0], location[1]) }, bounds)
    assertTrue(provider.performAction(24, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertEquals(listOf("g"), typed)
    assertTrue(spoken.isEmpty())

    val action = checkNotNull(provider.createAccessibilityNodeInfo(44))
    assertFalse(liftsToType(LIFT_TO_TYPE_EXCEPT_ACTION_KEY, AccessibilityNodeInfoCompat.wrap(action)))
    val hidden = checkNotNull(provider.createAccessibilityNodeInfo(24))
    assertEquals("g", hidden.text)
    assertFalse(hidden.isPassword)
    assertFalse(provider.performAction(70, AccessibilityNodeInfo.ACTION_CLICK, null))
    activity.finish()
  }

  @Test
  fun virtualShiftAndSymbolKeysRetainTheirActionsAcrossPageChanges() {
    preparePcKeyboard()
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    assertTrue(provider.performAction(14, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertTrue(provider.performAction(34, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertEquals(listOf("!"), typed)

    keyboard.showKeys(false, true, true, "Gửi")
    assertEquals(
      context.getString(R.string.vietnamese_ime_letters),
      provider.createAccessibilityNodeInfo(0)?.text?.toString(),
    )
    assertTrue(provider.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertFalse(provider.performAction(18, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertEquals(listOf("!", "letters"), typed)
  }

  @Test
  fun directSilentModeTwoFingersTypeBothKeysOnDown() {
    prepareKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 20f, 60f)), 100)
    assertEquals(listOf("q"), spoken)
    assertEquals(listOf("q"), typed)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 150)
    assertEquals(listOf("q", "w"), spoken)
    assertEquals(listOf("q", "w"), typed)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 200)
    assertEquals(listOf("q", "w"), typed)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 20f, 60f)), 250)

    assertEquals(listOf("q", "w"), typed)
    assertEquals(2, released)
  }

  @Test
  fun liftToTypeTwoFingersTypeBothKeysOnLift() {
    keyboard.typingMethod = LIFT_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 20f, 60f)), 100)
    assertEquals(listOf("q"), spoken)
    assertEquals(emptyList<String>(), typed)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 150)
    assertEquals(listOf("q", "w"), spoken)
    assertEquals(emptyList<String>(), typed)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 200)
    assertEquals(listOf("w"), typed)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 20f, 60f)), 250)

    assertEquals(listOf("w", "q"), typed)
    assertEquals(2, released)
  }

  @Test
  fun doubleTapInterleavedFingersConfirmIndependently() {
    keyboard.typingMethod = DOUBLE_TAP
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 20f, 60f)), 100)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 20f, 60f)), 170)
    assertEquals(emptyList<String>(), typed)
    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 60f, 60f)), 180)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 60f, 60f)), 250)
    assertEquals(emptyList<String>(), typed)
    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 20f, 60f)), 280)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 20f, 60f)), 350)
    assertEquals(listOf("q"), typed)
    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 60f, 60f)), 360)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 60f, 60f)), 430)

    assertEquals(listOf("q", "w"), typed)
  }

  @Test
  fun doubleTapDoesNotReannounceKeyOnSecondDown() {
    keyboard.typingMethod = DOUBLE_TAP
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)
    assertEquals(listOf("q"), spoken)
    assertEquals(emptyList<String>(), typed)
    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 270)

    assertEquals(listOf("q"), spoken)
    assertEquals(listOf("q"), typed)
    assertEquals(2, released)
  }

  @Test
  fun deleteRepeatIntervalShrinksToFloor() {
    assertEquals(
      listOf(102L, 86L, 73L, 62L, 52L, 44L, 40L, 40L),
      generateSequence(120L, VietnameseKeyboardView::nextDeleteRepeatIntervalMs)
        .drop(1).take(8).toList(),
    )
  }

  @Test
  fun doubleTapDeleteInSingleTapModeDeletesWord() {
    keyboard.typingMethod = SINGLE_TAP_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 170)
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 200)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 270)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))

    assertEquals(listOf("delete", "delete_word"), typed)
  }

  @Test
  fun doubleTapDeleteInDoubleTapModeDeletesOneCharacter() {
    keyboard.typingMethod = DOUBLE_TAP
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 170)
    assertTrue(typed.isEmpty())
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 200)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 270)
    assertEquals(listOf("delete"), typed)
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 300)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 370)
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 400)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 470)

    assertEquals(listOf("delete", "delete"), typed)
    assertFalse(typed.contains("delete_word"))
  }

  @Test
  fun doubleTapDeleteInDirectModeDeletesWordOnSecondDown() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    assertEquals(listOf("delete"), typed)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 170)
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 200)
    assertEquals(listOf("delete", "delete_word"), typed)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 270)

    assertEquals(listOf("delete", "delete_word"), typed)
  }

  @Test
  fun holdingDeleteRepeatsAndAccelerates() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    assertEquals(listOf("delete"), typed)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
    assertEquals(listOf("delete"), typed)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(listOf("delete", "delete"), typed)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    val afterFirstWindow = typed.size
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    val afterSecondWindow = typed.size

    assertTrue(afterSecondWindow - afterFirstWindow > afterFirstWindow - 1)
    assertFalse(typed.contains("delete_word"))
    touch(MotionEvent.ACTION_UP, 368f, 175f, 1700)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

    assertEquals(afterSecondWindow, typed.size)
  }

  @Test
  fun holdingDeleteInSingleTapModeDoesNotTypeExtraDeleteOnLift() {
    keyboard.typingMethod = SINGLE_TAP_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    assertTrue(typed.isEmpty())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    assertEquals(listOf("delete"), typed)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 1700)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

    assertEquals(listOf("delete"), typed)
    assertFalse(typed.contains("delete_word"))
  }

  @Test
  fun liftingOnDifferentKeyWithoutMoveCannotConfirmItsDoubleTap() {
    keyboard.typingMethod = DOUBLE_TAP
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)
    touch(MotionEvent.ACTION_DOWN, 40.5f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 40.5f, 60f, 130)
    touch(MotionEvent.ACTION_DOWN, 39.5f, 60f, 160)
    touch(MotionEvent.ACTION_UP, 40.5f, 60f, 190)

    assertTrue(typed.isEmpty())
  }

  @Test
  fun liftingOutsidePcShiftDoesNotLeaveTheNextLetterShifted() {
    preparePcKeyboard()
    touch(MotionEvent.ACTION_DOWN, 240f, 360f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 450f, 150)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 250)

    assertEquals(listOf("q"), typed)
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
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeFiftyPercentHalvesHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizePortraitPercent = 50
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 187f..197f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeOneHundredFiftyPercentGrowsHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizePortraitPercent = 150
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 571f..581f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeFiftyPercentWithBothRowsOnSixRowKeyHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.showNumberRow = true
    keyboard.showSymbolsRow = true
    keyboard.sizePortraitPercent = 50
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 187f..197f)
    assertTrue(keyboard.measuredHeight / density / 6 in 30f..34f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeOneHundredFiftyPercentWithRowsOffFourRowKeyHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.showNumberRow = false
    keyboard.showSymbolsRow = false
    keyboard.sizePortraitPercent = 150
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 571f..581f)
    assertTrue(keyboard.measuredHeight / density / 4 in 140f..148f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeFiftyPercentHalvesKeyText() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizePortraitPercent = 50
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    assertEquals(9f, keyboard.keyTextSizeSp("q"), 0.01f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeOneHundredFiftyPercentGrowsKeyText() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizePortraitPercent = 150
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    assertEquals(27f, keyboard.keyTextSizeSp("q"), 0.01f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeDefaultKeyText() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    assertEquals(18f, keyboard.keyTextSizeSp("q"), 0.01f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun pcLayoutPortraitKeyTextIgnoresSize() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.sizePortraitPercent = 50
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    assertEquals(18f, keyboard.keyTextSizeSp("q"), 0.01f)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun portraitSizeFiftyPercentKeyHeightObserved() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setContentView(keyboard)
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizePortraitPercent = 50
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
    )
    keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)

    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val letter = checkNotNull(provider.createAccessibilityNodeInfo(24))
    val bounds = Rect()
    letter.getBoundsInScreen(bounds)
    assertTrue(bounds.height() / density in 36f..41f)
    activity.finish()
  }

  @Test
  fun passwordAnnouncesDigitLikeNormalField() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 20f, 170)

    assertEquals(listOf("1"), spoken)
    assertEquals(listOf("1"), typed)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeFillsScreenHeight() {
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 395f..405f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun phoneLayoutLandscapeStaysShortStrip() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 190f..200f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun landscapeSizeFiftyPercentHalvesHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizeLandscapePercent = 50
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 93f..103f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun landscapeSizeOneHundredFiftyPercentGrowsHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.sizeLandscapePercent = 150
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 288f..298f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun landscapeSizeFiftyPercentWithBothRowsOnSixRowKeyHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.showNumberRow = true
    keyboard.showSymbolsRow = true
    keyboard.sizeLandscapePercent = 50
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 93f..103f)
    assertTrue(keyboard.measuredHeight / density / 6 in 15f..18f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun landscapeSizeOneHundredFiftyPercentWithRowsOffFourRowKeyHeight() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.showNumberRow = false
    keyboard.showSymbolsRow = false
    keyboard.sizeLandscapePercent = 150
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )

    assertTrue(keyboard.measuredHeight / density in 288f..298f)
    assertTrue(keyboard.measuredHeight / density / 4 in 71f..76f)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun landscapeSizeDefaultKeyHeightObserved() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setContentView(keyboard)
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    val density = context.resources.displayMetrics.density
    keyboard.measure(
      View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST),
    )
    keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)

    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val letter = checkNotNull(provider.createAccessibilityNodeInfo(24))
    val bounds = Rect()
    letter.getBoundsInScreen(bounds)
    assertTrue(bounds.height() / density in 37f..41f)
    activity.finish()
  }

  @Test
  fun pcLayoutSpaceBarIsTall() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 80f, 200f, 100)
    touch(MotionEvent.ACTION_UP, 80f, 200f, 170)
    touch(MotionEvent.ACTION_DOWN, 80f, 55f, 200)
    touch(MotionEvent.ACTION_UP, 80f, 55f, 270)
    touch(MotionEvent.ACTION_DOWN, 80f, 344f, 300)
    touch(MotionEvent.ACTION_UP, 80f, 344f, 370)
    touch(MotionEvent.ACTION_DOWN, 80f, 87f, 400)
    touch(MotionEvent.ACTION_UP, 80f, 87f, 470)

    assertEquals(listOf(" ", "language", "picker", ","), typed)
  }

  @Test
  fun pcLayoutShiftDigitTypesSymbol() {
    preparePcKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 100)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 720f, 20f)), 150)
    assertEquals(listOf("!"), typed)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_uppercase), "!"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 720f, 20f)), 200)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 250)

    assertEquals(listOf("!"), typed)
  }

  @Test
  fun pcLayoutShiftLetterTypesCapital() {
    preparePcKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 100)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 150)
    assertEquals(listOf("Q"), typed)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_uppercase), "Q"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 200)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 250)

    assertEquals(listOf("Q"), typed)
  }

  @Test
  fun pcLayoutShiftTapIsStickyForNextKey() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 240f, 360f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 360f, 170)
    touch(MotionEvent.ACTION_DOWN, 720f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 720f, 20f, 270)
    touch(MotionEvent.ACTION_DOWN, 720f, 55f, 300)
    touch(MotionEvent.ACTION_UP, 720f, 55f, 370)

    assertEquals(listOf("!", "2"), typed)
  }

  @Test
  fun pcLayoutShiftHoldDoesNotToggleCaps() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 240f, 360f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 360f, 700)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 750)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 820)

    assertEquals(listOf("q"), typed)
  }

  @Test
  fun pcLayoutSymbolsPageStillReachable() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, true, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)

    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 400f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 400f, 20f, 270)
    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 300)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 350)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 400)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 450)

    assertEquals(listOf("@", "!", "@"), typed)
  }

  @Test
  fun phoneLayoutShiftToggleKeepsDigitTitles() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    keyboard.typingMethod = LIFT_TO_TYPE
    keyboard.showKeys(true, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)

    assertEquals(listOf("1"), spoken)
  }

  @Test
  fun leftEdgeDownAnnouncesAndTypesFirstSecondRowKey() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 10f, 120f, 100)
    assertEquals(listOf("a"), spoken)
    assertEquals(listOf("a"), typed)
    touch(MotionEvent.ACTION_UP, 10f, 120f, 170)

    assertEquals(listOf("a"), typed)
    assertEquals(1, released)
  }

  @Test
  fun rightEdgeDownAnnouncesAndTypesLastSecondRowKey() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 390f, 120f, 100)
    assertEquals(listOf("l"), spoken)
    assertEquals(listOf("l"), typed)
    touch(MotionEvent.ACTION_UP, 390f, 120f, 170)

    assertEquals(listOf("l"), typed)
    assertEquals(1, released)
  }

  @Test
  fun liftToTypeLeftEdgeDownAnnouncesFirstSecondRowKey() {
    keyboard.typingMethod = LIFT_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 10f, 120f, 100)

    assertEquals(listOf("a"), spoken)
    assertEquals(emptyList<String>(), typed)
  }

  @Test
  fun aKeyWidthIsCloseToQKeyWidth() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 40f, 120f, 100)
    touch(MotionEvent.ACTION_UP, 40f, 120f, 170)
    touch(MotionEvent.ACTION_DOWN, 50f, 120f, 200)
    touch(MotionEvent.ACTION_UP, 50f, 120f, 270)

    assertEquals(listOf("a", "s"), spoken)
    assertEquals(listOf("a", "s"), typed)
    assertEquals(2, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeColumnsAreVertical() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 240f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 390f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 390f, 20f, 270)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 300)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 370)
    touch(MotionEvent.ACTION_DOWN, 720f, 20f, 400)
    touch(MotionEvent.ACTION_UP, 720f, 20f, 470)

    assertEquals(listOf("z", "a", "q", "1"), spoken)
    assertEquals(listOf("z", "a", "q", "1"), typed)
    assertEquals(4, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeBottomColumnIsFirst() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 80f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 80f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 240f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 240f, 20f, 270)
    touch(MotionEvent.ACTION_DOWN, 390f, 20f, 300)
    touch(MotionEvent.ACTION_UP, 390f, 20f, 370)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 400)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 470)
    touch(MotionEvent.ACTION_DOWN, 720f, 20f, 500)
    touch(MotionEvent.ACTION_UP, 720f, 20f, 570)

    assertEquals(listOf("symbols", "z", "a", "q", "1"), typed)
    assertEquals(5, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeShiftAndDeleteAtColumnBottoms() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 240f, 360f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 360f, 170)
    touch(MotionEvent.ACTION_DOWN, 720f, 380f, 200)
    touch(MotionEvent.ACTION_UP, 720f, 380f, 270)

    assertEquals(
      listOf(
        context.getString(R.string.vietnamese_ime_uppercase),
        context.getString(R.string.vietnamese_ime_delete),
      ),
      spoken,
    )
    assertEquals(listOf("delete"), typed)
    assertEquals(2, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeShiftDigitTypesSymbolFromColumns() {
    preparePcKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 100)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 720f, 20f)), 150)
    assertEquals(listOf("!"), typed)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_uppercase), "!"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 720f, 20f)), 200)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 250)

    assertEquals(listOf("!"), typed)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeShiftLetterTypesCapitalFromColumns() {
    preparePcKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 100)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 150)
    assertEquals(listOf("Q"), typed)
    assertEquals(listOf(context.getString(R.string.vietnamese_ime_uppercase), "Q"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 200)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 250)

    assertEquals(listOf("Q"), typed)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeSymbolsPageColumns() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, true, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)

    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 400f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 400f, 20f, 270)
    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 240f, 360f)), 300)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 350)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 240f, 360f), Triple(1, 560f, 20f)), 400)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 240f, 360f)), 450)

    assertEquals(listOf("@", "!", "@"), typed)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcLayoutLandscapeSpaceBarIsTall() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 80f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 80f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 80f, 200f, 200)
    touch(MotionEvent.ACTION_UP, 80f, 200f, 270)
    touch(MotionEvent.ACTION_DOWN, 80f, 380f, 300)
    touch(MotionEvent.ACTION_UP, 80f, 380f, 370)

    assertEquals(listOf("symbols", " ", "enter"), typed)
    assertEquals(3, released)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun pcLayoutPortraitColumnsAreVertical() {
    preparePcKeyboardPortrait()

    touch(MotionEvent.ACTION_DOWN, 120f, 14f, 100)
    touch(MotionEvent.ACTION_UP, 120f, 14f, 170)
    touch(MotionEvent.ACTION_DOWN, 200f, 14f, 200)
    touch(MotionEvent.ACTION_UP, 200f, 14f, 270)
    touch(MotionEvent.ACTION_DOWN, 280f, 12f, 300)
    touch(MotionEvent.ACTION_UP, 280f, 12f, 370)
    touch(MotionEvent.ACTION_DOWN, 360f, 11f, 400)
    touch(MotionEvent.ACTION_UP, 360f, 11f, 470)

    assertEquals(listOf("z", "a", "q", "1"), spoken)
    assertEquals(listOf("z", "a", "q", "1"), typed)
    assertEquals(4, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun autoLayoutLandscapeUsesColumns() {
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)

    touch(MotionEvent.ACTION_DOWN, 240f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 240f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 390f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 390f, 20f, 270)

    assertEquals(listOf("z", "a"), spoken)
    assertEquals(listOf("z", "a"), typed)
  }

  @Test
  fun pcShiftHoldAcrossLayoutChangeDoesNotStickOrToggle() {
    preparePcKeyboard()

    touch(MotionEvent.ACTION_DOWN, 240f, 360f, 100)
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PHONE
    touch(MotionEvent.ACTION_UP, 240f, 360f, 170)
    assertEquals(emptyList<String>(), typed)
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.layout(0, 0, 800, 400)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 270)

    assertEquals(listOf("q"), typed)
  }

  @Test
  fun directSilentModeDownOnEmptyAreaDoesNotSilence() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    assertEquals(listOf("q"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 20f, 300f)), 150)
    assertEquals(listOf("q"), spoken)
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 20f, 300f)), 200)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 250)

    assertEquals(listOf("q"), typed)
  }

  @Test
  fun phoneLayoutTopRowIsDigits() {
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 20f, 170)

    assertEquals(listOf("1"), spoken)
    assertEquals(listOf("1"), typed)
  }

  @Test
  fun phoneRowsWithBothTogglesOffHaveFourRows() {
    prepareRowsKeyboard(showNumbers = false, showSymbols = false)

    touch(MotionEvent.ACTION_DOWN, 20f, 31f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 31f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 93f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 93f, 270)
    touch(MotionEvent.ACTION_DOWN, 76f, 156f, 300)
    touch(MotionEvent.ACTION_UP, 76f, 156f, 370)
    touch(MotionEvent.ACTION_DOWN, 200f, 218f, 400)
    touch(MotionEvent.ACTION_UP, 200f, 218f, 470)

    assertEquals(
      listOf("q", "a", "z", context.getString(R.string.vietnamese_ime_space)),
      spoken,
    )
    assertEquals(listOf("q", "a", "z", " "), typed)
    assertEquals(4, released)
  }

  @Test
  fun phoneRowsWithNumbersOffAndSymbolsOnHaveFiveRows() {
    prepareRowsKeyboard(showNumbers = false, showSymbols = true)

    touch(MotionEvent.ACTION_DOWN, 20f, 25f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 25f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 75f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 75f, 270)
    touch(MotionEvent.ACTION_DOWN, 20f, 125f, 300)
    touch(MotionEvent.ACTION_UP, 20f, 125f, 370)

    assertEquals(listOf("@", "q", "a"), spoken)
    assertEquals(listOf("@", "q", "a"), typed)
    assertEquals(3, released)
  }

  @Test
  fun phoneRowsWithBothTogglesOnHaveSixRows() {
    prepareRowsKeyboard(showNumbers = true, showSymbols = true)

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 62f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 62f, 270)
    touch(MotionEvent.ACTION_DOWN, 20f, 104f, 300)
    touch(MotionEvent.ACTION_UP, 20f, 104f, 370)

    assertEquals(listOf("@", "1", "q"), spoken)
    assertEquals(listOf("@", "1", "q"), typed)
    assertEquals(3, released)
  }

  @Test
  fun phoneSymbolsPageKeepsDigitsAndHidesSymbolsRow() {
    keyboard.showNumberRow = false
    keyboard.showSymbolsRow = true
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, true, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 25f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 25f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 75f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 75f, 270)
    touch(MotionEvent.ACTION_DOWN, 20f, 225f, 300)
    touch(MotionEvent.ACTION_UP, 20f, 225f, 370)

    assertEquals(
      listOf("1", "@", context.getString(R.string.vietnamese_ime_letters)),
      spoken,
    )
    assertEquals(listOf("1", "@", "letters"), typed)
    assertEquals(3, released)
  }

  @Test
  fun phoneShiftDoesNotChangeSymbolsRow() {
    keyboard.showNumberRow = true
    keyboard.showSymbolsRow = true
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(true, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 104f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 104f, 270)

    assertEquals(listOf("@", "Q"), spoken)
    assertEquals(listOf("@", "q"), typed)
    assertEquals(2, released)
  }

  @Test
  fun phoneSymbolsRowUsesCustomString() {
    keyboard.showSymbolsRow = true
    keyboard.symbolsRowString = "0123456789"
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 25f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 25f, 170)
    touch(MotionEvent.ACTION_DOWN, 380f, 25f, 200)
    touch(MotionEvent.ACTION_UP, 380f, 25f, 270)

    assertEquals(listOf("0", "9"), spoken)
    assertEquals(listOf("0", "9"), typed)
    assertEquals(2, released)
  }

  @Test
  fun rowTogglesRebuildLiveWithoutShowKeys() {
    prepareKeyboard()
    keyboard.showNumberRow = false

    touch(MotionEvent.ACTION_DOWN, 20f, 31f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 31f, 170)
    assertEquals(listOf("q"), spoken)
    assertEquals(listOf("q"), typed)

    keyboard.showSymbolsRow = true

    touch(MotionEvent.ACTION_DOWN, 20f, 25f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 25f, 270)
    assertEquals(listOf("q", "@"), spoken)
    assertEquals(listOf("q", "@"), typed)
    assertEquals(2, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcColumnsWithBothTogglesOffHaveFourColumns() {
    prepareRowsPcKeyboard(showNumbers = false, showSymbols = false)

    touch(MotionEvent.ACTION_DOWN, 100f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 100f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 300f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 300f, 20f, 270)
    touch(MotionEvent.ACTION_DOWN, 500f, 20f, 300)
    touch(MotionEvent.ACTION_UP, 500f, 20f, 370)
    touch(MotionEvent.ACTION_DOWN, 700f, 20f, 400)
    touch(MotionEvent.ACTION_UP, 700f, 20f, 470)
    touch(MotionEvent.ACTION_DOWN, 100f, 385f, 500)
    touch(MotionEvent.ACTION_UP, 100f, 385f, 570)

    assertEquals(listOf("symbols", "z", "a", "q", "delete"), typed)
    assertEquals(5, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcColumnsWithNumbersOffAndSymbolsOnHaveFiveColumns() {
    prepareRowsPcKeyboard(showNumbers = false, showSymbols = true)

    touch(MotionEvent.ACTION_DOWN, 720f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 720f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 240f, 20f, 200)
    touch(MotionEvent.ACTION_UP, 240f, 20f, 270)
    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 300)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 370)
    touch(MotionEvent.ACTION_DOWN, 720f, 380f, 400)
    touch(MotionEvent.ACTION_UP, 720f, 380f, 470)
    touch(MotionEvent.ACTION_DOWN, 80f, 385f, 500)
    touch(MotionEvent.ACTION_UP, 80f, 385f, 570)

    assertEquals(listOf("@", "z", "q", "_", "delete"), typed)
    assertEquals(5, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcColumnsWithBothTogglesOnHaveSixColumns() {
    prepareRowsPcKeyboard(showNumbers = true, showSymbols = true)

    touch(MotionEvent.ACTION_DOWN, 733f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 733f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 600f, 18f, 200)
    touch(MotionEvent.ACTION_UP, 600f, 18f, 270)
    touch(MotionEvent.ACTION_DOWN, 466f, 20f, 300)
    touch(MotionEvent.ACTION_UP, 466f, 20f, 370)
    touch(MotionEvent.ACTION_DOWN, 600f, 382f, 400)
    touch(MotionEvent.ACTION_UP, 600f, 382f, 470)

    assertEquals(listOf("@", "1", "q", "delete"), typed)
    assertEquals(4, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcSymbolsPageKeepsDigitsColumnAndHidesSymbolsColumn() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.showNumberRow = false
    keyboard.showSymbolsRow = true
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, true, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)

    touch(MotionEvent.ACTION_DOWN, 560f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 560f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 720f, 18f, 200)
    touch(MotionEvent.ACTION_UP, 720f, 18f, 270)
    touch(MotionEvent.ACTION_DOWN, 720f, 382f, 300)
    touch(MotionEvent.ACTION_UP, 720f, 382f, 370)
    touch(MotionEvent.ACTION_DOWN, 80f, 380f, 400)
    touch(MotionEvent.ACTION_UP, 80f, 380f, 470)

    assertEquals(listOf("@", "1", "delete", "enter"), typed)
    assertEquals(4, released)
  }

  @Test
  @Config(qualifiers = "w800dp-h400dp-land")
  fun pcShiftDoesNotChangeSymbolsColumn() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.showNumberRow = true
    keyboard.showSymbolsRow = true
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(true, false, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)

    touch(MotionEvent.ACTION_DOWN, 733f, 20f, 100)
    touch(MotionEvent.ACTION_UP, 733f, 20f, 170)
    touch(MotionEvent.ACTION_DOWN, 600f, 18f, 200)
    touch(MotionEvent.ACTION_UP, 600f, 18f, 270)

    assertEquals(listOf("@", "!"), spoken)
    assertEquals(listOf("@", "!"), typed)
    assertEquals(2, released)
  }

  @Test
  fun switchingToPcLayoutRequestsRelayout() {
    keyboard.showKeys(false, false, true, "Gửi")

    assertFalse(keyboard.isLayoutRequested)
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC

    assertTrue(keyboard.isLayoutRequested)
  }

  private fun prepareRowsKeyboard(showNumbers: Boolean, showSymbols: Boolean) {
    keyboard.showNumberRow = showNumbers
    keyboard.showSymbolsRow = showSymbols
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)
  }

  private fun prepareRowsPcKeyboard(showNumbers: Boolean, showSymbols: Boolean) {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.showNumberRow = showNumbers
    keyboard.showSymbolsRow = showSymbols
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)
  }

  @Test
  fun keyFeedbackFiresOnceOnDownWhenBothTogglesOn() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)
    assertEquals(listOf("q"), typed)
  }

  @Test
  fun keyFeedbackReportsEachToggleSeparately() {
    val feedback = installRecordingFeedback(vibrate = true, sound = false)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)
    assertEquals(listOf(feedbackCall(true, false, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)

    keyboard.vibrateOnKey = false
    keyboard.keySound = true
    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 270)

    assertEquals(
      listOf(
        feedbackCall(true, false, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(false, true, KeyboardKeySounds.Slot.NORMAL),
      ),
      feedback.calls,
    )
  }

  @Test
  fun keyFeedbackDoesNotFireWhenBothTogglesOff() {
    val feedback = installRecordingFeedback(vibrate = false, sound = false)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)

    assertTrue(feedback.calls.isEmpty())
    assertEquals(listOf("q"), typed)
  }

  @Test
  fun keyFeedbackDoesNotFireOnEmptyAreaDown() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 300f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 300f, 170)

    assertTrue(feedback.calls.isEmpty())
    assertTrue(typed.isEmpty())
  }

  @Test
  fun keyFeedbackDoesNotRepeatOnMove() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    keyboard.typingMethod = LIFT_TO_TYPE
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)
    touch(MotionEvent.ACTION_MOVE, 60f, 60f, 140)
    touch(MotionEvent.ACTION_MOVE, 100f, 60f, 160)
    touch(MotionEvent.ACTION_UP, 100f, 60f, 200)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)
    assertEquals(listOf("q", "w", "e"), spoken)
  }

  @Test
  fun keyFeedbackFiresOncePerFingerOnSecondFingerDown() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touchMulti(MotionEvent.ACTION_DOWN, 0, listOf(Triple(0, 20f, 60f)), 100)
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)
    touchMulti(MotionEvent.ACTION_POINTER_DOWN, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 150)
    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
      ),
      feedback.calls,
    )
    touchMulti(MotionEvent.ACTION_POINTER_UP, 1,
      listOf(Triple(0, 20f, 60f), Triple(1, 60f, 60f)), 200)
    touchMulti(MotionEvent.ACTION_UP, 0, listOf(Triple(0, 20f, 60f)), 250)

    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
      ),
      feedback.calls,
    )
    assertEquals(listOf("q", "w"), typed)
  }

  @Test
  fun keyFeedbackFiresOnFunctionKeyDown() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 360f, 175f, 100)
    touch(MotionEvent.ACTION_UP, 360f, 175f, 170)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE)), feedback.calls)
    assertEquals(listOf("delete"), typed)
  }

  @Test
  fun keyFeedbackUsesSpaceSoundForSpaceKey() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 200f, 225f, 100)
    touch(MotionEvent.ACTION_UP, 200f, 225f, 170)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.SPACE)), feedback.calls)
    assertEquals(listOf(" "), typed)
  }

  @Test
  fun keyFeedbackUsesNormalSoundForLetterDigitSymbolAndFunctionKeys() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)
    touch(MotionEvent.ACTION_DOWN, 20f, 25f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 25f, 270)
    touch(MotionEvent.ACTION_DOWN, 28f, 175f, 300)
    touch(MotionEvent.ACTION_UP, 28f, 175f, 370)
    keyboard.showKeys(false, true, true, "Gửi")
    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 400)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 470)

    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
      ),
      feedback.calls,
    )
    assertEquals(listOf("q", "1", "shift", "@"), typed)
  }

  @Test
  fun keyFeedbackUsesDeleteSoundForDeleteKey() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 170)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE)), feedback.calls)
    assertEquals(listOf("delete"), typed)
  }

  @Test
  fun keyFeedbackUsesEnterSoundForEnterKey() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 374f, 225f, 100)
    touch(MotionEvent.ACTION_UP, 374f, 225f, 170)

    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.ENTER)), feedback.calls)
    assertEquals(listOf("enter"), typed)
  }

  @Test
  fun keyFeedbackStaysSilentForMicKey() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    keyboard.micEnabled = true
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 294f, 225f, 100)
    touch(MotionEvent.ACTION_UP, 294f, 225f, 200)

    assertTrue(feedback.calls.isEmpty())
    assertTrue(typed.isEmpty())
  }

  @Test
  fun keyFeedbackDoesNotFireWhenBothTogglesOffEvenWithSoundsConfigured() {
    val feedback = installRecordingFeedback(vibrate = false, sound = false)
    keyboard.keySounds = KeyboardKeySounds.defaultSounds()
      .copy(normal = soundFor(KeyboardKeySounds.Slot.SPACE))
    keyboard.vibrateIntensityPercent = 50
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)

    assertTrue(feedback.calls.isEmpty())
    assertEquals(listOf("q"), typed)
  }

  @Test
  fun keyFeedbackDoubleTapDeletePlaysDeleteSoundOnBothDowns() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 170)
    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 200)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 270)

    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE),
        feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE),
      ),
      feedback.calls,
    )
    assertEquals(listOf("delete", "delete_word"), typed)
  }

  @Test
  fun keyFeedbackUsesReassignedSoundsAndIntensity() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 100)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 170)
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL)), feedback.calls)

    val reassigned = KeyboardKeySounds.defaultSounds()
      .copy(normal = soundFor(KeyboardKeySounds.Slot.SPACE))
    keyboard.keySounds = reassigned
    keyboard.vibrateIntensityPercent = 50
    touch(MotionEvent.ACTION_DOWN, 20f, 60f, 200)
    touch(MotionEvent.ACTION_UP, 20f, 60f, 270)

    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.NORMAL),
        feedbackCall(true, true, KeyboardKeySounds.Slot.SPACE, 50),
      ),
      feedback.calls,
    )
    assertEquals(reassigned, feedback.lastSetSounds)
  }

  @Test
  fun keyFeedbackFiresOncePerRepeatedDelete() {
    val feedback = installRecordingFeedback(vibrate = true, sound = true)
    prepareKeyboard()

    touch(MotionEvent.ACTION_DOWN, 368f, 175f, 100)
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE)), feedback.calls)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
    assertEquals(listOf(feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE)), feedback.calls)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(
      listOf(
        feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE),
        feedbackCall(true, true, KeyboardKeySounds.Slot.DELETE),
      ),
      feedback.calls,
    )
    assertEquals(listOf("delete", "delete"), typed)
    touch(MotionEvent.ACTION_UP, 368f, 175f, 1700)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

    assertEquals(2, feedback.calls.size)
    assertEquals(listOf("delete", "delete"), typed)
  }

  private fun installRecordingFeedback(vibrate: Boolean, sound: Boolean): RecordingKeyFeedback {
    keyboard.vibrateOnKey = vibrate
    keyboard.keySound = sound
    return RecordingKeyFeedback(context).also { keyboard.keyFeedback = it }
  }

  private class RecordingKeyFeedback(context: Context) : VietnameseKeyFeedback(context) {
    val calls = mutableListOf<FeedbackCall>()
    var lastSetSounds: KeySounds? = null

    override fun play(vibrate: Boolean, sound: Boolean, keySound: KeySound, vibratePercent: Int) {
      calls.add(FeedbackCall(vibrate, sound, keySound, vibratePercent))
    }

    override fun setSounds(sounds: KeySounds) {
      lastSetSounds = sounds
    }
  }

  private data class FeedbackCall(
    val vibrate: Boolean,
    val sound: Boolean,
    val keySound: KeySound,
    val vibratePercent: Int,
  )

  private fun soundFor(slot: KeyboardKeySounds.Slot): KeySound =
    KeyboardKeySounds.defaultSounds()[slot]

  private fun feedbackCall(
    vibrate: Boolean,
    sound: Boolean,
    slot: KeyboardKeySounds.Slot,
    vibratePercent: Int = 100,
  ) = FeedbackCall(vibrate, sound, soundFor(slot), vibratePercent)

  private fun prepareKeyboard() {
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 400, 250)
  }

  private fun preparePcKeyboard() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    keyboard.typingMethod = DIRECT_SILENT_TYPING
    keyboard.showKeys(false, false, true, "Gửi")
    keyboard.layout(0, 0, 800, 400)
  }

  private fun preparePcKeyboardPortrait() {
    keyboard.layoutSetting = VietnameseKeyboardView.LAYOUT_PC
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

  private fun touchMulti(
    actionMasked: Int, actionIndex: Int, points: List<Triple<Int, Float, Float>>, time: Long,
  ) {
    val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    val properties = points.map { (id, _, _) ->
      MotionEvent.PointerProperties().apply { this.id = id }
    }.toTypedArray()
    val coords = points.map { (_, x, y) ->
      MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
    }.toTypedArray()
    val event = MotionEvent.obtain(
      100, time, action, points.size, properties, coords,
      0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
    )
    try {
      keyboard.onTouchEvent(event)
    } finally {
      event.recycle()
    }
  }
}
