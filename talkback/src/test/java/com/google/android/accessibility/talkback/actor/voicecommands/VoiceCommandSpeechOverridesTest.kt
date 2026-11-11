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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the storage form and the editable-item registry of speech overrides. */
@RunWith(JUnit4::class)
class VoiceCommandSpeechOverridesTest {

  @Test
  fun storageRoundTrip() {
    val overrides =
        mapOf(
            "voice_param_volume" to listOf("âm lượng", "tiếng nói"),
            "voice_commands_delete" to listOf("xóa bỏ"),
        )
    assertEquals(
        overrides,
        VoiceCommandSpeechOverrides.parse(VoiceCommandSpeechOverrides.serialize(overrides)))
  }

  @Test
  fun parseSkipsBrokenLines() {
    val stored = "no-separator-here\nvoice_param_alarm\tbáo thức\u0002báo động\n\t\nkey\t"
    assertEquals(
        mapOf("voice_param_alarm" to listOf("báo thức", "báo động")),
        VoiceCommandSpeechOverrides.parse(stored))
    assertTrue(VoiceCommandSpeechOverrides.parse(null).isEmpty())
    assertTrue(VoiceCommandSpeechOverrides.parse("").isEmpty())
  }

  @Test
  fun sanitizeCleansTypedPhrases() {
    assertEquals(
        listOf("âm lượng", "tiếng nói"),
        VoiceCommandSpeechOverrides.sanitize(
            listOf("  âm   lượng ", "", "  ", "tiếng nói", "âm lượng")))
    assertTrue(VoiceCommandSpeechOverrides.sanitize(listOf("", " ")).isEmpty())
  }

  @Test
  fun registryIsWellFormed() {
    val all = VoiceCommandSpeechOverrides.KEYWORD_ITEMS + VoiceCommandSpeechOverrides.COMMAND_ITEMS
    assertEquals("item ids are unique", all.size, all.map { it.id }.distinct().size)
    for (item in all) {
      assertTrue("item ${item.id} names at least one resource", item.resIds.isNotEmpty())
      assertTrue("item ${item.id} has a title", item.titleRes != 0)
    }
    assertTrue(
        VoiceCommandSpeechOverrides.KEYWORD_ITEMS.all { it.keyword })
    assertTrue(
        VoiceCommandSpeechOverrides.COMMAND_ITEMS.none { it.keyword })
  }
}
