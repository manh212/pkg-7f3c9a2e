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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParametersTest {

  private val keywords =
    VoiceCommandParameters.Keywords(
      volume = "âm lượng",
      mediaVolume = "âm lượng nhạc",
      speechRate = "tốc độ đọc",
      percent = "phần trăm",
      alarm = "báo thức",
      countdown = "đếm ngược",
      hours = "giờ",
      minutes = "phút",
      seconds = "giây",
      times = "lần",
      yesAnswers = listOf("đúng", "có"),
    )

  @Test
  fun speechVolumePercent() {
    val bare = VoiceCommandParameters.parsePercent("âm lượng 50", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, bare?.kind)
    assertEquals(50, bare?.value)

    val withWord = VoiceCommandParameters.parsePercent("âm lượng 50 phần trăm", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, withWord?.kind)
    assertEquals(50, withWord?.value)

    val noDiacritics = VoiceCommandParameters.parsePercent("am luong 0", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, noDiacritics?.kind)
    assertEquals(0, noDiacritics?.value)
  }

  @Test
  fun mediaVolumePercentIsNotSpeechVolume() {
    val media = VoiceCommandParameters.parsePercent("âm lượng nhạc 30", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.MEDIA_VOLUME, media?.kind)
    assertEquals(30, media?.value)

    val mediaWithWord = VoiceCommandParameters.parsePercent("âm lượng nhạc 30 phần trăm", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.MEDIA_VOLUME, mediaWithWord?.kind)
  }

  @Test
  fun speechRatePercent() {
    val rate = VoiceCommandParameters.parsePercent("tốc độ đọc 80", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_RATE, rate?.kind)
    assertEquals(80, rate?.value)

    val fast = VoiceCommandParameters.parsePercent("tốc độ đọc 150 phần trăm", keywords)
    assertEquals(150, fast?.value)
  }

  @Test
  fun percentRejectsNonNumbersAndOutOfRange() {
    assertNull(VoiceCommandParameters.parsePercent("âm lượng", keywords))
    assertNull(VoiceCommandParameters.parsePercent("âm lượng to", keywords))
    assertNull(VoiceCommandParameters.parsePercent("âm lượng 150", keywords))
    assertNull(VoiceCommandParameters.parsePercent("tăng âm lượng", keywords))
    assertNull(VoiceCommandParameters.parsePercent("đọc từ đầu", keywords))
  }

  @Test
  fun alarmDurations() {
    assertEquals(300_000L, VoiceCommandParameters.parseDurationMillis("báo thức 5 phút", keywords))
    assertEquals(
      5_400_000L, VoiceCommandParameters.parseDurationMillis("đếm ngược 1 giờ 30 phút", keywords))
    assertEquals(90_000L, VoiceCommandParameters.parseDurationMillis("báo thức 90 giây", keywords))
    assertEquals(
      5_410_000L,
      VoiceCommandParameters.parseDurationMillis("báo thức 1 giờ 30 phút 10 giây", keywords))
    assertEquals(
      300_000L, VoiceCommandParameters.parseDurationMillis("bao thuc 5 phut", keywords))
  }

  @Test
  fun alarmDurationRejectsInvalid() {
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức 0 phút", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức 5", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức 100 giờ", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức 5 phút 5 phút", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("báo thức 5 ngày", keywords))
  }

  @Test
  fun repeatCounts() {
    val repeat = VoiceCommandParameters.parseRepeat("cuộn xuống 3 lần", keywords)
    assertEquals("cuon xuong", repeat?.text)
    assertEquals(3, repeat?.count)

    val clamped = VoiceCommandParameters.parseRepeat("mục tiếp theo 25 lần", keywords)
    assertEquals(20, clamped?.count)
  }

  @Test
  fun repeatRejectsNonRepeats() {
    assertNull(VoiceCommandParameters.parseRepeat("cuộn xuống", keywords))
    assertNull(VoiceCommandParameters.parseRepeat("cuộn xuống 1 lần", keywords))
    assertNull(VoiceCommandParameters.parseRepeat("cuộn xuống nhiều lần", keywords))
  }

  @Test
  fun yesAnswers() {
    assertTrue(VoiceCommandParameters.isYesAnswer("đúng", keywords))
    assertTrue(VoiceCommandParameters.isYesAnswer("có", keywords))
    assertTrue(VoiceCommandParameters.isYesAnswer("dung", keywords))
    assertTrue(VoiceCommandParameters.isYesAnswer("co", keywords))
    assertTrue(VoiceCommandParameters.isYesAnswer("Đúng!", keywords))
    assertFalse(VoiceCommandParameters.isYesAnswer("không", keywords))
    assertFalse(VoiceCommandParameters.isYesAnswer("xóa", keywords))
    assertFalse(VoiceCommandParameters.isYesAnswer("", keywords))
  }

  @Test
  fun wordNumberPercent() {
    val fifty = VoiceCommandParameters.parsePercent("âm lượng năm mươi phần trăm", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, fifty?.kind)
    assertEquals(50, fifty?.value)

    val media = VoiceCommandParameters.parsePercent("âm lượng nhạc ba mươi", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.MEDIA_VOLUME, media?.kind)
    assertEquals(30, media?.value)

    val rate = VoiceCommandParameters.parsePercent("tốc độ đọc một trăm hai mươi", keywords)
    assertEquals(VoiceCommandParameters.PercentKind.SPEECH_RATE, rate?.kind)
    assertEquals(120, rate?.value)

    val noDiacritics = VoiceCommandParameters.parsePercent("am luong nam muoi", keywords)
    assertEquals(50, noDiacritics?.value)

    val fifteen = VoiceCommandParameters.parsePercent("âm lượng mười lăm phần trăm", keywords)
    assertEquals(15, fifteen?.value)

    val twentyOne = VoiceCommandParameters.parsePercent("âm lượng hai mươi mốt", keywords)
    assertEquals(21, twentyOne?.value)
  }

  @Test
  fun wordNumberDurations() {
    assertEquals(
      5 * 60_000L, VoiceCommandParameters.parseDurationMillis("báo thức năm phút", keywords)
    )
    assertEquals(
      5_400_000L,
      VoiceCommandParameters.parseDurationMillis("đếm ngược một giờ ba mươi phút", keywords),
    )
    assertEquals(
      45_000L, VoiceCommandParameters.parseDurationMillis("bao thuc bon muoi lam giay", keywords)
    )
    assertEquals(
      7_200_000L, VoiceCommandParameters.parseDurationMillis("báo thức hai giờ", keywords)
    )
  }

  @Test
  fun wordNumberRepeats() {
    val repeat = VoiceCommandParameters.parseRepeat("cuộn xuống ba lần", keywords)
    assertEquals("cuon xuong", repeat?.text)
    assertEquals(3, repeat?.count)

    assertNull(VoiceCommandParameters.parseRepeat("cuộn xuống một lần", keywords))
  }

  @Test
  fun wordNumbersLeaveOtherWordsAlone() {
    // A unit word inside an ordinary phrase is not a parameter without its keyword.
    assertNull(VoiceCommandParameters.parsePercent("mở trang năm", keywords))
    assertNull(VoiceCommandParameters.parseDurationMillis("hẹn giờ năm phút", keywords))
    // A bare "không" is a negation, not the number zero.
    assertNull(VoiceCommandParameters.parsePercent("âm lượng không phần trăm", keywords))
  }
}
