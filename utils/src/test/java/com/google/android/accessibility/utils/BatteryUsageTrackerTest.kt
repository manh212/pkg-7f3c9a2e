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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BatteryUsageTrackerTest {

  @Test
  fun parseProcStat_standardThread_extractsNameAndCpuJiffies() {
    val line = "12345 (LowLatencyAudio) S 1 123 123 0 -1 4194368 100 0 0 0 42 18 0 0 20 0 10 0 12345"
    val sample = BatteryUsageTracker.parseProcStat(line)
    assertNotNull(sample)
    assertEquals("LowLatencyAudio", sample!!.name)
    assertEquals(60L, sample.jiffies)
  }

  @Test
  fun parseProcStat_threadNameWithSpacesAndParens_usesLastClosingParen() {
    val line = "999 (pool-1 (worker)) R 1 10 10 0 -1 0 0 0 0 0 105 25 0 0 20 0 1 0 500"
    val sample = BatteryUsageTracker.parseProcStat(line)
    assertNotNull(sample)
    assertEquals("pool-1 (worker)", sample!!.name)
    assertEquals(130L, sample.jiffies)
  }

  @Test
  fun parseProcStat_acceptsRepeatedWhitespaceBetweenFields() {
    val line = "42 (main) S  1 1 1 0 -1 0 0 0 0 0  90   10 0 0"
    assertEquals(100L, BatteryUsageTracker.parseProcStat(line)!!.jiffies)
  }

  @Test
  fun parseProcStat_malformed_returnsNull() {
    assertNull(BatteryUsageTracker.parseProcStat("invalid stat line"))
    assertNull(BatteryUsageTracker.parseProcStat("123 (short) S 1 2 3"))
  }

  @Test
  fun normalizeThreadName_groupsBinderPoolsAndKnownWorkers() {
    assertEquals("Binder (IPC pool)", BatteryUsageTracker.normalizeThreadName("binder:6349_2"))
    assertEquals("Binder (IPC pool)", BatteryUsageTracker.normalizeThreadName("Binder:1234_1"))
    assertEquals("BacktalkPrefetch (pool)", BatteryUsageTracker.normalizeThreadName("BacktalkPrefetch-1"))
    assertEquals("OkHttp (network)", BatteryUsageTracker.normalizeThreadName("OkHttp Dispatcher"))
    assertEquals("NavigationPcm", BatteryUsageTracker.normalizeThreadName("NavigationPcm"))
  }

  @Test
  fun formatDuration_formatsMillisecondsSecondsMinutesAndHours() {
    assertEquals("450 ms", BatteryUsageTracker.formatDuration(450L))
    assertEquals("12s", BatteryUsageTracker.formatDuration(12_000L))
    assertEquals("3m 05s", BatteryUsageTracker.formatDuration(185_000L))
    assertEquals("2h 04m 09s", BatteryUsageTracker.formatDuration(7_449_000L))
  }

  @Test
  fun formatBytes_formatsBytesKilobytesAndMegabytes() {
    assertEquals("512 B", BatteryUsageTracker.formatBytes(512L))
    assertEquals("1.5 KB", BatteryUsageTracker.formatBytes(1536L))
    assertEquals("2.00 MB", BatteryUsageTracker.formatBytes(2L * 1024L * 1024L))
  }

  @Test
  fun defaultEnabledState_isFalse() {
    assertFalse(BatteryUsageTracker.isEnabled())
  }

}
