package com.google.android.accessibility.talkback.vietnameseime

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeyboardServiceTest {
  private val text = SpannableStringBuilder()
  private val actions = mutableListOf<Int>()
  private var rejectComposition = false
  private var finishCount = 0
  private var commits = 0
  private lateinit var service: VietnameseKeyboardService
  private lateinit var keyboard: View

  @Before
  fun setUp() {
    service = Robolectric.setupService(VietnameseKeyboardService::class.java)
    Selection.setSelection(text, 0)
    val connection = object : BaseInputConnection(View(service), true) {
      override fun getEditable(): Editable = text

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
    val provider = checkNotNull(keyboard.accessibilityNodeProvider)
    val host = checkNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    val id = (0 until host.childCount).first {
      provider.createAccessibilityNodeInfo(it)?.text?.toString() == label
    }
    assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null))
  }
}
