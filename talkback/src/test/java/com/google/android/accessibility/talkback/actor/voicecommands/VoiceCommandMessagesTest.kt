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

import com.google.android.accessibility.talkback.scripting.ScriptCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandMessagesTest {

  private val keywords =
    VoiceCommandMessages.Keywords(
      messagePrefixes =
        listOf(
          "nhắn cho",
          "nhắn",
          "nhắn tin cho",
          "nhắn tin",
          "gửi tin nhắn cho",
          "gửi tin nhắn",
          "text",
          "send a message to",
        ),
      describeScreenPrefixes =
        listOf("gửi mô tả màn hình cho", "gửi mô tả màn hình", "send the screen description to"),
      describeImagePrefixes =
        listOf("gửi mô tả ảnh cho", "gửi mô tả ảnh", "send the image description to"),
      appConnectors = listOf("qua", "bằng", "via"),
      zaloNames = listOf("zalo"),
      whatsappNames = listOf("whatsapp"),
      smsNames = listOf("sms", "tin nhắn"),
      textSeparators = listOf("rằng", "that"),
    )

  private fun parse(command: String): VoiceCommandMessages.Request? =
    VoiceCommandMessages.parseCommand(command, keywords)

  @Test
  fun messageKeepsTextAsSaid() {
    val request = parse("nhắn cho An rằng mai đi học nhé")
    assertNull(request?.app)
    assertEquals("An rằng mai đi học nhé", request?.whoAndText)
    assertNull(request?.describeScreen)
  }

  @Test
  fun messageWithoutRangAndWithoutDiacritics() {
    val request = parse("nhan cho An mai di hoc nhe")
    assertEquals("An mai di hoc nhe", request?.whoAndText)
    assertNull(request?.describeScreen)
  }

  @Test
  fun messageNamesAppBeforeRecipient() {
    val request = parse("nhắn qua Zalo cho An mai đi học nhé")
    assertEquals(VoiceCommandMessages.App.ZALO, request?.app)
    assertEquals("An mai đi học nhé", request?.whoAndText)
  }

  @Test
  fun messageNamesAppAfterRecipient() {
    // The app after the recipient is read when the recipient is found, not here: the
    // parse keeps the whole tail together so the contact name can be found first.
    val request = parse("send a message to An via WhatsApp hello there")
    assertNull(request?.app)
    assertEquals("An via WhatsApp hello there", request?.whoAndText)
  }

  @Test
  fun messageWithNoTextIsStillARequest() {
    val request = parse("nhắn cho An")
    assertEquals("An", request?.whoAndText)
    assertNull(request?.describeScreen)
  }

  @Test
  fun messageWithNoRecipientIsNotARequest() {
    assertNull(parse("nhắn cho"))
    assertNull(parse("nhắn"))
    assertNull(parse("gửi tin nhắn cho"))
  }

  @Test
  fun notAMessage() {
    assertNull(parse("mở cài đặt"))
    assertNull(parse("gửi văn bản"))
  }

  @Test
  fun describeScreenSend() {
    val request = parse("gửi mô tả màn hình cho An")
    assertEquals(true, request?.describeScreen)
    assertEquals("An", request?.whoAndText)
    assertNull(request?.app)
  }

  @Test
  fun describeImageSendWithTrailingApp() {
    val request = parse("gửi mô tả ảnh cho An qua Zalo")
    assertEquals(false, request?.describeScreen)
    assertEquals(VoiceCommandMessages.App.ZALO, request?.app)
    assertEquals("An", request?.whoAndText)
  }

  @Test
  fun describeSendWithoutRecipientIsNotARequest() {
    assertNull(parse("gửi mô tả màn hình cho"))
    assertNull(parse("gửi mô tả màn hình"))
  }

  @Test
  fun numberAtStartOfMessage() {
    val (number, rest) = VoiceCommandContacts.numberAtStart("0903 567 890 mai đi học nhé")!!
    assertEquals("0903567890", number)
    assertEquals("mai đi học nhé", rest)
  }

  @Test
  fun digitWordsAtStartOfMessage() {
    val (number, rest) = VoiceCommandContacts.numberAtStart("không chín không ba mai nhé")!!
    assertEquals("0903", number)
    assertEquals("mai nhé", rest)
  }

  @Test
  fun noNumberAtStartOfMessage() {
    assertNull(VoiceCommandContacts.numberAtStart("mai đi học nhé"))
  }

  @Test
  fun internationalDigits() {
    assertEquals("84903567890", VoiceCommandMessages.internationalDigits("0903567890"))
    assertEquals("84903567890", VoiceCommandMessages.internationalDigits("+84903567890"))
    assertEquals("84903567890", VoiceCommandMessages.internationalDigits("84903567890"))
  }

  @Test
  fun zaloNeedsTyping() {
    assertTrue(VoiceCommandMessages.needsTyping(VoiceCommandMessages.App.ZALO))
    assertFalse(VoiceCommandMessages.needsTyping(VoiceCommandMessages.App.SMS))
    assertFalse(VoiceCommandMessages.needsTyping(VoiceCommandMessages.App.WHATSAPP))
  }

  @Test
  fun leadingAppAndSeparatorAfterRecipient() {
    val (app, rest) = VoiceCommandMessages.leadingApp("qua Zalo rằng mai nhé", keywords)
    assertEquals(VoiceCommandMessages.App.ZALO, app)
    assertEquals("rằng mai nhé", rest)
    assertEquals("mai nhé", VoiceCommandMessages.dropTextSeparator(rest, keywords))
    val (noApp, same) = VoiceCommandMessages.leadingApp("mai nhé", keywords)
    assertNull(noApp)
    assertEquals("mai nhé", same)
  }

  private val numbers =
    listOf(
      VoiceCommandContacts.Number(1, "An Nguyễn", "0901111111", true, false),
      VoiceCommandContacts.Number(2, "An Trần", "0902222222", true, false),
      VoiceCommandContacts.Number(3, "Bình Lâm", "0903333333", false, true),
      VoiceCommandContacts.Number(3, "Bình Lâm", "0903333334", true, false),
    )

  @Test
  fun contactFoundByName() {
    val found = VoiceCommandContacts.find(numbers, "An Nguyễn")
    assertEquals("0901111111", found.number?.number)
    assertTrue(found.names.isEmpty())
  }

  @Test
  fun contactFoundWithoutDiacritics() {
    val found = VoiceCommandContacts.find(numbers, "An Nguyen")
    assertEquals("0901111111", found.number?.number)
  }

  @Test
  fun contactFoundByFirstNameOnlyWhenUnique() {
    val found = VoiceCommandContacts.find(numbers, "Bình")
    assertEquals("0903333333", found.number?.number)
  }

  @Test
  fun severalContactsWithOneFirstName() {
    val found = VoiceCommandContacts.find(numbers, "An")
    assertNull(found.number)
    assertEquals(listOf("An Nguyễn", "An Trần"), found.names)
  }

  @Test
  fun noContact() {
    val found = VoiceCommandContacts.find(numbers, "Chị Lan")
    assertNull(found.number)
    assertTrue(found.names.isEmpty())
  }

  @Test
  fun contactAtStartLeavesMessageText() {
    val (found, rest) = VoiceCommandContacts.findAtStart(numbers, "Bình Lâm mai đi học nhé")!!
    assertEquals("0903333333", found.number?.number)
    assertEquals("mai đi học nhé", rest)
  }

  @Test
  fun scriptCommandValueRoundTrip() {
    val value = ScriptCommands.valueOf("hello", "greet")
    assertEquals("SCRIPT:hello/greet", value)
    assertTrue(ScriptCommands.isCommand(value))
    assertFalse(ScriptCommands.isCommand("some-other-value"))
    assertEquals("hello" to "greet", ScriptCommands.parse(value))
    assertNull(ScriptCommands.parse("SCRIPT:hello"))
    assertNull(ScriptCommands.parse("SCRIPT:/greet"))
    assertNull(ScriptCommands.parse(null))
  }

  @Test
  fun scriptCommandMatchesBySpokenName() {
    // A taught phrase for a script command is matched like any catalog action: the
    // catalog entry is the script's name and the command's title.
    val entries =
      listOf(
        VoiceCommandMatcher.Entry("Hello: Greet", ScriptCommands.valueOf("hello", "greet")),
        VoiceCommandMatcher.Entry("Đọc màn hình", "read-screen"),
      )
    val match = VoiceCommandMatcher.match("hello greet", entries)
    assertEquals(ScriptCommands.valueOf("hello", "greet"), match?.value)
    val vietnamese = VoiceCommandMatcher.match("đọc màn hình", entries)
    assertEquals("read-screen", vietnamese?.value)
  }
}
