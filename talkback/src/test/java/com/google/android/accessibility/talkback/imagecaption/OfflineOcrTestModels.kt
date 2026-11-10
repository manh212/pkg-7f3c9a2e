package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

internal fun installOfflineOcrTestModels(context: Context) {
  val directory = OcrModelDownload.directory(context)
  val files = mapOf("inference.onnx" to 9_880_512L, "tessdata/vie.traineddata" to 531_275L)
  files.forEach { (name, size) ->
    val file = File(directory, name)
    file.parentFile?.mkdirs()
    RandomAccessFile(file, "rw").use { it.setLength(size) }
  }
}
