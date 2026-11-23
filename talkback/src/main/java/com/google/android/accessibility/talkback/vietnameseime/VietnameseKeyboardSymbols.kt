package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.R

/** The user-customizable symbols row: 10 symbols shown above the number row. */
internal object VietnameseKeyboardSymbols {
  const val DEFAULT_ROW = "@#$%&*+=-_"
  const val ROW_LENGTH = 10

  /** Page 1 of the symbols keyboard: 30 most common everyday ASCII symbols. */
  const val SYMBOL_PAGE_1_ROW_1 = "@#%&*+-=()"
  const val SYMBOL_PAGE_1_ROW_2 = "!?:;/'\"_\$\\"
  const val SYMBOL_PAGE_1_ROW_3 = "^~<>[]{}`|"

  /** Page 2 of the symbols keyboard: 30 popular currency, math, quote, arrow & mark symbols. */
  const val SYMBOL_PAGE_2_ROW_1 = "₫€£¥×÷±°≠≈"
  const val SYMBOL_PAGE_2_ROW_2 = "≤≥…•–—“”‘’"
  const val SYMBOL_PAGE_2_ROW_3 = "←→↑↓✓✗©®™§"

  /** Less common / specialized symbols assignable to the custom symbols row in settings. */
  const val EXTRA_SYMBOLS = "¢₹₩₽₿∞√π∆∑∏∫‰¶†‡№«»‹›„‚¡¿¦¬¯¨´¸·⁰¹²³⁴⁵⁶⁷⁸⁹¼½¾⅓⅔★☆♠♣♥♦♪♫"

  /**
   * Every symbol the user can place in the custom symbols row:
   * Page 1 + Page 2 + specialized symbols + digits.
   */
  const val AVAILABLE_SYMBOLS =
    SYMBOL_PAGE_1_ROW_1 + SYMBOL_PAGE_1_ROW_2 + SYMBOL_PAGE_1_ROW_3 +
      SYMBOL_PAGE_2_ROW_1 + SYMBOL_PAGE_2_ROW_2 + SYMBOL_PAGE_2_ROW_3 +
      EXTRA_SYMBOLS + "0123456789"

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
