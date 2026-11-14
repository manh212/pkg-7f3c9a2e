package com.google.android.accessibility.talkback.vietnameseime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.style.LocaleSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.dictation.SandflyDictation
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.ImeEditFeedback
import com.google.android.accessibility.utils.input.TextEventFilter
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_NO_HISTORY
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.output.SpeechController.UTTERANCE_GROUP_KEYBOARD
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VietnameseKeyboardService : InputMethodService() {
  private lateinit var prefs: SharedPreferences
  private var keyboard: VietnameseKeyboardView? = null
  private var editor: EditorInfo? = null
  private var word = ""
  private var wordTruncated = false
  private var composing = false
  private var cursor = -1
  private var selectionStart = -1
  private var composingStart = -1
  private data class SelectionUpdate(val end: Int, val composingStart: Int)
  private val pendingSelections = ArrayDeque<SelectionUpdate>()
  private var composingUnavailable = false
  private var shifted = false
  private var symbols = false
  private var vietnamese = true
  private var telexAllowed = false
  private var echo = TextEventFilter.PREF_ECHO_NONE
  private var method = 0
  private var layout = VietnameseKeyboardView.LAYOUT_AUTO
  private var timing = VietnameseKeyboardTiming()
  private var ownVoice = false
  private var sizePortraitPercent = 100
  private var sizeLandscapePercent = 100
  private var micEnabled = false
  private var showNumberRow = true
  private var showSymbolsRow = false
  private var symbolsRow = VietnameseKeyboardSymbols.DEFAULT_ROW
  private var autoCapitalize = true
  private var doubleSpacePeriod = true
  private var vibrateOnKey = false
  private var keySound = false
  private var keySounds: KeySounds = KeyboardKeySounds.defaultSounds()
  private var vibrateIntensityPercent: Int = 100
  private var dictating = false
  internal var dictationAvailable: () -> Boolean = { SandflyDictation.isAvailable }
  internal var dictationListen: ((String?) -> Unit) -> Boolean = SandflyDictation::listen
  internal var dictationStop: () -> Unit = SandflyDictation::stop
  private val keyNames by lazy { VietnameseKeyNames(this) }
  private val main = Handler(Looper.getMainLooper())
  private var windowShown = false
  private val bounds = Rect()
  private val sentBounds = Rect()
  private val location = IntArray(2)
  private var boundsReceiver: TalkBackService? = null
  private var hasSentBounds = false
  private var editorActionId: Int? = null
  private var editorActionLabel = ""
  private var keyboardVoice: VietnameseKeyboardVoice? = null
  private var voiceWarmedUp = false
  private var observedSpeechController: SpeechControllerImpl? = null
  private val readerSpeechObserver = object : SpeechController.Observer {
    override fun onSpeechStarting() {
      main.post {
        if (observedSpeechController?.hasOtherSpeech(UTTERANCE_GROUP_KEYBOARD) == true) {
          stopKeyboardVoice()
        }
      }
    }

    override fun onSpeechCompleted() = Unit

    override fun onSpeechPaused() = Unit
  }
  private val keySpeech = VietnameseKeySpeech(
    ::submitKeySpeech,
    ::stopKeySpeech,
    ::shouldRetryKeySpeech,
  )
  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == null || key == getString(R.string.pref_vietnamese_keyboard_bottom_spacing_key)) {
      updateBottomSpacing()
    }
    if (key == null || key == getString(R.string.pref_typing_confirmation_key) ||
      key == getString(R.string.pref_direct_input_tap_delay_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_layout_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_own_voice_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_size_portrait_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_size_landscape_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_mic_enabled_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_show_number_row_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_show_symbols_row_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_symbols_row_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_auto_capitalize_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_double_space_period_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_vibrate_on_key_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_key_sound_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_key_sound_space_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_key_sound_normal_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_key_sound_delete_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_key_sound_enter_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_vibration_level_key) ||
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
    windowShown = false
    observeReaderSpeech(null)
    reportKeyboardBounds()
    endTouchSession()
    releaseKeyboardVoice()
    super.onDestroy()
  }

  override fun onCreateInputView(): View {
    val view = VietnameseKeyboardView(
      this,
      ::announceKey,
      ::activateKey,
      { waitsForCommit -> keySpeech.release(timing.releaseSpeechMs, waitsForCommit) },
      keyName = keyNames::spokenName,
      reportBounds = ::reportKeyboardBounds,
      micStop = ::stopMicDictation,
      onDirectKeyTouchDown = { TalkBackService.getInstance()?.onDirectKeyboardTouchDown() },
    )
    keyboard = view
    configureView()
    updateLayout()
    return FrameLayout(this).apply {
      setBackgroundResource(R.color.vietnamese_ime_background)
      addView(view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      updateBottomSpacing()
    }
  }

  private fun updateBottomSpacing() {
    val container = keyboard?.parent as? FrameLayout ?: return
    val spacingDp = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_bottom_spacing_key),
      resources.getInteger(R.integer.pref_vietnamese_keyboard_bottom_spacing_default),
    ).coerceIn(0, resources.getInteger(R.integer.pref_vietnamese_keyboard_bottom_spacing_max))
    val spacing = (spacingDp * resources.displayMetrics.density).roundToInt()
    if (container.paddingBottom != spacing) container.setPadding(0, 0, 0, spacing)
  }

  override fun onEvaluateFullscreenMode(): Boolean = false

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    updateBottomSpacing()
    updateLayout()
  }

  override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    endTouchSession()
    if (restarting && composing) finishWord(currentInputConnection)
    editor = attribute
    resetWord()
    pendingSelections.clear()
    cursor = attribute?.initialSelEnd ?: -1
    selectionStart = attribute?.initialSelStart ?: -1
    shifted = false
    val inputType = attribute?.inputType ?: InputType.TYPE_NULL
    telexAllowed = allowsTelex(inputType)
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    symbols = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE ||
      inputClass == InputType.TYPE_CLASS_DATETIME
    editorActionId = editorAction(attribute)
    editorActionLabel = attribute?.actionLabel?.toString()?.takeIf {
      editorActionId != null && it.isNotBlank()
    } ?: getString(actionTitle(editorActionId))
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
    reportKeyboardBounds()
    if (ownVoice) {
      observeReaderSpeech(speechController())
      warmUpKeyboardVoice()
    }
  }

  override fun onWindowHidden() {
    windowShown = false
    observeReaderSpeech(null)
    endTouchSession()
    super.onWindowHidden()
    reportKeyboardBounds()
  }

  private fun reportKeyboardBounds() {
    val service = TalkBackService.getInstance()
    if (service == null) {
      boundsReceiver = null
      hasSentBounds = false
      return
    }
    val view = keyboard
    if (windowShown && isInputViewShown && view != null && view.isShown &&
      view.isAttachedToWindow && view.width > 0 && view.height > 0) {
      view.getLocationOnScreen(location)
      bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
    } else {
      bounds.setEmpty()
    }
    if (hasSentBounds && boundsReceiver === service && sentBounds == bounds) return
    if (service.setVietnameseKeyboardBounds(bounds.takeUnless { it.isEmpty })) {
      boundsReceiver = service
      sentBounds.set(bounds)
      hasSentBounds = true
    }
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    endTouchSession()
    if (!finishingInput) {
      if (composing) expectSelection(cursor)
      resetWord()
    }
    super.onFinishInputView(finishingInput)
  }

  override fun onFinishInput() {
    endTouchSession()
    resetWord()
    editor = null
    pendingSelections.clear()
    cursor = -1
    selectionStart = -1
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
      cursor = newSelEnd
      selectionStart = newSelStart
      return
    }
    if (newSelStart == newSelEnd &&
      (candidatesStart < 0 || candidatesEnd == newSelEnd) &&
      consumeExpectedSelection(newSelEnd, candidatesStart)) return
    pendingSelections.clear()
    val sameSelection = newSelStart == selectionStart && newSelEnd == cursor
    val sameComposition = newSelStart == newSelEnd && newSelEnd == candidatesEnd &&
      composingStart == candidatesStart && candidatesStart >= 0 &&
      candidatesEnd - candidatesStart == word.length
    if (composing && !sameComposition) {
      // A selection report without composing-span information (candidatesStart < 0)
      // and with the cursor still where the composition ends is not proof the
      // composition ended: some editors do not report the span. Dropping the
      // tracked word here makes the next tone key type a letter instead of
      // replacing the tone, so keep the word and wait for a real move.
      if (candidatesStart < 0 && newSelStart == newSelEnd && newSelEnd == cursor) return
      keyboard?.cancelInput(cancelPendingEdits = false)
      keySpeech.clearPreview()
      finishWord(currentInputConnection)
    } else if (!composing && !sameSelection &&
      (cursor >= 0 || selectionStart >= 0 || newSelStart != newSelEnd)) {
      keyboard?.cancelInput(cancelPendingEdits = false)
      keySpeech.clearPreview()
      resetWord()
    }
    cursor = newSelEnd
    selectionStart = newSelStart
  }

  private fun endTouchSession() {
    keyboard?.cancelInput()
    keySpeech.cancel()
    stopMicDictation()
  }

  private fun readPreferences() {
    val newMethod = SharedPreferencesUtils.getIntFromStringPref(
      prefs, resources, R.string.pref_typing_confirmation_key, R.string.pref_typing_confirmation_default,
    )
    val newTiming = VietnameseKeyboardTiming.read(this, prefs)
    val newLayout = prefs.getString(
      getString(R.string.pref_vietnamese_keyboard_layout_key),
      VietnameseKeyboardView.LAYOUT_AUTO,
    ) ?: VietnameseKeyboardView.LAYOUT_AUTO
    val newEcho = KeyboardEchoSettings.read(prefs, resources, ON_SCREEN_KEYBOARD)
    val newOwnVoice = SharedPreferencesUtils.getBooleanPref(
      prefs, resources, R.string.pref_vietnamese_keyboard_own_voice_key,
      R.bool.pref_vietnamese_keyboard_own_voice_default,
    )
    val newSizePortrait = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_size_portrait_key), 100,
    ).coerceIn(50, 150)
    val newSizeLandscape = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_size_landscape_key), 100,
    ).coerceIn(50, 150)
    val newMicEnabled = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_mic_enabled_key), false,
    )
    val newShowNumberRow = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_show_number_row_key), true,
    )
    val newShowSymbolsRow = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_show_symbols_row_key), false,
    )
    val newSymbolsRow = VietnameseKeyboardSymbols.read(this, prefs)
    val newAutoCapitalize = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_auto_capitalize_key), true,
    )
    val newDoubleSpacePeriod = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_double_space_period_key), true,
    )
    val newVibrateOnKey = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_vibrate_on_key_key), false,
    )
    val newKeySound = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_key_sound_key), false,
    )
    val newKeySounds = KeySounds(
      space = KeyboardKeySounds.resolve(this, KeyboardKeySounds.Slot.SPACE),
      normal = KeyboardKeySounds.resolve(this, KeyboardKeySounds.Slot.NORMAL),
      delete = KeyboardKeySounds.resolve(this, KeyboardKeySounds.Slot.DELETE),
      enter = KeyboardKeySounds.resolve(this, KeyboardKeySounds.Slot.ENTER),
    )
    val newVibrateIntensityPercent = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_vibration_level_key), 100,
    ).coerceIn(0, 100)
    if (method != newMethod || timing != newTiming || layout != newLayout || ownVoice != newOwnVoice ||
      sizePortraitPercent != newSizePortrait || sizeLandscapePercent != newSizeLandscape ||
      micEnabled != newMicEnabled || showNumberRow != newShowNumberRow ||
      showSymbolsRow != newShowSymbolsRow || symbolsRow != newSymbolsRow) {
      endTouchSession()
    } else if (echo != newEcho) {
      keySpeech.cancel()
    }
    method = newMethod
    timing = newTiming
    layout = newLayout
    echo = newEcho
    sizePortraitPercent = newSizePortrait
    sizeLandscapePercent = newSizeLandscape
    micEnabled = newMicEnabled
    showNumberRow = newShowNumberRow
    showSymbolsRow = newShowSymbolsRow
    symbolsRow = newSymbolsRow
    autoCapitalize = newAutoCapitalize
    doubleSpacePeriod = newDoubleSpacePeriod
    vibrateOnKey = newVibrateOnKey
    keySound = newKeySound
    keySounds = newKeySounds
    vibrateIntensityPercent = newVibrateIntensityPercent
    if (ownVoice != newOwnVoice) {
      ownVoice = newOwnVoice
      if (!ownVoice) {
        observeReaderSpeech(null)
        releaseKeyboardVoice()
        ImeEditFeedback.clear()
      } else if (windowShown) {
        observeReaderSpeech(speechController())
        warmUpKeyboardVoice()
      }
    }
    configureView()
    updateLayout()
  }

  private fun configureView() {
    keyboard?.apply {
      typingMethod = method
      timing = this@VietnameseKeyboardService.timing
      speakHoveredKeys = ownVoice && method != DIRECT_SILENT_TYPING
      hasCommitAnnouncement = ::hasCommitAnnouncement
      layoutSetting = layout
      sizePortraitPercent = this@VietnameseKeyboardService.sizePortraitPercent
      sizeLandscapePercent = this@VietnameseKeyboardService.sizeLandscapePercent
      micEnabled = isMicVisible()
      showNumberRow = this@VietnameseKeyboardService.showNumberRow
      showSymbolsRow = this@VietnameseKeyboardService.showSymbolsRow
      symbolsRowString = this@VietnameseKeyboardService.symbolsRow
      autoCapitalize = this@VietnameseKeyboardService.autoCapitalize
      doubleSpacePeriod = this@VietnameseKeyboardService.doubleSpacePeriod
      vibrateOnKey = this@VietnameseKeyboardService.vibrateOnKey
      keySound = this@VietnameseKeyboardService.keySound
      keySounds = this@VietnameseKeyboardService.keySounds
      vibrateIntensityPercent = this@VietnameseKeyboardService.vibrateIntensityPercent
    }
  }

  private fun updateLayout() {
    keyboard?.showKeys(
      shifted,
      symbols,
      vietnamese && telexAllowed,
      editorActionLabel.ifEmpty { getString(R.string.vietnamese_ime_enter) },
      telexAllowed,
    )
  }

  private fun hasCommitAnnouncement(id: String): Boolean = when (id) {
    VietnameseKeyboardView.DELETE, VietnameseKeyboardView.DELETE_WORD ->
      echo != TextEventFilter.PREF_ECHO_SILENT_TYPING
    VietnameseKeyboardView.ENTER -> echoesCharacters() || (echoesWords() && completedWord().isNotEmpty())
    VietnameseKeyboardView.LANGUAGE -> telexAllowed
    else -> id.length == 1 &&
      (echoesCharacters() || (echoesWords() && !id.single().isLetterOrDigit() && completedWord().isNotEmpty()))
  }

  private fun activateKey(id: String, releasedAt: Long): Boolean {
    if (editor == null || !isInputViewShown) return false
    val connection = currentInputConnection ?: return false
    val speakAt = if (releasedAt > 0) releasedAt + timing.releaseSpeechMs else 0L
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
      VietnameseKeyboardView.DELETE -> return delete(
        connection,
        echo != TextEventFilter.PREF_ECHO_SILENT_TYPING,
        speakAt,
      )
      VietnameseKeyboardView.DELETE_WORD -> return deleteWord(
        connection,
        echo != TextEventFilter.PREF_ECHO_SILENT_TYPING,
        speakAt,
      )
      VietnameseKeyboardView.ENTER -> if (!enter(connection, speakAt)) return false
      VietnameseKeyboardView.MIC -> return startMicDictation()
      else -> {
        val letter = id.single()
        if (!type(connection, if (shifted) letter.uppercaseChar() else letter, speakAt)) return false
        if (!shifted || !letter.isLetter()) return true
        shifted = false
      }
    }
    if (id == VietnameseKeyboardView.LANGUAGE && telexAllowed) {
      val feedback = if (vietnamese) R.string.vietnamese_ime_telex_on else R.string.vietnamese_ime_telex_off
      keySpeech.confirm(getString(feedback), speakAt)
    }
    updateLayout()
    return true
  }

  private fun isMicVisible(): Boolean = micEnabled && dictationAvailable()

  private fun startMicDictation(): Boolean {
    if (dictating) {
      stopMicDictation()
      return true
    }
    if (!dictationAvailable()) {
      if (ownVoice) keySpeech.confirm(getString(R.string.dictation_unavailable), 0)
      return false
    }
    if (!dictationListen(::onDictatedText)) {
      if (ownVoice) keySpeech.confirm(getString(R.string.dictation_unavailable), 0)
      return false
    }
    dictating = true
    keySpeech.cancel()
    return true
  }

  private fun stopMicDictation() {
    if (!dictating) return
    dictating = false
    dictationStop()
  }

  private fun onDictatedText(text: String?) {
    dictating = false
    if (text.isNullOrBlank()) return
    val connection = currentInputConnection ?: return
    dropComposingWord(connection)
    if (!insertDictatedText(connection, text)) return
    if (echoesWords() || echoesCharacters()) {
      speakWord(text, 0)
    } else {
      keySpeech.confirm(getString(R.string.vietnamese_ime_voice_input_stopped), 0)
    }
  }

  private fun dropComposingWord(connection: InputConnection) {
    if (word.isEmpty()) {
      resetWord()
      return
    }
    markEdit()
    val length = word.length
    if (composing) {
      if (!connection.setComposingText("", 1)) {
        connection.finishComposingText()
        connection.deleteSurroundingTextInCodePoints(length, 0)
      }
      expectSelection(if (composingStart >= 0) composingStart else -1)
    } else {
      connection.deleteSurroundingTextInCodePoints(length, 0)
      expectSelection(if (cursor >= 0) cursor - length else -1)
    }
    resetWord()
  }

  private fun insertDictatedText(connection: InputConnection, text: String): Boolean {
    if (editor?.inputType == InputType.TYPE_NULL) {
      for (character in text) {
        if (!commitText(connection, character.toString())) return false
      }
      return true
    }
    return commitText(connection, text)
  }

  private fun type(connection: InputConnection, letter: Char, speakAt: Long): Boolean {
    if (letter == ' ' && !composing && doubleSpacePeriod && telexAllowed &&
      selectionStart == cursor && tryDoubleSpacePeriod(connection)) {
      if (echoesCharacters()) speakEnteredCharacter('.', speakAt)
      return true
    }
    val capitalized = if (letter.isLowerCase() && word.isEmpty() &&
      shouldCapitalize(connection)) letter.uppercaseChar() else letter
    val wordCharacter = letter.isLetterOrDigit()
    if (letter.isLetter() && vietnamese && telexAllowed && !composingUnavailable) {
      val updated = Telex.apply(word, capitalized)
      markEdit()
      if (updated.length <= MAX_WORD_LENGTH && updateComposition(connection, updated)) {
        val changed = if (echoesCharacters()) changedCharacter(word, updated) else null
        word = updated
        if (changed != null) speakEnteredCharacter(changed, speakAt)
        return true
      }
      composingUnavailable = true
    }
    val finished = if (wordCharacter) "" else completedWord()
    if (!commitText(connection, capitalized.toString())) return false
    if (wordCharacter) {
      composingUnavailable = true
      composing = false
      composingStart = -1
      if (word.length == MAX_WORD_LENGTH) wordTruncated = true
      if (!wordTruncated) word += capitalized
    } else {
      resetWord()
    }
    if (echoesWords() && finished.isNotEmpty()) speakWord(finished, speakAt)
    else if (echoesCharacters()) speakEnteredCharacter(capitalized, speakAt)
    return true
  }

  private fun shouldCapitalize(connection: InputConnection): Boolean {
    if (!autoCapitalize || !telexAllowed) return false
    val before = connection.getTextBeforeCursor(2, 0) ?: return true
    if (before.isEmpty()) return true
    if (before.last() == '\n') return true
    return before.length == 2 && before[1] == ' ' && before[0] in ".!?"
  }

  private fun tryDoubleSpacePeriod(connection: InputConnection): Boolean {
    val before = connection.getTextBeforeCursor(2, 0) ?: return false
    if (before.length != 2 || before[1] != ' ' || !before[0].isLetter()) return false
    markEdit()
    if (!connection.deleteSurroundingTextInCodePoints(1, 0)) return false
    if (cursor > 0) expectSelection(cursor - 1)
    return commitText(connection, ". ")
  }

  private fun delete(connection: InputConnection, announceDeletion: Boolean, speakAt: Long): Boolean {
    markEdit()
    if (composing && word.isNotEmpty()) {
      val deleted = word.last().toString()
      val shortened = Telex.deleteLast(word)
      if (updateComposition(connection, shortened)) {
        word = shortened
        if (shortened.isEmpty()) resetWord()
        if (announceDeletion) speakDeletedText(deleted, speakAt)
        return true
      }
      if (!connection.finishComposingText()) return false
      expectSelection(cursor)
      composing = false
      composingStart = -1
      composingUnavailable = true
    }
    if (editor?.inputType == InputType.TYPE_NULL) {
      if (!sendKey(connection, KeyEvent.KEYCODE_DEL)) return false
      resetWord()
      expectSelection(-1)
      if (announceDeletion) keySpeech.confirm(getString(R.string.vietnamese_ime_delete), speakAt)
      return true
    }
    if (selectionStart >= 0 && cursor >= 0 && selectionStart != cursor) {
      val deleted = if (announceDeletion) selectedTextForSpeech(connection) else null
      if (!connection.commitText("", 1)) return false
      expectSelection(insertionStart())
      resetWord()
      if (deleted != null) speakDeletedText(deleted, speakAt)
      return true
    }
    val trackedCharacter = word.isNotEmpty() && !wordTruncated
    val atStart = cursor == 0
    val deleted = when {
      !announceDeletion || atStart -> null
      trackedCharacter -> word.last().toString()
      else -> connection.getTextBeforeCursor(2, 0)?.let {
        if (it.isEmpty()) return true
        val codePoint = Character.codePointBefore(it, it.length)
        it.subSequence(it.length - Character.charCount(codePoint), it.length).toString()
      }
    }
    if (!connection.deleteSurroundingTextInCodePoints(1, 0) && !sendKey(connection, KeyEvent.KEYCODE_DEL)) {
      return false
    }
    if (trackedCharacter) {
      word = word.dropLast(1)
      if (word.isEmpty()) resetWord()
      if (cursor > 0) expectSelection(cursor - 1)
    } else if (cursor > 0 && deleted != null && deleted.length <= cursor) {
      expectSelection(cursor - deleted.length)
    } else if (cursor != 0) {
      expectSelection(-1)
    }
    if (announceDeletion && (deleted != null || !atStart)) {
      if (deleted == null) keySpeech.confirm(getString(R.string.vietnamese_ime_delete), speakAt)
      else speakDeletedText(deleted, speakAt)
    }
    return true
  }

  private fun deleteWord(connection: InputConnection, announceDeletion: Boolean, speakAt: Long): Boolean {
    markEdit()
    if (composing && word.isNotEmpty()) {
      val deletedWord = completedWord()
      if (updateComposition(connection, "")) {
        resetWord()
        if (announceDeletion) {
          if (deletedWord.isNotEmpty()) speakDeletedText(deletedWord, speakAt)
          else keySpeech.confirm(getString(R.string.vietnamese_ime_delete), speakAt)
        }
        return true
      }
      if (!connection.finishComposingText()) return false
      expectSelection(cursor)
      composing = false
      composingStart = -1
      composingUnavailable = true
    }
    if (editor?.inputType == InputType.TYPE_NULL) {
      if (!sendKey(connection, KeyEvent.KEYCODE_DEL)) return false
      resetWord()
      expectSelection(-1)
      if (announceDeletion) keySpeech.confirm(getString(R.string.vietnamese_ime_delete), speakAt)
      return true
    }
    if (selectionStart >= 0 && cursor >= 0 && selectionStart != cursor) {
      val deleted = if (announceDeletion) selectedTextForSpeech(connection) else null
      if (!connection.commitText("", 1)) return false
      expectSelection(insertionStart())
      resetWord()
      if (deleted != null) speakDeletedText(deleted, speakAt)
      return true
    }
    val deletedWord = wordBeforeCursor(connection)
    val length = deletedWord.length
    if (length == 0) return true
    val deleted = when {
      !announceDeletion -> null
      else -> deletedWord
    }
    if (!connection.deleteSurroundingTextInCodePoints(length, 0)) return false
    if (wordTruncated || word.length <= length) resetWord()
    else word = word.dropLast(length)
    if (cursor > 0 && length <= cursor) expectSelection(cursor - length)
    else expectSelection(-1)
    if (announceDeletion && deleted != null) speakDeletedText(deleted, speakAt)
    return true
  }

  private fun wordBeforeCursor(connection: InputConnection): String {
    var window = 64
    while (true) {
      val before = connection.getTextBeforeCursor(window, 0) ?: return ""
      var length = 0
      while (length < before.length && before[before.length - 1 - length].isLetterOrDigit()) length++
      if (length < before.length || before.length < window) {
        return before.subSequence(before.length - length, before.length).toString()
      }
      if (window >= MAX_WORD_DELETE_LOOKBACK) return before.toString()
      window = minOf(window * 4, MAX_WORD_DELETE_LOOKBACK)
    }
  }

  private fun selectedTextForSpeech(connection: InputConnection): String {
    val length = abs(selectionStart - cursor)
    if (length <= 2) {
      val selected = connection.getSelectedText(0)
      if (selected != null && selected.length == length &&
        Character.codePointCount(selected, 0, length) == 1) return selected.toString()
    }
    return getString(R.string.vietnamese_ime_selected_text)
  }

  private fun commitText(connection: InputConnection, text: String): Boolean {
    markEdit()
    val start = if (composing) {
      if (composingStart >= 0) composingStart + word.length else -1
    } else insertionStart()
    val accepted = if (editor?.inputType == InputType.TYPE_NULL) {
      val key = text.single()
      when (key) {
        '\n' -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        in '0'..'9' -> sendKey(connection, KeyEvent.KEYCODE_0 + (key - '0'))
        else -> connection.commitText(text, 1)
      }
    } else {
      connection.commitText(if (composing) word + text else text, 1)
    }
    if (!accepted) return false
    expectSelection(if (start >= 0) start + text.length else -1)
    return true
  }

  private fun sendKey(connection: InputConnection, keyCode: Int): Boolean {
    val down = connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    val up = connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    return down && up
  }

  private fun updateComposition(connection: InputConnection, updated: String): Boolean {
    val start = if (composing) composingStart else insertionStart()
    if (!connection.setComposingText(updated, 1)) return false
    composingStart = if (updated.isEmpty()) -1 else start
    composing = updated.isNotEmpty()
    expectSelection(if (start >= 0) start + updated.length else -1, composingStart)
    return true
  }

  private fun insertionStart(): Int =
    if (selectionStart >= 0 && cursor >= 0) minOf(selectionStart, cursor) else -1

  private fun expectSelection(position: Int, composition: Int = -1) {
    if (position >= 0) {
      val update = SelectionUpdate(position, composition)
      if (pendingSelections.lastOrNull() != update) {
        if (pendingSelections.size >= 16) pendingSelections.removeFirst()
        pendingSelections.addLast(update)
      }
    } else {
      pendingSelections.clear()
    }
    cursor = position
    selectionStart = position
  }

  private fun consumeExpectedSelection(position: Int, candidatesStart: Int): Boolean {
    val index = pendingSelections.indexOfFirst { it.end == position && it.composingStart == candidatesStart }
    if (index < 0) return false
    repeat(index + 1) { pendingSelections.removeFirst() }
    return true
  }

  private fun finishWord(connection: InputConnection?) {
    if (composing && connection?.finishComposingText() == true) expectSelection(cursor)
    resetWord()
  }

  private fun completedWord(): String = if (wordTruncated) "" else word

  private fun resetWord() {
    word = ""
    wordTruncated = false
    composing = false
    composingStart = -1
    composingUnavailable = false
  }

  private fun enter(connection: InputConnection, speakAt: Long): Boolean {
    val finished = completedWord()
    val action = editorActionId
    if (action != null) {
      finishWord(connection)
      if (!connection.performEditorAction(action) && !sendKey(connection, KeyEvent.KEYCODE_ENTER)) {
        return false
      }
    } else {
      if (!commitText(connection, "\n")) return false
      resetWord()
    }
    if (finished.isNotEmpty() && echoesWords()) speakWord(finished, speakAt)
    else if (echoesCharacters()) keySpeech.confirm(editorActionLabel, speakAt)
    return true
  }

  private fun actionTitle(action: Int?): Int = when (action) {
    EditorInfo.IME_ACTION_DONE -> R.string.vietnamese_ime_done
    EditorInfo.IME_ACTION_SEND -> R.string.vietnamese_ime_send
    EditorInfo.IME_ACTION_SEARCH -> R.string.vietnamese_ime_search
    EditorInfo.IME_ACTION_NEXT -> R.string.vietnamese_ime_next
    EditorInfo.IME_ACTION_PREVIOUS -> R.string.vietnamese_ime_previous
    EditorInfo.IME_ACTION_GO -> R.string.vietnamese_ime_go
    else -> R.string.vietnamese_ime_enter
  }

  private fun markEdit() {
    ImeEditFeedback.recordEdit(editor?.packageName)
  }

  private fun announceKey(label: String) {
    if (label.isEmpty()) keySpeech.clearPreview()
    else keySpeech.explore(keyNames.spokenName(label), timing.exploreSpeechMs)
  }

  private fun speakEnteredCharacter(character: Char, speakAt: Long) {
    keySpeech.confirm(keyNames.spokenName(characterLabel(character)), speakAt)
  }

  private fun speakWord(word: String, speakAt: Long) {
    keySpeech.confirm(keyNames.spokenName(word), speakAt)
  }

  private fun speakDeletedText(text: String, speakAt: Long) {
    keySpeech.confirm(getString(R.string.template_text_removed, keyNames.spokenName(text)), speakAt)
  }

  private fun submitKeySpeech(label: String, started: () -> Unit, completed: () -> Unit): Boolean {
    if (dictating || PauseController.isPaused()) return false
    val reader = speechController()
    if (ownVoice) {
      observeReaderSpeech(reader)
      // Do not wait for Meridian speech: refusing the submit holds the echo in the
      // retry loop for seconds, then drains several queued echoes as one burst.
      if (reader?.isMute == true) {
        return false
      }
      warmUpKeyboardVoice()
      return keyboardVoice?.speak(label, started, { main.post { completed() } }) == true
    }
    if (reader == null || !reader.isTtsReady || reader.isMute) return false
    val localized = SpannableString(label)
    localized.setSpan(LocaleSpan(VIETNAMESE), 0, localized.length, 0)
    val options = SpeakOptions.create()
      .setUtteranceGroup(UTTERANCE_GROUP_KEYBOARD)
      .setQueueMode(QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH)
      .setFlags(
        FLAG_NO_HISTORY or FLAG_FORCE_FEEDBACK_ALL or FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP or
          FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP or
          FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP,
      )
      .setStartingAction { main.post { started() } }
      .setCompletedAction { main.post { completed() } }
    reader.speak(localized, null, options)
    return true
  }

  private fun shouldRetryKeySpeech(): Boolean {
    if (dictating || PauseController.isPaused()) return false
    val speaker = speechController()
    if (ownVoice) return speaker?.isMute != true
    return speaker != null && !speaker.isTtsReady && !speaker.isMute
  }

  private fun observeReaderSpeech(reader: SpeechControllerImpl?) {
    if (observedSpeechController === reader) return
    observedSpeechController?.removeObserver(readerSpeechObserver)
    observedSpeechController = reader
    reader?.addObserver(readerSpeechObserver)
  }

  private fun warmUpKeyboardVoice() {
    if (voiceWarmedUp) return
    val voice = keyboardVoice ?: VietnameseKeyboardVoice(this).also { keyboardVoice = it }
    voiceWarmedUp = true
    voice.warmUp(fixedKeyLabels())
  }

  private fun stopKeyboardVoice() {
    keyboardVoice?.stop(pauseWarmup = !windowShown)
    if (!windowShown) voiceWarmedUp = false
  }

  private fun stopKeySpeech() {
    if (ownVoice) stopKeyboardVoice()
    else speechController()?.stopUtteranceGroup(UTTERANCE_GROUP_KEYBOARD)
  }

  private fun releaseKeyboardVoice() {
    keyboardVoice?.shutdown()
    keyboardVoice = null
    voiceWarmedUp = false
  }

  private fun fixedKeyLabels(): List<String> {
    val labels = ArrayList<String>()
    for (letter in 'a'..'z') labels.add(keyNames.spokenName(letter.toString()))
    for (letter in 'A'..'Z') labels.add(keyNames.spokenName(letter.toString()))
    for (digit in '0'..'9') labels.add(keyNames.spokenName(digit.toString()))
    for (telex in "đâăêôơưàáảãạèéẻẽẹìíỉĩịòóỏõọùúủũụỳýỷỹỵ") {
      labels.add(keyNames.spokenName(telex.toString()))
      labels.add(keyNames.spokenName(telex.uppercaseChar().toString()))
    }
    for (symbol in "@#%&*+-=()!?:;/'\"_\$^~<>[]{},.\\`|") labels.add(keyNames.spokenName(symbol.toString()))
    labels.add(keyNames.spokenName(getString(R.string.vietnamese_ime_space)))
    labels.add(keyNames.spokenName(getString(R.string.vietnamese_ime_password_character)))
    labels.add(getString(R.string.vietnamese_ime_uppercase))
    labels.add(getString(R.string.vietnamese_ime_lowercase))
    labels.add(getString(R.string.vietnamese_ime_symbols))
    labels.add(getString(R.string.vietnamese_ime_letters))
    labels.add(getString(R.string.vietnamese_ime_picker_key))
    labels.add(getString(R.string.vietnamese_ime_voice_input))
    labels.add(getString(R.string.vietnamese_ime_voice_input_stopped))
    labels.add(getString(R.string.dictation_unavailable))
    labels.add(getString(R.string.vietnamese_ime_delete))
    labels.add(getString(R.string.vietnamese_ime_telex_on))
    labels.add(getString(R.string.vietnamese_ime_telex_off))
    labels.add(getString(R.string.vietnamese_ime_enter))
    labels.add(getString(R.string.vietnamese_ime_done))
    labels.add(getString(R.string.vietnamese_ime_send))
    labels.add(getString(R.string.vietnamese_ime_search))
    labels.add(getString(R.string.vietnamese_ime_next))
    labels.add(getString(R.string.vietnamese_ime_previous))
    labels.add(getString(R.string.vietnamese_ime_go))
    return labels.distinct()
  }

  private fun speechController() =
    TalkBackService.getInstance()?.takeIf { TalkBackService.isServiceActive() }?.getSpeechController()

  private fun echoesCharacters(): Boolean =
    KeyboardEchoSettings.echoesCharacters(echo)

  private fun echoesWords(): Boolean =
    echo == TextEventFilter.PREF_ECHO_WORDS || echo == TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS

  private fun characterLabel(character: Char): String =
    if (character == ' ') getString(R.string.vietnamese_ime_space) else character.toString()

  companion object {
    private val VIETNAMESE = Locale.forLanguageTag("vi-VN")
    private const val MAX_WORD_LENGTH = 48
    private const val MAX_WORD_DELETE_LOOKBACK = 512

    internal fun editorAction(info: EditorInfo?): Int? {
      if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
      if (!info.actionLabel.isNullOrBlank()) return info.actionId
      val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
      return action.takeUnless {
        it == EditorInfo.IME_ACTION_NONE || it == EditorInfo.IME_ACTION_UNSPECIFIED
      }
    }

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
