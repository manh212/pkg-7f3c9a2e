package com.google.android.accessibility.talkback.vietnameseime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BartphoTokenizerReferenceTest {
  @Test fun matchesTheOfficialSentencePieceImplementationOnTheRealPinnedModel() {
    val path = System.getenv("VI_SPELL_REFERENCE_FILE")
    assumeTrue("The model reference is prepared by CI for spelling model changes", !path.isNullOrBlank())
    val fixture = JSONObject(File(path!!).readText())
    val directory = File(fixture.getString("directory"))
    val tokenizer = BartphoTokenizer.load(directory.resolve("sentencepiece.bpe.model"), directory.resolve("dict.txt"))
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
      val case = cases.getJSONObject(index)
      val expectedIds = case.getJSONArray("ids")
      val expected = LongArray(expectedIds.length()) { expectedIds.getLong(it) }
      val actual = tokenizer.encode(case.getString("text"))
      assertArrayEquals("Reference case $index", expected, actual)
      assertEquals("Decoded case $index", case.getString("decoded"), tokenizer.decode(actual.map(Long::toInt)))
    }
  }
}
