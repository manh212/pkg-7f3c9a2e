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

package com.google.android.accessibility.talkback.timer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keep-alive service runs exactly while at least one clock or timer is armed. */
class TimerKeepAlivePolicyTest {
  @Test
  fun nothingArmed_staysOff() {
    assertFalse(TimerKeepAlivePolicy.anyActive(false, false, false, false))
  }

  @Test
  fun clockAlone_keepsAlive() {
    assertTrue(TimerKeepAlivePolicy.anyActive(true, false, false, false))
  }

  @Test
  fun summaryAlone_keepsAlive() {
    assertTrue(TimerKeepAlivePolicy.anyActive(false, true, false, false))
  }

  @Test
  fun stopwatchAlone_keepsAlive() {
    assertTrue(TimerKeepAlivePolicy.anyActive(false, false, true, false))
  }

  @Test
  fun countdownAlone_keepsAlive() {
    assertTrue(TimerKeepAlivePolicy.anyActive(false, false, false, true))
  }

  @Test
  fun everythingArmed_keepsAlive() {
    assertTrue(TimerKeepAlivePolicy.anyActive(true, true, true, true))
  }
}
