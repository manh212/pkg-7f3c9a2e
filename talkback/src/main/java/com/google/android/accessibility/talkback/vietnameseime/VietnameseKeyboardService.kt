package com.google.android.accessibility.talkback.vietnameseime

import android.content.SharedPreferences
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.style.LocaleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.ImeEditFeedback
import com.google.android.accessibility.utils.input.TextEventFilter
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_NO_HISTORY
import com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable
import com.google.android.accessibility.utils.output.SpeechController.UtteranceStartRunnable
import com.google.android.accessibility.utils.output.SpeechController.UTTERANCE_GROUP_KEYBOARD
import java.util.Locale

class VietnameseKeyboardService : InputMethodService() {
  private lateinit var prefs: SharedPreferences
  private var keyboard: VietnameseKeyboardView? = null
  private var editor: EditorInfo? = null
  private var word = ""
  private var composing = false
  private var cursor = -1
  private var selectionStart = -1
  private var expectedCursor = -1
  private var composingStart = -1
  private val pendingSelections = ArrayDeque<Int>()
  private var composingUnavailable = false
  private var shifted = false
  private var symbols = false
  private var vietnamese = true
  private var secure = false
  private var telexAllowed = false
  private var echo = TextEventFilter.PREF_ECHO_NONE
  private var method = 0
  private var timing = VietnameseKeyboardTiming()
  private val keyNames by lazy { VietnameseKeyNames(this) }
  private var windowShown = false
  private val main = Handler(Looper.getMainLooper())
  private val keySpeech = VietnameseKeySpeech(::submitKeySpeech) {
    speechController()?.stopUtteranceGroup(UTTERANCE_GROUP_KEYBOARD)
  }
  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == getString(R.string.pref_typing_confirmation_key) ||
      key == getString(R.string.pref_direct_input_tap_delay_key) ||
      VietnameseKeyboardTiming.isTimingKey(key) ||
      key == KeyboardEchoSettings.key(resources, ON_SCREEN_KEYBOARD)) readPreferences()
  }

  override fun onCreate() {
    super.onCreate()
    prefs = SharedPreferencesUtils.getSharedPreferences(this)
    readPreferences()
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
  }

  override fun onDestroy() {
    prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    endTouchSession()
    TalkBackService.getInstance()?.setVietnameseKeyboardBounds(null)
    super.onDestroy()
  }

  override fun onCreateInputView(): View {
    val view = VietnameseKeyboardView(this, ::announceKey, ::activateKey) {
      keySpeech.release(timing.releaseSpeechMs)
    }
    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportKeyboardBounds() }
    keyboard = view
    configureView()
    updateLayout()
    return view
  }

  override fun onEvaluateFullscreenMode(): Boolean = false

  override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    endTouchSession()
    if (restarting && composing) currentInputConnection?.finishComposingText()
    editor = attribute
    word = ""
    composing = false
    composingUnavailable = false
    composingStart = -1
    pendingSelections.clear()
    cursor = attribute?.initialSelEnd ?: -1
    selectionStart = attribute?.initialSelStart ?: -1
    expectedCursor = cursor
    shifted = false
    val inputType = attribute?.inputType ?: InputType.TYPE_NULL
    secure = isPassword(inputType)
    telexAllowed = allowsTelex(inputType)
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    symbols = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
    configureView()
    updateLayout()
  }

  override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    configureView()
    updateLayout()
  }

  override fun onWindowShown() {
    super.onWindowShown()
    windowShown = true
    keyboard?.post(::reportKeyboardBounds)
  }

  override fun onWindowHidden() {
    windowShown = false
    endTouchSession()
    super.onWindowHidden()
    reportKeyboardBounds()
  }

  private fun reportKeyboardBounds() {
    val view = keyboard
    val bounds = if (windowShown && view != null && view.isShown && view.width > 0 && view.height > 0) {
      val location = IntArray(2)
      view.getLocationOnScreen(location)
      Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    } else null
    TalkBackService.getInstance()?.setVietnameseKeyboardBounds(bounds)
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    endTouchSession()
    if (composing) finishWord(currentInputConnection)
    super.onFinishInputView(finishingInput)
  }

  override fun onFinishInput() {
    endTouchSession()
    if (composing) finishWord(currentInputConnection)
    editor = null
    word = ""
    composing = false
    composingStart = -1
    pendingSelections.clear()
    cursor = -1
    selectionStart = -1
    expectedCursor = -1
    super.onFinishInput()
  }

  override fun onUpdateSelection(
    oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int,
  ) {
    super.onUpdateSelection(
      oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
    )
    if (composing && composingStart < 0 && candidatesStart >= 0 &&
      newSelStart == newSelEnd && newSelEnd == candidatesEnd &&
      candidatesEnd - candidatesStart == word.length) {
      composingStart = candidatesStart
      anticipateSelection(newSelEnd)
    }
    if (newSelStart == newSelEnd && consumeExpectedSelection(newSelEnd)) return
    pendingSelections.clear()
    val stillComposing = newSelStart == newSelEnd &&
      (newSelEnd == expectedCursor ||
        (composingStart >= 0 && composingStart == candidatesStart && newSelEnd == candidatesEnd))
    if (composing && !stillComposing) {
      keyboard?.cancelInput()
      finishWord(currentInputConnection)
    }
    cursor = newSelEnd
    selectionStart = newSelStart
    if (!composing) {
      if (newSelStart != newSelEnd || (expectedCursor >= 0 && cursor != expectedCursor)) word = ""
      expectedCursor = cursor
    }
  }

  private fun endTouchSession() {
    keyboard?.cancelInput()
    keySpeech.cancel(stopSpeech = true)
  }

  private fun readPreferences() {
    val newMethod = SharedPreferencesUtils.getIntFromStringPref(
      prefs, resources, R.string.pref_typing_confirmation_key, R.string.pref_typing_confirmation_default,
    )
    val newTiming = VietnameseKeyboardTiming.read(this, prefs)
    if (method != newMethod || timing != newTiming) endTouchSession()
    method = newMethod
    timing = newTiming
    echo = KeyboardEchoSettings.read(prefs, resources, ON_SCREEN_KEYBOARD)
    configureView()
  }

  private fun configureView() {
    keyboard?.apply {
      typingMethod = method
      timing = this@VietnameseKeyboardService.timing
      hideLetterSpeech = secure
    }
  }

  private fun updateLayout() {
    keyboard?.showKeys(shifted, symbols, vietnamese && telexAllowed, getString(actionTitle()))
  }

  private fun activateKey(id: String) {
    if (editor == null || !isInputViewShown) return
    val connection = currentInputConnection ?: return
    when (id) {
      VietnameseKeyboardView.SHIFT -> shifted = !shifted
      VietnameseKeyboardView.SYMBOLS -> symbols = true
      VietnameseKeyboardView.LETTERS -> symbols = false
      VietnameseKeyboardView.LANGUAGE -> {
        finishWord(connection)
        if (telexAllowed) vietnamese = !vietnamese
      }
      VietnameseKeyboardView.PICKER -> {
        finishWord(connection)
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
      }
      VietnameseKeyboardView.DELETE -> delete(connection)
      VietnameseKeyboardView.ENTER -> enter(connection)
      else -> {
        val letter = id.single()
        type(connection, if (shifted) letter.uppercaseChar() else letter)
        if (!shifted || !letter.isLetter()) return
        shifted = false
      }
    }
    if (id == VietnameseKeyboardView.LANGUAGE && telexAllowed) {
      speak(getString(if (vietnamese) R.string.vietnamese_ime_telex_on else R.string.vietnamese_ime_telex_off))
    }
    updateLayout()
  }

  private fun type(connection: InputConnection, letter: Char) {
    val useTelex = vietnamese && telexAllowed && !composingUnavailable
    if (letter.isLetter() && useTelex) {
      if (word.length >= MAX_WORD_LENGTH) finishWord(connection)
      val updated = Telex.apply(word, letter)
      markEdit()
      if (updateComposition(connection, updated)) {
        val changed = changedCharacter(word, updated)
        word = updated
        if (echoesCharacters() && changed != null) speakEnteredCharacter(changed)
        return
      }
      composingUnavailable = true
      finishWord(connection)
    }
    val finished = if ((!useTelex && letter.isLetterOrDigit()) || secure) "" else finishWord(connection)
    markEdit()
    val insertionStart = insertionStart()
    val previousCursor = cursor
    val previousSelectionStart = selectionStart
    anticipateSelection(if (insertionStart >= 0) insertionStart + 1 else -1)
    val result = if (editor?.inputType == InputType.TYPE_NULL) {
      sendKeyChar(letter)
      true
    } else {
      connection.commitText(letter.toString(), 1)
    }
    if (!result) {
      expectedCursor = previousCursor
      cursor = previousCursor
      selectionStart = previousSelectionStart
      pendingSelections.clear()
      return
    }
    if (!useTelex && !secure && letter.isLetterOrDigit()) {
      if (word.length >= MAX_WORD_LENGTH) word = ""
      word += letter
    }
    if (echoesWords() && finished.isNotEmpty()) speak(finished)
    else if (echoesCharacters() && !secure) speakEnteredCharacter(letter)
  }

  private fun delete(connection: InputConnection) {
    if (composing && word.isNotEmpty()) {
      val shortened = word.dropLast(1)
      markEdit()
      if (updateComposition(connection, shortened)) {
        word = shortened
        if (shortened.isEmpty()) finishWord(connection)
      }
      return
    }
    word = word.dropLast(1)
    markEdit()
    if (selectionStart >= 0 && cursor >= 0 && selectionStart != cursor) {
      anticipateSelection(insertionStart())
      connection.commitText("", 1)
      return
    }
    if (cursor > 0) anticipateSelection(cursor - 1)
    if (editor?.inputType == InputType.TYPE_NULL || !connection.deleteSurroundingTextInCodePoints(1, 0)) {
      connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
      connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
  }

  private fun updateComposition(connection: InputConnection, updated: String): Boolean {
    val start = if (composing) composingStart else insertionStart()
    val previousCursor = cursor
    val previousSelectionStart = selectionStart
    anticipateSelection(if (start >= 0) start + updated.length else -1)
    if (!connection.setComposingText(updated, 1)) {
      expectedCursor = previousCursor
      cursor = previousCursor
      selectionStart = previousSelectionStart
      pendingSelections.clear()
      return false
    }
    composingStart = start
    composing = true
    return true
  }

  private fun insertionStart(): Int =
    if (selectionStart >= 0 && cursor >= 0) minOf(selectionStart, cursor) else cursor

  private fun anticipateSelection(position: Int) {
    if (position >= 0 && position != expectedCursor) {
      if (pendingSelections.size >= 16) pendingSelections.removeFirst()
      pendingSelections.addLast(position)
    }
    expectedCursor = position
    cursor = position
    selectionStart = position
  }

  private fun consumeExpectedSelection(position: Int): Boolean {
    val index = pendingSelections.indexOf(position)
    if (index < 0) return false
    repeat(index + 1) { pendingSelections.removeFirst() }
    return true
  }

  private fun finishWord(connection: InputConnection?): String {
    if (composing) {
      connection?.finishComposingText()
      cursor = expectedCursor
    }
    val completed = word
    word = ""
    composing = false
    composingStart = -1
    return completed
  }

  private fun enter(connection: InputConnection) {
    val finished = finishWord(connection)
    if (finished.isNotEmpty() && echoesWords()) speak(finished)
    if (!sendDefaultEditorAction(true)) {
      markEdit()
      if (editor?.inputType == InputType.TYPE_NULL) sendKeyChar('\n')
      else connection.commitText("\n", 1)
    }
  }

  private fun actionTitle(): Int {
    val info = editor ?: return R.string.vietnamese_ime_enter
    if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
      return R.string.vietnamese_ime_enter
    }
    return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
      EditorInfo.IME_ACTION_DONE -> R.string.vietnamese_ime_done
      EditorInfo.IME_ACTION_SEND -> R.string.vietnamese_ime_send
      EditorInfo.IME_ACTION_SEARCH -> R.string.vietnamese_ime_search
      EditorInfo.IME_ACTION_NEXT -> R.string.vietnamese_ime_next
      EditorInfo.IME_ACTION_PREVIOUS -> R.string.vietnamese_ime_previous
      EditorInfo.IME_ACTION_GO -> R.string.vietnamese_ime_go
      else -> R.string.vietnamese_ime_enter
    }
  }

  private fun markEdit() {
    ImeEditFeedback.recordEdit(editor?.packageName)
  }

  private fun announceKey(label: String) {
    if (label.isEmpty()) keySpeech.cancel(stopSpeech = true)
    else keySpeech.explore(keyNames.spokenName(label), timing.exploreSpeechMs)
  }

  private fun speakEnteredCharacter(character: Char) {
    speak(keyNames.spokenName(characterLabel(character)))
  }

  private fun submitKeySpeech(label: String, started: () -> Unit, completed: (Int) -> Unit): Boolean =
    speak(label, { main.post { started() } }, { status -> main.post { completed(status) } })

  private fun speak(
    label: String,
    started: UtteranceStartRunnable? = null,
    completed: UtteranceCompleteRunnable? = null,
  ): Boolean {
    val speaker = speechController() ?: return false
    if (!speaker.isTtsReady || speaker.isMute) return false
    if (speaker.waitsForCurrentSpeech() &&
      speaker.hasOtherSpeech(UTTERANCE_GROUP_KEYBOARD)) return false
    val localized = SpannableString(label)
    localized.setSpan(LocaleSpan(VIETNAMESE), 0, localized.length, 0)
    val options = SpeakOptions.create()
        .setUtteranceGroup(UTTERANCE_GROUP_KEYBOARD)
        .setQueueMode(QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH)
        .setFlags(
          FLAG_NO_HISTORY or FLAG_FORCE_FEEDBACK_ALL or FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP or
            FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP or
            FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP,
        )
        .setCompletedAction(completed)
    if (started != null) options.setStartingAction(started)
    speaker.speak(localized, null, options)
    return true
  }

  private fun speechController() =
    TalkBackService.getInstance()?.takeIf {
      TalkBackService.isServiceActive() && !PauseController.isPaused()
    }?.getSpeechController()

  private fun echoesCharacters(): Boolean =
    KeyboardEchoSettings.echoesCharacters(echo)

  private fun echoesWords(): Boolean =
    echo == TextEventFilter.PREF_ECHO_WORDS || echo == TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS

  private fun characterLabel(character: Char): String =
    if (character == ' ') getString(R.string.vietnamese_ime_space) else character.toString()

  companion object {
    private val VIETNAMESE = Locale.forLanguageTag("vi-VN")
    private const val MAX_WORD_LENGTH = 48

    private fun changedCharacter(previous: String, updated: String): Char? {
      if (updated.length > previous.length) return updated.last()
      return updated.indices.firstOrNull { updated[it] != previous[it] }?.let { updated[it] }
    }

    internal fun isPassword(inputType: Int): Boolean {
      val variation = inputType and InputType.TYPE_MASK_VARIATION
      return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
      }
    }

    private fun allowsTelex(inputType: Int): Boolean {
      if (isPassword(inputType) || inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
        return false
      }
      return when (inputType and InputType.TYPE_MASK_VARIATION) {
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> false
        else -> true
      }
    }
  }
}
