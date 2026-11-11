/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
 * applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandPhrasesTest {

  private val keywords =
    VoiceCommandPhrases.Keywords(
      createOpeners = listOf("tạo lệnh mới", "thêm lệnh mới", "đặt lệnh mới"),
      setPrefixes = listOf("đặt lệnh", "gán lệnh", "tạo lệnh"),
      separator = "cho",
      listCommands = listOf("liệt kê lệnh của tôi", "danh sách lệnh của tôi"),
      deletePrefixes = listOf("xóa lệnh", "xóa cụm từ"),
      cancelWords = listOf("hủy", "hủy bỏ"),
      finishWords = listOf("xong"),
    )

  @Test
  fun storageRoundTrips() {
    val phrases =
      listOf(
        VoiceCommandPhrases.Phrase("về đầu", listOf("shortcut_value_first_item")),
        VoiceCommandPhrases.Phrase("đọc tiếp đi", listOf("shortcut_value_read_from_cursor")),
      )
    val parsed = VoiceCommandPhrases.parse(VoiceCommandPhrases.serialize(phrases))
    assertEquals(2, parsed.size)
    assertEquals("về đầu", parsed[0].phrase)
    assertEquals(listOf("shortcut_value_first_item"), parsed[0].values)
    assertEquals("đọc tiếp đi", parsed[1].phrase)
    assertEquals(listOf("shortcut_value_read_from_cursor"), parsed[1].values)
  }

  @Test
  fun storageRoundTripsAMacro() {
    val phrases =
      listOf(
        VoiceCommandPhrases.Phrase(
          "chuẩn bị",
          listOf("shortcut_value_first_item", "shortcut_value_read_from_cursor"),
        )
      )
    val parsed = VoiceCommandPhrases.parse(VoiceCommandPhrases.serialize(phrases))
    assertEquals(1, parsed.size)
    assertEquals(
      listOf("shortcut_value_first_item", "shortcut_value_read_from_cursor"),
      parsed[0].values,
    )
    // A phase 3 entry (one value, no separator) still parses as a one-action phrase.
    val legacy = VoiceCommandPhrases.parse("về đầu\tshortcut_value_first_item")
    assertEquals(listOf("shortcut_value_first_item"), legacy[0].values)
  }

  @Test
  fun finishWordsMatchExactly() {
    assertTrue(VoiceCommandPhrases.isFinish("xong", keywords))
    assertTrue(VoiceCommandPhrases.isFinish("Xong", keywords))
    assertFalse(VoiceCommandPhrases.isFinish("xong rồi", keywords))
  }

  @Test
  fun storageSkipsBrokenLines() {
    assertTrue(VoiceCommandPhrases.parse(null).isEmpty())
    assertTrue(VoiceCommandPhrases.parse("").isEmpty())
    val parsed = VoiceCommandPhrases.parse("không có trường\nvề đầu\tgiá trị\n\t")
    assertEquals(1, parsed.size)
    assertEquals("về đầu", parsed[0].phrase)
  }

  @Test
  fun identityIgnoresCaseAndDiacritics() {
    assertEquals(
      VoiceCommandPhrases.identity("Về Đầu"),
      VoiceCommandPhrases.identity("ve dau"),
    )
  }

  @Test
  fun openersAndListCommandsMatchExactly() {
    assertTrue(VoiceCommandPhrases.isCreateOpener("tạo lệnh mới", keywords))
    assertTrue(VoiceCommandPhrases.isCreateOpener("Tao lenh moi", keywords))
    assertFalse(VoiceCommandPhrases.isCreateOpener("tạo lệnh mới cho tôi", keywords))
    assertTrue(VoiceCommandPhrases.isListCommand("liệt kê lệnh của tôi", keywords))
    assertFalse(VoiceCommandPhrases.isListCommand("liệt kê lệnh", keywords))
    assertTrue(VoiceCommandPhrases.isCancel("hủy bỏ", keywords))
    assertFalse(VoiceCommandPhrases.isCancel("hủy lệnh này", keywords))
  }

  @Test
  fun assignmentSplitsAtTheSeparator() {
    val splits = VoiceCommandPhrases.splitAssignment("đặt lệnh về đầu cho mục đầu tiên", keywords)
    assertEquals(1, splits?.size)
    assertEquals("về đầu", splits?.get(0)?.first)
    assertEquals("mục đầu tiên", splits?.get(0)?.second)

    val noDiacritics = VoiceCommandPhrases.splitAssignment("dat lenh ve dau cho muc dau tien", keywords)
    assertEquals("ve dau", noDiacritics?.get(0)?.first)
    assertEquals("muc dau tien", noDiacritics?.get(0)?.second)
  }

  @Test
  fun assignmentTriesTheLongestActionFirst() {
    // The phrase itself contains the separator word: the longest action is tried first.
    val splits =
      VoiceCommandPhrases.splitAssignment("gán lệnh đọc cho nghe cho đọc tiếp", keywords)
    assertEquals(2, splits?.size)
    assertEquals("đọc", splits?.get(0)?.first)
    assertEquals("nghe cho đọc tiếp", splits?.get(0)?.second)
    assertEquals("đọc cho nghe", splits?.get(1)?.first)
    assertEquals("đọc tiếp", splits?.get(1)?.second)
  }

  @Test
  fun assignmentRejectsNonAssignments() {
    assertNull(VoiceCommandPhrases.splitAssignment("đặt lệnh về đầu", keywords))
    assertNull(VoiceCommandPhrases.splitAssignment("về đầu cho mục đầu tiên", keywords))
    assertNull(VoiceCommandPhrases.splitAssignment("mở cài đặt", keywords))
  }

  @Test
  fun deleteTargets() {
    assertEquals("ve dau", VoiceCommandPhrases.deleteTarget("xóa lệnh về đầu", keywords))
    assertEquals("ve dau", VoiceCommandPhrases.deleteTarget("xóa cụm từ về đầu", keywords))
    assertNull(VoiceCommandPhrases.deleteTarget("xóa lệnh", keywords))
    assertNull(VoiceCommandPhrases.deleteTarget("xóa", keywords))
    assertNull(VoiceCommandPhrases.deleteTarget("mở cài đặt", keywords))
  }
}
