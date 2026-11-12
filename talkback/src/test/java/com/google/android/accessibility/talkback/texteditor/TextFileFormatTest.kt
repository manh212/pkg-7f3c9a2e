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

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextFileFormatTest {

  @Test
  fun sanitizeReplacesCharactersAFileNameCannotHold() {
    assertEquals("11-11-2026 21-05", TextFileFormat.sanitizeFileName("11/11/2026 21:05"))
    assertEquals(
      "a-b-c-d-e-f-g-h-i-j", TextFileFormat.sanitizeFileName("a/b:c*d?e\"f<g>h|i\\j"))
    assertEquals("spaced", TextFileFormat.sanitizeFileName("  spaced  "))
    assertEquals("", TextFileFormat.sanitizeFileName("   "))
    assertEquals("notes", TextFileFormat.sanitizeFileName("notes."))
  }

  @Test
  fun fileNameAddsTheExtensionOnce() {
    assertEquals("notes.txt", TextFileFormat.fileName("notes", TextFileFormat.TXT))
    assertEquals("notes.md", TextFileFormat.fileName("notes.md", TextFileFormat.MD))
    assertEquals("NOTES.MD", TextFileFormat.fileName("NOTES.MD", TextFileFormat.MD))
    assertEquals("document.txt", TextFileFormat.fileName("", TextFileFormat.TXT))
    assertEquals("11-11-2026 21-05.pdf", TextFileFormat.fileName("11/11/2026 21:05", TextFileFormat.PDF))
    assertEquals("notes.docx", TextFileFormat.fileName("notes", TextFileFormat.DOCX))
    assertEquals("notes.docx", TextFileFormat.fileName("notes.docx", TextFileFormat.DOCX))
  }

  @Test
  fun plainTypesWriteTheTextAsIs() {
    val text = "one\ntwo"
    for (format in listOf(TextFileFormat.TXT, TextFileFormat.MD, TextFileFormat.CSV)) {
      assertEquals(text, String(TextFileFormat.toBytes(text, format, "doc"), StandardCharsets.UTF_8))
    }
  }

  @Test
  fun htmlWrapsTheTextAndEscapesIt() {
    val html =
      String(
        TextFileFormat.toBytes("a<b>&c", TextFileFormat.HTML, "My doc"),
        StandardCharsets.UTF_8)
    assertTrue(html.contains("<!DOCTYPE html>"))
    assertTrue(html.contains("<title>My doc</title>"))
    assertTrue(html.contains("<pre>a&lt;b&gt;&amp;c</pre>"))
  }

  @Test
  fun rtfEscapesSpecialCharactersAndBreaks() {
    val rtf =
      String(
        TextFileFormat.toBytes("a{b}\\c\nd\te\u00e9", TextFileFormat.RTF, "doc"),
        StandardCharsets.UTF_8)
    assertTrue(rtf.startsWith("{\\rtf1"))
    assertTrue(rtf.contains("a\\{b\\}\\\\c"))
    assertTrue(rtf.contains("\\par"))
    assertTrue(rtf.contains("\\tab "))
    assertTrue(rtf.contains("\\u233?"))
    assertTrue(rtf.endsWith("}"))
  }

  @Test
  fun paginateWrapsWordsAndSplitsIntoPages() {
    assertEquals(
      listOf(listOf("one two", "three"), listOf("four")),
      TextFileFormat.paginate("one two three four", 7, 2))
  }

  @Test
  fun paginateSplitsWordsLongerThanALine() {
    assertEquals(listOf(listOf("abcd", "efgh")), TextFileFormat.paginate("abcdefgh", 4, 10))
  }

  @Test
  fun paginateKeepsEmptyLines() {
    assertEquals(listOf(listOf("a", "", "b")), TextFileFormat.paginate("a\n\nb", 10, 10))
    assertEquals(listOf(listOf("")), TextFileFormat.paginate("", 10, 10))
  }

  @Test
  fun docxIsAZipWithFourParts() {
    val entries = docxEntries("one\ntwo")
    assertEquals(
      listOf(
        "[Content_Types].xml",
        "_rels/.rels",
        "word/document.xml",
        "word/_rels/document.xml.rels",
      ),
      entries.keys.toList())
    assertTrue(
      entries.getValue("[Content_Types].xml")
        .contains("wordprocessingml.document.main+xml"))
    assertTrue(entries.getValue("_rels/.rels").contains("word/document.xml"))
  }

  @Test
  fun docxDocumentXmlIsWellFormedAndHoldsTheText() {
    val xml = docxEntries("one\n\ntwo").getValue("word/document.xml")
    val document = parseXml(xml)
    assertEquals(3, document.getElementsByTagName("w:p").length)
    val texts = document.getElementsByTagName("w:t")
    assertEquals(2, texts.length)
    assertEquals("one", texts.item(0).textContent)
    assertEquals("two", texts.item(1).textContent)
  }

  @Test
  fun docxEscapesSpecialCharacters() {
    val text = "a<b>&\"c\" 'd' Tiếng Việt"
    val xml = docxEntries(text).getValue("word/document.xml")
    assertTrue(xml.contains("a&lt;b&gt;&amp;&quot;c&quot; &apos;d&apos; Tiếng Việt"))
    val texts = parseXml(xml).getElementsByTagName("w:t")
    assertEquals(1, texts.length)
    assertEquals(text, texts.item(0).textContent)
  }

  @Test
  fun docxEmptyDocumentIsAValidFile() {
    val xml = docxEntries("").getValue("word/document.xml")
    val document = parseXml(xml)
    assertEquals(1, document.getElementsByTagName("w:p").length)
    assertEquals(0, document.getElementsByTagName("w:t").length)
  }

  private fun docxEntries(text: String): Map<String, String> {
    val entries = LinkedHashMap<String, String>()
    val bytes = TextFileFormat.toBytes(text, TextFileFormat.DOCX, "doc")
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        entries[entry.name] = String(zip.readBytes(), StandardCharsets.UTF_8)
        entry = zip.nextEntry
      }
    }
    return entries
  }

  private fun parseXml(xml: String) =
    DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))

  @Test
  fun formatsHaveDistinctExtensionsAndMimeTypes() {
    val formats = TextFileFormat.values()
    assertEquals(7, formats.size)
    assertEquals(formats.size, formats.map { it.extension }.toSet().size)
    assertEquals(formats.size, formats.map { it.mimeType }.toSet().size)
    assertEquals(formats.size, formats.map { it.labelRes }.toSet().size)
  }
}
