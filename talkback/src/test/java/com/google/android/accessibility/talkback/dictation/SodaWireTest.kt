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

  @Test fun languageMetadataDoesNotReplaceRecognizedWords() {
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(1,
      SodaWire.text(1, "Xin chào!"), SodaWire.nested(4, SodaWire.text(2, "vi-VN")))))
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

  @Test fun partialLanguageMetadataDoesNotReplaceRecognizedWords() {
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(2,
      SodaWire.text(1, "Xin"), SodaWire.nested(2, SodaWire.text(2, "vi-VN")))))
    val result = requireNotNull(SodaWire.result(packet))
    assertEquals("Xin", result.text)
    assertFalse(result.final)
  }

  @Test fun speechEndpointsAreNotRecognitionFailures() {
    val endpoint = SodaWire.message(SodaWire.nested(3, SodaWire.Field(1, 0)))
    assertEquals(null, SodaWire.result(endpoint))
  }

  @Test fun enhancedPartialResultsUseTheirFirstHypothesis() {
    val packet = SodaWire.message(SodaWire.nested(2, SodaWire.nested(4,
      SodaWire.text(1, "Xin chào"), SodaWire.nested(5, SodaWire.text(2, "vi-VN")))))
    assertEquals(SodaWire.Result("Xin chào", false), SodaWire.result(packet))
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
    val config = SodaWire.fields(SodaWire.coreConfig("/private/models/", "/private/cache/", "Pixel 8"))
    val asr = SodaWire.fields(requireNotNull(config.first { it.number == 3 }.bytes))
    assertEquals("/private/models/", asr.first { it.number == 13 }.bytes?.toString(Charsets.UTF_8))
    val audio = SodaWire.fields(requireNotNull(config.first { it.number == 1 }.bytes))
    val format = SodaWire.fields(requireNotNull(audio.first { it.number == 1 }.bytes))
    assertEquals(16000, format.first { it.number == 3 }.value.toInt())
    val client = SodaWire.fields(requireNotNull(config.first { it.number == 10 }.bytes))
    assertEquals("Android", client.first { it.number == 2 }.bytes?.toString(Charsets.UTF_8))
    assertEquals("Pixel 8", client.first { it.number == 3 }.bytes?.toString(Charsets.UTF_8))
    assertEquals(6L, client.first { it.number == 6 }.value)
    assertTrue(config.none { it.number == 2 })
  }
}
