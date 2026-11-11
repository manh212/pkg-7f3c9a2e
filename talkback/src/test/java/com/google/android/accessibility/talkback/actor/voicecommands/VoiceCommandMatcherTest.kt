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
import org.junit.Test

class VoiceCommandMatcherTest {

  private val entries =
    listOf(
      VoiceCommandMatcher.Entry("Đọc từ đầu", "READ_FROM_TOP"),
      VoiceCommandMatcher.Entry("Đọc từ con trỏ", "READ_FROM_CURSOR"),
      VoiceCommandMatcher.Entry("Cài đặt nhanh", "QUICK_SETTINGS"),
      VoiceCommandMatcher.Entry("Mở Zalo", "OPEN_ZALO"),
      VoiceCommandMatcher.Entry("Sao chép", "COPY"),
      VoiceCommandMatcher.Entry("Sao chép câu vừa đọc", "COPY_LAST"),
      VoiceCommandMatcher.Entry("Tăng âm lượng", "VOLUME_UP"),
      VoiceCommandMatcher.Entry("Giảm âm lượng", "VOLUME_DOWN"),
    )

  @Test
  fun exactNameMatches() {
    val match = VoiceCommandMatcher.match("đọc từ đầu", entries)
    assertEquals("READ_FROM_TOP", match?.value)
    assertEquals(1.0, match?.score ?: 0.0, 0.001)
  }

  @Test
  fun caseAndPunctuationDoNotMatter() {
    assertEquals("READ_FROM_TOP", VoiceCommandMatcher.match("Đọc từ đầu!", entries)?.value)
    assertEquals("QUICK_SETTINGS", VoiceCommandMatcher.match("  cài   đặt nhanh ", entries)?.value)
  }

  @Test
  fun speechWithoutDiacriticsMatches() {
    assertEquals("READ_FROM_TOP", VoiceCommandMatcher.match("doc tu dau", entries)?.value)
    assertEquals("OPEN_ZALO", VoiceCommandMatcher.match("mo zalo", entries)?.value)
  }

  @Test
  fun extraWordsAroundTheNameStillMatch() {
    assertEquals(
      "READ_FROM_TOP", VoiceCommandMatcher.match("đọc từ đầu màn hình", entries)?.value)
    assertEquals("VOLUME_UP", VoiceCommandMatcher.match("tăng âm lượng nhạc", entries)?.value)
  }

  @Test
  fun exactMatchBeatsALongerName() {
    assertEquals("COPY", VoiceCommandMatcher.match("sao chép", entries)?.value)
  }

  @Test
  fun aSingleCommonWordDoesNotMatch() {
    assertNull(VoiceCommandMatcher.match("đọc", entries))
    assertNull(VoiceCommandMatcher.match("mở", entries))
  }

  @Test
  fun mostOfTheNameStillMatches() {
    // Every spoken word is in the name and covers most of it, so the action still runs.
    assertEquals("READ_FROM_TOP", VoiceCommandMatcher.match("đọc đầu", entries)?.value)
  }

  @Test
  fun aLooseResemblanceDoesNotMatch() {
    // Only one word in common with any name is never enough.
    assertNull(VoiceCommandMatcher.match("đầu trang", entries))
    assertNull(VoiceCommandMatcher.match("cài nhạc", entries))
  }

  @Test
  fun anAmbiguousSentenceDoesNotMatch() {
    // "âm lượng" fits both volume names equally well, so neither may run.
    assertNull(VoiceCommandMatcher.match("âm lượng", entries))
  }

  @Test
  fun unrelatedSpeechDoesNotMatch() {
    assertNull(VoiceCommandMatcher.match("thời tiết hôm nay thế nào", entries))
    assertNull(VoiceCommandMatcher.match("", entries))
    assertNull(VoiceCommandMatcher.match("đọc từ đầu", emptyList()))
  }

  @Test
  fun normalization() {
    assertEquals("đọc từ đầu", VoiceCommandMatcher.normalize("  Đọc,  TỪ đầu! "))
    assertEquals("doc tu dau", VoiceCommandMatcher.stripDiacritics("đọc từ đầu"))
    assertEquals("tieng viet", VoiceCommandMatcher.stripDiacritics("tiếng việt"))
  }
}
