/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.texteditor

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextFileReaderTest {

  @Test
  fun formatForPrefersTheMimeType() {
    assertEquals(TextFileFormat.TXT, TextFileReader.formatFor("text/plain", "notes.md"))
    assertEquals(TextFileFormat.MD, TextFileReader.formatFor("text/markdown", "notes.txt"))
    assertEquals(TextFileFormat.CSV, TextFileReader.formatFor("text/csv", "notes.txt"))
    assertEquals(TextFileFormat.HTML, TextFileReader.formatFor("text/html", "notes.txt"))
    assertEquals(TextFileFormat.RTF, TextFileReader.formatFor("application/rtf", "notes.txt"))
    assertEquals(
      TextFileFormat.DOCX,
      TextFileReader.formatFor(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "notes.txt"
      )
    )
  }

  @Test
  fun formatForFallsBackToTheExtension() {
    assertEquals(TextFileFormat.TXT, TextFileReader.formatFor(null, "notes.txt"))
    assertEquals(TextFileFormat.MD, TextFileReader.formatFor("application/octet-stream", "notes.md"))
    assertEquals(TextFileFormat.MD, TextFileReader.formatFor(null, "notes.markdown"))
    assertEquals(TextFileFormat.CSV, TextFileReader.formatFor(null, "notes.csv"))
    assertEquals(TextFileFormat.HTML, TextFileReader.formatFor(null, "notes.htm"))
    assertEquals(TextFileFormat.RTF, TextFileReader.formatFor(null, "notes.rtf"))
    assertEquals(TextFileFormat.DOCX, TextFileReader.formatFor(null, "notes.docx"))
  }

  @Test
  fun formatForRejectsPdfAndUnknownTypes() {
    assertNull(TextFileReader.formatFor("application/pdf", "notes.pdf"))
    assertNull(TextFileReader.formatFor("application/pdf", "notes.txt"))
    assertNull(TextFileReader.formatFor(null, "notes.pdf"))
    assertNull(TextFileReader.formatFor("image/png", "notes.png"))
    assertNull(TextFileReader.formatFor(null, "notes"))
    assertNull(TextFileReader.formatFor(null, null))
  }

  @Test
  fun readPlainTextKeepsTheTextAndDropsTheBom() {
    val text = "Tiếng Việt\nDòng hai"
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.TXT))
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.MD))
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.CSV))
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
    assertEquals(text, TextFileReader.readText(bom, TextFileFormat.TXT))
  }

  @Test
  fun readHtmlRoundTripsThroughTheWriter() {
    val text = "Tiêu đề & nội dung <quan trọng>\nDòng hai"
    val bytes = TextFileFormat.toBytes(text, TextFileFormat.HTML, "Ghi chú")
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.HTML))
  }

  @Test
  fun readHtmlDropsHeadAndTags() {
    val html =
      "<html><head><title>Bỏ qua</title><style>.a{}</style></head>" +
        "<body><p>Một &amp; hai</p><p>Ba<br>Bốn</p><script>x();</script></body></html>"
    assertEquals("Một & hai\nBa\nBốn", TextFileReader.readText(html.toByteArray(StandardCharsets.UTF_8), TextFileFormat.HTML))
  }

  @Test
  fun readRtfRoundTripsThroughTheWriter() {
    val text = "Tiếng Việt\thas tab\nDòng hai {ngoặc} \\gạch\\"
    val bytes = TextFileFormat.toBytes(text, TextFileFormat.RTF, "Ghi chú")
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.RTF))
  }

  @Test
  fun readDocxRoundTripsThroughTheWriter() {
    val text = "Tiếng Việt & nội dung <quan trọng>\n\nDòng ba"
    val bytes = TextFileFormat.toBytes(text, TextFileFormat.DOCX, "Ghi chú")
    assertEquals(text, TextFileReader.readText(bytes, TextFileFormat.DOCX))
  }

  @Test
  fun readRejectsOversizedAndUnreadableFiles() {
    val big = ByteArray(TextFileReader.MAX_BYTES + 1) { 'a'.code.toByte() }
    assertNull(TextFileReader.readText(big, TextFileFormat.TXT))
    assertNull(TextFileReader.readText("not a zip".toByteArray(StandardCharsets.UTF_8), TextFileFormat.DOCX))
    assertNull(TextFileReader.readText("x".toByteArray(StandardCharsets.UTF_8), TextFileFormat.PDF))
  }
}
