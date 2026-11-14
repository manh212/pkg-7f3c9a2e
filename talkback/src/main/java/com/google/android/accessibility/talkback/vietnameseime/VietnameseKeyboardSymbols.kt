package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.R

/** The user-customizable symbols row: 10 symbols shown above the number row. */
internal object VietnameseKeyboardSymbols {
  const val DEFAULT_ROW = "@#$%&*+=-_"
  const val ROW_LENGTH = 10

  /**
   * Every symbol the user can place in the row: the three symbols-page rows plus the digits.
   * The second row ends with a backslash and the third row holds the backtick.
   */
  const val AVAILABLE_SYMBOLS = "@#%&*+-=()!?:;/'\"_\$\\^~<>[]{}`|0123456789"

  fun read(context: Context, prefs: SharedPreferences): String {
    val stored = prefs.getString(
      context.getString(R.string.pref_vietnamese_keyboard_symbols_row_key),
      DEFAULT_ROW,
    )
    return stored?.takeIf { it.length == ROW_LENGTH } ?: DEFAULT_ROW
  }

  /** Returns [row] with [symbol] placed at the 1-based [position], replacing what was there. */
  fun placeSymbolAt(row: String, symbol: Char, position: Int): String {
    val base = row.takeIf { it.length == ROW_LENGTH } ?: DEFAULT_ROW
    val index = position.coerceIn(1, ROW_LENGTH) - 1
    return base.substring(0, index) + symbol + base.substring(index + 1)
  }
}
