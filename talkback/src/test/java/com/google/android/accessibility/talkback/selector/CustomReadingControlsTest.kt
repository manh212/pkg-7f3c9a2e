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

package com.google.android.accessibility.talkback.selector

import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.talkback.selector.SelectorController.Setting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomReadingControlsTest {

  @Test
  fun serializeAndParseRoundTripPreservesControls() {
    val controls =
      listOf(
        CustomReadingControls.Control(
          id = "c1",
          name = "Tốc độ và Âm lượng",
          upAction = "SPEECH_RATE_INCREASE",
          downAction = "SPEECH_RATE_DECREASE",
        ),
        CustomReadingControls.Control(
          id = "c2",
          name = "Thao tác nhanh",
          upAction = Commands.readingControlValue(Setting.ACTIONS, next = false),
          downAction = Commands.readingControlValue(Setting.ACTIONS, next = true),
        ),
      )

    val json = CustomReadingControls.serializeJson(controls)
    val parsed = CustomReadingControls.parseJson(json)
    assertEquals(controls, parsed)
  }

  @Test
  fun parseJsonSkipsMalformedEntries() {
    assertTrue(CustomReadingControls.parseJson("not json").isEmpty())
    assertTrue(
      CustomReadingControls.parseJson("""[{"id":"1","name":"","upAction":"A","downAction":"B"}]""")
        .isEmpty()
    )
  }

  @Test
  fun customItemIdHelpersRoundTrip() {
    val itemId = CustomReadingControls.itemIdFor("abc-123")
    assertTrue(CustomReadingControls.isCustomItem(itemId))
    assertEquals("abc-123", CustomReadingControls.customId(itemId))
    assertFalse(CustomReadingControls.isCustomItem("script:foo"))
    assertEquals("", CustomReadingControls.customId("script:foo"))
  }

  @Test
  fun extendedReadingControlValueCommandsRoundTrip() {
    val checkedSettings =
      listOf(
        Setting.ACTIONS,
        Setting.GRANULARITY_TYPO,
        Setting.GRANULARITY_SEARCH,
        Setting.ADJUSTABLE_WIDGET,
        Setting.SCROLLING_SEQUENTIAL,
        Setting.CHANGE_BRIGHTNESS,
        Setting.CHANGE_LIFT_TO_ACTIVATE,
        Setting.WRAP_AROUND,
        Setting.SWITCH_TTS_ENGINE,
        Setting.CLIPBOARD_HISTORY,
      )
    for (setting in checkedSettings) {
      val nextVal = Commands.readingControlValue(setting, next = true)
      val prevVal = Commands.readingControlValue(setting, next = false)
      assertEquals(setting, Commands.readingControlSettingOf(nextVal))
      assertEquals(setting, Commands.readingControlSettingOf(prevVal))
      assertTrue(Commands.isReadingControlNext(nextVal))
      assertFalse(Commands.isReadingControlNext(prevVal))
    }
    assertNull(Commands.readingControlSettingOf("NEXT"))
    assertNull(
      Commands.readingControlSettingOf(
        Commands.readingControlValue(Setting.REPEAT_AND_TRANSLATE, true)
      )
    )
    assertNull(
      Commands.readingControlSettingOf(Commands.readingControlValue(Setting.COPY_MODE, true))
    )
    assertNull(
      Commands.readingControlSettingOf(
        Commands.readingControlValue(Setting.VERTICAL_NAVIGATION, true)
      )
    )
  }
}
