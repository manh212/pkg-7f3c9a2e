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

class VoiceCommandAiTest {

  private val entries =
    listOf(
      VoiceCommandMatcher.Entry("Cuộn xuống", "scroll_down"),
      VoiceCommandMatcher.Entry("Mở Chrome", "open_chrome"),
      VoiceCommandMatcher.Entry("Về đầu", "first_item"),
    )

  @Test
  fun promptListsTheSentenceAndTheNumberedActions() {
    val prompt = VoiceCommandAi.buildPrompt("cho tôi xuống dưới một chút", entries)
    assertTrue(prompt.contains("cho tôi xuống dưới một chút"))
    assertTrue(prompt.contains("1. Cuộn xuống"))
    assertTrue(prompt.contains("2. Mở Chrome"))
    assertTrue(prompt.contains("3. Về đầu"))
  }

  @Test
  fun parsesAnActionPick() {
    val result = VoiceCommandAi.parseAnswer("{\"action\": 2}", entries)
    val action = result as VoiceCommandAi.Interpretation.Action
    assertEquals("open_chrome", action.entry.value)
    assertEquals(1, action.repeat)
  }

  @Test
  fun parsesAnActionPickWithARepeat() {
    val action =
      VoiceCommandAi.parseAnswer("{\"action\": 1, \"repeat\": 3}", entries)
        as VoiceCommandAi.Interpretation.Action
    assertEquals("scroll_down", action.entry.value)
    assertEquals(3, action.repeat)

    val clamped =
      VoiceCommandAi.parseAnswer("{\"action\": 1, \"repeat\": 25}", entries)
        as VoiceCommandAi.Interpretation.Action
    assertEquals(20, clamped.repeat)
  }

  @Test
  fun parsesAnAnswerWrappedInCodeFences() {
    val action =
      VoiceCommandAi.parseAnswer("```json\n{\"action\": 3}\n```", entries)
        as VoiceCommandAi.Interpretation.Action
    assertEquals("first_item", action.entry.value)
  }

  @Test
  fun parsesPercentages() {
    val volume =
      VoiceCommandAi.parseAnswer("{\"volume\": 40}", entries)
        as VoiceCommandAi.Interpretation.Percent
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, volume.percent.kind)
    assertEquals(40, volume.percent.value)

    val media =
      VoiceCommandAi.parseAnswer("{\"media\": 30}", entries)
        as VoiceCommandAi.Interpretation.Percent
    assertEquals(VoiceCommandParameters.PercentKind.MEDIA_VOLUME, media.percent.kind)
    assertEquals(30, media.percent.value)

    val rate =
      VoiceCommandAi.parseAnswer("{\"rate\": 120}", entries)
        as VoiceCommandAi.Interpretation.Percent
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_RATE, rate.percent.kind)
    assertEquals(120, rate.percent.value)
  }

  @Test
  fun parsesAnAlarm() {
    val alarm =
      VoiceCommandAi.parseAnswer("{\"alarmSeconds\": 300}", entries)
        as VoiceCommandAi.Interpretation.Alarm
    assertEquals(300_000L, alarm.durationMillis)
  }

  @Test
  fun rejectsEmptyOutOfRangeAndUnreadableAnswers() {
    assertTrue(
      VoiceCommandAi.parseAnswer("{\"action\": 0}", entries)
        is VoiceCommandAi.Interpretation.None
    )
    assertTrue(
      VoiceCommandAi.parseAnswer("{\"action\": 4}", entries)
        is VoiceCommandAi.Interpretation.None
    )
    assertTrue(
      VoiceCommandAi.parseAnswer("{\"volume\": 150}", entries)
        is VoiceCommandAi.Interpretation.None
    )
    assertTrue(
      VoiceCommandAi.parseAnswer("{\"alarmSeconds\": 400000}", entries)
        is VoiceCommandAi.Interpretation.None
    )
    assertTrue(
      VoiceCommandAi.parseAnswer("Tôi không hiểu câu này.", entries)
        is VoiceCommandAi.Interpretation.None
    )
    assertTrue(
      VoiceCommandAi.parseAnswer("", entries) is VoiceCommandAi.Interpretation.None
    )
  }
}
