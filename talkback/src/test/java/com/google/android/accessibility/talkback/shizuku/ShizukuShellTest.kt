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

package com.google.android.accessibility.talkback.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuShellTest {

  @Test
  fun splitInputText_leavesTextWithoutPercentSAlone() {
    assertEquals(listOf("100% sure"), ShizukuShell.splitInputText("100% sure"))
    assertEquals(listOf("plain text"), ShizukuShell.splitInputText("plain text"))
  }

  @Test
  fun splitInputText_cutsBetweenPercentAndS() {
    assertEquals(listOf("100%", "sure"), ShizukuShell.splitInputText("100%sure"))
    assertEquals(listOf("%", "s"), ShizukuShell.splitInputText("%s"))
    assertEquals(listOf("a%", "s%", "sb"), ShizukuShell.splitInputText("a%s%sb"))
    assertEquals(listOf("100%", "s"), ShizukuShell.splitInputText("100%s"))
  }

  @Test
  fun splitInputText_keepsLonePercentSigns() {
    assertEquals(listOf("x%y"), ShizukuShell.splitInputText("x%y"))
    assertEquals(listOf("100%"), ShizukuShell.splitInputText("100%"))
  }

  @Test
  fun splitInputText_piecesJoinBackToTheOriginal() {
    val texts = listOf("100%sure", "%s", "a%s%sb", "x%y", "100%", "%s%s", "a %s b")
    for (text in texts) {
      assertEquals(text, ShizukuShell.splitInputText(text).joinToString(""))
    }
  }
}
