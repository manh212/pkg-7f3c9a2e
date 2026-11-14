package com.google.android.accessibility.talkback.vietnameseime

import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_NONE
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_SILENT_TYPING
import com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_WORDS
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD
import com.google.android.accessibility.utils.output.SpeechController.STATUS_SPOKEN
import java.time.Duration
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
  private lateinit var service: VietnameseKeyboardService
  private lateinit var keyboard: View

  @Before
  fun setUp() {
    service = Robolectric.setupService(VietnameseKeyboardService::class.java)
    val feedback = VietnameseKeySpeech({ label, started, completed ->
      spoken += label
      started()
      completed(STATUS_SPOKEN)
      true
    }, {})
    ReflectionHelpers.setField(service, "keySpeech", feedback)
    Selection.setSelection(text, 0)
    val connection = object : BaseInputConnection(View(service), true) {
      override fun getEditable(): Editable = text

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
  fun spaceCommitsTheComposedWordInOneEditorCall() {
    startEditor()
    press("a")
    press("a")
    press("sờ")
    press("Dấu cách")

    assertEquals("ấ ", text.toString())
    assertEquals(1, commits)
    assertEquals(0, finishCount)
  }

  @Test
  fun backspaceFallsBackWhenEditorRejectsCompositionUpdate() {
    startEditor()
    press("a")
    press("bê")
    rejectComposition = true
    press("Xóa")

    assertEquals("a", text.toString())
    assertEquals(1, finishCount)
    assertEquals(removed("bê"), spoken.last())
  }

  @Test
  fun backspaceReplacesSelectionAndKeepsFollowingTelexAtThatPosition() {
    text.append("abc")
    Selection.setSelection(text, 0, 3)
    startEditor { initialSelEnd = 3 }
    press("Xóa")
    press("a")
    press("a")

    assertEquals("â", text.toString())
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

    assertEquals("ấ", text.toString())
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

    assertEquals("a", text.toString())
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

    assertEquals("a\n", text.toString())
    assertTrue(actions.isEmpty())
  }

  @Test
  fun liftReadsAcceptedCharacterEvenWhenItsPreviewHasTheSameName() {
    setEcho(PREF_ECHO_CHARACTERS)
    setTypingMethod(LIFT_TO_TYPE)
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
    assertEquals("a", text.toString())
    assertEquals(listOf("a"), spoken)
    advance(80)
    assertEquals(listOf("a", "a"), spoken)
  }

  @Test
  fun turningVoiceOffStopsAnnouncementsAndLeavesKeysAccessible() {
    setEcho(PREF_ECHO_CHARACTERS)
    setTypingMethod(LIFT_TO_TYPE)
    startEditor()
    press("a")
    assertEquals(listOf("a"), spoken)

    val voiceKey = service.getString(R.string.pref_vietnamese_keyboard_own_voice_key)
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putBoolean(voiceKey, false).apply()
    val downTime = SystemClock.uptimeMillis()
    touch(downTime, MotionEvent.ACTION_DOWN, 20f, 125f)
    touch(downTime, MotionEvent.ACTION_CANCEL, 20f, 125f)
    press("Xóa")
    assertTrue(text.isEmpty())
    assertEquals(listOf("a"), spoken)
    assertEquals(0, editorReads)

    prefs.edit().putBoolean(voiceKey, true).apply()
    press("a")
    press("Xóa")
    assertTrue(text.isEmpty())
    assertEquals(listOf("a", "a", removed("a")), spoken)
  }

  @Test
  fun wordsOnlyDoesNotReadACharacterAfterItWasEntered() {
    setEcho(PREF_ECHO_WORDS)
    startEditor()
    press("a")
    assertTrue(spoken.isEmpty())
    press("Dấu cách")
    assertEquals(listOf("a"), spoken)
  }

  @Test
  fun backspaceReadsTheActualTelexCharacter() {
    startEditor()
    press("a")
    press("a")
    press("sờ")
    assertEquals(listOf("a", "â", "ấ"), spoken)
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(removed("ấ"), spoken.last())
    assertEquals(0, editorReads)
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
  fun passwordSpeechMasksBothInsertionAndDeletion() {
    startEditor {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val masked = service.getString(R.string.vietnamese_ime_password_character)
    press(masked)
    assertEquals("1", text.toString())
    press("Xóa")

    assertTrue(text.isEmpty())
    assertEquals(listOf(masked, removed(masked)), spoken)
    assertEquals(0, editorReads)
  }

  private fun startEditor(configure: EditorInfo.() -> Unit = {}) {
    val info = EditorInfo().apply {
      inputType = InputType.TYPE_CLASS_TEXT
      initialSelStart = 0
      initialSelEnd = 0
      configure()
    }
    service.onStartInput(info, false)
    keyboard = service.onCreateInputView()
    keyboard.layout(0, 0, 400, 250)
  }

  private fun press(label: String) {
    assertTrue(activate(label))
  }

  private fun activate(label: String): Boolean {
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    val id = (0 until host.childCount).first {
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
