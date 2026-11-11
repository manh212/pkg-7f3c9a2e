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
import org.junit.Test

class VoiceCommandChainTest {

  private val keywords = VoiceCommandChain.Keywords(listOf("rồi", "sau đó", "then"))

  @Test
  fun split_twoCommandsAtRoi() {
    assertEquals(
      listOf("mo chrome", "cuon xuong"),
      VoiceCommandChain.split("mở chrome rồi cuộn xuống", keywords),
    )
  }

  @Test
  fun split_twoCommandsAtSauDo() {
    assertEquals(
      listOf("quay lai", "ve man hinh chinh"),
      VoiceCommandChain.split("quay lại sau đó về màn hình chính", keywords),
    )
  }

  @Test
  fun split_threeCommandsAndEnglishConnector() {
    assertEquals(
      listOf("back", "scroll down", "scroll up"),
      VoiceCommandChain.split("back then scroll down rồi scroll up", keywords),
    )
  }

  @Test
  fun split_noConnectorIsOnePart() {
    assertEquals(listOf("cuon xuong"), VoiceCommandChain.split("cuộn xuống", keywords))
  }

  @Test
  fun split_connectorAtTheEdgesDropsAway() {
    assertEquals(listOf("cuon xuong"), VoiceCommandChain.split("rồi cuộn xuống", keywords))
    assertEquals(listOf("cuon xuong"), VoiceCommandChain.split("cuộn xuống rồi", keywords))
  }
}
