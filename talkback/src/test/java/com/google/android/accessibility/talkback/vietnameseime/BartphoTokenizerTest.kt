package com.google.android.accessibility.talkback.vietnameseime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BartphoTokenizerTest {
  private fun tokenizer(vararg pieces: Pair<String, Float>): BartphoTokenizer {
    val directory = Files.createTempDirectory("bartpho").toFile().apply { deleteOnExit() }
    val model = directory.resolve("sentencepiece.bpe.model")
    val vocabulary = directory.resolve("dict.txt")
    model.writeBytes(pieces.flatMap { (word, score) ->
      val text = word.toByteArray()
      val body = byteArrayOf(10, text.size.toByte()) + text + byteArrayOf(21) +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(score).array()
      (byteArrayOf(10, body.size.toByte()) + body).toList()
    }.toByteArray())
    vocabulary.writeText(pieces.joinToString("\n") { it.first + " 1" })
    return BartphoTokenizer.load(model, vocabulary)
  }

  @Test fun usesFairseqIdsAndTheHighestScoringCompleteSegmentation() {
    val tokenizer = tokenizer("▁" to -1f, "a" to -1f, "b" to -1f, "ab" to -10f)
    assertArrayEquals(longArrayOf(0, 4, 5, 6, 2), tokenizer.encode("ab"))
    assertEquals("ab", tokenizer.decode(listOf(0, 4, 5, 6, 2, 1)))
  }

  @Test fun preservesVietnameseWordBoundariesAndCollapsesUnknownSpans() {
    val tokenizer = tokenizer("▁Xin" to -1f, "▁chào" to -1f, "▁" to -4f)
    assertArrayEquals(longArrayOf(0, 4, 5, 2), tokenizer.encode("  Xin   chào  "))
    assertEquals("Xin chào", tokenizer.decode(listOf(0, 4, 5, 2)))
    assertArrayEquals(longArrayOf(0, 6, 3, 2), tokenizer.encode("🙂🙂"))
  }

  @Test fun refusesToSilentlyTruncateLongInput() {
    val tokenizer = tokenizer("▁a" to -1f)
    assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("a a a", maximumTokens = 4) }
  }

  @Test fun rejectsTruncatedModelInsteadOfLoadingPartialVocabulary() {
    val directory = Files.createTempDirectory("invalid-bartpho").toFile()
    val model = directory.resolve("bad.model").apply { writeBytes(byteArrayOf(10, 10, 1)) }
    val vocabulary = directory.resolve("dict.txt").apply { writeText("▁a 1") }
    assertThrows(IllegalArgumentException::class.java) { BartphoTokenizer.load(model, vocabulary) }
    directory.deleteRecursively()
  }
}
