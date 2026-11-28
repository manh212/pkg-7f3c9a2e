package com.google.android.accessibility.talkback.dictation

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SodaWireTest {
  @Test fun finalHypothesisPreservesVietnameseAndPunctuation() {
    val text = "Xin chào, hôm nay bạn khỏe không?"
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(1, SodaWire.text(1, text))))
    assertEquals(SodaWire.Result(text, true), SodaWire.result(packet))
  }

  @Test fun formattedHypothesisTakesPriorityOverRawText() {
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(1,
      SodaWire.text(1, "xin chào"), SodaWire.nested(4, SodaWire.text(2, "Xin chào!")))))
    assertEquals("Xin chào!", SodaWire.result(packet)?.text)
  }

  @Test fun partialsReplaceEachOtherAndFinalCommitsOnlyOnce() {
    val transcript = SodaTranscript()
    transcript.update(SodaWire.Result("xin", false))
    transcript.update(SodaWire.Result("xin chào", false))
    transcript.update(SodaWire.Result("Xin chào!", true))
    transcript.update(SodaWire.Result("Bạn khỏe không?", true))
    assertEquals("Xin chào! Bạn khỏe không?", transcript.value())
  }

  @Test fun partialFormattedTextIsReadFromThePartialSchema() {
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(2,
      SodaWire.text(1, "xin"), SodaWire.nested(2, SodaWire.text(2, "Xin")))))
    val result = requireNotNull(SodaWire.result(packet))
    assertEquals("Xin", result.text)
    assertFalse(result.final)
  }

  @Test(expected = IOException::class) fun truncatedLengthCannotBecomeAResult() {
    SodaWire.result(byteArrayOf(0x12, 0x7f, 0x0a))
  }

  @Test(expected = IOException::class) fun overflowingVarintIsRejected() {
    SodaWire.fields(ByteArray(11) { 0xff.toByte() })
  }

  @Test(expected = IOException::class) fun incompatibleModelStatusIsAFailure() {
    SodaWire.requireSuccess(byteArrayOf(0x08, 0x05))
  }

  @Test fun coreConfigurationUsesLocalModelAndPcmAudio() {
    val config = SodaWire.fields(SodaWire.coreConfig("/private/models/", "/private/cache/"))
    val asr = SodaWire.fields(requireNotNull(config.first { it.number == 3 }.bytes))
    assertEquals("/private/models/", asr.first { it.number == 13 }.bytes?.toString(Charsets.UTF_8))
    val audio = SodaWire.fields(requireNotNull(config.first { it.number == 1 }.bytes))
    val format = SodaWire.fields(requireNotNull(audio.first { it.number == 1 }.bytes))
    assertEquals(16000, format.first { it.number == 3 }.value.toInt())
    assertTrue(config.none { it.number == 2 })
  }
}
