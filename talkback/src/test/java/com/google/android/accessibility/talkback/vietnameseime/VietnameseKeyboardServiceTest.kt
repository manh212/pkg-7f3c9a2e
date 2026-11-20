package com.google.android.accessibility.talkback.vietnameseime

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings
import com.google.android.accessibility.talkback.selector.SelectorController
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_NONE
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_SILENT_TYPING
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_WORDS
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD
import java.time.Duration
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class VietnameseKeyboardServiceTest {
  private val text = SpannableStringBuilder()
  private val actions = mutableListOf<Int>()
  private val spoken = mutableListOf<String>()
  private var rejectComposition = false
  private var rejectDelete = false
  private var editorReads = 0
  private var finishCount = 0
  private var commits = 0
  private var dictatedCallback: ((String?) -> Unit)? = null
  private var dictationStops = 0
  private lateinit var service: VietnameseKeyboardService
  private lateinit var keyboard: View

  @Before
  fun setUp() {
    service = Robolectric.setupService(VietnameseKeyboardService::class.java)
    val feedback = VietnameseKeySpeech({ label, started, completed ->
      spoken += label
      started()
      completed()
      true
    }, {})
    ReflectionHelpers.setField(service, "keySpeech", feedback)
    Selection.setSelection(text, 0)
    val connection = object : BaseInputConnection(View(service), true) {
      override fun getEditable(): Editable = text

      override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
        ExtractedText().apply {
          val current = this@VietnameseKeyboardServiceTest.text
          text = current.toString()
          selectionStart = Selection.getSelectionStart(current)
          selectionEnd = Selection.getSelectionEnd(current)
        }

      override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
        editorReads++
        return super.getTextBeforeCursor(length, flags)
      }

      override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        !rejectDelete && super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

      override fun sendKeyEvent(event: KeyEvent): Boolean =
        !rejectDelete && super.sendKeyEvent(event)

      override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        !rejectComposition && super.setComposingText(text, newCursorPosition)

      override fun finishComposingText(): Boolean {
        finishCount++
        return super.finishComposingText()
      }

      override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        commits++
        return super.commitText(text, newCursorPosition)
      }

      override fun performEditorAction(actionCode: Int): Boolean = actions.add(actionCode)
    }
    ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    ReflectionHelpers.setField(service, "mIsInputViewShown", true)
    ReflectionHelpers.setField(service, "mDecorViewVisible", true)
  }

  @After
  fun tearDown() {
    service.onDestroy()
  }

  @Test
  fun autoCapitalizeCapitalizesFirstLetterOfField() {
    startEditor()
    press("ích")
    press("i")
    press("nờ")

    assertEquals("Xin", text.toString())
    press("sờ")

    assertEquals("Xín", text.toString())
  }

  @Test
  fun autoCapitalizeCapitalizesAfterPeriodSpace() {
    startEditor()
    press("ích")
    press("i")
    press("nờ")
    press("Dấu chấm")
    press("Dấu cách")
    press("xê")
    press("hát")
    press("a")
    press("o")
    press("ép")

    assertEquals("Xin. Chào", text.toString())
  }

  @Test
  fun autoCapitalizeDoesNotCapitalizeMidSentence() {
    startEditor()
    press("ích")
    press("i")
    press("nờ")
    press("Dấu cách")
    press("xê")
    press("hát")
    press("a")
    press("o")
    press("ép")

    assertEquals("Xin chào", text.toString())
  }

  @Test
  fun autoCapitalizeDoesNotCapitalizeWhenToggledOff() {
    setAutoCapitalize(false)
    startEditor()
    press("ích")
    press("i")
    press("nờ")

    assertEquals("xin", text.toString())
  }

  @Test
  fun autoCapitalizeDoesNotCapitalizeInPasswordField() {
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    tap(20f, 125f)

    assertEquals("a", text.toString())
  }

  @Test
  fun doubleSpacePeriodReplacesTwoSpacesWithPeriodSpace() {
    startEditor()
    press("a")
    press("Dấu cách")
    press("Dấu cách")

    assertEquals("A. ", text.toString())
  }

  @Test
  fun doubleSpacePeriodDoesNotFireOnSingleSpace() {
    startEditor()
    press("a")
    press("Dấu cách")

    assertEquals("A ", text.toString())
  }

  @Test
  fun doubleSpacePeriodDoesNotFireWhenToggledOff() {
    setDoubleSpacePeriod(false)
    startEditor()
    press("a")
    press("Dấu cách")
    press("Dấu cách")

    assertEquals("A  ", text.toString())
  }

  @Test
  fun doubleSpacePeriodDoesNotFireInPasswordField() {
    setTypingMethod(LIFT_TO_TYPE)
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    press("một")
    // In lift-to-type mode the space key confirms with a double-tap,
    // so each space needs two taps.
    tap(200f, 225f)
    tap(200f, 225f)
    tap(200f, 225f)
    tap(200f, 225f)

    assertEquals("1  ", text.toString())
  }

  @Test
  fun numberFieldShowsT9Pad() {
    startEditor { inputType = InputType.TYPE_CLASS_NUMBER }

    assertTrue(hasKey("năm"))
    assertTrue(hasKey(service.getString(R.string.vietnamese_ime_letters)))
    assertTrue(hasKey(service.getString(R.string.vietnamese_ime_delete)))
    assertFalse(hasKey("quy"))
    assertFalse(hasKey(service.getString(R.string.vietnamese_ime_space)))
    press("năm")

    assertEquals("5", text.toString())
  }

  @Test
  fun phoneFieldShowsT9Pad() {
    startEditor { inputType = InputType.TYPE_CLASS_PHONE }

    assertTrue(hasKey("năm"))
    assertFalse(hasKey("quy"))
    assertFalse(hasKey(service.getString(R.string.vietnamese_ime_space)))
  }

  @Test
  fun dateTimeFieldShowsT9Pad() {
    startEditor { inputType = InputType.TYPE_CLASS_DATETIME }

    assertTrue(hasKey("năm"))
    assertFalse(hasKey("quy"))
    assertFalse(hasKey(service.getString(R.string.vietnamese_ime_space)))
  }

  @Test
  fun numericPinFieldShowsT9Pad() {
    startEditor {
      inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    assertTrue(hasKey("năm"))
    assertFalse(hasKey("quy"))
    assertFalse(hasKey(service.getString(R.string.vietnamese_ime_space)))
  }

  @Test
  fun abcKeyOnT9PadOpensLettersPage() {
    startEditor { inputType = InputType.TYPE_CLASS_NUMBER }
    assertFalse(hasKey("quy"))

    press(service.getString(R.string.vietnamese_ime_letters))

    assertTrue(hasKey("quy"))
    assertTrue(hasKey(service.getString(R.string.vietnamese_ime_space)))

    press(service.getString(R.string.vietnamese_ime_symbols))

    assertFalse(hasKey("quy"))
    assertTrue(hasKey(service.getString(R.string.vietnamese_ime_space)))
  }

  @Test
  fun reenteringNumberFieldRestoresT9Pad() {
    startEditor { inputType = InputType.TYPE_CLASS_NUMBER }
    press(service.getString(R.string.vietnamese_ime_letters))
    assertTrue(hasKey("quy"))

    startEditor { inputType = InputType.TYPE_CLASS_NUMBER }

    assertTrue(hasKey("năm"))
    assertFalse(hasKey("quy"))
  }

  @Test
  fun doubleSpacePeriodFinishesComposingWordFirst() {
    startEditor()
    press("ích")
    press("i")
    press("nờ")
    press("Dấu cách")
    press("Dấu cách")

    assertEquals("Xin. ", text.toString())
    assertEquals(2, commits)
    assertEquals(0, finishCount)
  }

  @Test
  fun doubleSpacePeriodThenNextLetterCapitalizes() {
    startEditor()
    press("a")
    press("Dấu cách")
    press("Dấu cách")
    press("bê")

    assertEquals("A. B", text.toString())
  }

  @Test
  fun doubleSpacePeriodSpeaksThePeriod() {
    setEcho(PREF_ECHO_CHARACTERS)
    startEditor()
    press("a")
    press("Dấu cách")
    press("Dấu cách")

    assertEquals("A. ", text.toString())
    assertEquals(listOf("a hoa", "Dấu cách", "Dấu chấm"), spoken)
  }

  @Test
  fun spaceCommitsTheComposedWordInOneEditorCall() {
    startEditor()
    press("a")
    press("a")
    press("sờ")
    press("Dấu cách")

    assertEquals("Ấ ", text.toString())
    assertEquals(1, commits)
    assertEquals(0, finishCount)
  }

  @Test
  fun toneKeyReplacesToneAfterEditorReportsNoComposingSpan() {
    startEditor()
    press("mờ")
    press("a")
    press("ép")
    assertEquals("Mà", text.toString())
    service.onUpdateSelection(2, 2, 2, 2, -1, -1)
    press("gi")

    assertEquals("Mạ", text.toString())
  }

  @Test
  fun typesNguoiAfterAnEarlierSelectionUpdateWithNoComposingSpan() {
    setAutoCapitalize(false)
    startEditor()
    listOf("nờ", "gờ", "u", "vê kép", "o", "vê kép", "i").forEach(::press)
    service.onUpdateSelection(2, 2, 3, 3, -1, -1)
    press("ép")

    assertEquals("người", text.toString())
  }

  @Test
  fun physicalKeyboardComposesEvenWhenTheInputViewIsHidden() {
    service.onStartInput(EditorInfo().apply {
      inputType = InputType.TYPE_CLASS_TEXT
      initialSelStart = 0
      initialSelEnd = 0
    }, false)
    ReflectionHelpers.setField(service, "mIsInputViewShown", false)
    for (letter in "nguwowif dduowngf") {
      val keyCode = if (letter == ' ') KeyEvent.KEYCODE_SPACE else KeyEvent.KEYCODE_A + (letter - 'a')
      assertTrue(service.onKeyDown(keyCode, physicalKey(KeyEvent.ACTION_DOWN, keyCode)))
      assertTrue(service.onKeyUp(keyCode, physicalKey(KeyEvent.ACTION_UP, keyCode)))
    }

    assertEquals("người đường", text.toString())
  }

  @Test
  fun quickTelexRequiresTheBeginningOfTheWordAndCanBeDisabledLive() {
    setAutoCapitalize(false)
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_quick_telex_key, true)
    text.append("x")
    Selection.setSelection(text, 1)
    startEditor { initialSelStart = 1; initialSelEnd = 1 }
    repeat(2) { press("xê") }
    press("a")
    press("Dấu cách")
    repeat(2) { press("xê") }
    press("a")
    press("Dấu cách")

    assertEquals("xcca cha ", text.toString())
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_quick_telex_key, false)
    repeat(2) { press("xê") }
    press("a")

    assertEquals("xcca cha cca", text.toString())
  }

  @Test
  fun languageKeySwitchesTypingModeAndCanBeHiddenLive() {
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_show_language_key, true)
    setAutoCapitalize(false)
    startEditor()
    press("Chuyển sang gõ tiếng Anh")
    press("a")
    press("a")
    press("Dấu cách")
    press("Chuyển sang gõ tiếng Việt")
    press("a")
    press("a")

    assertEquals("aa â", text.toString())
    assertTrue(hasKey("Chuyển sang gõ tiếng Anh"))
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_show_language_key, false)
    assertFalse(hasKey("Chuyển sang gõ tiếng Anh"))
    assertTrue(hasKey("Đổi bàn phím"))
  }

  @Test
  fun hideKeyHidesTheKeyboardViewImmediatelyAndShowsItAgain() {
    startEditor()
    (keyboard as VietnameseKeyboardView).layoutSetting = VietnameseKeyboardView.LAYOUT_PC
    press(service.getString(R.string.vietnamese_ime_hide))
    assertEquals(View.GONE, (keyboard.parent as View).visibility)
    service.onStartInputView(EditorInfo(), false)
    assertEquals(View.VISIBLE, (keyboard.parent as View).visibility)
  }

  @Test
  fun threeLetterInitialExpandsInTheEditorWhenAVowelFollows() {
    setAutoCapitalize(false)
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_quick_telex_key, true)
    startEditor()
    repeat(3) { press("gờ") }
    assertEquals("ggg", text.toString())

    press("i")

    assertEquals("ghi", text.toString())
  }

  @Test
  fun shortcutMatchesTypedKeysBeforeTelexAndInsertsMultilineTextOnce() {
    saveShortcuts(mapOf("as" to "Một dòng\nHai dòng"))
    startEditor()
    press("a")
    press("sờ")
    assertEquals("Á", text.toString())
    press("Dấu cách")

    assertEquals("Một dòng\nHai dòng ", text.toString())
    assertEquals(1, commits)
  }

  @Test
  fun periodExpandsShortcut() {
    saveShortcuts(mapOf("as" to "Một câu"))
    startEditor()
    press("a")
    press("sờ")
    assertEquals("Á", text.toString())
    press("Dấu chấm")

    assertEquals("Một câu.", text.toString())
  }

  @Test
  fun commaExpandsShortcut() {
    saveShortcuts(mapOf("as" to "Một câu"))
    startEditor()
    press("a")
    press("sờ")
    assertEquals("Á", text.toString())
    press("Dấu phẩy")

    assertEquals("Một câu,", text.toString())
  }

  @Test
  fun questionMarkExpandsShortcut() {
    saveShortcuts(mapOf("as" to "Một câu"))
    startEditor()
    press("a")
    press("sờ")
    assertEquals("Á", text.toString())
    press("Số và ký hiệu")
    press("Dấu chấm hỏi")

    assertEquals("Một câu?", text.toString())
  }

  @Test
  fun periodAfterNonShortcutWordDoesNotExpand() {
    saveShortcuts(mapOf("as" to "Một câu"))
    startEditor()
    press("ích")
    press("i")
    press("nờ")
    assertEquals("Xin", text.toString())
    press("Dấu chấm")

    assertEquals("Xin.", text.toString())
  }

  @Test
  fun hardwareSpaceExpandsACommittedCaseSensitiveShortcutInEnglish() {
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_show_language_key, true)
    saveShortcuts(mapOf("VN" to "Việt Nam", "vn" to "văn nghệ"))
    startEditor()
    press("Chuyển sang gõ tiếng Anh")
    text.append("Xin VN")
    Selection.setSelection(text, text.length)
    val space = KeyEvent.KEYCODE_SPACE

    assertTrue(service.onKeyDown(space, physicalKey(KeyEvent.ACTION_DOWN, space)))
    assertTrue(service.onKeyUp(space, physicalKey(KeyEvent.ACTION_UP, space)))
    assertEquals("Xin Việt Nam ", text.toString())
    assertEquals(text.length, Selection.getSelectionEnd(text))
  }

  @Test
  fun hardwarePeriodExpandsACommittedCaseSensitiveShortcutInEnglish() {
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_show_language_key, true)
    saveShortcuts(mapOf("VN" to "Việt Nam", "vn" to "văn nghệ"))
    startEditor()
    press("Chuyển sang gõ tiếng Anh")
    text.append("Xin VN")
    Selection.setSelection(text, text.length)
    val period = KeyEvent.KEYCODE_PERIOD

    assertTrue(service.onKeyDown(period, physicalKey(KeyEvent.ACTION_DOWN, period)))
    assertTrue(service.onKeyUp(period, physicalKey(KeyEvent.ACTION_UP, period)))
    assertEquals("Xin Việt Nam.", text.toString())
    assertEquals(text.length, Selection.getSelectionEnd(text))
  }

  @Test
  fun shortcutRequiresAWholeCodeAndKeepsEntriesWhenDisabled() {
    saveShortcuts(mapOf("VN" to "Việt Nam"))
    text.append("VNextra")
    Selection.setSelection(text, 2)
    startEditor { initialSelStart = 2; initialSelEnd = 2 }
    press("Dấu cách")
    assertEquals("VN extra", text.toString())

    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_shortcuts_enabled_key, false)
    text.replace(0, text.length, "VN")
    Selection.setSelection(text, 2)
    startEditor { initialSelStart = 2; initialSelEnd = 2 }
    press("Dấu cách")

    assertEquals("VN ", text.toString())
    assertEquals(mapOf("VN" to "Việt Nam"), VietnameseKeyboardShortcuts.read(keyboardPrefs()))
  }

  @Test
  fun theShortcutCodeFieldAcceptsLiteralTelexKeys() {
    saveShortcuts(mapOf("as" to "Một câu"))
    startEditor {
      packageName = service.packageName
      privateImeOptions = VietnameseKeyboardShortcuts.LITERAL_CODE_FIELD
    }
    press("a")
    press("sờ")
    press("Dấu cách")

    assertEquals("as ", text.toString())
  }

  @Test
  fun physicalKeyboardLeavesShortcutAndPasswordKeysToTheEditor() {
    val code = KeyEvent.KEYCODE_A
    startEditor()
    assertFalse(service.onKeyDown(code, physicalKey(KeyEvent.ACTION_DOWN, code, KeyEvent.META_CTRL_ON)))
    assertFalse(service.onKeyUp(code, physicalKey(KeyEvent.ACTION_UP, code, KeyEvent.META_CTRL_ON)))
    startEditor { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    assertFalse(service.onKeyDown(code, physicalKey(KeyEvent.ACTION_DOWN, code)))
    assertTrue(text.isEmpty())
  }

  @Test
  fun wordNavigationDeletesTheWholeWordAtTheCursor() {
    text.append("xin người")
    val insideWord = 6
    Selection.setSelection(text, insideWord)
    startEditor { initialSelStart = insideWord; initialSelEnd = insideWord }
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.WORD)

    press("Xóa")

    assertEquals("xin ", text.toString())
    assertEquals(removed("người"), spoken.last())
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.CHARACTER)

    press("Xóa")

    assertEquals("xin", text.toString())
  }

  @Test
  fun lineNavigationDeletesTheWholeLineAtTheCursor() {
    text.append("Dòng đầu\nDòng giữa\nDòng cuối")
    val caret = text.indexOf("giữa") + 2
    Selection.setSelection(text, caret)
    startEditor { initialSelStart = caret; initialSelEnd = caret }
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.LINE)

    press("Xóa")

    assertEquals("Dòng đầu\nDòng cuối", text.toString())
    assertEquals("Đã xóa dòng", spoken.last())
  }

  @Test
  fun paragraphNavigationDeletesTheParagraphAndItsSeparatorWithEmoji() {
    text.append("Đầu\n\n🙂 Đoạn cần xóa\n\nCuối")
    val caret = text.indexOf("cần")
    Selection.setSelection(text, caret)
    startEditor { initialSelStart = caret; initialSelEnd = caret }
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.PARAGRAPH)

    press("Xóa")

    assertEquals("Đầu\n\nCuối", text.toString())
    assertEquals("Đã xóa đoạn", spoken.last())
  }

  @Test
  fun lineNavigationKeepsExplicitTextSelectionAsTheDeletionTarget() {
    text.append("một hai ba")
    Selection.setSelection(text, 4, 8)
    startEditor { initialSelStart = 4; initialSelEnd = 8 }
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.LINE)

    press("Xóa")

    assertEquals("một ba", text.toString())
  }

  @Test
  fun aWrappedLineKeepsTheParagraphSeparator() {
    val text = "First wrap\nOther paragraph"
    val range = textUnitToDelete(text, 8, paragraph = false) { _, _ -> 6 until 10 }
    assertEquals("First \nOther paragraph", text.removeRange(range))
  }

  @Test
  fun backspaceFallsBackWhenEditorRejectsCompositionUpdate() {
    startEditor()
    press("a")
    press("bê")
    rejectComposition = true
    press("Xóa")

    assertEquals("A", text.toString())
    assertEquals(1, finishCount)
    assertEquals(removed("bê"), spoken.last())
  }

  @Test
  fun retypingAfterWordLostRecoversToneFromEditorText() {
    startEditor()
    press("nờ")
    press("gờ")
    press("u")
    press("o")
    press("i")
    press("ép")
    assertEquals("Nguòi", text.toString())
    service.onUpdateSelection(5, 5, 5, 5, 0, 4)
    press("Xóa")
    assertEquals("Nguò", text.toString())
    press("vê kép")
    press("i")

    assertEquals("Người", text.toString())
  }

  @Test
  fun backspaceAfterRejectedCompositionStillLetsTelexFixTheWord() {
    startEditor()
    press("nờ")
    press("gờ")
    press("u")
    press("o")
    press("i")
    press("ép")
    assertEquals("Nguòi", text.toString())
    rejectComposition = true
    press("Xóa")
    assertEquals("Nguò", text.toString())
    rejectComposition = false
    press("vê kép")
    press("i")

    assertEquals("Người", text.toString())
  }

  @Test
  fun backspaceReplacesSelectionAndKeepsFollowingTelexAtThatPosition() {
    text.append("abc")
    Selection.setSelection(text, 0, 3)
    startEditor { initialSelEnd = 3 }
    press("Xóa")
    press("a")
    press("a")

    assertEquals("Â", text.toString())
  }

  @Test
  fun backspaceDeletesAnEmojiBeforeStartingANewComposition() {
    text.append("🙂")
    Selection.setSelection(text, 2)
    startEditor { initialSelStart = 2; initialSelEnd = 2 }
    press("Xóa")
    press("a")
    press("a")
    service.onUpdateSelection(2, 2, 1, 1, 0, 1)
    press("sờ")

    assertEquals("Ấ", text.toString())
  }

  @Test
  fun customEnterLabelExecutesEditorActionInsteadOfAddingANewline() {
    startEditor {
      imeOptions = EditorInfo.IME_ACTION_DONE
      actionLabel = "Lưu"
      actionId = 42
    }
    press("a")
    press("Lưu")

    assertEquals("A", text.toString())
    assertEquals(listOf(42), actions)
  }

  @Test
  fun unspecifiedAndNoEnterActionAllowNewline() {
    assertNull(VietnameseKeyboardService.editorAction(EditorInfo()))
    startEditor {
      imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
      actionLabel = "Gửi"
      actionId = 42
    }
    press("a")
    press("Xuống dòng")

    assertEquals("A\n", text.toString())
    assertTrue(actions.isEmpty())
  }

  @Test
  fun liftReadsAcceptedCharacterEvenWhenItsPreviewHasTheSameName() {
    setEcho(PREF_ECHO_CHARACTERS)
    setTypingMethod(LIFT_TO_TYPE)
    setAutoCapitalize(false)
    startEditor()
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 20f, 125f)
    assertEquals(listOf("a"), spoken)
    assertTrue(text.isEmpty())
    touch(downTime, MotionEvent.ACTION_UP, 20f, 125f)

    assertEquals("a", text.toString())
    assertEquals(listOf("a", "a"), spoken)
  }

  @Test
  fun directTouchReadsSelectionAndEditsWithOwnVoiceOff() {
    setTypingMethod(DIRECT_SILENT_TYPING)
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    setOwnVoice(false)
    setAutoCapitalize(false)
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putInt(service.getString(R.string.pref_direct_input_tap_delay_key), 15)
      .apply()
    startEditor()

    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 20f, 125f)
    assertTrue(text.isEmpty())
    assertEquals(listOf("a"), spoken)
    touch(downTime, MotionEvent.ACTION_MOVE, 105f, 125f)
    assertEquals(listOf("a", "dê"), spoken)
    touch(downTime, MotionEvent.ACTION_UP, 105f, 125f)
    advance(14)
    assertTrue(text.isEmpty())
    advance(1)
    assertEquals("d", text.toString())
    assertEquals(listOf("a", "dê", "dê"), spoken)

    tap(20f, 125f)
    advance(15)
    tap(200f, 225f)
    advance(15)
    tap(20f, 125f)
    advance(15)
    tapDelete()

    assertEquals("da ", text.toString())
    assertEquals(
      listOf("a", "dê", "dê", "a", "a", "Dấu cách", "da", "a", "a", "Xóa", removed("a")),
      spoken,
    )
  }

  @Test
  fun singleTapToTypeKeepsHoveredKeysSilentEvenWithOwnVoiceOn() {
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    setOwnVoice(true)
    startEditor()

    assertFalse(view().speakHoveredKeys)
  }

  @Test
  fun liftToTypeSpeaksHoveredKeysWithOwnVoiceOn() {
    setTypingMethod(LIFT_TO_TYPE)
    setOwnVoice(true)
    startEditor()

    assertTrue(view().speakHoveredKeys)
  }

  @Test
  fun directInputWaitsForTheEditAndTheReleaseSpeechDelay() {
    setEcho(PREF_ECHO_CHARACTERS)
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putInt(service.getString(R.string.pref_direct_input_tap_delay_key), 100)
      .putInt(VietnameseKeyboardTiming.RELEASE_SPEECH, 180)
      .apply()
    startEditor()
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 20f, 125f)
    touch(downTime, MotionEvent.ACTION_UP, 20f, 125f)

    advance(99)
    assertTrue(text.isEmpty())
    assertEquals(listOf("a"), spoken)
    advance(1)
    assertEquals("A", text.toString())
    assertEquals(listOf("a"), spoken)
    advance(80)
    assertEquals(listOf("a", "a hoa"), spoken)
  }

  @Test
  fun spaceKeyStaysAboveTheUnmappedAreaAtTheBottomOfTheInputView() {
    setTypingMethod(LIFT_TO_TYPE)
    startEditor()
    val inputView = checkNotNull(keyboard.parent) as View
    inputView.measure(
      View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST),
    )
    inputView.layout(0, 0, 400, inputView.measuredHeight)
    val spacing = (56 * service.resources.displayMetrics.density).roundToInt()
    assertEquals(spacing, inputView.height - keyboard.bottom)

    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    val space = (0 until host.childCount).mapNotNull { provider.createAccessibilityNodeInfo(it) }
      .first { it.text == "Dấu cách" }
    val bounds = Rect()
    @Suppress("DEPRECATION")
    space.getBoundsInParent(bounds)
    assertEquals(inputView.height - spacing, bounds.bottom)
    val now = SystemClock.uptimeMillis()
    val x = bounds.exactCenterX()
    val y = inputView.height - spacing / 2f
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
      val event = MotionEvent.obtain(now, now, action, x, y, 0)
      try {
        inputView.dispatchTouchEvent(event)
      } finally {
        event.recycle()
      }
    }
    assertTrue(text.isEmpty())
    assertTrue(spoken.isEmpty())
  }

  @Test
  fun bottomSpacingPreferenceMovesTheKeysWithoutChangingTheirMeasuredHeight() {
    startEditor()
    val inputView = checkNotNull(keyboard.parent) as View
    val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
    val height = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST)
    inputView.measure(width, height)
    val keysHeight = keyboard.measuredHeight

    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putInt(service.getString(R.string.pref_vietnamese_keyboard_bottom_spacing_key), 64)
      .commit()
    inputView.measure(width, height)
    inputView.layout(0, 0, 400, inputView.measuredHeight)

    assertEquals(keysHeight, keyboard.height)
    val spacing = (64 * service.resources.displayMetrics.density).roundToInt()
    assertEquals(spacing, inputView.height - keyboard.bottom)
  }

  @Test
  fun turningVoiceOffKeepsAnnouncementsAndLeavesKeysAccessible() {
    setEcho(PREF_ECHO_CHARACTERS)
    setTypingMethod(LIFT_TO_TYPE)
    startEditor()
    press("a")
    assertEquals(listOf("a hoa"), spoken)

    val voiceKey = service.getString(R.string.pref_vietnamese_keyboard_own_voice_key)
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putBoolean(voiceKey, false).apply()
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 20f, 125f)
    touch(downTime, MotionEvent.ACTION_CANCEL, 20f, 125f)
    press("Xóa")
    assertTrue(text.isEmpty())
    assertEquals(listOf("a hoa", "a", removed("a hoa")), spoken)
    assertEquals(1, editorReads)

    prefs.edit().putBoolean(voiceKey, true).apply()
    press("a")
    press("Xóa")
    assertTrue(text.isEmpty())
    assertEquals(listOf("a hoa", "a", removed("a hoa"), "a hoa", removed("a hoa")), spoken)
  }

  @Test
  fun typingWithOwnVoiceOffStillSubmitsTheVietnameseName() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    setOwnVoice(false)
    setAutoCapitalize(false)
    startEditor()
    press("sờ")

    assertEquals("s", text.toString())
    assertEquals(listOf("sờ"), spoken)
  }

  @Test
  fun deleteEchoWithOwnVoiceOffStillSubmitsTheVietnameseName() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    setOwnVoice(false)
    setAutoCapitalize(false)
    startEditor()
    press("sờ")
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(listOf("sờ", removed("sờ")), spoken)
  }

  @Test
  fun wordEchoWithOwnVoiceOffStillSubmitsTheVietnameseName() {
    setOwnVoice(false)
    setEcho(PREF_ECHO_WORDS)
    startEditor()
    press("a")
    assertTrue(spoken.isEmpty())
    press("Dấu cách")
    assertEquals(listOf("a hoa"), spoken)
  }

  @Test
  fun wordsOnlyDoesNotReadACharacterAfterItWasEntered() {
    setEcho(PREF_ECHO_WORDS)
    startEditor()
    press("a")
    assertTrue(spoken.isEmpty())
    press("Dấu cách")
    assertEquals(listOf("a hoa"), spoken)
  }

  @Test
  fun backspaceReadsTheActualTelexCharacter() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    startEditor()
    press("a")
    press("a")
    press("sờ")
    assertEquals(listOf("a hoa", "Â", "Ấ"), spoken)
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(removed("Ấ"), spoken.last())
    assertEquals(1, editorReads)
  }

  @Test
  fun backspaceReadsExistingTextWhenCharacterEchoIsOff() {
    setEcho(PREF_ECHO_NONE)
    text.append("b🙂")
    Selection.setSelection(text, text.length)
    startEditor { initialSelStart = 3; initialSelEnd = 3 }
    press("Xóa")
    press("Xóa")
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(listOf(removed("🙂"), removed("bê")), spoken)
  }

  @Test
  fun backspaceReportsASelectionAsOneEdit() {
    text.append("abc")
    Selection.setSelection(text, 0, 3)
    startEditor { initialSelEnd = 3 }
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(listOf(removed(service.getString(R.string.vietnamese_ime_selected_text))), spoken)
  }

  @Test
  fun rejectedBackspaceDoesNotAnnounceAnEdit() {
    text.append("b")
    Selection.setSelection(text, 1)
    startEditor { initialSelStart = 1; initialSelEnd = 1 }
    rejectDelete = true
    assertFalse(activate("Xóa"))

    assertEquals("b", text.toString())
    assertTrue(spoken.isEmpty())
  }

  @Test
  fun silentTypingDeletesWithoutReadingEditorText() {
    setEcho(PREF_ECHO_SILENT_TYPING)
    text.append("b")
    Selection.setSelection(text, 1)
    startEditor { initialSelStart = 1; initialSelEnd = 1 }
    press("Xóa")

    assertTrue(text.isEmpty())
    assertTrue(spoken.isEmpty())
    assertEquals(0, editorReads)
  }

  @Test
  fun micReadsNewTextWithKeyboardEchoOffAndDropsComposingWord() {
    setEcho(PREF_ECHO_NONE)
    text.append("Đoạn cũ. ")
    Selection.setSelection(text, text.length)
    enableMicWithFakeDictation()
    startEditor { initialSelStart = text.length; initialSelEnd = text.length }
    press("a")
    press("a")
    press("sờ")
    assertEquals("Đoạn cũ. Ấ", text.toString())
    press(micLabel())
    val callback = checkNotNull(dictatedCallback)
    callback("xin chào")

    assertEquals("Đoạn cũ. xin chào", text.toString())
    assertEquals(1, commits)
    assertEquals("xin chào", spoken.last())
  }

  @Test
  fun micNullCallbackSpeaksErrorAndInsertsNothing() {
    service.dictationAvailable = { true }
    service.dictationListen = { callback ->
      callback(null)
      false
    }
    setMicEnabled(true)
    startEditor()
    assertFalse(activate(micLabel()))

    assertTrue(text.isEmpty())
    assertEquals(0, commits)
    assertEquals(service.getString(R.string.dictation_unavailable), spoken.last())
  }

  @Test
  fun micHiddenWhenDictationUnavailable() {
    setMicEnabled(true)
    startEditor()
    assertFalse(hasMicLabel())

    service.dictationAvailable = { true }
    setMicEnabled(false)
    setMicEnabled(true)
    assertTrue(hasMicLabel())
  }

  @Test
  fun micHoldStartsOnlyAfterOneSecondAndReleaseStopsListening() {
    setTypingMethod(DIRECT_SILENT_TYPING)
    enableMicWithFakeDictation()
    startEditor()
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 294f, 225f)
    advance(999)
    assertNull(dictatedCallback)
    touch(downTime, MotionEvent.ACTION_UP, 294f, 225f)
    assertEquals(0, dictationStops)
    assertTrue(text.isEmpty())

    val secondDown = SystemClock.uptimeMillis()
    touch(secondDown, MotionEvent.ACTION_DOWN, 294f, 225f)
    advance(1000)
    assertTrue(dictatedCallback != null)
    touch(secondDown, MotionEvent.ACTION_UP, 294f, 225f)
    assertEquals(1, dictationStops)
    checkNotNull(dictatedCallback)("xin chào")
    assertEquals("xin chào", text.toString())
  }

  @Test
  fun doubleTapDeleteDeletesWholeWordAndKeepsSpace() {
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    text.append("hello world")
    Selection.setSelection(text, 11)
    startEditor { initialSelStart = 11; initialSelEnd = 11 }
    tapDelete()
    assertEquals("hello worl", text.toString())
    tapDelete()

    assertEquals("hello ", text.toString())
    assertEquals(removed("worl"), spoken.last())
  }

  @Test
  fun deleteWordAfterSpaceDeletesTheSpaceFirst() {
    setEcho(PREF_ECHO_WORDS)
    text.append("hello ")
    Selection.setSelection(text, 6)
    startEditor { initialSelStart = 6; initialSelEnd = 6 }
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.WORD)

    press("Xóa")

    assertEquals("hello", text.toString())
    assertEquals(listOf(removed(service.getString(R.string.vietnamese_ime_space))), spoken)

    press("Xóa")

    assertEquals("", text.toString())
    assertEquals(removed("hello"), spoken.last())
    SelectorController.updateSettingPrefForGranularity(service, CursorGranularity.CHARACTER)
  }

  @Test
  fun spaceReadsWordBeforeCursorEvenWhenWordWasNotTracked() {
    setEcho(PREF_ECHO_WORDS)
    text.append("hello")
    Selection.setSelection(text, 5)
    startEditor { initialSelStart = 5; initialSelEnd = 5 }

    press("Dấu cách")

    assertEquals("hello ", text.toString())
    assertEquals(listOf("hello"), spoken)
  }

  @Test
  fun singleTapModeSingleTapDeleteDeletesOneCharacter() {
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    text.append("hello world")
    Selection.setSelection(text, 11)
    startEditor { initialSelStart = 11; initialSelEnd = 11 }
    tapDelete()

    assertEquals("hello worl", text.toString())
  }

  @Test
  fun doubleTapModeDoubleTapDeleteDeletesOneCharacter() {
    setTypingMethod(DOUBLE_TAP)
    text.append("hello world")
    Selection.setSelection(text, 11)
    startEditor { initialSelStart = 11; initialSelEnd = 11 }
    tapDelete()
    tapDelete()
    assertEquals("hello worl", text.toString())
    tapDelete()
    tapDelete()

    assertEquals("hello wor", text.toString())
    assertFalse(spoken.contains(removed("worl")))
  }

  @Test
  fun liftToTypeModeDoubleTapDeleteDeletesOneCharacter() {
    setTypingMethod(LIFT_TO_TYPE)
    text.append("hello world")
    Selection.setSelection(text, 11)
    startEditor { initialSelStart = 11; initialSelEnd = 11 }
    tapDelete()
    tapDelete()

    assertEquals("hello worl", text.toString())
    assertFalse(spoken.contains(removed("worl")))
  }

  @Test
  fun doubleTapDeleteClearsComposingWord() {
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    startEditor()
    press("mờ")
    press("a")
    assertEquals("Ma", text.toString())
    tapDelete()
    assertEquals("M", text.toString())
    tapDelete()
    assertEquals("", text.toString())
    press("gi")

    assertEquals("J", text.toString())
  }

  @Test
  fun doubleTapDeleteInPasswordFieldReadsLikeNormalField() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    setTypingMethod(SINGLE_TAP_TO_TYPE)
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    press("một")
    press("một")
    assertEquals("11", text.toString())
    tapDelete()
    assertEquals("1", text.toString())
    tapDelete()

    assertTrue(text.isEmpty())
    assertEquals(removed("một"), spoken.last())
    assertEquals(2, spoken.count { it == removed("một") })
    assertTrue(spoken.contains("một"))
  }

  @Test
  fun passwordSpeechReadsInsertionAndDeletionLikeNormalField() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    press("một")
    assertEquals("1", text.toString())
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(listOf("một", removed("một")), spoken)
    assertEquals(0, editorReads)
  }

  @Test
  fun passwordFieldReadsTypedLetterLikeNormalField() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    press("sờ")

    assertEquals("s", text.toString())
    assertEquals(listOf("sờ"), spoken)
  }

  @Test
  fun passwordFieldReadsWordOnSpace() {
    setEcho(PREF_ECHO_CHARACTERS_AND_WORDS)
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    press("a")
    press("Dấu cách")

    assertEquals("a ", text.toString())
    assertEquals(listOf("a", "a"), spoken)
  }

  @Test
  fun keySoundsDefaultOnFreshService() {
    startEditor()

    assertEquals(KeyboardKeySounds.defaultSounds(), view().keySounds)
    assertEquals(100, view().vibrateIntensityPercent)
  }

  @Test
  fun keySoundPrefsLandOnView() {
    startEditor()
    setKeySound(KeyboardKeySounds.Slot.SPACE, "space")
    setKeySound(KeyboardKeySounds.Slot.NORMAL, "click")
    setKeySound(KeyboardKeySounds.Slot.DELETE, "tick")
    setKeySound(KeyboardKeySounds.Slot.ENTER, "clock")

    assertEquals(
      KeySounds(
        space = KeyboardKeySounds.resolve(service, KeyboardKeySounds.Slot.SPACE),
        normal = KeyboardKeySounds.resolve(service, KeyboardKeySounds.Slot.NORMAL),
        delete = KeyboardKeySounds.resolve(service, KeyboardKeySounds.Slot.DELETE),
        enter = KeyboardKeySounds.resolve(service, KeyboardKeySounds.Slot.ENTER),
      ),
      view().keySounds,
    )
  }

  @Test
  fun vibrationLevelAppliesLiveWhenChangedAfterKeyboardShown() {
    startEditor()
    setVibrationLevel(37)

    assertEquals(37, view().vibrateIntensityPercent)
    setVibrationLevel(150)

    assertEquals(100, view().vibrateIntensityPercent)
    setVibrationLevel(-5)

    assertEquals(0, view().vibrateIntensityPercent)
  }

  @Test
  fun keySoundPrefsDoNotCancelComposingWord() {
    startEditor()
    press("a")
    press("a")
    press("sờ")
    assertEquals("Ấ", text.toString())
    assertEquals(0, finishCount)

    setKeySound(KeyboardKeySounds.Slot.DELETE, "tick")
    setVibrationLevel(40)

    assertEquals("Ấ", text.toString())
    assertEquals(0, finishCount)
    assertEquals(40, view().vibrateIntensityPercent)
    press("Dấu cách")

    assertEquals("Ấ ", text.toString())
    assertEquals(1, commits)
    assertEquals(0, finishCount)
  }

  private fun enableMicWithFakeDictation() {
    service.dictationAvailable = { true }
    service.dictationListen = { callback ->
      dictatedCallback = callback
      true
    }
    service.dictationStop = { dictationStops++ }
    setMicEnabled(true)
  }

  private fun setMicEnabled(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(service.getString(R.string.pref_vietnamese_keyboard_mic_enabled_key), enabled)
      .apply()
  }

  private fun micLabel() = service.getString(R.string.vietnamese_ime_voice_input)

  private fun hasMicLabel(): Boolean = hasKey(micLabel())

  companion object {
    // Virtual view ids are assigned once, in first-seen order, and never
    // cleared across pages, so a key on a page built after another page can
    // hold an id above that page's child count. hasKey and activate must scan
    // the whole id space instead of treating the child index as the id:
    // after the T9 pad is built first, the space key's id (45) sits above
    // the letters page's child count (45).
    private const val VIRTUAL_ID_SCAN_LIMIT = 256
  }

  private fun hasKey(label: String): Boolean {
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    return (0 until VIRTUAL_ID_SCAN_LIMIT).any {
      provider.createAccessibilityNodeInfo(it)?.text?.toString() == label
    }
  }

  private fun physicalKey(action: Int, code: Int, modifiers: Int = 0): KeyEvent =
    KeyEvent(0L, 0L, action, code, 0, modifiers, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)

  private fun startEditor(configure: EditorInfo.() -> Unit = {}) {
    val info = EditorInfo().apply {
      inputType = InputType.TYPE_CLASS_TEXT
      initialSelStart = 0
      initialSelEnd = 0
      configure()
    }
    service.onStartInput(info, false)
    keyboard = (service.onCreateInputView() as ViewGroup).getChildAt(0)
    keyboard.layout(0, 0, 400, 250)
  }

  private fun press(label: String) {
    assertTrue(activate(label))
  }

  private fun activate(label: String): Boolean {
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    val id = (0 until VIRTUAL_ID_SCAN_LIMIT).first {
      provider.createAccessibilityNodeInfo(it)?.text?.toString() == label
    }
    return provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)
  }

  private fun setEcho(echo: Int) = KeyboardEchoSettings.write(
    SharedPreferencesUtils.getSharedPreferences(service), service.resources, ON_SCREEN_KEYBOARD, echo,
  )

  private fun setTypingMethod(method: Int) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putString(service.getString(R.string.pref_typing_confirmation_key), method.toString())
      .apply()
  }

  private fun keyboardPrefs() = SharedPreferencesUtils.getSharedPreferences(service)

  private fun setKeyboardSwitch(key: Int, enabled: Boolean) {
    keyboardPrefs().edit().putBoolean(service.getString(key), enabled).apply()
  }

  private fun saveShortcuts(entries: Map<String, String>) {
    VietnameseKeyboardShortcuts.write(keyboardPrefs(), entries)
    setKeyboardSwitch(R.string.pref_vietnamese_keyboard_shortcuts_enabled_key, true)
  }

  private fun tapDelete() {
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 368f, 175f)
    touch(downTime, MotionEvent.ACTION_UP, 368f, 175f)
    advance(150)
  }

  private fun setAutoCapitalize(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(service.getString(R.string.pref_vietnamese_keyboard_auto_capitalize_key), enabled)
      .apply()
  }

  private fun setOwnVoice(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(service.getString(R.string.pref_vietnamese_keyboard_own_voice_key), enabled)
      .apply()
  }

  private fun setDoubleSpacePeriod(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(service.getString(R.string.pref_vietnamese_keyboard_double_space_period_key), enabled)
      .apply()
  }

  private fun setKeySound(slot: KeyboardKeySounds.Slot, value: String) {
    val key = when (slot) {
      KeyboardKeySounds.Slot.SPACE -> R.string.pref_vietnamese_keyboard_key_sound_space_key
      KeyboardKeySounds.Slot.NORMAL -> R.string.pref_vietnamese_keyboard_key_sound_normal_key
      KeyboardKeySounds.Slot.DELETE -> R.string.pref_vietnamese_keyboard_key_sound_delete_key
      KeyboardKeySounds.Slot.ENTER -> R.string.pref_vietnamese_keyboard_key_sound_enter_key
    }
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putString(service.getString(key), value)
      .apply()
  }

  private fun setVibrationLevel(percent: Int) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putInt(service.getString(R.string.pref_vietnamese_keyboard_vibration_level_key), percent)
      .apply()
  }

  private fun view(): VietnameseKeyboardView = keyboard as VietnameseKeyboardView

  private fun tap(x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, x, y)
    touch(downTime, MotionEvent.ACTION_UP, x, y)
  }

  private fun touch(downTime: Long, action: Int, x: Float, y: Float) {
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
    try {
      keyboard.onTouchEvent(event)
    } finally {
      event.recycle()
    }
  }

  private fun advance(milliseconds: Long) =
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

  private fun removed(label: String) = service.getString(R.string.template_text_removed, label)
}
