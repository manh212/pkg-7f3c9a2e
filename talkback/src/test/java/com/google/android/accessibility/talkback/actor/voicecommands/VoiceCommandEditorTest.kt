/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandEditorTest {
  private val keywords =
    VoiceCommandEditor.Keywords(
      listOf("chọn câu này", "chọn đoạn này", "select this sentence"),
      listOf("xóa đoạn", "xóa phần đã chọn"),
      listOf("đọc lại", "đọc câu này"),
      listOf("đọc toàn bộ", "đọc toàn văn"),
      listOf("hoàn tác"),
      listOf("làm lại"),
      listOf("tìm", "tìm kiếm"),
    )

  private fun parse(command: String) = VoiceCommandEditor.parse(command, keywords)

  private fun findQuery(command: String): String? {
    val result = parse(command)
    assertTrue("expected a Find command for: $command", result is VoiceCommandEditor.Command.Find)
    return (result as VoiceCommandEditor.Command.Find).query
  }

  @Test
  fun selectCurrentInVietnameseEnglishAndWithoutDiacritics() {
    assertEquals(VoiceCommandEditor.Command.SelectCurrent, parse("chọn câu này"))
    assertEquals(VoiceCommandEditor.Command.SelectCurrent, parse("Chọn đoạn này"))
    assertEquals(VoiceCommandEditor.Command.SelectCurrent, parse("chon cau nay"))
    assertEquals(VoiceCommandEditor.Command.SelectCurrent, parse("select this sentence"))
  }

  @Test
  fun deleteSelectionButNeverTheBareDeleteWord() {
    assertEquals(VoiceCommandEditor.Command.DeleteSelection, parse("xóa đoạn"))
    assertEquals(VoiceCommandEditor.Command.DeleteSelection, parse("Xóa phần đã chọn"))
    // The bare word belongs to the fixed Delete command, with its confirmation.
    assertNull(parse("xóa"))
  }

  @Test
  fun readCurrentAndReadAllAreDifferentCommands() {
    assertEquals(VoiceCommandEditor.Command.ReadCurrent, parse("đọc lại"))
    assertEquals(VoiceCommandEditor.Command.ReadCurrent, parse("đọc câu này"))
    assertEquals(VoiceCommandEditor.Command.ReadAll, parse("đọc toàn bộ"))
    assertEquals(VoiceCommandEditor.Command.ReadAll, parse("doc toan van"))
  }

  @Test
  fun undoAndRedoAreDifferentCommands() {
    assertEquals(VoiceCommandEditor.Command.Undo, parse("hoàn tác"))
    assertEquals(VoiceCommandEditor.Command.Redo, parse("làm lại"))
  }

  @Test
  fun findTakesTheRestOfTheSentenceAsSpoken() {
    assertEquals("ngân sách", findQuery("tìm ngân sách"))
    assertEquals("Ngân Sách", findQuery("Tìm   Ngân Sách"))
    assertEquals("con mèo", findQuery("tìm kiếm con mèo"))
  }

  @Test
  fun findWithoutWordsRepeatsTheSearchBefore() {
    assertNull(findQuery("tìm"))
    assertNull(findQuery("tìm kiếm"))
  }

  @Test
  fun otherSentencesAreNotEditorCommands() {
    assertNull(parse(""))
    assertNull(parse("mở facebook"))
    assertNull(parse("chọn câu"))
    assertNull(parse("đọc"))
    assertNull(parse("xóa đoạn này đi ngay"))
  }
}
