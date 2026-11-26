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

package com.google.android.accessibility.talkback.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRecognitionPromptsTest {

  @Test
  fun onlineTextParser_readsTextAndRejectsNonText() {
    val ok = OnlineTextResponseParser.parse("""{"van_ban":"Xin chào Việt Nam"}""")
    assertEquals(OnlineTextOutcome.Text("Xin chào Việt Nam"), ok)

    val none = OnlineTextResponseParser.parse("""{"khong_co_van_ban":true}""")
    assertEquals(OnlineTextOutcome.NoText, none)
  }

  @Test
  fun onlineCurrencyParser_readsForeignCurrencyAndRejectsNonCurrency() {
    val usd = OnlineCurrencyResponseParser.parse(
      """{"la_tien":true,"quoc_gia":"Hoa Kỳ","loai_tien":"đô la Mỹ","menh_gia":"100"}"""
    )
    assertTrue(usd is OnlineCurrencyOutcome.Currency)
    assertEquals("100 đô la Mỹ, Hoa Kỳ.", (usd as OnlineCurrencyOutcome.Currency).speak())

    val notMoney = OnlineCurrencyResponseParser.parse("""{"khong_phai_tien":true}""")
    assertEquals(OnlineCurrencyOutcome.NotCurrency, notMoney)
  }

  @Test
  fun mienBacSpecialSymbolsAndPrizeMoney_workAccurately() {
    val html = """
      <tr><td>ĐB</td><td>12345</td></tr>
      <tr><td>G1</td><td>54321</td></tr>
      <tr><td colspan="2">Mã ĐB: 5 7 9 13 14 15 (LT)</td></tr>
    """.trimIndent()
    val symbols = LotteryResultChecker.parseMienBacSpecialSymbols(html)
    assertTrue("5LT" in symbols)
    assertTrue("14LT" in symbols)

    val mbStation = LotteryOnlineResponseParser.matchStation("Miền Bắc")!!
    val exactWin = LotteryResultChecker.evaluateTraditionalHtml(html, "25/11/2026", "12345", mbStation, "14LT")
    assertTrue(exactWin is LotteryCheckResult.Won)
    assertTrue(exactWin!!.message.contains("Giải Đặc biệt"))
    assertTrue(exactWin.message.contains("1.000.000.000 đồng"))

    val subWin = LotteryResultChecker.evaluateTraditionalHtml(html, "25/11/2026", "12345", mbStation, "2AB")
    assertTrue(subWin is LotteryCheckResult.Won)
    assertTrue(subWin!!.message.contains("Giải Phụ Đặc biệt"))
    assertTrue(subWin.message.contains("25.000.000 đồng"))

    val consolation = LotteryResultChecker.evaluateTraditionalHtml(html, "25/11/2026", "99945", mbStation, "2AB")
    assertTrue(consolation is LotteryCheckResult.Won)
    assertTrue(consolation!!.message.contains("Giải Khuyến khích"))
    assertTrue(consolation.message.contains("40.000 đồng"))
  }

  @Test
  fun savedLotteryTicket_jsonRoundTripPreservesSymbolCode() {
    val mbStation = LotteryOnlineResponseParser.matchStation("Miền Bắc")
    val ticket = ParsedLotteryTicket.Traditional(mbStation, "Miền Bắc", "25/11/2026", "12345", "14LT")
    val saved = SavedLotteryTicket(
      id = "t-1",
      ticket = ticket,
      savedAtMs = 1_700_000_000_000L,
      status = SavedTicketStatus.PENDING,
      nextCheckAtMs = 1_700_010_000_000L,
    )
    val decoded = LotteryTicketStore.fromJson(LotteryTicketStore.toJson(saved))
    assertEquals(saved, decoded)
  }
}
