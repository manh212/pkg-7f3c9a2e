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

package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandPhoneNumbersTest {

  private val keywords =
    VoiceCommandPhoneNumbers.Keywords(listOf("gọi đến số", "gọi số", "quay số"))

  /* The same phrases in English: both languages are always active, on any device language. */
  private val englishKeywords =
    VoiceCommandPhoneNumbers.Keywords(listOf("call number", "call the number", "dial number"))

  private fun numberOf(command: String, words: VoiceCommandPhoneNumbers.Keywords = keywords) =
    (VoiceCommandPhoneNumbers.parseCommand(command, words)
        as VoiceCommandPhoneNumbers.Request.Number)
      .number

  @Test
  fun digitWordsInARow() {
    assertEquals(
      "0903567890",
      numberOf("gọi đến số không chín không ba năm sáu bảy tám chín không"),
    )
    assertEquals("123", numberOf("gọi số một hai ba"))
  }

  @Test
  fun rawDigitGroups() {
    assertEquals("0903567890", numberOf("gọi số 0903 567 890"))
    assertEquals("0903567890", numberOf("quay số 0903567890"))
    assertEquals("0903567890", numberOf("gọi số 0903.567.890"))
  }

  @Test
  fun mixedDigitWordsAndRawDigits() {
    assertEquals("090356", numberOf("quay số không chín 03 năm sáu"))
  }

  @Test
  fun noDiacritics() {
    assertEquals(
      "0903567890",
      numberOf("goi den so khong chin khong ba nam sau bay tam chin khong"),
    )
  }

  @Test
  fun englishPhrases() {
    assertEquals("0903567890", numberOf("call number 0903 567 890", englishKeywords))
    assertEquals("123", numberOf("call the number 123", englishKeywords))
    assertEquals("84903567890", numberOf("dial number 84 903 567 890", englishKeywords))
  }

  @Test
  fun leadingCongBecomesPlus() {
    assertEquals(
      "+840903567890",
      numberOf("gọi đến số cộng tám bốn không chín không ba năm sáu bảy tám chín không"),
    )
    assertEquals(
      "+84903567890", VoiceCommandPhoneNumbers.parseNumber("cộng 84 903 567 890"))
    assertEquals(
      "+84903567890", VoiceCommandPhoneNumbers.parseNumber("+84 903 567 890"))
  }

  @Test
  fun phraseAloneAsksForTheNumber() {
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("gọi đến số", keywords)
        is VoiceCommandPhoneNumbers.Request.AskNumber)
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("quay số", keywords)
        is VoiceCommandPhoneNumbers.Request.AskNumber)
  }

  @Test
  fun notACallCommand() {
    assertNull(VoiceCommandPhoneNumbers.parseCommand("âm lượng 50", keywords))
    assertNull(VoiceCommandPhoneNumbers.parseCommand("gọi", keywords))
    assertNull(VoiceCommandPhoneNumbers.parseCommand("gọi điện cho mẹ", keywords))
    assertNull(VoiceCommandPhoneNumbers.parseCommand("mở số điện thoại", keywords))
  }

  @Test
  fun invalidTails() {
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("gọi số một trăm", keywords)
        is VoiceCommandPhoneNumbers.Request.InvalidNumber)
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("gọi số 12", keywords)
        is VoiceCommandPhoneNumbers.Request.InvalidNumber)
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("gọi số xin chào", keywords)
        is VoiceCommandPhoneNumbers.Request.InvalidNumber)
    // Sixteen digits is past the longest a phone number can be.
    assertTrue(
      VoiceCommandPhoneNumbers.parseCommand("gọi số 0903 5678 9012 3456", keywords)
        is VoiceCommandPhoneNumbers.Request.InvalidNumber)
  }

  @Test
  fun parseNumberOnItsOwn() {
    assertEquals(
      "0903567890",
      VoiceCommandPhoneNumbers.parseNumber("không chín không ba năm sáu bảy tám chín không"),
    )
    assertEquals("0903567890", VoiceCommandPhoneNumbers.parseNumber("0903 567 890"))
    assertEquals("123", VoiceCommandPhoneNumbers.parseNumber("một hai ba"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("12"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("năm sáu"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("xin chào"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber(""))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("cộng"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("0903 5678 9012 3456"))
  }

  @Test
  fun quantityPhrasesAreNotPhoneNumbers() {
    // A quantity is read digit by digit here: "trăm" breaks the run of digit words,
    // so the words of a quantity never add up to a phone number.
    assertNull(VoiceCommandPhoneNumbers.parseNumber("một trăm"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("một trăm hai mươi"))
    assertNull(VoiceCommandPhoneNumbers.parseNumber("hai mươi mốt"))
  }

  @Test
  fun groupedNumbers() {
    assertEquals("090 356 7890", VoiceCommandPhoneNumbers.grouped("0903567890"))
    assertEquals("+849 035 678 90", VoiceCommandPhoneNumbers.grouped("+84903567890"))
    assertEquals("123", VoiceCommandPhoneNumbers.grouped("123"))
    assertEquals("1234", VoiceCommandPhoneNumbers.grouped("1234"))
    assertEquals("123 45", VoiceCommandPhoneNumbers.grouped("12345"))
  }
}
