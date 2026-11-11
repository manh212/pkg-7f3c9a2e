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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [VoiceCommandChain]. */
@RunWith(JUnit4::class)
class VoiceCommandChainTest {

  private val keywords = VoiceCommandChain.Keywords(listOf("rồi", "sau đó", "then"))

  @Test
  fun split_twoCommandsAtRoi() {
    assertThat(VoiceCommandChain.split("mở chrome rồi cuộn xuống", keywords))
      .containsExactly("mo chrome", "cuon xuong")
      .inOrder()
  }

  @Test
  fun split_twoCommandsAtSauDo() {
    assertThat(VoiceCommandChain.split("quay lại sau đó về màn hình chính", keywords))
      .containsExactly("quay lai", "ve man hinh chinh")
      .inOrder()
  }

  @Test
  fun split_threeCommandsAndEnglishConnector() {
    assertThat(VoiceCommandChain.split("back then scroll down rồi scroll up", keywords))
      .containsExactly("back", "scroll down", "scroll up")
      .inOrder()
  }

  @Test
  fun split_noConnectorIsOnePart() {
    assertThat(VoiceCommandChain.split("cuộn xuống", keywords)).containsExactly("cuon xuong")
  }

  @Test
  fun split_connectorAtTheEdgesDropsAway() {
    assertThat(VoiceCommandChain.split("rồi cuộn xuống", keywords)).containsExactly("cuon xuong")
    assertThat(VoiceCommandChain.split("cuộn xuống rồi", keywords)).containsExactly("cuon xuong")
  }

  @Test
  fun split_connectorInsideAWordDoesNotSplit() {
    // "rồi" must be its own token; a longer word containing its letters is untouched.
    assertThat(VoiceCommandChain.split("mở rồi rào", keywords))
      .containsExactly("mo", "rao")
      .inOrder()
  }
}
