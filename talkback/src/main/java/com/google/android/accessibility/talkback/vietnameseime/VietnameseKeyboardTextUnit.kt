package com.google.android.accessibility.talkback.vietnameseime

internal fun textUnitToDelete(
  text: String, caret: Int, paragraph: Boolean, visualLine: (Int, Int) -> IntRange?,
): IntRange {
  var start = text.lastIndexOf('\n', caret - 1) + 1
  var end = text.indexOf('\n', caret).takeIf { it >= 0 } ?: text.length
  val contentEnd = if (end < text.length && end > start && text[end - 1] == '\r') end - 1 else end
  if (!paragraph && contentEnd > start) {
    val visual = visualLine(start, contentEnd)
    if (visual != null && (visual.first > start || visual.last + 1 < contentEnd)) {
      if (caret <= visual.last + 1) return visual
      return visual.first until caret
    }
  }
  if (end < text.length) {
    end++
    if (paragraph) {
      while (end < text.length) {
        if (text.startsWith("\r\n", end)) end += 2
        else if (text[end] == '\n') end++
        else break
      }
    }
  } else if (start > 0) {
    do {
      start -= if (start >= 2 && text[start - 2] == '\r') 2 else 1
    } while (paragraph && start > 0 && text[start - 1] == '\n')
  }
  return start until end
}
