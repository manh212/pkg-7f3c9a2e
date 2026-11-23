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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.dictation.SandflyDictation
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.talkback.selector.SelectorController
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.CursorGranularity
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
import com.google.android.accessibility.utils.output.VoiceProfiles
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VietnameseKeyboardService : InputMethodService() {
  private lateinit var prefs: SharedPreferences
  private var keyboard: VietnameseKeyboardView? = null
  private var editor: EditorInfo? = null
  private var inputGeneration = 0
  private var word = ""
  private var wordTruncated = false
  private var composing = false
  private var cursor = -1
  private var selectionStart = -1
  private var composingStart = -1
  private data class SelectionUpdate(val end: Int, val composingStart: Int)
  private val pendingSelections = ArrayDeque<SelectionUpdate>()
  private val handledHardwareKeys = mutableSetOf<Pair<Int, Int>>()
  private var composingUnavailable = false
  private var wordLost = false
  private var shifted = false
  private var symbols = false
  private var symbolSecondPage = false
  // True while the T9 pad is the shown page for a numeric field; ABC clears it.
  private var t9 = false
  private var vietnamese = true
  private var telexAllowed = false
  private var quickTelex = false
  private var t9DoubleTap = false
  private var showLanguageKey = true
  private var shortcuts = emptyMap<String, String>()
  private var longestShortcut = 0
  private var shortcutKeys: String? = null
  private var echo = TextEventFilter.PREF_ECHO_NONE
  private var method = 0
  private var layout = VietnameseKeyboardView.LAYOUT_AUTO
  private var timing = VietnameseKeyboardTiming()
  private var ownVoice = false
  private var sizePortraitPercent = 100
  private var sizeLandscapePercent = 100
  private var micEnabled = false
  private var autoSwitchLettersOnSpace = true
  private var voiceProfileId = ""
  private var voiceProfileSignature = ""
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
  internal var dictationListen: ((String?) -> Unit) -> Boolean = SandflyDictation::listenKeyboard
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
      key == getString(R.string.pref_vietnamese_keyboard_voice_profile_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_auto_switch_letters_on_space_key) ||
      key.startsWith(VoiceProfiles.PREF_PREFIX) ||
      key == getString(R.string.pref_vietnamese_keyboard_show_language_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_quick_telex_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_t9_double_tap_key) ||
      key == getString(R.string.pref_vietnamese_keyboard_shortcuts_enabled_key) ||
      key == VietnameseKeyboardShortcuts.ENTRIES_KEY ||
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
    inputGeneration++
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
      isTextRecording = { SandflyDictation.isTextRecording },
      stopTextRecording = { SandflyDictation.stopTextRecording() },
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

  private fun hideInputViewImmediately() {
    endTouchSession()
    (keyboard?.parent as? FrameLayout)?.visibility = View.GONE
    reportKeyboardBounds()
  }

  private fun showInputView() {
    (keyboard?.parent as? FrameLayout)?.visibility = View.VISIBLE
  }

  override fun onEvaluateFullscreenMode(): Boolean = false

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    updateBottomSpacing()
    updateLayout()
  }

  override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    inputGeneration++
    endTouchSession()
    if (restarting && composing) {
      finishWord(currentInputConnection)
      wordLost = true
    }
    editor = attribute
    resetWord()
    pendingSelections.clear()
    cursor = attribute?.initialSelEnd ?: -1
    selectionStart = attribute?.initialSelStart ?: -1
    shifted = false
    val inputType = attribute?.inputType ?: InputType.TYPE_NULL
    telexAllowed = allowsTelex(inputType) && !(attribute != null && attribute.packageName == packageName &&
      attribute.privateImeOptions == VietnameseKeyboardShortcuts.LITERAL_CODE_FIELD)
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    symbols = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE ||
      inputClass == InputType.TYPE_CLASS_DATETIME
    symbolSecondPage = false
    t9 = symbols
    editorActionId = editorAction(attribute)
    editorActionLabel = attribute?.actionLabel?.toString()?.takeIf {
      editorActionId != null && it.isNotBlank()
    } ?: getString(actionTitle(editorActionId))
    configureView()
    updateLayout()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (!event.isFromSource(InputDevice.SOURCE_KEYBOARD) ||
      event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return super.onKeyDown(keyCode, event)
    val connection = currentInputConnection ?: return super.onKeyDown(keyCode, event)
    val hardwareKey = event.deviceId to keyCode
    val handled = hardwareKey in handledHardwareKeys
    if (event.repeatCount > 0 && !handled) return super.onKeyDown(keyCode, event)
    if (editor != null && telexAllowed && !event.isCtrlPressed &&
      !event.isAltPressed && !event.isMetaPressed && !event.isFunctionPressed && !event.isSymPressed) {
      val unicode = event.unicodeChar
      val composed = when {
        unicode != 0 && !unicode.toChar().isLetterOrDigit() && !unicode.toChar().isISOControl() &&
          expandShortcut(connection, null, unicode.toChar()) -> true
        !vietnamese -> false
        unicode in 'a'.code..'z'.code || unicode in 'A'.code..'Z'.code ||
          (unicode != 0 && unicode.toChar() in "[]{}") ->
          composeLetter(connection, unicode.toChar(), null)
        keyCode == KeyEvent.KEYCODE_DEL && composing -> deleteComposingCharacter(connection, null)
        unicode == ' '.code && composing -> {
          val committed = commitText(connection, " ", markImeEdit = false)
          if (committed) resetWord()
          committed
        }
        else -> false
      }
      if (composed) {
        handledHardwareKeys.add(hardwareKey)
        return true
      }
    }
    if (!KeyEvent.isModifierKey(keyCode) && keyCode != KeyEvent.KEYCODE_CAPS_LOCK &&
      (composing || wordLost || composingUnavailable)) finishWord(connection)
    return handled || super.onKeyDown(keyCode, event)
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
    handledHardwareKeys.remove(event.deviceId to keyCode) || super.onKeyUp(keyCode, event)

  override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    showInputView()
    configureView()
    updateLayout()
  }

  override fun onWindowShown() {
    super.onWindowShown()
    windowShown = true
    showInputView()
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
    inputGeneration++
    endTouchSession()
    resetWord()
    editor = null
    pendingSelections.clear()
    handledHardwareKeys.clear()
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
      wordLost = true
    } else if (!composing && !sameSelection &&
      (cursor >= 0 || selectionStart >= 0 || newSelStart != newSelEnd)) {
      keyboard?.cancelInput(cancelPendingEdits = false)
      keySpeech.clearPreview()
      resetWord()
      wordLost = true
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
      VietnameseKeyboardView.LAYOUT_PHONE,
    ) ?: VietnameseKeyboardView.LAYOUT_PHONE
    val newEcho = KeyboardEchoSettings.read(prefs, resources, ON_SCREEN_KEYBOARD)
    val newOwnVoice = SharedPreferencesUtils.getBooleanPref(
      prefs, resources, R.string.pref_vietnamese_keyboard_own_voice_key,
      R.bool.pref_vietnamese_keyboard_own_voice_default,
    )
    val newSizePortrait = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_size_portrait_key), 65,
    ).coerceIn(50, 150)
    val newSizeLandscape = prefs.getInt(
      getString(R.string.pref_vietnamese_keyboard_size_landscape_key), 100,
    ).coerceIn(50, 150)
    val newMicEnabled = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_mic_enabled_key), true,
    )
    val newAutoSwitchLettersOnSpace = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_auto_switch_letters_on_space_key), true,
    )
    val newVoiceProfileId = prefs.getString(
      getString(R.string.pref_vietnamese_keyboard_voice_profile_key), "",
    ).orEmpty()
    val newVoiceProfileSignature = voiceProfileSignature(prefs, newVoiceProfileId)
    val newShowLanguageKey = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_show_language_key), false,
    )
    val newShowNumberRow = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_show_number_row_key), true,
    )
    val newShowSymbolsRow = prefs.getBoolean(
      getString(R.string.pref_vietnamese_keyboard_show_symbols_row_key), true,
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
      getString(R.string.pref_vietnamese_keyboard_key_sound_key), true,
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
      micEnabled != newMicEnabled ||
      showLanguageKey != newShowLanguageKey ||
      showNumberRow != newShowNumberRow ||
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
    autoSwitchLettersOnSpace = newAutoSwitchLettersOnSpace
    showLanguageKey = newShowLanguageKey
    quickTelex = prefs.getBoolean(getString(R.string.pref_vietnamese_keyboard_quick_telex_key), true)
    t9DoubleTap = prefs.getBoolean(getString(R.string.pref_vietnamese_keyboard_t9_double_tap_key), false)
    shortcuts = if (prefs.getBoolean(getString(R.string.pref_vietnamese_keyboard_shortcuts_enabled_key), false)) {
      VietnameseKeyboardShortcuts.read(prefs)
    } else emptyMap()
    longestShortcut = shortcuts.keys.maxOfOrNull(String::length) ?: 0
    if (longestShortcut == 0) shortcutKeys = null
    showNumberRow = newShowNumberRow
    showSymbolsRow = newShowSymbolsRow
    symbolsRow = newSymbolsRow
    autoCapitalize = newAutoCapitalize
    doubleSpacePeriod = newDoubleSpacePeriod
    vibrateOnKey = newVibrateOnKey
    keySound = newKeySound
    keySounds = newKeySounds
    vibrateIntensityPercent = newVibrateIntensityPercent
    val profileChanged = voiceProfileId != newVoiceProfileId ||
      voiceProfileSignature != newVoiceProfileSignature
    voiceProfileId = newVoiceProfileId
    voiceProfileSignature = newVoiceProfileSignature
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
    } else if (ownVoice && profileChanged) {
      releaseKeyboardVoice()
      if (windowShown) {
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
      t9DoubleTap = this@VietnameseKeyboardService.t9DoubleTap
      timing = this@VietnameseKeyboardService.timing
      speakHoveredKeys =
        ownVoice && method != DIRECT_SILENT_TYPING && method != SINGLE_TAP_TO_TYPE
      hasCommitAnnouncement = ::hasCommitAnnouncement
      layoutSetting = layout
      sizePortraitPercent = this@VietnameseKeyboardService.sizePortraitPercent
      sizeLandscapePercent = this@VietnameseKeyboardService.sizeLandscapePercent
      micEnabled = isMicVisible()
      showLanguageKey = this@VietnameseKeyboardService.showLanguageKey
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
      t9 = t9,
      symbolSecondPage = symbolSecondPage,
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
      VietnameseKeyboardView.SHIFT -> {
        if (symbols) {
          symbolSecondPage = !symbolSecondPage
          val pageAnnouncement = if (symbolSecondPage) {
            R.string.vietnamese_ime_symbols_page_2
          } else {
            R.string.vietnamese_ime_symbols_page_1
          }
          keySpeech.confirm(getString(pageAnnouncement), speakAt)
        } else {
          shifted = !shifted
        }
      }
      VietnameseKeyboardView.SYMBOLS -> {
        symbols = true
        symbolSecondPage = false
        t9 = false
      }
      VietnameseKeyboardView.LETTERS -> {
        symbols = false
        symbolSecondPage = false
        t9 = false
      }
      VietnameseKeyboardView.LANGUAGE -> {
        finishWord(connection)
        if (telexAllowed) vietnamese = !vietnamese
      }
      VietnameseKeyboardView.HIDE -> {
        finishWord(connection)
        hideInputViewImmediately()
        requestHideSelf(0)
        return true
      }
      VietnameseKeyboardView.DELETE, VietnameseKeyboardView.DELETE_WORD -> {
        val announceDeletion = echo != TextEventFilter.PREF_ECHO_SILENT_TYPING
        return when (deletionGranularity()) {
          CursorGranularity.LINE -> deleteTextUnit(connection, false, announceDeletion, speakAt)
          CursorGranularity.PARAGRAPH -> deleteTextUnit(connection, true, announceDeletion, speakAt)
          CursorGranularity.WORD -> deleteWord(connection, announceDeletion, speakAt, true)
          else -> if (id == VietnameseKeyboardView.DELETE_WORD) {
            deleteWord(connection, announceDeletion, speakAt, false)
          } else {
            delete(connection, announceDeletion, speakAt)
          }
        }
      }
      VietnameseKeyboardView.ENTER -> if (!enter(connection, speakAt)) return false
      VietnameseKeyboardView.MIC -> return startMicDictation()
      else -> {
        val letter = id.single()
        if (!type(connection, if (shifted) letter.uppercaseChar() else letter, speakAt)) return false
        if (symbols && autoSwitchLettersOnSpace && letter == ' ') {
          symbols = false
          symbolSecondPage = false
        } else if (shifted && letter.isLetter()) {
          shifted = false
        } else {
          return true
        }
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
      keySpeech.confirm(getString(R.string.dictation_unavailable), 0)
      return false
    }
    val generation = inputGeneration
    val target = findDictationEditor()
    if (!dictationListen { text -> onDictatedText(text, generation, target) }) {
      keySpeech.confirm(getString(R.string.dictation_unavailable), 0)
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

  private fun onDictatedText(text: String?, generation: Int, originalTarget: AccessibilityNodeInfoCompat?) {
    if (generation != inputGeneration) return
    dictating = false
    if (text.isNullOrBlank()) return
    val connection = currentInputConnection ?: return
    val target = originalTarget ?: findDictationEditor()
    if (target != null) ImeEditFeedback.recordDictationEdit(target.unwrap())
    dropComposingWord(connection)
    if (!insertDictatedText(connection, text)) return
    if (target != null) TalkBackService.getInstance()?.focusDictationEditor(target)
    if (target?.isPassword != true && editor?.inputType?.let(::isPassword) != true) {
      speakWord(text, 0)
    }
  }

  private fun findDictationEditor(): AccessibilityNodeInfoCompat? {
    val node = TalkBackService.getInstance()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
    if (node.isEditable && node.packageName?.toString() == editor?.packageName) {
      return AccessibilityNodeInfoCompat.wrap(node)
    }
    node.recycle()
    return null
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
    if (!letter.isLetterOrDigit() && !letter.isISOControl() &&
      expandShortcut(connection, speakAt, letter)) return true
    if (letter == ' ' && !composing && doubleSpacePeriod && telexAllowed &&
      selectionStart == cursor && tryDoubleSpacePeriod(connection)) {
      if (echoesCharacters()) speakEnteredCharacter('.', speakAt)
      return true
    }
    val capitalized = if (letter.isLowerCase() && word.isEmpty() &&
      shouldCapitalize(connection)) letter.uppercaseChar() else letter
    val wordCharacter = letter.isLetterOrDigit()
    if (Telex.isTelexKey(letter) && vietnamese && telexAllowed &&
      composeLetter(connection, capitalized, speakAt, typedKey = letter)) return true
    wordLost = false
    val finished = if (wordCharacter) "" else completedWord().ifEmpty {
      adjacentWord(connection, beforeCursor = true)
    }
    if (!commitText(connection, capitalized.toString())) return false
    if (wordCharacter) {
      rememberShortcutKey(letter, word.isEmpty())
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

  private fun composeLetter(
    connection: InputConnection, letter: Char, speakAt: Long?, typedKey: Char = letter,
  ): Boolean {
    if (composingUnavailable) return false
    val hasSelection = selectionStart >= 0 && cursor >= 0 && selectionStart != cursor
    if (!composing && !hasSelection && wordLost) {
      val before = adjacentWord(connection, beforeCursor = true)
      val after = adjacentWord(connection, beforeCursor = false)
      if (after.isNotEmpty() && before.length + after.length <= MAX_WORD_LENGTH) {
        val fullWord = before + after
        val transformed = Telex.apply(fullWord, letter, quickTelex = false)
        if (transformed != fullWord + letter) {
          wordLost = false
          if (!connection.deleteSurroundingTextInCodePoints(before.length, after.length)) {
            return false
          }
          val wordStart = if (cursor >= before.length) cursor - before.length else -1
          if (connection.setComposingText(transformed, 1)) {
            composing = transformed.isNotEmpty()
            composingStart = if (composing) wordStart else -1
            expectSelection(
              if (wordStart >= 0) wordStart + transformed.length else -1,
              composingStart,
            )
          } else if (connection.commitText(transformed, 1)) {
            composing = false
            composingStart = -1
            expectSelection(if (wordStart >= 0) wordStart + transformed.length else -1)
          } else {
            return false
          }
          val changed =
            if (speakAt != null && echoesCharacters()) changedCharacter(fullWord, transformed) else null
          word = transformed
          if (changed != null) {
            keySpeech.confirm(keyNames.spokenName(characterLabel(changed)), speakAt!!)
          }
          return true
        }
      } else if (before.isNotEmpty()) {
        if (!recoverWord(connection, before)) {
          val transformed = Telex.apply(before, letter, quickTelex = false)
          if (transformed != before + letter && transformed.length <= MAX_WORD_LENGTH) {
            wordLost = false
            if (!connection.deleteSurroundingTextInCodePoints(before.length, 0)) {
              return false
            }
            val wordStart = if (cursor >= before.length) cursor - before.length else -1
            if (connection.setComposingText(transformed, 1)) {
              composing = transformed.isNotEmpty()
              composingStart = if (composing) wordStart else -1
              expectSelection(
                if (wordStart >= 0) wordStart + transformed.length else -1,
                composingStart,
              )
            } else if (connection.commitText(transformed, 1)) {
              composing = false
              composingStart = -1
              expectSelection(if (wordStart >= 0) wordStart + transformed.length else -1)
            } else {
              return false
            }
            val changed =
              if (speakAt != null && echoesCharacters()) changedCharacter(before, transformed) else null
            word = transformed
            if (changed != null) {
              keySpeech.confirm(keyNames.spokenName(characterLabel(changed)), speakAt!!)
            }
            return true
          }
        }
      } else if (wordLost) {
        wordLost = false
      }
    }
    val useQuickTelex = quickTelex && letter.lowercaseChar() in "aeiouy" && word.length in 2..3 &&
      word.all { it.equals(word[0], ignoreCase = true) } &&
      connection.getTextBeforeCursor(word.length + 2, 0)
        ?.let { WORD_BEFORE_CURSOR.find(it)?.value == word } == true
    val updated = Telex.apply(word, letter, useQuickTelex)
    if (speakAt != null) markEdit()
    if (updated.length > MAX_WORD_LENGTH || !updateComposition(connection, updated)) {
      composingUnavailable = true
      return false
    }
    val changed = if (speakAt != null && echoesCharacters()) changedCharacter(word, updated) else null
    rememberShortcutKey(typedKey, word.isEmpty())
    word = updated
    if (speakAt != null && changed != null) speakEnteredCharacter(changed, speakAt)
    return true
  }

  private fun rememberShortcutKey(key: Char, startsWord: Boolean) {
    val previous = if (startsWord) "" else shortcutKeys
    shortcutKeys = previous?.takeIf { it.length < longestShortcut }?.plus(key)
  }

  private fun expandShortcut(
    connection: InputConnection, speakAt: Long?, trigger: Char = ' ',
  ): Boolean {
    if (!telexAllowed || shortcuts.isEmpty()) return false
    val before = connection.getTextBeforeCursor(maxOf(longestShortcut, word.length) + 2, 0) ?: return false
    val code = WORD_BEFORE_CURSOR.find(before)?.value ?: return false
    val rawCode = shortcutKeys.takeIf { code == word }
    val replacement = rawCode?.let(shortcuts::get) ?: shortcuts[code] ?: return false
    if (!connection.getSelectedText(0).isNullOrEmpty()) return false
    val after = connection.getTextAfterCursor(2, 0) ?: return false
    if (WORD_AFTER_CURSOR.containsMatchIn(after)) return false
    val existingComposition = composing && code == word
    val start = if (existingComposition) composingStart else {
      shortcutStartInEditor(connection, code) ?: return false
    }
    connection.beginBatchEdit()
    try {
      if (!existingComposition && !connection.setComposingRegion(start, start + code.length)) return false
      if (speakAt != null) markEdit()
      if (!connection.commitText(replacement + trigger, 1)) {
        if (!existingComposition) {
          connection.finishComposingText()
          resetWord()
        }
        return false
      }
      expectSelection(if (start < 0) -1 else start + replacement.length + 1)
      resetWord()
    } finally {
      connection.endBatchEdit()
    }
    if (speakAt != null && (echoesCharacters() || echoesWords())) speakWord(replacement, speakAt)
    return true
  }

  private fun shortcutStartInEditor(connection: InputConnection, code: String): Int? {
    val request = ExtractedTextRequest().apply {
      hintMaxChars = code.length + 4
      hintMaxLines = 1
    }
    val extracted = connection.getExtractedText(request, 0) ?: return null
    val text = extracted.text ?: return null
    val end = extracted.selectionEnd
    val start = end - code.length
    if (extracted.startOffset < 0 || start < 0 || end > text.length || extracted.selectionStart != end ||
      text.subSequence(start, end).toString() != code) return null
    return extracted.startOffset + start
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
    shortcutKeys = null
    if (composing && word.isNotEmpty()) {
      if (deleteComposingCharacter(connection, speakAt.takeIf { announceDeletion })) return true
      if (!connection.finishComposingText()) return false
      expectSelection(cursor)
      composing = false
      composingStart = -1
      composingUnavailable = true
      wordLost = true
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
    // Deleting a character that is not part of the tracked word leaves the
    // cursor next to text that may be a word (e.g. a space typed after a
    // word was just deleted), so the next letter recovers that word from
    // the field instead of starting a new one.
    if (!trackedCharacter) wordLost = true
    if (trackedCharacter) {
      word = Telex.deleteLast(word)
      if (word.isEmpty()) resetWord() else composingUnavailable = false
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

  private fun deleteComposingCharacter(connection: InputConnection, speakAt: Long?): Boolean {
    val deleted = word.last().toString()
    val shortened = Telex.deleteLast(word)
    if (!updateComposition(connection, shortened)) return false
    shortcutKeys = null
    word = shortened
    if (shortened.isEmpty()) resetWord()
    if (speakAt != null) speakDeletedText(deleted, speakAt)
    return true
  }

  private fun deletionGranularity(): CursorGranularity {
    val current = TalkBackService.getInstance()?.takeIf { TalkBackService.isServiceActive() }
      ?.getActorState()?.getDirectionNavigation()?.currentGranularity
    if (current != null && current != CursorGranularity.DEFAULT) return current
    return when (SelectorController.getCurrentSetting(this)) {
      SelectorController.Setting.GRANULARITY_WORDS -> CursorGranularity.WORD
      SelectorController.Setting.GRANULARITY_LINES -> CursorGranularity.LINE
      SelectorController.Setting.GRANULARITY_PARAGRAPHS -> CursorGranularity.PARAGRAPH
      else -> CursorGranularity.CHARACTER
    }
  }

  private fun deleteTextUnit(
    connection: InputConnection, paragraph: Boolean, announceDeletion: Boolean, speakAt: Long,
  ): Boolean {
    if (editor?.inputType == InputType.TYPE_NULL) return delete(connection, announceDeletion, speakAt)
    val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)?.takeIf {
      val length = it.text?.length ?: return@takeIf false
      it.startOffset == 0 && it.selectionStart in 0..length && it.selectionEnd in 0..length
    }
    val selected = extracted?.let { it.selectionStart != it.selectionEnd }
      ?: !connection.getSelectedText(0).isNullOrEmpty()
    connection.beginBatchEdit()
    try {
      if (composing && !connection.finishComposingText()) return false
      resetWord()
      if (selected) {
        markEdit()
        if (!connection.commitText("", 1)) return false
        expectSelection(extracted?.let { minOf(it.selectionStart, it.selectionEnd) } ?: -1)
        if (announceDeletion) speakDeletedText(getString(R.string.vietnamese_ime_selected_text), speakAt)
        return true
      }
      val nearby = if (extracted == null) nearbyTextForDeletion(connection) ?: return false else null
      val text = extracted?.text?.toString() ?: checkNotNull(nearby).first
      val caret = extracted?.selectionEnd ?: checkNotNull(nearby).second
      val range = textUnitToDelete(text, caret, paragraph) { start, end ->
        if (extracted == null) null else visualLine(text, caret, start, end)
      }
      if (range.isEmpty()) return true
      markEdit()
      if (!connection.deleteSurroundingTextInCodePoints(
          text.codePointCount(range.first, caret), text.codePointCount(caret, range.last + 1),
        )) return false
      expectSelection(if (extracted != null) range.first else -1)
    } finally {
      connection.endBatchEdit()
    }
    if (announceDeletion) {
      val message = if (paragraph) R.string.vietnamese_ime_deleted_paragraph
        else R.string.vietnamese_ime_deleted_line
      keySpeech.confirm(getString(message), speakAt)
    }
    return true
  }

  private fun nearbyTextForDeletion(connection: InputConnection): Pair<String, Int>? {
    val before = connection.getTextBeforeCursor(MAX_UNIT_LOOKAROUND, 0)?.toString() ?: return null
    val after = connection.getTextAfterCursor(MAX_UNIT_LOOKAROUND, 0)?.toString() ?: return null
    if ((before.length == MAX_UNIT_LOOKAROUND && '\n' !in before) ||
      (after.length == MAX_UNIT_LOOKAROUND && '\n' !in after)) return null
    return (before + after) to before.length
  }

  private fun visualLine(text: String, caret: Int, start: Int, end: Int): IntRange? {
    val reader = TalkBackService.getInstance()?.takeIf { TalkBackService.isServiceActive() } ?: return null
    val focused = reader.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
    val node = AccessibilityNodeInfoCompat.wrap(focused)
    try {
      if (!node.isEditable || node.packageName?.toString() != editor?.packageName ||
        node.textSelectionStart != caret || node.textSelectionEnd != caret ||
        node.text?.toString() != text || !AccessibilityNodeInfoUtils.supportsTextLocation(node)) return null
      val anchor = minOf(caret, end - 1)
      val from = maxOf(start, anchor - 511)
      val to = minOf(end, anchor + 512)
      val locations = AccessibilityNodeInfoUtils.getTextLocations(node, text, from, to, false)
        ?.takeIf { it.size == to - from } ?: return null
      val top = locations[anchor - from].top
      var left = anchor
      var right = anchor + 1
      while (left > from && locations[left - from - 1].top == top) left--
      while (right < to && locations[right - from].top == top) right++
      if ((left == from && from > start) || (right == to && to < end)) return null
      return left until right
    } finally {
      node.recycle()
    }
  }

  private fun deleteWord(
    connection: InputConnection, announceDeletion: Boolean, speakAt: Long, currentWord: Boolean,
  ): Boolean {
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
      wordLost = true
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
    var before = adjacentWord(connection, beforeCursor = true)
    val after = if (currentWord) adjacentWord(connection, beforeCursor = false) else ""
    if (before.isEmpty() && after.isEmpty()) {
      if (connection.getTextBeforeCursor(1, 0)?.singleOrNull()?.isWhitespace() == true) {
        return delete(connection, announceDeletion, speakAt)
      }
      if (!currentWord) return true
      before = adjacentWord(connection, beforeCursor = true, skipWhitespace = true)
      if (before.isEmpty()) return delete(connection, announceDeletion, speakAt)
    }
    if (!connection.deleteSurroundingTextInCodePoints(
        before.codePointCount(0, before.length), after.codePointCount(0, after.length),
      )) return false
    resetWord()
    expectSelection(if (cursor >= before.length) cursor - before.length else -1)
    if (announceDeletion) speakDeletedText(before.trimEnd() + after, speakAt)
    return true
  }

  // When no composition is active the tracked word may be stale or lost, so
  // re-read the word before the cursor from the editor and mark it as the
  // composition. The next Telex key then shapes the word on screen instead
  // of starting a new word after it.
  private fun recoverWord(connection: InputConnection, prefetched: String? = null): Boolean {
    wordLost = false
    if (selectionStart != cursor || cursor < 0) return false
    val current = prefetched ?: adjacentWord(connection, beforeCursor = true)
    if (current.isEmpty()) return false
    val start = cursor - current.length
    if (start < 0 || !connection.setComposingRegion(start, cursor)) return false
    word = current
    composing = true
    composingStart = start
    expectSelection(cursor, composingStart)
    return true
  }

  private fun adjacentWord(
    connection: InputConnection, beforeCursor: Boolean, skipWhitespace: Boolean = false,
  ): String {
    var window = 64
    val pattern = when {
      !beforeCursor -> WORD_AFTER_CURSOR
      skipWhitespace -> WORD_BEFORE_WHITESPACE
      else -> WORD_BEFORE_CURSOR
    }
    while (true) {
      val text = if (beforeCursor) connection.getTextBeforeCursor(window, 0)
        else connection.getTextAfterCursor(window, 0)
      if (text == null) return ""
      val found = pattern.find(text)?.value
      if (found == null) {
        if (!skipWhitespace || text.length < window || text.isNotBlank() ||
          window >= MAX_WORD_DELETE_LOOKBACK) return ""
      } else if (found.length < text.length || text.length < window || window >= MAX_WORD_DELETE_LOOKBACK) {
        return found
      }
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

  private fun commitText(
    connection: InputConnection, text: String, markImeEdit: Boolean = true,
  ): Boolean {
    if (markImeEdit) markEdit()
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
    var index = pendingSelections.indexOfFirst { it.end == position && it.composingStart == candidatesStart }
    if (index < 0 && candidatesStart < 0) {
      index = pendingSelections.indexOfFirst { it.end == position }
    }
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
    wordLost = false
    shortcutKeys = null
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
    val spoken =
      if (text == " ") getString(R.string.vietnamese_ime_space) else keyNames.spokenName(text)
    keySpeech.confirm(getString(R.string.template_text_removed, spoken), speakAt)
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

  private fun currentVoiceProfile(): VoiceProfiles.VoiceProfile? {
    if (voiceProfileId.isEmpty()) return null
    val prefs = SharedPreferencesUtils.getSharedPreferences(this)
    if (!VoiceProfiles.ids(prefs).contains(voiceProfileId)) return null
    return VoiceProfiles.read(prefs, voiceProfileId)
  }

  private fun voiceProfileSignature(prefs: SharedPreferences, id: String): String {
    if (id.isEmpty() || !VoiceProfiles.ids(prefs).contains(id)) return ""
    val p = VoiceProfiles.read(prefs, id)
    return "${p.id}|${p.engine}|${p.language}|${p.voice}|${p.volume}|${p.rate}|${p.pitch}"
  }

  private fun warmUpKeyboardVoice() {
    if (voiceWarmedUp) return
    val voice = keyboardVoice ?: VietnameseKeyboardVoice(this, currentVoiceProfile()).also {
      keyboardVoice = it
    }
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
    for (symbol in VietnameseKeyboardSymbols.SYMBOL_PAGE_1_ROW_1 +
      VietnameseKeyboardSymbols.SYMBOL_PAGE_1_ROW_2 +
      VietnameseKeyboardSymbols.SYMBOL_PAGE_1_ROW_3 +
      VietnameseKeyboardSymbols.SYMBOL_PAGE_2_ROW_1 +
      VietnameseKeyboardSymbols.SYMBOL_PAGE_2_ROW_2 +
      VietnameseKeyboardSymbols.SYMBOL_PAGE_2_ROW_3 + ",.") {
      labels.add(keyNames.spokenName(symbol.toString()))
    }
    labels.add(keyNames.spokenName(getString(R.string.vietnamese_ime_space)))
    labels.add(keyNames.spokenName(getString(R.string.vietnamese_ime_password_character)))
    labels.add(getString(R.string.vietnamese_ime_uppercase))
    labels.add(getString(R.string.vietnamese_ime_lowercase))
    labels.add(getString(R.string.vietnamese_ime_symbols))
    labels.add(getString(R.string.vietnamese_ime_letters))
    labels.add(getString(R.string.vietnamese_ime_symbols_page_1))
    labels.add(getString(R.string.vietnamese_ime_symbols_page_2))
    labels.add(getString(R.string.vietnamese_ime_hide))
    labels.add(getString(R.string.vietnamese_ime_voice_input))
    labels.add(getString(R.string.vietnamese_ime_voice_input_stopped))
    labels.add(getString(R.string.dictation_unavailable))
    labels.add(getString(R.string.vietnamese_ime_delete))
    labels.add(getString(R.string.vietnamese_ime_telex_on))
    labels.add(getString(R.string.vietnamese_ime_telex_off))
    labels.add(getString(R.string.vietnamese_ime_switch_to_english))
    labels.add(getString(R.string.vietnamese_ime_switch_to_vietnamese))
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
    private const val MAX_UNIT_LOOKAROUND = 16_384
    private const val WORD_CHARACTERS = VietnameseKeyboardShortcuts.WORD_CHARACTERS
    private val WORD_BEFORE_CURSOR = Regex("""$WORD_CHARACTERS\z""")
    private val WORD_BEFORE_WHITESPACE = Regex("""$WORD_CHARACTERS\s*\z""")
    private val WORD_AFTER_CURSOR = Regex("""\A$WORD_CHARACTERS""")

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
