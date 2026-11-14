package com.google.android.accessibility.talkback.vietnameseime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DIRECT_SILENT_TYPING
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.IME_ACTION_KEY_ID_SUFFIX
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import kotlin.math.abs
import kotlin.math.roundToInt

internal class VietnameseKeyboardView(
  context: Context,
  private val announce: (String) -> Unit,
  private val activate: (String, Long) -> Boolean,
  private val releaseAnnouncement: (Boolean) -> Unit = {},
  private val shiftSpoken: () -> Unit = {},
  private val keyName: (String) -> String = { it },
  private val reportBounds: () -> Unit = {},
  private val micStop: () -> Unit = {},
  private val onDirectKeyTouchDown: () -> Unit = {},
) : View(context) {
  private class Cell(
    val key: VietnameseKeyTouch.Key,
    val baseTitle: String,
    val weight: Float = 1f,
    val bounds: RectF = RectF(),
    var title: String = baseTitle,
    var textSize: Float = 0f,
    var baseline: Float = 0f,
    var nextVirtualId: Int = ExploreByTouchHelper.INVALID_ID,
  )

  private val configuration = ViewConfiguration.get(context)
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val backgroundColor = ContextCompat.getColor(context, R.color.vietnamese_ime_background)
  private val keyColor = ContextCompat.getColor(context, R.color.vietnamese_ime_key)
  private val selectionColor = ContextCompat.getColor(context, R.color.vietnamese_ime_selection)
  private val density = resources.displayMetrics.density
  private var rows = emptyList<List<Cell>>()
  private var shifted = false
  private var symbolPage = false
  private var telexEnabled = true
  private var telexAvailable = true
  private var actionLabel = context.getString(R.string.vietnamese_ime_enter)
  private val pointers = LinkedHashMap<Int, PointerState>()
  private val firstTaps = LinkedHashMap<VietnameseKeyTouch.Key, VietnameseKeyTouch.Tap>()
  private val selectedKeys = HashSet<VietnameseKeyTouch.Key>()
  private var speechPointer: Int? = null
  private var hoveredKey: VietnameseKeyTouch.Key? = null
  private val virtualIds = HashMap<String, Int>()
  private var virtualCells = emptyMap<Int, Cell>()
  private val keyAccessibility = KeyAccessibility()
  private val touchHandler = Handler(Looper.getMainLooper())
  private var pendingKeyDelay = 0
  private var lastDeleteTapMs = -1L
  private var deleteRepeatPointer: Int? = null
  private var deleteRepeatIntervalMs = DELETE_REPEAT_START_INTERVAL_MS
  private val deleteRepeatRunnable = Runnable { repeatDelete() }
  private val reportBeforeDraw = ViewTreeObserver.OnPreDrawListener {
    reportBounds()
    true
  }
  private var rowsArePc = false
  private val shiftHolders = LinkedHashSet<Int>()
  private var shiftSticky = false
  private var shiftUsed = false
  private var micHoldPointer: Int? = null
  private var micListeningPointer: Int? = null
  private val micHoldRunnable = Runnable {
    val pointer = micHoldPointer ?: return@Runnable
    if (pointers[pointer]?.startingCell?.key?.id != MIC) return@Runnable
    if (activate(MIC, 0L)) micListeningPointer = pointer
    micHoldPointer = null
  }

  private class PointerState(
    val pointerId: Int,
    val downX: Float,
    val downY: Float,
    val downTime: Long,
    val startingCell: Cell?,
    val method: Int,
    val symbolPage: Boolean,
    val touch: VietnameseKeyTouch,
    val pcShift: Boolean,
    var moved: Boolean = false,
    var lastSpoken: Cell? = null,
    var lastX: Float = downX,
    var lastY: Float = downY,
    var deleteRepeated: Boolean = false,
  )

  var typingMethod = 0
    set(value) {
      if (field != value) cancelInput()
      field = value
    }
  var timing = VietnameseKeyboardTiming()
    set(value) {
      if (field != value) cancelInput()
      field = value
    }
  var speakHoveredKeys = false
    set(value) {
      field = value
      if (!value) hoveredKey = null
    }
  var hasCommitAnnouncement: (String) -> Boolean = { false }

  var layoutSetting = LAYOUT_AUTO
    set(value) {
      if (field == value) return
      cancelInput()
      field = value
      rebuildRows()
    }
  var sizePortraitPercent = 100
    set(value) {
      val percent = value.coerceIn(50, 150)
      if (field == percent) return
      field = percent
      requestLayout()
    }
  var sizeLandscapePercent = 100
    set(value) {
      val percent = value.coerceIn(50, 150)
      if (field == percent) return
      field = percent
      requestLayout()
    }
  var micEnabled = false
    set(value) {
      if (field == value) return
      cancelInput()
      field = value
      rebuildRows()
    }
  var showNumberRow = true
    set(value) {
      if (field == value) return
      cancelInput()
      field = value
      rebuildRows()
    }
  var showSymbolsRow = false
    set(value) {
      if (field == value) return
      cancelInput()
      field = value
      rebuildRows()
    }
  var symbolsRowString = VietnameseKeyboardSymbols.DEFAULT_ROW
    set(value) {
      if (field == value) return
      cancelInput()
      field = value
      rebuildRows()
    }
  var autoCapitalize = true
  var doubleSpacePeriod = true
  var vibrateOnKey = false
  var keySound = false
  internal var keyFeedback: VietnameseKeyFeedback = VietnameseKeyFeedback(context)
  var keySounds: KeySounds = KeyboardKeySounds.defaultSounds()
    set(value) {
      field = value
      keyFeedback.setSounds(value)
    }
  var vibrateIntensityPercent: Int = 100

  init {
    ViewCompat.setAccessibilityDelegate(this, keyAccessibility)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    isFocusable = false
    isFocusableInTouchMode = false
  }

  fun showKeys(
    shifted: Boolean,
    symbols: Boolean,
    telex: Boolean,
    actionLabel: String,
    telexAvailable: Boolean = true,
  ) {
    val pageChanged = symbolPage != symbols
    val layoutChanged = isPcLayout() != rowsArePc
    val rebuild = rows.isEmpty() || pageChanged || layoutChanged || telexEnabled != telex ||
      this.telexAvailable != telexAvailable || this.actionLabel != actionLabel
    val shiftChanged = this.shifted != shifted
    if (layoutChanged) cancelInput()
    if (pageChanged) {
      firstTaps.clear()
      lastDeleteTapMs = -1
    }
    this.shifted = shifted
    symbolPage = symbols
    telexEnabled = telex
    this.telexAvailable = telexAvailable
    this.actionLabel = actionLabel
    if (rebuild) rebuildRows() else if (shiftChanged) refreshShiftLabels()
  }

  fun cancelInput(cancelPendingEdits: Boolean = true) {
    if (cancelPendingEdits && pendingKeyDelay > 0) {
      touchHandler.removeCallbacksAndMessages(null)
      pendingKeyDelay = 0
    }
    disarmMicHold()
    if (micListeningPointer != null) {
      micListeningPointer = null
      micStop()
    }
    pointers.clear()
    firstTaps.clear()
    lastDeleteTapMs = -1
    deleteRepeatPointer = null
    touchHandler.removeCallbacks(deleteRepeatRunnable)
    speechPointer = null
    hoveredKey = null
    val hadShift = shiftHolders.isNotEmpty() || shiftSticky
    shiftHolders.clear()
    shiftSticky = false
    shiftUsed = false
    if (hadShift) refreshShiftLabels() else invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val configuration = resources.configuration
    val heightDp = if (isPcLayout()) {
      configuration.screenHeightDp
    } else if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      (195 * activeSizePercent() / 100f).roundToInt()
    } else {
      (maxOf(240, (configuration.screenHeightDp * 0.48f).roundToInt()) * activeSizePercent() / 100f)
        .roundToInt()
    }
    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec),
      resolveSize((heightDp * density).roundToInt(), heightMeasureSpec),
    )
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    if (oldWidth > 0 && oldHeight > 0) cancelInput()
    if (isPcLayout() != rowsArePc) {
      rebuildRows()
    } else {
      placeKeys()
      keyAccessibility.invalidateRoot()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnPreDrawListener(reportBeforeDraw)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnPreDrawListener(reportBeforeDraw)
    cancelInput()
    keyFeedback.release()
    super.onDetachedFromWindow()
  }

  public override fun dispatchHoverEvent(event: MotionEvent): Boolean {
    if (speakHoveredKeys && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
      when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
          val cell = cellAt(event.x, event.y)
          if (hoveredKey != cell?.key) {
            hoveredKey = cell?.key
            announce(cell?.let(::keyLabel).orEmpty())
          }
        }
        MotionEvent.ACTION_HOVER_EXIT -> finishHover(waitsForCommit = false)
      }
    }
    return keyAccessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
  }

  private fun finishHover(waitsForCommit: Boolean) {
    if (hoveredKey == null) return
    hoveredKey = null
    releaseAnnouncement(waitsForCommit)
  }

  override fun onDraw(canvas: Canvas) {
    canvas.drawColor(backgroundColor)
    val gap = 2 * density
    val corner = 5 * density
    paint.textAlign = Paint.Align.CENTER
    paint.color = Color.WHITE
    selectedKeys.clear()
    pointers.values.mapNotNullTo(selectedKeys) { it.touch.selected }
    for (row in rows) {
      for (cell in row) {
        val bounds = cell.bounds
        val active = cell.key in selectedKeys
        paint.color = if (active) selectionColor else keyColor
        canvas.drawRoundRect(
          bounds.left + gap, bounds.top + gap, bounds.right - gap, bounds.bottom - gap,
          corner, corner, paint,
        )
        paint.color = Color.WHITE
        paint.textSize = cell.textSize
        canvas.drawText(cell.title, bounds.centerX(), cell.baseline, paint)
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    var moveChanged = true
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pointers.isNotEmpty()) {
          cancelInput(cancelPendingEdits = false)
        }
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        val cell = cellAt(x, y)
        val state = PointerState(
          pointerId = id,
          downX = x,
          downY = y,
          downTime = event.eventTime,
          startingCell = cell,
          method = typingMethod,
          symbolPage = symbolPage,
          touch = VietnameseKeyTouch(
            timing.doubleTapMs, configuration.scaledDoubleTapSlop, firstTaps,
          ),
          pcShift = rowsArePc && cell?.key?.id == SHIFT,
        )
        pointers[id] = state
        val key = cell?.key
        if (event.actionMasked == MotionEvent.ACTION_DOWN && state.method == DIRECT_SILENT_TYPING &&
          event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && key != null && isKeyEnabled(key)) {
          onDirectKeyTouchDown()
        }
        if (key != null) playKeyFeedback(key)
        if (key?.id == DELETE) startDeleteRepeat(state)
        val confirmingSecondTap = key != null &&
          VietnameseKeyTouch.confirmsWithDoubleTap(key, state.method) &&
          state.touch.hasPendingFirstTap(key, x, y, event.eventTime)
        state.touch.start(key, state.method)
        if (state.pcShift) {
          pressShiftPointer(state)
        }
        if (!confirmingSecondTap && cell != null) announceSelected(state, cell)
        if (key?.id == MIC) {
          armMicHold(state)
        } else if (!state.pcShift && state.method == DIRECT_SILENT_TYPING && key != null) {
          submitKey(key, event.eventTime, state.method, id)
        }
      }
      MotionEvent.ACTION_MOVE -> {
        moveChanged = false
        for (index in 0 until event.pointerCount) {
          val state = pointers[event.getPointerId(index)] ?: continue
          val x = event.getX(index)
          val y = event.getY(index)
          state.lastX = x
          state.lastY = y
          if (state.pointerId == micHoldPointer && movedBeyondSlop(state, x, y)) disarmMicHold()
          if (!state.pcShift && state.symbolPage != symbolPage) continue
          if (!state.pcShift && state.method == DIRECT_SILENT_TYPING) continue
          if (movedBeyondSlop(state, x, y)) state.moved = true
          if (state.pcShift) continue
          val cell = cellAt(x, y)
          if (cell?.key != state.touch.selected) {
            moveChanged = true
            state.moved = true
            state.touch.explore(cell?.key)
            announceSelected(state, cell)
          }
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
        val index = event.actionIndex
        val state = pointers.remove(event.getPointerId(index))
        if (state != null) {
          stopDeleteRepeat(state.pointerId)
          val x = event.getX(index)
          val y = event.getY(index)
          val cell = cellAt(x, y)
          val micKey = state.startingCell?.key?.id == MIC
          val tap = !state.moved && !movedBeyondSlop(state, x, y) &&
            event.eventTime - state.downTime <= timing.tapHoldMs &&
            cell?.key == state.startingCell?.key
          if (micKey) {
            finishMicHold(state)
            finishAnnouncement(state, cell)
          } else if (state.pcShift) {
            releaseShiftPointer(state, tap)
          } else if (state.method != DIRECT_SILENT_TYPING && state.symbolPage == symbolPage) {
            if (cell?.key != state.touch.selected) {
              state.touch.explore(cell?.key)
              announceSelected(state, cell)
            }
            val key = state.touch.release(cell?.key, state.method, x, y, event.eventTime, tap)
            finishAnnouncement(state, cell, waitsForCommit = key != null && hasCommitAnnouncement(key.id))
            if (key != null && !state.deleteRepeated) {
              submitReleasedKey(key, state.method, event.eventTime, state.pointerId)
            }
          }
          if ((state.pcShift || state.method == DIRECT_SILENT_TYPING) && !micKey) finishAnnouncement(state, cell)
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        cancelInput(cancelPendingEdits = false)
        announce("")
      }
    }
    if (moveChanged) invalidate()
    return true
  }

  private fun isPcLayout(): Boolean =
    layoutSetting == LAYOUT_PC ||
      (layoutSetting == LAYOUT_AUTO &&
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

  private fun activeSizePercent(): Int =
    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      sizeLandscapePercent
    } else {
      sizePortraitPercent
    }

  private fun textScalePercent(): Int = if (rowsArePc) 100 else activeSizePercent()

  @VisibleForTesting
  internal fun keyTextSizeSp(keyId: String): Float {
    val cell = rows.flatten().firstOrNull { it.key.id == keyId } ?: return 0f
    return cell.textSize / density
  }

  private fun isShiftHeld(): Boolean = shiftHolders.isNotEmpty()

  private fun isShiftActive(): Boolean = shifted || isShiftHeld() || shiftSticky

  private fun movedBeyondSlop(state: PointerState, x: Float, y: Float): Boolean =
    abs(x - state.downX) > configuration.scaledTouchSlop ||
      abs(y - state.downY) > configuration.scaledTouchSlop

  private fun armMicHold(state: PointerState) {
    disarmMicHold()
    micHoldPointer = state.pointerId
    touchHandler.postDelayed(micHoldRunnable, MIC_HOLD_MS)
  }

  private fun disarmMicHold() {
    micHoldPointer = null
    touchHandler.removeCallbacks(micHoldRunnable)
  }

  private fun finishMicHold(state: PointerState) {
    disarmMicHold()
    if (micListeningPointer == state.pointerId) {
      micListeningPointer = null
      micStop()
    }
  }

  private fun pressShiftPointer(state: PointerState) {
    val wasHeld = isShiftHeld()
    if (!wasHeld) {
      shiftUsed = false
      shiftSticky = false
    }
    shiftHolders.add(state.pointerId)
    refreshShiftLabels()
    if (!wasHeld) shiftSpoken()
  }

  private fun releaseShiftPointer(state: PointerState, tap: Boolean) {
    shiftHolders.remove(state.pointerId)
    if (shiftHolders.isNotEmpty()) return
    shiftSticky = !shiftUsed && tap
    shiftUsed = false
    refreshShiftLabels()
  }

  private fun rebuildRows() {
    val pc = isPcLayout()
    val layoutChanged = pc != rowsArePc
    rowsArePc = pc
    val newRows = buildRows()
    var previous: Cell? = null
    val newCells = newRows.flatten().associateBy { cell ->
      val id = virtualId(cell.key)
      previous?.nextVirtualId = id
      previous = cell
      id
    }
    val focusedId = keyAccessibility.accessibilityFocusedVirtualViewId
    if (focusedId != ExploreByTouchHelper.INVALID_ID && focusedId !in newCells) {
      keyAccessibility.getAccessibilityNodeProvider(this)?.performAction(
        focusedId, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null,
      )
    }
    rows = newRows
    virtualCells = newCells
    placeKeys()
    keyAccessibility.invalidateRoot()
    if (layoutChanged) requestLayout()
    invalidate()
  }

  /**
   * Short vibration and/or keypress tone for a touch-down that landed on a key.
   * Runs on the local vibrator and tone generator only, never through speech,
   * so it still fires while Meridian is muted, paused, or off.
   */
  private fun playKeyFeedback(key: VietnameseKeyTouch.Key, vibrate: Boolean = vibrateOnKey) {
    if (key.id == MIC) return
    if (!vibrate && !keySound) return
    val slot = when (key.id) {
      " " -> KeyboardKeySounds.Slot.SPACE
      DELETE -> KeyboardKeySounds.Slot.DELETE
      ENTER -> KeyboardKeySounds.Slot.ENTER
      else -> KeyboardKeySounds.Slot.NORMAL
    }
    keyFeedback.play(vibrate, keySound, keySounds[slot], vibrateIntensityPercent)
  }

  private fun submitKey(key: VietnameseKeyTouch.Key, eventMs: Long, method: Int, pointerId: Int) {
    if (!isKeyEnabled(key) || (rowsArePc && key.id == SHIFT)) return
    val id = resolveKey(key)
    if (id == DELETE) {
      activate(deleteTapId(eventMs, method, pointerId), 0)
      return
    }
    lastDeleteTapMs = -1
    activate(id, 0)
  }

  private fun submitReleasedKey(key: VietnameseKeyTouch.Key, method: Int, releasedAt: Long, pointerId: Int) {
    if (!isKeyEnabled(key) || (rowsArePc && key.id == SHIFT)) return
    val id = resolveKey(key)
    val targetId = if (id == DELETE) deleteTapId(releasedAt, method, pointerId) else {
      lastDeleteTapMs = -1
      id
    }
    if (method != SINGLE_TAP_TO_TYPE || timing.directInputMs == 0L) {
      activate(targetId, releasedAt)
      return
    }
    pendingKeyDelay++
    touchHandler.postDelayed({
      pendingKeyDelay--
      activate(targetId, releasedAt)
    }, timing.directInputMs)
  }

  private fun deleteTapId(eventMs: Long, method: Int, pointerId: Int): String {
    if (VietnameseKeyTouch.confirmsWithDoubleTap(VietnameseKeyTouch.Key(DELETE), method)) {
      lastDeleteTapMs = -1
      return DELETE
    }
    val doubleTap = lastDeleteTapMs >= 0 && eventMs - lastDeleteTapMs in 0..timing.doubleTapMs &&
      pointers.values.none {
        it.pointerId != pointerId && it.startingCell?.key?.id == DELETE
      }
    lastDeleteTapMs = if (doubleTap) -1 else eventMs
    return if (doubleTap) DELETE_WORD else DELETE
  }

  private fun startDeleteRepeat(state: PointerState) {
    if (deleteRepeatPointer != null) return
    deleteRepeatPointer = state.pointerId
    deleteRepeatIntervalMs = DELETE_REPEAT_START_INTERVAL_MS
    touchHandler.postDelayed(deleteRepeatRunnable, timing.tapHoldMs)
  }

  private fun stopDeleteRepeat(pointerId: Int) {
    if (deleteRepeatPointer != pointerId) return
    deleteRepeatPointer = null
    touchHandler.removeCallbacks(deleteRepeatRunnable)
  }

  private fun repeatDelete() {
    val pointerId = deleteRepeatPointer ?: return
    val state = pointers[pointerId] ?: return
    val key = state.touch.selected
    if (key?.id != DELETE || cellAt(state.lastX, state.lastY)?.key?.id != DELETE) {
      deleteRepeatPointer = null
      return
    }
    state.deleteRepeated = true
    lastDeleteTapMs = -1
    if (!isKeyEnabled(key) || !activate(DELETE, 0)) {
      deleteRepeatPointer = null
      return
    }
    playKeyFeedback(key)
    deleteRepeatIntervalMs = nextDeleteRepeatIntervalMs(deleteRepeatIntervalMs)
    touchHandler.postDelayed(deleteRepeatRunnable, deleteRepeatIntervalMs)
  }

  private fun resolveKey(key: VietnameseKeyTouch.Key): String {
    val id = effectiveId(key)
    if (rowsArePc && isShiftHeld() && id != key.id) shiftUsed = true
    if (shiftSticky && !isShiftHeld() && id != key.id) {
      shiftSticky = false
      refreshShiftLabels()
    }
    return id
  }

  private fun effectiveId(key: VietnameseKeyTouch.Key): String {
    if (!rowsArePc || !isShiftActive()) return key.id
    if (key.id.length == 1) {
      val symbol = SHIFT_SYMBOLS[key.id.single()]
      if (symbol != null) return symbol
      val letter = key.id.single()
      if (letter.isLetter()) return letter.uppercaseChar().toString()
    }
    return key.id
  }

  private fun announceSelected(state: PointerState, cell: Cell?) {
    if (cell === state.lastSpoken && (cell == null || speechPointer == state.pointerId)) return
    state.lastSpoken = cell
    if (cell != null) {
      speechPointer = state.pointerId
      announce(keyLabel(cell))
    } else if (speechPointer == state.pointerId) {
      speechPointer = null
      announce("")
    }
  }

  private fun finishAnnouncement(state: PointerState, cell: Cell?, waitsForCommit: Boolean = false) {
    if (cell == null) {
      if (state.method != DIRECT_SILENT_TYPING) announceSelected(state, null)
    } else if (speechPointer == state.pointerId || speechPointer == null ||
      state.method == DIRECT_SILENT_TYPING) {
      releaseAnnouncement(waitsForCommit)
    }
    if (speechPointer == state.pointerId) speechPointer = null
  }

  private fun isKeyEnabled(key: VietnameseKeyTouch.Key): Boolean =
    isEnabled && (key.id != LANGUAGE || telexAvailable)

  private fun keyLabel(cell: Cell): String = spokenTitle(cell)

  private fun spokenTitle(cell: Cell): String = when (cell.key.id) {
    SHIFT -> if (rowsArePc) context.getString(R.string.vietnamese_ime_uppercase)
      else if (shifted) context.getString(R.string.vietnamese_ime_lowercase)
      else context.getString(R.string.vietnamese_ime_uppercase)
    SYMBOLS -> context.getString(R.string.vietnamese_ime_symbols)
    LETTERS -> context.getString(R.string.vietnamese_ime_letters)
    LANGUAGE -> context.getString(
      if (telexEnabled) R.string.vietnamese_ime_telex_on else R.string.vietnamese_ime_telex_off,
    )
    DELETE -> context.getString(R.string.vietnamese_ime_delete)
    MIC -> context.getString(R.string.vietnamese_ime_voice_input)
    else -> cell.title
  }

  private fun virtualId(key: VietnameseKeyTouch.Key): Int =
    virtualIds.getOrPut(if (key.id == LETTERS) SYMBOLS else key.id) { virtualIds.size }

  private inner class KeyAccessibility : ExploreByTouchHelper(this@VietnameseKeyboardView) {
    override fun getVirtualViewAt(x: Float, y: Float): Int =
      cellAt(x, y)?.let { virtualId(it.key) } ?: INVALID_ID

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
      if (width > 0 && height > 0) virtualViewIds.addAll(virtualCells.keys)
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
      val cell = virtualCells[id]
      if (cell == null) {
        node.text = " "
        node.isEnabled = false
        node.setBoundsInParent(Rect())
        return
      }
      val key = cell.key
      node.text = keyName(keyLabel(cell))
      node.className = Button::class.java.name
      node.isEnabled = isKeyEnabled(key)
      node.setTextEntryKey(key.id.length == 1)
      if (key.editorAction) node.viewIdResourceName = context.packageName + IME_ACTION_KEY_ID_SUFFIX
      node.setScreenReaderFocusable(true)
      node.isClickable = node.isEnabled
      if (node.isEnabled) node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
      if (key.id == SHIFT || key.id == LANGUAGE) {
        node.isCheckable = true
        node.isChecked = if (key.id == SHIFT) isShiftActive() else telexEnabled
      }
      val bounds = Rect()
      cell.bounds.round(bounds)
      node.setBoundsInParent(bounds)
      if (cell.nextVirtualId != INVALID_ID) {
        node.setTraversalBefore(this@VietnameseKeyboardView, cell.nextVirtualId)
      }
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
      if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
      val key = virtualCells[id]?.key ?: return false
      if (!isKeyEnabled(key)) return false
      lastDeleteTapMs = -1
      finishHover(hasCommitAnnouncement(key.id))
      if (rowsArePc && key.id == SHIFT) {
        shiftSticky = !shiftSticky
        refreshShiftLabels()
      } else if (!activate(resolveKey(key), 0)) {
        return false
      }
      playKeyFeedback(key, vibrate = false)
      sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
      return true
    }
  }

  private fun cellAt(x: Float, y: Float): Cell? {
    val cx = if (x == width.toFloat()) x - 0.5f else x
    val cy = if (y == height.toFloat()) y - 0.5f else y
    for (row in rows) {
      if (row.isEmpty()) continue
      if (rowsArePc) {
        if (cx < row.first().bounds.left || cx >= row.first().bounds.right) continue
      } else if (cy < row.first().bounds.top || cy >= row.first().bounds.bottom) continue
      for (cell in row) if (cell.bounds.contains(cx, cy)) return cell
    }
    return null
  }

  private fun placeKeys() {
    if (rowsArePc) {
      val columnWidth = if (rows.isEmpty()) 0f else width.toFloat() / rows.size
      rows.forEachIndexed { index, column ->
        val totalWeight = column.sumOf { it.weight.toDouble() }.toFloat()
        val unit = height / totalWeight
        var top = (height - totalWeight * unit) / 2
        for ((cellIndex, cell) in column.withIndex()) {
          val bottom = top + cell.weight * unit
          cell.bounds.set(
            index * columnWidth,
            if (cellIndex == 0) 0f else top,
            (index + 1) * columnWidth,
            if (cellIndex == column.lastIndex) height.toFloat() else bottom,
          )
          measureTitle(cell, displayTitle(cell))
          top = bottom
        }
      }
      return
    }
    val rowHeight = if (rows.isEmpty()) 0f else height.toFloat() / rows.size
    rows.forEachIndexed { index, row ->
      val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
      val unit = width / totalWeight
      var left = (width - totalWeight * unit) / 2
      for ((cellIndex, cell) in row.withIndex()) {
        val right = left + cell.weight * unit
        cell.bounds.set(
          if (cellIndex == 0) 0f else left,
          index * rowHeight,
          if (cellIndex == row.lastIndex) width.toFloat() else right,
          (index + 1) * rowHeight,
        )
        measureTitle(cell, displayTitle(cell))
        left = right
      }
    }
  }

  private fun measureTitle(cell: Cell, title: String) {
    cell.title = title
    paint.textSize = (if (title.length > 4) 13 else 18) * density * textScalePercent() / 100f
    val available = cell.bounds.width() - 6 * density
    val textWidth = paint.measureText(title)
    if (available > 0 && textWidth > available) paint.textSize *= available / textWidth
    cell.textSize = paint.textSize
    cell.baseline = cell.bounds.centerY() - (paint.ascent() + paint.descent()) / 2
  }

  private fun displayTitle(cell: Cell): String {
    if (cell.key.id == SHIFT) return if (isShiftActive()) "⇧" else "↑"
    val key = cell.key.id.singleOrNull() ?: return cell.baseTitle
    if (!isShiftActive()) return cell.baseTitle
    if (rowsArePc) SHIFT_SYMBOLS[key]?.let { return it }
    return if (key.isLetter()) key.uppercaseChar().toString() else cell.baseTitle
  }

  private fun refreshShiftLabels() {
    for (row in rows) {
      for (cell in row) {
        val title = displayTitle(cell)
        if (title != cell.title) measureTitle(cell, title)
      }
    }
    keyAccessibility.invalidateRoot()
    invalidate()
  }

  private fun buildRows(): List<List<Cell>> {
    val digits = characters("1234567890")
    val first = characters(if (symbolPage) "@#%&*+-=()" else "qwertyuiop")
    val second = characters(if (symbolPage) "!?:;/'\"_\$\\" else "asdfghjkl")
    val third = characters(if (symbolPage) "^~<>[]{}`|" else "zxcvbnm")
    val pc = rowsArePc
    val shift = function(SHIFT, "↑", if (pc) 2.0f else 1.4f)
    val delete = function(DELETE, "⌫", if (pc) 1.0f else 1.6f)
    val thirdRow = listOf(shift) + third + delete
    val bottom = listOfNotNull(
      if (symbolPage) function(LETTERS, "ABC", 1.3f) else function(SYMBOLS, "123", 1.3f),
      function(LANGUAGE, if (telexEnabled) "VI" else "EN", 1.1f),
      characters(",").single(),
      Cell(VietnameseKeyTouch.Key(" "),
        context.getString(R.string.vietnamese_ime_space), if (pc) 6.5f else 3.2f),
      characters(".").single(),
      function(MIC, context.getString(R.string.vietnamese_ime_voice_input), 1.1f)
        .takeIf { micEnabled },
      function(PICKER, context.getString(R.string.vietnamese_ime_picker_key), 1.1f),
      Cell(VietnameseKeyTouch.Key(ENTER, editorAction = true), actionLabel, 1.3f),
    )
    val symbolsRow = if (showSymbolsRow && !symbolPage) characters(symbolsRowString) else null
    val digitsRow = if (showNumberRow || symbolPage) digits else null
    return if (pc) {
      val digitsColumn = digitsRow?.let { it + delete }
      val bottomColumn = if (digitsColumn == null) bottom + delete else bottom
      listOfNotNull(bottomColumn, third + shift, second, first, digitsColumn, symbolsRow)
    } else {
      listOfNotNull(symbolsRow, digitsRow, first, second, thirdRow, bottom)
    }
  }

  private fun characters(text: String): List<Cell> =
    text.map { letter ->
      val key = letter.toString()
      Cell(VietnameseKeyTouch.Key(key, textEntry = letter.isLetter()), key)
    }

  private fun function(id: String, title: String, weight: Float): Cell =
    Cell(VietnameseKeyTouch.Key(id), title, weight)

  companion object {
    const val SHIFT = "shift"
    const val DELETE = "delete"
    const val DELETE_WORD = "delete_word"
    const val ENTER = "enter"
    const val SYMBOLS = "symbols"
    const val LETTERS = "letters"
    const val LANGUAGE = "language"
    const val PICKER = "picker"
    const val MIC = "mic"
    const val LAYOUT_PHONE = "phone"
    const val LAYOUT_PC = "pc"
    const val LAYOUT_AUTO = "auto"

    private const val MIC_HOLD_MS = 1000L

    private val SHIFT_SYMBOLS = mapOf(
      '1' to "!", '2' to "@", '3' to "#", '4' to "$", '5' to "%",
      '6' to "^", '7' to "&", '8' to "*", '9' to "(", '0' to ")",
    )

    private const val DELETE_REPEAT_START_INTERVAL_MS = 120L
    private const val DELETE_REPEAT_MIN_INTERVAL_MS = 40L
    private const val DELETE_REPEAT_SHRINK_PERCENT = 85L

    internal fun nextDeleteRepeatIntervalMs(currentMs: Long): Long =
      maxOf(DELETE_REPEAT_MIN_INTERVAL_MS, currentMs * DELETE_REPEAT_SHRINK_PERCENT / 100)
  }
}
