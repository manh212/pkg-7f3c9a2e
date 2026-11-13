package com.google.android.accessibility.talkback.vietnameseime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import kotlin.math.abs
import kotlin.math.roundToInt

internal class VietnameseKeyboardView(
  context: Context,
  private val announce: (String) -> Unit,
  private val activate: (String) -> Unit,
  private val releaseAnnouncement: () -> Unit = {},
) : View(context) {
  private data class Cell(
    val key: VietnameseKeyTouch.Key,
    val title: String,
    val weight: Float = 1f,
    val bounds: RectF = RectF(),
    var textSize: Float = 0f,
    var baseline: Float = 0f,
  )

  private val configuration = ViewConfiguration.get(context)
  private val touch = VietnameseKeyTouch(
    ViewConfiguration.getDoubleTapTimeout().toLong(), configuration.scaledDoubleTapSlop,
  )
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val backgroundColor = ContextCompat.getColor(context, R.color.vietnamese_ime_background)
  private val keyColor = ContextCompat.getColor(context, R.color.vietnamese_ime_key)
  private val selectionColor = ContextCompat.getColor(context, R.color.vietnamese_ime_selection)
  private val density = resources.displayMetrics.density
  private var rows = emptyList<List<Cell>>()
  private var shifted = false
  private var symbolPage = false
  private var telexEnabled = true
  private var actionLabel = ""
  private var pointer = MotionEvent.INVALID_POINTER_ID
  private var downX = 0f
  private var downY = 0f
  private var downTime = 0L
  private var startingCell: Cell? = null
  private var moved = false
  private var explored = false
  private var lastSpoken: Cell? = null
  private var gestureMethod = 0
  private var inputGeneration = 0

  var typingMethod = 0
    set(value) {
      if (field != value) cancelInput()
      field = value
    }
  var timing = VietnameseKeyboardTiming()
    set(value) {
      if (field != value) cancelInput()
      field = value
      touch.doubleTapTimeout = value.doubleTapMs
    }
  var hideLetterSpeech = false

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    isFocusable = false
    isFocusableInTouchMode = false
  }

  fun showKeys(shifted: Boolean, symbols: Boolean, telex: Boolean, actionLabel: String) {
    this.shifted = shifted
    symbolPage = symbols
    telexEnabled = telex
    this.actionLabel = actionLabel
    rows = buildRows()
    placeKeys()
    invalidate()
  }

  fun cancelInput() {
    inputGeneration++
    touch.cancel()
    pointer = MotionEvent.INVALID_POINTER_ID
    startingCell = null
    lastSpoken = null
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val configuration = resources.configuration
    val heightDp = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      195
    } else {
      maxOf(240, (configuration.screenHeightDp * 0.48f).roundToInt())
    }
    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec),
      resolveSize((heightDp * density).roundToInt(), heightMeasureSpec),
    )
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    placeKeys()
  }

  override fun onDraw(canvas: Canvas) {
    canvas.drawColor(backgroundColor)
    val gap = 2 * density
    val corner = 5 * density
    paint.textAlign = Paint.Align.CENTER
    paint.color = Color.WHITE
    for (row in rows) {
      for (cell in row) {
        val bounds = cell.bounds
        val active = cell.key == touch.selected
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
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        pointer = event.getPointerId(0)
        downX = event.x
        downY = event.y
        downTime = event.eventTime
        startingCell = cellAt(downX, downY)
        moved = false
        explored = false
        lastSpoken = null
        gestureMethod = typingMethod
        touch.start(startingCell?.key, gestureMethod)
        announceSelected()
      }
      MotionEvent.ACTION_MOVE -> {
        val index = event.findPointerIndex(pointer)
        if (index >= 0) {
          val x = event.getX(index)
          val y = event.getY(index)
          val cell = cellAt(x, y)
          if (abs(x - downX) > configuration.scaledTouchSlop ||
            abs(y - downY) > configuration.scaledTouchSlop) moved = true
          if ((explored || cell != startingCell) && cell?.key != touch.selected) {
            moved = true
            explored = true
            touch.explore(cell?.key)
            announceSelected()
          }
        }
      }
      MotionEvent.ACTION_UP -> {
        if (pointer == event.getPointerId(event.actionIndex)) {
          val cell = cellAt(event.x, event.y)
          val tap = !moved && event.eventTime - downTime <= timing.tapHoldMs
          if (!tap && cell?.key != touch.selected) {
            touch.explore(cell?.key)
            announceSelected()
          }
          val key = touch.release(cell?.key, gestureMethod, event.x, event.y, event.eventTime, tap)
          pointer = MotionEvent.INVALID_POINTER_ID
          if (cell != null) releaseAnnouncement() else announce("")
          if (key != null) {
            if (gestureMethod == SINGLE_TAP_TO_TYPE && timing.directInputMs > 0) {
              val generation = inputGeneration
              postDelayed({ if (generation == inputGeneration) activate(key.id) }, timing.directInputMs)
            } else {
              activate(key.id)
            }
          }
        }
      }
      MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
        cancelInput()
        announce("")
      }
    }
    invalidate()
    return true
  }

  private fun announceSelected() {
    val selected = rows.asSequence().flatten().firstOrNull { it.key == touch.selected }
    if (selected == lastSpoken) return
    lastSpoken = selected
    if (selected != null) {
      announce(if (hideLetterSpeech && selected.key.id.length == 1 && selected.key.id != " ") {
        context.getString(R.string.vietnamese_ime_password_character)
      } else {
        spokenTitle(selected)
      })
    } else {
      announce("")
    }
  }

  private fun spokenTitle(cell: Cell): String = when (cell.key.id) {
    SHIFT -> if (shifted) context.getString(R.string.vietnamese_ime_lowercase)
      else context.getString(R.string.vietnamese_ime_uppercase)
    SYMBOLS -> context.getString(R.string.vietnamese_ime_symbols)
    LETTERS -> context.getString(R.string.vietnamese_ime_letters)
    LANGUAGE -> context.getString(
      if (telexEnabled) R.string.vietnamese_ime_telex_on else R.string.vietnamese_ime_telex_off,
    )
    DELETE -> context.getString(R.string.vietnamese_ime_delete)
    else -> cell.title
  }

  private fun cellAt(x: Float, y: Float): Cell? {
    for (row in rows) {
      if (row.isEmpty() || y < row.first().bounds.top || y >= row.first().bounds.bottom) continue
      for (cell in row) if (cell.bounds.contains(x, y)) return cell
    }
    return null
  }

  private fun placeKeys() {
    val rowHeight = if (rows.isEmpty()) 0f else height.toFloat() / rows.size
    rows.forEachIndexed { index, row ->
      val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
      val unit = width / totalWeight.coerceAtLeast(10f)
      var left = (width - totalWeight * unit) / 2
      for (cell in row) {
        val right = left + cell.weight * unit
        cell.bounds.set(left, index * rowHeight, right, (index + 1) * rowHeight)
        paint.textSize = (if (cell.title.length > 4) 13 else 18) * density
        val available = cell.bounds.width() - 6 * density
        val textWidth = paint.measureText(cell.title)
        if (available > 0 && textWidth > available) paint.textSize *= available / textWidth
        cell.textSize = paint.textSize
        cell.baseline = cell.bounds.centerY() - (paint.ascent() + paint.descent()) / 2
        left = right
      }
    }
  }

  private fun buildRows(): List<List<Cell>> {
    val digits = characters("1234567890")
    val first = characters(if (symbolPage) "@#%&*+-=()" else "qwertyuiop")
    val second = characters(if (symbolPage) "!?:;/'\"_\$" else "asdfghjkl")
    val third = characters(if (symbolPage) "^~<>[]{}" else "zxcvbnm")
    return listOf(
      digits,
      first,
      second,
      listOf(function(SHIFT, if (shifted) "⇧" else "↑", 1.4f)) +
        third + function(DELETE, "⌫", 1.6f),
      listOf(
        if (symbolPage) function(LETTERS, "ABC", 1.3f) else function(SYMBOLS, "123", 1.3f),
        function(LANGUAGE, if (telexEnabled) "VI" else "EN", 1.1f),
        characters(",").single(),
        Cell(VietnameseKeyTouch.Key(" "),
          context.getString(R.string.vietnamese_ime_space), 3.2f),
        characters(".").single(),
        function(PICKER, context.getString(R.string.vietnamese_ime_picker_key), 1.1f),
        Cell(VietnameseKeyTouch.Key(ENTER, editorAction = true), actionLabel, 1.3f),
      ),
    )
  }

  private fun characters(text: String): List<Cell> = text.map { letter ->
    val output = if (shifted) letter.uppercaseChar() else letter
    Cell(VietnameseKeyTouch.Key(letter.toString(), textEntry = letter.isLetter()), output.toString())
  }

  private fun function(id: String, title: String, weight: Float): Cell =
    Cell(VietnameseKeyTouch.Key(id), title, weight)

  companion object {
    const val SHIFT = "shift"
    const val DELETE = "delete"
    const val ENTER = "enter"
    const val SYMBOLS = "symbols"
    const val LETTERS = "letters"
    const val LANGUAGE = "language"
    const val PICKER = "picker"
  }
}
