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
import org.junit.Test

class VoiceCommandBilingualTest {

  @Test
  fun combineMergesEveryLanguage() {
    val merged =
      VoiceCommandBilingual.combine(listOf("đúng,có", "yes", "đúng, có"))
    assertEquals(listOf("đúng", "có", "yes"), merged)
  }

  @Test
  fun combineDropsEmptiesAndKeepsOrder() {
    val merged = VoiceCommandBilingual.combine(listOf(" rồi , sau đó ", "", "then,"))
    assertEquals(listOf("rồi", "sau đó", "then"), merged)
  }
}
