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

import android.graphics.Rect
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class LiveRecognitionExtensionsTest {

  @Test fun qrFormatterParsesVietQrCccdWifiAndEan893() {
    val vietQr = "00020101021238570010A00000072701270006970422011312345678901200208QRIBFTTA53037045405500005802VN62100806Ca phe63048C3B"
    val formattedQr = QrContentFormatter.format(vietQr, BarcodeFormat.QR_CODE)
    assertTrue(formattedQr.contains("VietQR"))
    assertTrue(formattedQr.contains("MB Bank"))
    assertTrue(formattedQr.contains("50.000"))
    assertNull(QrContentFormatter.parseVietQr(vietQr.dropLast(1) + "0"))

    val cccd = "001099012345||Nguyen Van A|15081999|Nam|Ha Noi|01012022"
    val formattedCccd = QrContentFormatter.format(cccd, BarcodeFormat.QR_CODE)
    assertTrue(formattedCccd.contains("Căn cước công dân: Nguyen Van A"))
    assertTrue(formattedCccd.contains("15/08/1999"))

    val wifi = "WIFI:T:WPA;S:MeridianHome;P:12345678;;"
    val formattedWifi = QrContentFormatter.format(wifi, BarcodeFormat.QR_CODE)
    assertTrue(formattedWifi.contains("MeridianHome"))
    assertTrue(formattedWifi.contains("12345678"))

    val ean = QrContentFormatter.format("8934563138165", BarcodeFormat.EAN_13)
    assertTrue(ean.contains("tiền tố GS1 Việt Nam"))
  }

  @Test fun citizenIdentityContentTakesPriorityOverGenericBarcodes() {
    val payload = "\uFEFF001099012345||Nguyễn Văn An|15/08/1999|Nam|Hà Nội|01012022\r\n"
    val identity = QrContentFormatter.format(payload, BarcodeFormat.PDF_417)
    assertTrue(identity.startsWith("Căn cước công dân: Nguyễn Văn An"))
    assertTrue(identity.contains("cấp ngày 01/01/2022"))
    val barcode = QrBarcodeResult("8934563138165", BarcodeFormat.EAN_13, "Mã vạch")
    val card = QrBarcodeResult(payload, BarcodeFormat.QR_CODE, identity)
    assertEquals(card, QrBarcodeRecognizer.selectResult(listOf(barcode, card)))
    assertEquals(card, QrBarcodeRecognizer.selectResult(listOf(card, barcode)))
    assertNull(QrContentFormatter.parseVietnameseCccd(payload.replace("15/08/1999", "31/02/1999")))
    assertNull(QrContentFormatter.parseVietnameseCccd(payload.replace("Nguyễn Văn An", "")))
    assertTrue(QrContentFormatter.format("001099012345", BarcodeFormat.QR_CODE).startsWith("Mã QR"))
    val extended = payload.trim() + "|001098123456|Nguyễn Văn Bình|Trần Thị An"
    assertTrue(QrContentFormatter.format(extended, BarcodeFormat.QR_CODE)
      .startsWith("Căn cước công dân: Nguyễn Văn An"))
    val direct = "001099012345|Nguyễn Văn An|15081999|Nam|Hà Nội|01012024||"
    val directSpeech = QrContentFormatter.format(direct, BarcodeFormat.QR_CODE)
    assertTrue(directSpeech.startsWith("Căn cước: Nguyễn Văn An"))
    assertTrue(directSpeech.contains("cấp ngày 01/01/2024"))
  }

  @Test fun numericBarcodeNeedsMatchingPrintedIdentityBeforeBeingCalledACard() {
    val code = QrBarcodeResult("001099012345", BarcodeFormat.CODE_128, "Mã CODE_128")
    val printed = "CĂN CƯỚC CÔNG DÂN\nSố: 001 099 012 345\nNguyễn Văn An"
    assertTrue(QrContentFormatter.formatIdentityText(code, printed)!!.startsWith("Căn cước công dân."))
    assertNull(QrContentFormatter.formatIdentityText(code, printed.replace("012 345", "012 346")))
    assertNull(QrContentFormatter.formatIdentityText(code, "Mã khách hàng 001099012345"))
    val oldCard = QrBarcodeResult("123456789", BarcodeFormat.CODE_128, "")
    assertTrue(QrContentFormatter.formatIdentityText(oldCard,
      "CHỨNG MINH NHÂN DÂN\n123456789")!!.startsWith("Chứng minh nhân dân."))
  }

  @Test fun documentCornersRemainDistinctAtFortyFiveDegreesAndWarpKeepsAspectRatio() {
    val diamond = arrayOf(Point(50.0, 0.0), Point(100.0, 50.0), Point(50.0, 100.0), Point(0.0, 50.0))
    val corners = DocumentAlignmentScanner.orderCorners(arrayOf(diamond[2], diamond[0], diamond[3], diamond[1]))
    assertEquals(diamond.toList(), corners.toList())
    assertTrue(DocumentAlignmentScanner.isValidQuad(corners))
    assertTrue(!DocumentAlignmentScanner.isValidQuad(arrayOf(corners[0], corners[1], corners[1], corners[3])))
    assertTrue(!DocumentAlignmentScanner.isValidQuad(arrayOf(Point(0.0, 0.0), Point(100.0, 0.0),
      Point(40.0, 20.0), Point(0.0, 100.0))))
    assertEquals(100 to 1000, DocumentAlignmentScanner.warpDimensions(100.0, 1000.0))
    assertEquals(1100 to 2200, DocumentAlignmentScanner.warpDimensions(2000.0, 4000.0))
  }

  @Test fun onlineLotteryParserHandlesTicketsErrorsAndRejectsNonLotteryImages() {
    val tradJson = """{"loai":"truyen_thong","dai":"Tiền Giang","ngay":"22/11/2026","so":"482910"}"""
    val tradOutcome = LotteryOnlineResponseParser.parse(tradJson) as LotteryRecognitionOutcome.Ticket
    val trad = tradOutcome.ticket as ParsedLotteryTicket.Traditional
    assertEquals("Tiền Giang", trad.station?.displayName)
    assertEquals("22/11/2026", trad.drawDate)
    assertEquals("482910", trad.number)
    assertTrue(trad.speak().contains("4, 8, 2, 9, 1, 0"))

    val vietlottJson = """{"loai":"vietlott","san_pham":"Power 6/55","ky":"01125","ngay":"21/11/2026","bo_so":[{"ky_hieu":"A","so":["05","12","23","34","41","55"]}]}"""
    val vOutcome = LotteryOnlineResponseParser.parse(vietlottJson) as LotteryRecognitionOutcome.Ticket
    val v = vOutcome.ticket as ParsedLotteryTicket.Vietlott
    assertEquals("Power 6/55", v.product)
    assertEquals("01125", v.drawId)
    assertEquals(1, v.lines.size)

    assertEquals(LotteryRecognitionOutcome.NotLottery, LotteryOnlineResponseParser.parse("""{"khong_phai_ve_so":true}"""))
    assertEquals(LotteryRecognitionOutcome.NotLottery, LotteryOnlineResponseParser.parse("Đây là một chiếc bàn gỗ có ly cà phê."))

    val errOutcome = LotteryOnlineResponseParser.parse("""{"loi":"Ảnh mờ, không đọc được số vé."}""")
    assertTrue(errOutcome is LotteryRecognitionOutcome.Error)
  }

  @Test fun lotteryResultCheckerDistinguishesWonNotWonSpecialSubPrizesAndNotDrawn() {
    val rss = """
      <rss><channel><item>
        <title>KẾT QUẢ XỔ SỐ TIỀN GIANG NGÀY 22/11/2026</title>
        <link>https://xskt.com.vn/xstg/ngay-22-11-2026</link>
        <description>ĐB: 182910
1: 12345
8: 10</description>
      </item></channel></rss>
    """.trimIndent()
    val station = LotteryOnlineResponseParser.matchStation("Tiền Giang")
    val won = LotteryResultChecker.evaluateRssXml(rss, "22/11/2026", "482910", station)
    assertTrue(won is LotteryCheckResult.Won)
    assertTrue((won as LotteryCheckResult.Won).prizes.contains("Giải Tám"))
    assertTrue(won.prizes.contains("Giải Phụ Đặc biệt"))

    val consolation = LotteryResultChecker.evaluateRssXml(rss, "22/11/2026", "182919", station)
    assertTrue(consolation is LotteryCheckResult.Won)
    assertTrue((consolation as LotteryCheckResult.Won).prizes.contains("Giải Khuyến khích"))

    val notWon = LotteryResultChecker.evaluateRssXml(rss, "22/11/2026", "555555", station)
    assertTrue(notWon is LotteryCheckResult.NotWon)
    assertTrue(notWon!!.message.contains("không trúng"))

    assertNull(LotteryResultChecker.evaluateRssXml(rss, "22/11/2025", "482910", station))
    assertEquals(listOf("Giải Tám"), LotteryResultChecker.checkPrizes("7: 9568: 91", "482991"))
  }

  @Test fun cameraOcrFilterDropsLowConfidenceAndSymbolNoise() {
    val lines = listOf(
      TesseractOcr.TextLine("XOA BÓP BẤM HUYỆT", Rect(0, 0, 100, 30), emptyList(), 88),
      TesseractOcr.TextLine("l.-", Rect(0, 0, 20, 10), emptyList(), 75),
      TesseractOcr.TextLine("1 cu phldi!gÌ)", Rect(0, 0, 80, 20), emptyList(), 32),
      TesseractOcr.TextLine("200.000Đ", Rect(0, 0, 60, 20), emptyList(), 82),
    )
    val kept = TesseractOcr.filterCameraLines(lines, 55)
    assertEquals(listOf("XOA BÓP BẤM HUYỆT", "200.000Đ"), kept)
  }

  @Test fun colorRecognizerAndTrackerRequireStabilityAndWarnLowLight() {
    assertEquals("Màu đen", ColorRecognizer.describeColor(null, 0, floatArrayOf(0f, 0f, 0.05f)))
    assertEquals("Màu trắng", ColorRecognizer.describeColor(null, 0, floatArrayOf(0f, 0.05f, 0.95f)))
    assertTrue(ColorRecognizer.describeColor(null, 0, floatArrayOf(220f, 0.8f, 0.7f)).contains("xanh dương"))

    val tracker = ColorTracker()
    val darkBlue = ColorSample(rgb = 0xFF0A1020.toInt(), label = "Màu xanh dương đậm", lowLight = true)
    assertNull(tracker.observe(darkBlue, 1000L))
    val spoken = tracker.observe(darkBlue, 1500L)
    assertNotNull(spoken)
    assertTrue(spoken!!.contains("đèn flash"))
  }

  @Test fun documentAlignmentGuidesBeforeCapturingAndExtractsExpiry() {
    val frameW = 480.0
    val frameH = 360.0

    val clippedLeft = arrayOf(Point(2.0, 50.0), Point(380.0, 50.0), Point(380.0, 310.0), Point(2.0, 310.0))
    assertEquals(
      DocumentAlignmentState.MOVE_LEFT,
      DocumentAlignmentScanner.evaluateGeometry(clippedLeft, frameW, frameH, 0.50, 120.0),
    )

    val tooSmall = arrayOf(Point(180.0, 130.0), Point(300.0, 130.0), Point(300.0, 230.0), Point(180.0, 230.0))
    assertEquals(
      DocumentAlignmentState.MOVE_CLOSER,
      DocumentAlignmentScanner.evaluateGeometry(tooSmall, frameW, frameH, 0.16, 120.0),
    )

    val centered = arrayOf(Point(60.0, 40.0), Point(420.0, 40.0), Point(420.0, 320.0), Point(60.0, 320.0))
    assertEquals(
      DocumentAlignmentState.BLURRY,
      DocumentAlignmentScanner.evaluateGeometry(centered, frameW, frameH, 0.58, 25.0),
    )

    assertEquals(
      DocumentAlignmentState.READY,
      DocumentAlignmentScanner.evaluateGeometry(centered, frameW, frameH, 0.58, 120.0),
    )
    val tracker = DocumentAlignmentTracker()
    val ready = DocumentAlignmentResult(DocumentAlignmentState.READY, "OK", centered, 120.0)
    assertTrue(tracker.observe(ready, 1000L) is DocumentAlignmentTracker.Decision.Wait)
    assertTrue(tracker.observe(ready, 1600L) is DocumentAlignmentTracker.Decision.Capture)

    val formattedDoc = ExpiryDateExtractor.formatDocumentSpeech("Sữa tươi NSX: 10/11/2026 HSD: 10/05/2027 Thể tích 180ml")
    assertTrue(formattedDoc.startsWith("Hạn sử dụng: 10/05/2027. Ngày sản xuất: 10/11/2026."))
  }

  @Test fun objectFinderParsesCocoYoloAndDescribesDirection() {
    val count = 2
    val features = CocoObjectCatalog.FEATURES
    val tensor = FloatArray(features * count)
    tensor[0 * count + 0] = 0.2f
    tensor[1 * count + 0] = 0.5f
    tensor[2 * count + 0] = 0.3f
    tensor[3 * count + 0] = 0.4f
    tensor[(56 + 4) * count + 0] = 0.85f

    val detected = CocoObjectCatalog.parseDetections(tensor, intArrayOf(1, features, count), 640, 640)
    assertEquals(1, detected.size)
    assertEquals("Cái ghế, bên trái", detected[0].describeLocation())
  }

  @Test fun lightToneFrequencyAndCurrencyRunningTotalWork() {
    val fDark = LightToneGenerator.frequencyForLux(0f)
    val fBright = LightToneGenerator.frequencyForLux(1000f)
    assertTrue(fDark in 255.0..265.0)
    assertTrue(fBright in 1315.0..1325.0)
    assertTrue(fBright > fDark)
    assertEquals(0, LiveLightDialog.lightPercentForLux(0f))
    assertEquals(100, LiveLightDialog.lightPercentForLux(1000f))
    assertEquals(100, LiveLightDialog.lightPercentForLux(5000f))
    val indoorPercent = LiveLightDialog.lightPercentForLux(100f)
    assertTrue(indoorPercent in 60..70)
    assertEquals(0, indoorPercent % 5)

    val tracker = CurrencyTracker()
    val fifty = CurrencyPrediction(50000, 0.92f)
    assertNull(tracker.observe(fifty, 1000L, accumulateTotal = true))
    val first = tracker.observe(fifty, 1450L, accumulateTotal = true)
    assertNotNull(first)
    assertTrue(first!!.contains("50.000 đồng"))
    assertEquals(50000L, tracker.runningTotalVnd)
    val flicker = CurrencyPrediction(20000, 0.92f)
    tracker.observe(flicker, 2000L, accumulateTotal = true)
    tracker.observe(flicker, 2450L, accumulateTotal = true)
    assertEquals(50000L, tracker.runningTotalVnd)
    repeat(3) { tracker.observe(null, 3000L + it * 450, accumulateTotal = true) }
    tracker.observe(fifty, 4400L, accumulateTotal = true)
    tracker.observe(fifty, 4850L, accumulateTotal = true)
    assertEquals(100000L, tracker.runningTotalVnd)
    tracker.resetTotal()
    assertEquals(0L, tracker.runningTotalVnd)
  }
}
