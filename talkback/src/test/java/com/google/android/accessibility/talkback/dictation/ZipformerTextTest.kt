package com.google.android.accessibility.talkback.dictation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipformerTextTest {
  private val tokens =
    mapOf(0 to "<blk>", 1 to "<sos/eos>", 2 to "<unk>", 3 to "▁HAI", 4 to "▁KHÔNG", 5 to "▁ĐƯỢC")

  @Test fun loadTokensReadsPiecesAndIds() {
    val file = File.createTempFile("tokens", ".txt")
    file.writeText("<blk> 0\n▁HAI 3\n▁KHÔNG 4\n")
    assertEquals(mapOf(0 to "<blk>", 3 to "▁HAI", 4 to "▁KHÔNG"), ZipformerText.loadTokens(file))
  }

  @Test fun detokenizeJoinsPiecesIntoWords() {
    assertEquals("HAI KHÔNG ĐƯỢC", ZipformerText.detokenize(listOf(3, 4, 5), tokens))
  }

  @Test fun detokenizeSkipsUnknownIds() {
    assertEquals("HAI", ZipformerText.detokenize(listOf(3, 99), tokens))
  }

  @Test fun formatLowersCapitalsAndRaisesTheFirstLetter() {
    assertEquals("Hai không được", ZipformerText.format("HAI KHÔNG ĐƯỢC"))
  }

  @Test fun formatKeepsEmptyTextEmpty() {
    assertEquals("", ZipformerText.format(""))
  }
}
