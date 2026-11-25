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

package com.google.android.accessibility.talkback.speechdictionary

import com.google.android.accessibility.talkback.notifications.Replacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for per-app spoken text editing helpers in [SpeechDictionary]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = 34)
class EditSpokenTextTest {

  @Test
  fun upsertEntry_replacesDuplicateInSameAppAndKeepsOtherApps() {
    val initial =
      listOf(
        DictionaryEntry(Replacement("Checkout now", "Thanh toán"), app = "com.shop.a", appName = "Shop A"),
        DictionaryEntry(Replacement("Checkout now", "Mua ngay"), app = "com.shop.b", appName = "Shop B"),
      )

    val updated =
      SpeechDictionary.upsertEntry(
        initial,
        DictionaryEntry(Replacement("Checkout now", "Đặt hàng"), app = "com.shop.a", appName = "Shop A"),
      )

    assertEquals(2, updated.size)
    assertEquals("Đặt hàng", updated[0].replacement.replace)
    assertEquals("Mua ngay", updated[1].replacement.replace)
  }

  @Test
  fun findMatchingEntry_prefersAppSpecificOverGlobalAndIgnoresRegex() {
    val entries =
      listOf(
        DictionaryEntry(Replacement("Submit", "Gửi chung"), app = ""),
        DictionaryEntry(Replacement("Submit", "Xác nhận giao dịch"), app = "com.bank.app", appName = "Bank"),
        DictionaryEntry(Replacement("Order.*", "Đơn hàng", regex = true), app = "com.bank.app", appName = "Bank"),
      )

    val bankMatch = SpeechDictionary.findMatchingEntry(entries, "Submit", "com.bank.app")
    assertEquals("Xác nhận giao dịch", bankMatch?.replacement?.replace)

    val otherAppMatch = SpeechDictionary.findMatchingEntry(entries, "Submit", "com.other.app")
    assertEquals("Gửi chung", otherAppMatch?.replacement?.replace)

    assertNull(SpeechDictionary.findMatchingEntry(entries, "Order.*", "com.bank.app"))
  }

  @Test
  fun apply_replacesSpokenTextOnlyInTargetApp() {
    val entries =
      listOf(
        DictionaryEntry(
          Replacement("Transfer VND", "Chuyển tiền", regex = false, wholeWord = true),
          app = "com.bank.app",
          appName = "Bank",
        )
      )

    assertEquals(
      "Chuyển tiền nhanh",
      SpeechDictionary.apply("Transfer VND nhanh", entries, "com.bank.app")?.toString(),
    )
    assertNull(SpeechDictionary.apply("Transfer VND nhanh", entries, "com.other.app"))
  }
}
