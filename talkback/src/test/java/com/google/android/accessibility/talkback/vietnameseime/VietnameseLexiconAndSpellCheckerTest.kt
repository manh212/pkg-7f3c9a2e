/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.vietnameseime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VietnameseLexiconAndSpellCheckerTest {

  @Before
  fun setUp() {
    VietnameseLexicon.loadFromLinesForTest(
      listOf(
        "U\ttiếp\t90",
        "U\ttục\t85",
        "U\tsức\t80",
        "U\tngười\t95",
        "U\tkhông\t99",
        "U\ttối\t82",
        "B\ttiếp\ttục\t90",
        "B\ttiếp\tsức\t80",
      )
    )
  }

  @Test
  fun personalLearning_promotesRepeatedUserChoiceOverDefaultBigram() {
    val initial = VietnameseLexicon.suggestWords(null, previousWord = "tiếp", currentPrefix = "")
    assertEquals("tục", initial.first())
    VietnameseLexicon.recordChosenSuggestion(null, previousWord = "tiếp", chosenWord = "sức", rejectedTopWord = "tục")
    VietnameseLexicon.recordChosenSuggestion(null, previousWord = "tiếp", chosenWord = "sức", rejectedTopWord = "tục")

    val adapted = VietnameseLexicon.suggestWords(null, previousWord = "tiếp", currentPrefix = "")
    assertEquals("sức", adapted.first())
  }

  @Test
  fun spellChecker_validatesVietnamesePhonotacticsAndFlagsErrors() {
    assertTrue(VietnameseSpellChecker.isValidWord(null, "người"))
    assertTrue(VietnameseSpellChecker.isValidWord(null, "học"))
    assertTrue(VietnameseSpellChecker.isValidWord(null, "nghỉ"))
    assertTrue(VietnameseSpellChecker.isValidWord(null, "kiến"))
    assertFalse(VietnameseSpellChecker.isValidWord(null, "nghà"))
    assertFalse(VietnameseSpellChecker.isValidWord(null, "kà"))
    assertFalse(VietnameseSpellChecker.isValidWord(null, "tôis"))
    assertFalse(VietnameseSpellChecker.isValidWord(null, "hòc"))
  }

  @Test
  fun spellChecker_recoversTelexMisfireAndNavigatesErrors() {
    val fixes = VietnameseSpellChecker.suggestCorrections(null, "tôis")
    assertEquals("tối", fixes.firstOrNull())

    val text = "Xin chào nghà hôm nay hòc tốt"
    val first = VietnameseSpellChecker.findNextError(null, text, 0)
    assertNotNull(first)
    assertEquals("nghà", first!!.word)

    val second = VietnameseSpellChecker.findNextError(null, text, first.end)
    assertNotNull(second)
    assertEquals("hòc", second!!.word)

    val prev = VietnameseSpellChecker.findPreviousError(null, text, second.start)
    assertNotNull(prev)
    assertEquals("nghà", prev!!.word)
  }
}
