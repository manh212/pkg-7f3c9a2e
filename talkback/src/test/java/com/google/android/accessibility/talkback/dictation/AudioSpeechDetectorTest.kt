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

package com.google.android.accessibility.talkback.dictation

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpeechDetectorTest {

  @Test
  fun steadySpeechStillCountsAsSpeech() {
    val detector = AudioSpeechDetector()
    assertTrue(!detector.isSpeechLevel(100.0))
    val levels =
      listOf(
        10000.0, 10100.0, 9900.0, 10100.0, 9900.0, 10100.0, 9900.0, 10100.0, 9900.0, 10100.0,
        9900.0, 10100.0, 9900.0, 10100.0, 9900.0,
      )
    val results = levels.map { detector.isSpeechLevel(it) }
    assertTrue(results.subList(2, results.size).all { it })
  }
}
