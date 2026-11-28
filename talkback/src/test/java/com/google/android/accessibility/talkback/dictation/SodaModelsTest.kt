package com.google.android.accessibility.talkback.dictation

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class SodaModelsTest {
  @Test(expected = IOException::class) fun modelArchiveCannotEscapeItsDirectory() {
    val directory = java.nio.file.Files.createTempDirectory("soda-extract").toFile()
    try {
      val archive = File(directory, "model.zip")
      ZipOutputStream(archive.outputStream()).use {
        it.putNextEntry(ZipEntry("SODAModels/../../outside"))
        it.write(byteArrayOf(1))
        it.closeEntry()
      }
      SodaModels.extractModel(archive, File(directory, "target")) { false }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test(expected = InterruptedException::class) fun cancellationStopsExtraction() {
    val directory = java.nio.file.Files.createTempDirectory("soda-cancel").toFile()
    try {
      val archive = File(directory, "model.zip")
      ZipOutputStream(archive.outputStream()).use {
        it.putNextEntry(ZipEntry("SODAModels/metadata"))
        it.write(byteArrayOf(1))
        it.closeEntry()
      }
      SodaModels.extractModel(archive, File(directory, "target")) { true }
    } finally {
      directory.deleteRecursively()
    }
  }
}
