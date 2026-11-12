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

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.annotation.StringRes
import com.google.android.accessibility.talkback.R
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max

/** The file types the text editor can save a document as. Plain text only: the editor keeps no formatting, so every type is written from the text alone. */
enum class TextFileFormat(
  val extension: String,
  val mimeType: String,
  @StringRes val labelRes: Int,
) {
  TXT("txt", "text/plain", R.string.text_editor_file_type_txt),
  MD("md", "text/markdown", R.string.text_editor_file_type_md),
  CSV("csv", "text/csv", R.string.text_editor_file_type_csv),
  HTML("html", "text/html", R.string.text_editor_file_type_html),
  RTF("rtf", "application/rtf", R.string.text_editor_file_type_rtf),
  PDF("pdf", "application/pdf", R.string.text_editor_file_type_pdf);

  companion object {
    private const val PAGE_WIDTH_PT = 595
    private const val PAGE_HEIGHT_PT = 842
    private const val MARGIN_PT = 40f
    private const val TEXT_SIZE_PT = 12f

    /** A document name made safe to use as a file name: characters a file name cannot hold become dashes. */
    @JvmStatic
    fun sanitizeFileName(name: String): String {
      val cleaned = StringBuilder()
      for (c in name.trim()) {
        cleaned.append(
          if (c < ' ' ||
            c == '/' ||
            c == '\\' ||
            c == ':' ||
            c == '*' ||
            c == '?' ||
            c == '"' ||
            c == '<' ||
            c == '>' ||
            c == '|'
          ) {
            '-'
          } else {
            c
          }
        )
      }
      return cleaned.toString().trim().trimEnd('.')
    }

    /** The file name for [name] saved as [format]: the sanitized name with the extension, added once. */
    @JvmStatic
    fun fileName(name: String, format: TextFileFormat): String {
      val base = sanitizeFileName(name).ifEmpty { "document" }
      val suffix = "." + format.extension
      return if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix
    }

    /** The document text as the bytes of a file of [format]. */
    @JvmStatic
    fun toBytes(text: String, format: TextFileFormat, title: String): ByteArray =
      when (format) {
        TXT, MD, CSV -> text.toByteArray(StandardCharsets.UTF_8)
        HTML -> htmlBytes(text, title)
        RTF -> rtfBytes(text)
        PDF -> ByteArrayOutputStream().also { writePdf(it, text) }.toByteArray()
      }

    private fun htmlBytes(text: String, title: String): ByteArray {
      val escaped = escapeHtml(text)
      val safeTitle = escapeHtml(title)
      return ("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>$safeTitle</title>\n</head>\n<body>\n<pre>$escaped</pre>\n</body>\n</html>\n")
        .toByteArray(StandardCharsets.UTF_8)
    }

    private fun escapeHtml(text: String): String =
      text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun rtfBytes(text: String): ByteArray {
      val body = StringBuilder()
      for (c in text) {
        when (c) {
          '\\' -> body.append("\\\\")
          '{' -> body.append("\\{")
          '}' -> body.append("\\}")
          '\t' -> body.append("\\tab ")
          '\n' -> body.append("\\par\n")
          '\r' -> Unit
          else -> {
            if (c.code < 128) {
              body.append(c)
            } else {
              body.append("\\u${if (c.code > 32767) c.code - 65536 else c.code}?")
            }
          }
        }
      }
      return ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\fnil sans-serif;}}\n\\f0\\fs24 $body}")
        .toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * The text split into pages of wrapped lines: each line is wrapped at word boundaries to
     * [charsPerLine] characters, and [linesPerPage] lines make a page.
     */
    @JvmStatic
    fun paginate(text: String, charsPerLine: Int, linesPerPage: Int): List<List<String>> {
      require(charsPerLine > 0 && linesPerPage > 0)
      val lines = ArrayList<String>()
      for (rawLine in text.split('\n')) {
        var rest = rawLine
        if (rest.isEmpty()) {
          lines.add("")
          continue
        }
        while (rest.length > charsPerLine) {
          val breakAt = rest.lastIndexOf(' ', charsPerLine)
          if (breakAt <= 0) {
            lines.add(rest.substring(0, charsPerLine))
            rest = rest.substring(charsPerLine)
          } else {
            lines.add(rest.substring(0, breakAt))
            rest = rest.substring(breakAt + 1)
          }
        }
        lines.add(rest)
      }
      return lines.chunked(linesPerPage)
    }

    /** Renders the text as a PDF on [output]: A4 pages of monospaced text. */
    @JvmStatic
    fun writePdf(output: OutputStream, text: String) {
      val paint =
        Paint().apply {
          typeface = Typeface.MONOSPACE
          textSize = TEXT_SIZE_PT
        }
      val charsPerLine =
        max(1, ((PAGE_WIDTH_PT - 2 * MARGIN_PT) / paint.measureText("M")).toInt())
      val linesPerPage =
        max(1, ((PAGE_HEIGHT_PT - 2 * MARGIN_PT) / paint.fontSpacing).toInt())
      val pages = paginate(text, charsPerLine, linesPerPage)
      val document = PdfDocument()
      try {
        for ((index, pageLines) in pages.withIndex()) {
          val page =
            document.startPage(
              PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, index + 1).create()
            )
          var y = MARGIN_PT + paint.fontSpacing
          for (line in pageLines) {
            page.canvas.drawText(line, MARGIN_PT, y, paint)
            y += paint.fontSpacing
          }
          document.finishPage(page)
        }
        document.writeTo(output)
      } finally {
        document.close()
      }
    }
  }
}
