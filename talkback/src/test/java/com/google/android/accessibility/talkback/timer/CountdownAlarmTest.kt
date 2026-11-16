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

import android.content.Context
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The countdown alarms: one malformed entry must not drop the alarms after it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountdownAlarmTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
  }

  @Test
  fun alarms_skipsMalformedEntryAndKeepsLaterOnes() {
    prefs
      .edit()
      .putString(
        CountdownAlarm.KEY_ALARMS,
        """[{"id":1,"name":"first","deadline":3000,"duration":1000},
           |{"id":"not-a-number","name":"broken","deadline":2000,"duration":1000},
           |{"id":3,"name":"third","deadline":1000,"duration":1000}]"""
          .trimMargin(),
      )
      .commit()

    val alarms = CountdownAlarm.alarms(context)

    assertEquals(listOf(3L, 1L), alarms.map { it.id })
  }

  @Test
  fun alarms_returnsEmptyForUnparseableList() {
    prefs.edit().putString(CountdownAlarm.KEY_ALARMS, "not json at all").commit()

    assertEquals(emptyList<CountdownAlarm.Entry>(), CountdownAlarm.alarms(context))
  }
}
