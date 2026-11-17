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
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

/**
 * Reads the plain text out of a file the text editor can open. The editor keeps no
 * formatting, so every format is reduced to its text alone. PDF is not here: the editor can
 * save a PDF but Android has no way to read the text back out of one.
 */
object TextFileReader {

  /** Files larger than this are refused, so one huge file cannot fill the document store. */
  const val MAX_BYTES = 2_000_000

  private val SKIP_RTF_GROUPS =
    setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "footnote")

  /** The format of a file, from its MIME type or its file name, or null when unsupported. */
  @JvmStatic
  fun formatFor(mimeType: String?, fileName: String?): TextFileFormat? {
    when (mimeType?.lowercase()) {
      "text/plain" -> return TextFileFormat.TXT
      "text/markdown", "text/x-markdown" -> return TextFileFormat.MD
      "text/csv" -> return TextFileFormat.CSV
      "text/html" -> return TextFileFormat.HTML
      "application/rtf", "text/rtf" -> return TextFileFormat.RTF
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
        return TextFileFormat.DOCX
      null, "", "application/octet-stream" -> {}
      else -> return null
    }
    return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
      "txt", "text" -> TextFileFormat.TXT
      "md", "markdown" -> TextFileFormat.MD
      "csv" -> TextFileFormat.CSV
      "html", "htm" -> TextFileFormat.HTML
      "rtf" -> TextFileFormat.RTF
      "docx" -> TextFileFormat.DOCX
      else -> null
    }
  }

  /** The plain text of [bytes] as a file of [format], or null when the bytes cannot be read. */
  @JvmStatic
  fun readText(bytes: ByteArray, format: TextFileFormat): String? {
    if (bytes.size > MAX_BYTES) return null
    return try {
      when (format) {
        TextFileFormat.TXT, TextFileFormat.MD, TextFileFormat.CSV -> decodeUtf8(bytes)
        TextFileFormat.HTML -> stripHtml(decodeUtf8(bytes))
        TextFileFormat.RTF -> stripRtf(bytes)
        TextFileFormat.DOCX -> extractDocxText(bytes)
        TextFileFormat.PDF -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  /** UTF-8 text, with a leading byte-order mark removed. */
  private fun decodeUtf8(bytes: ByteArray): String {
    val start =
      if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
      ) {
        3
      } else {
        0
      }
    return String(bytes, start, bytes.size - start, StandardCharsets.UTF_8)
  }

  /** HTML reduced to its text: head, scripts and styles are dropped, blocks become breaks. */
  private fun stripHtml(text: String): String {
    var html = text
    for (tag in listOf("head", "script", "style")) {
      html = html.replace(Regex("(?is)<$tag[^>]*>.*?</$tag>"), "")
    }
    html = html.replace(Regex("(?i)<br[^>]*>"), "\n")
    html =
      html.replace(
        Regex("(?i)</(p|div|li|tr|h1|h2|h3|h4|h5|h6|blockquote|pre|section|article)>"),
        "\n"
      )
    html = html.replace(Regex("<[^>]*>"), "")
    return unescapeHtml(html).replace(Regex("\n{3,}"), "\n\n").trim('\n')
  }

  private fun unescapeHtml(text: String): String {
    if (!text.contains('&')) return text
    return unescapeEntities(text) { entity ->
      when (entity) {
        "nbsp" -> " "
        "copy" -> "©"
        "reg" -> "®"
        "hellip" -> "…"
        "mdash" -> "—"
        "ndash" -> "–"
        "lsquo" -> "‘"
        "rsquo" -> "’"
        "ldquo" -> "“"
        "rdquo" -> "”"
        "laquo" -> "«"
        "raquo" -> "»"
        else -> null
      }
    }
  }

  /** RTF reduced to its text: control words are skipped, \par and \tab become breaks. */
  private fun stripRtf(bytes: ByteArray): String {
    val input = String(bytes, StandardCharsets.ISO_8859_1)
    val out = StringBuilder()
    val skipStack = ArrayDeque<Boolean>()
    var i = 0
    while (i < input.length) {
      when (val c = input[i]) {
        '{' -> {
          skipStack.addLast(isSkipGroup(input, i + 1))
          i++
        }
        '}' -> {
          if (skipStack.isNotEmpty()) skipStack.removeLast()
          i++
        }
        '\\' -> {
          if (i + 1 >= input.length) {
            i++
          } else {
            i = readRtfControl(input, i, out, skipStack.any { it })
          }
        }
        '\n', '\r' -> i++
        else -> {
          if (skipStack.none { it }) out.append(c)
          i++
        }
      }
    }
    return out.toString()
  }

  /** True when the group opening after [index] holds tables or metadata instead of text. */
  private fun isSkipGroup(input: String, index: Int): Boolean {
    if (index >= input.length || input[index] != '\\') return false
    if (index + 1 < input.length && input[index + 1] == '*') return true
    var k = index + 1
    val word = StringBuilder()
    while (k < input.length && input[k].isLetter()) {
      word.append(input[k])
      k++
    }
    return word.toString() in SKIP_RTF_GROUPS
  }

  /**
   * Reads the RTF control sequence at [index], writes what it means into [out] unless
   * [skipping], and returns the index just past the sequence.
   */
  private fun readRtfControl(
    input: String,
    index: Int,
    out: StringBuilder,
    skipping: Boolean
  ): Int {
    val next = input[index + 1]
    if (next == '\\' || next == '{' || next == '}') {
      if (!skipping) out.append(next)
      return index + 2
    }
    if (next == '\'') {
      if (index + 3 < input.length) {
        val value = input.substring(index + 2, index + 4).toIntOrNull(16)
        if (value != null && !skipping) out.append(value.toChar())
        return index + 4
      }
      return index + 2
    }
    if (!next.isLetter()) return index + 2
    var k = index + 1
    val word = StringBuilder()
    while (k < input.length && input[k].isLetter()) {
      word.append(input[k])
      k++
    }
    var param: Int? = null
    if (k < input.length && (input[k] == '-' || input[k].isDigit())) {
      var negative = false
      if (input[k] == '-') {
        negative = true
        k++
      }
      val digits = StringBuilder()
      while (k < input.length && input[k].isDigit()) {
        digits.append(input[k])
        k++
      }
      param = digits.toString().toIntOrNull()?.let { if (negative) -it else it }
    }
    if (k < input.length && input[k] == ' ') k++
    if (!skipping) {
      when (word.toString()) {
        "par", "line" -> out.append('\n')
        "tab" -> out.append('\t')
        "u" -> {
          val code = param ?: 0
          out.append((if (code < 0) code + 65536 else code).toChar())
          if (k < input.length && input[k] != '\\' && input[k] != '{' && input[k] != '}') k++
        }
      }
    }
    return k
  }

  /** The text of a .docx: the runs of word/document.xml, paragraphs becoming line breaks. */
  private fun extractDocxText(bytes: ByteArray): String? {
    val xmlBytes =
      try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
          var entry = zip.nextEntry
          var found: ByteArray? = null
          while (entry != null) {
            if (entry.name == "word/document.xml") {
              found = readFully(zip)
              break
            }
            entry = zip.nextEntry
          }
          found
        }
      } catch (e: RuntimeException) {
        null
      } ?: return null
    val doc = String(xmlBytes, StandardCharsets.UTF_8)
    val out = StringBuilder()
    var i = 0
    while (i < doc.length) {
      val lt = doc.indexOf('<', i)
      if (lt < 0) break
      val gt = doc.indexOf('>', lt)
      if (gt < 0) break
      val tag = doc.substring(lt + 1, gt).trim()
      when {
        isTag(tag, "w:t") -> {
          val end = doc.indexOf("</w:t>", gt + 1)
          if (end < 0) break
          out.append(unescapeXml(doc.substring(gt + 1, end)))
          i = end + 6
        }
        isTag(tag, "w:tab") -> {
          out.append('\t')
          i = gt + 1
        }
        isTag(tag, "w:br") || isTag(tag, "w:cr") -> {
          out.append('\n')
          i = gt + 1
        }
        tag == "/w:p" -> {
          out.append('\n')
          i = gt + 1
        }
        tag == "w:p/" || tag == "w:p /" -> {
          out.append('\n')
          i = gt + 1
        }
        else -> i = gt + 1
      }
    }
    if (out.isNotEmpty() && out.last() == '\n') out.deleteCharAt(out.length - 1)
    return out.toString()
  }

  private fun isTag(tag: String, name: String): Boolean =
    tag == name || tag.startsWith("$name ") || tag.startsWith("$name/")

  private fun readFully(input: java.io.InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  private fun unescapeXml(text: String): String =
    unescapeEntities(text) { null }

  /**
   * Replaces &...; references with their characters: the five XML references, numeric
   * references, and whatever [named] resolves, leaving unknown references untouched.
   */
  private fun unescapeEntities(text: String, named: (String) -> String?): String {
    if (!text.contains('&')) return text
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c != '&') {
        out.append(c)
        i++
        continue
      }
      val semi = text.indexOf(';', i)
      if (semi < 0) {
        out.append(c)
        i++
        continue
      }
      val entity = text.substring(i + 1, semi)
      val replacement =
        when (entity) {
          "amp" -> "&"
          "lt" -> "<"
          "gt" -> ">"
          "quot" -> "\""
          "apos" -> "'"
          else -> named(entity) ?: numericEntity(entity)
        }
      if (replacement != null) {
        out.append(replacement)
      } else {
        out.append('&')
        out.append(entity)
        out.append(';')
      }
      i = semi + 1
    }
    return out.toString()
  }

  private fun numericEntity(entity: String): String? {
    val code =
      when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
          entity.substring(2).toIntOrNull(16)
        entity.startsWith("#") -> entity.substring(1).toIntOrNull(10)
        else -> null
      } ?: return null
    if (code < 0 || code > 0x10FFFF) return null
    return StringBuilder().appendCodePoint(code).toString()
  }
}
