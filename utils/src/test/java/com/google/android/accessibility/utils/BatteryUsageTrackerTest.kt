/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BatteryUsageTrackerTest {

  @Test
  fun parseProcStatExtractsCommAndUserPlusSystemJiffies() {
    // Fields after (comm): state(0) ppid(1) pgrp(2) session(3) tty(4) tpgid(5)
    // flags(6) minflt(7) cminflt(8) majflt(9) cmajflt(10) utime(11) stime(12)
    val line = "12345 (LowLatencyAudio) S 1 1 1 0 -1 4194304 10 0 0 0 142 58 0 0 20 0"
    val sample = BatteryUsageTracker.parseProcStat(line)
    assertNotNull(sample)
    assertEquals("LowLatencyAudio", sample!!.name)
    assertEquals(200L, sample.jiffies)
  }

  @Test
  fun parseProcStatHandlesThreadNameWithSpacesAndParentheses() {
    val line = "999 (Binder:1234 (1)) R 1 1 1 0 -1 0 0 0 0 0 30 15 0 0"
    val sample = BatteryUsageTracker.parseProcStat(line)
    assertNotNull(sample)
    assertEquals("Binder:1234 (1)", sample!!.name)
    assertEquals(45L, sample.jiffies)
  }

  @Test
  fun parseProcStatAcceptsRepeatedWhitespaceBetweenFields() {
    val line = "42 (main) S  1 1 1 0 -1 0 0 0 0 0  90   10 0 0"
    assertEquals(100L, BatteryUsageTracker.parseProcStat(line)!!.jiffies)
  }

  @Test
  fun parseProcStatRejectsMalformedLine() {
    assertNull(BatteryUsageTracker.parseProcStat("invalid stat line"))
    assertNull(BatteryUsageTracker.parseProcStat("123 (short) S 1 2 3"))
  }

  @Test
  fun formatDurationFormatsMillisecondsSecondsMinutesAndHours() {
    assertEquals("450 ms", BatteryUsageTracker.formatDuration(450L))
    assertEquals("12s", BatteryUsageTracker.formatDuration(12_000L))
    assertEquals("3m 05s", BatteryUsageTracker.formatDuration(185_000L))
    assertEquals("2h 04m 09s", BatteryUsageTracker.formatDuration(7_449_000L))
  }
}
