package com.google.android.accessibility.talkback.vietnameseime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseKeyboardSymbolsTest {
  @Test
  fun availableSymbols_holdFortyUniqueCharactersIncludingTheDefaults() {
    val available = VietnameseKeyboardSymbols.AVAILABLE_SYMBOLS
    assertEquals(40, available.length)
    assertEquals(40, available.toSet().size)
    for (symbol in VietnameseKeyboardSymbols.DEFAULT_ROW) {
      assertTrue(available.contains(symbol))
    }
    for (digit in '0'..'9') {
      assertTrue(available.contains(digit))
    }
  }

  @Test
  fun placeSymbolAt_replacesOnlyTheChosenPosition() {
    assertEquals("!#\$%&*+=-_", VietnameseKeyboardSymbols.placeSymbolAt("@#\$%&*+=-_", '!', 1))
    assertEquals("@#\$%&*+=-!", VietnameseKeyboardSymbols.placeSymbolAt("@#\$%&*+=-_", '!', 10))
    assertEquals("@#\$%&*+=-_", VietnameseKeyboardSymbols.placeSymbolAt("@#\$%&*+=-_", '@', 1))
    assertEquals("@#\$%&*+=-_", VietnameseKeyboardSymbols.placeSymbolAt("short", '@', 1))
    assertEquals("@#\$%&*+=-@", VietnameseKeyboardSymbols.placeSymbolAt("@#\$%&*+=-_", '@', 99))
  }
}
