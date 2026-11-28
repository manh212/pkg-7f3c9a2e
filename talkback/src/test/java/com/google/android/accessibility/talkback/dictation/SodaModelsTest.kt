package com.google.android.accessibility.talkback.dictation

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

class SodaModelsTest {
  @Test fun crxHeaderIsRemovedBeforeValidatingArchivePaths() {
    val directory = java.nio.file.Files.createTempDirectory("soda-crx").toFile()
    try {
      val archive = File(directory, "model.zip")
      ZipOutputStream(archive.outputStream()).use {
        it.putNextEntry(ZipEntry("SODAModels/../../outside"))
        it.write(byteArrayOf(1))
        it.closeEntry()
      }
      val crx = File(directory, "model.crx")
      crx.writeBytes(ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        .put("Cr24".toByteArray(Charsets.US_ASCII)).putInt(3).putInt(3)
        .put(byteArrayOf(1, 2, 3)).array() + archive.readBytes())
      try {
        SodaModels.extractModel(crx, File(directory, "target")) { false }
        fail("Archive traversal must be rejected")
      } catch (failure: IOException) {
        assertEquals("Invalid SODA archive path", failure.message)
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test(expected = IOException::class) fun truncatedCrxHeaderIsRejected() {
    val directory = java.nio.file.Files.createTempDirectory("soda-header").toFile()
    try {
      val archive = File(directory, "model.crx")
      archive.writeBytes(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .put("Cr24".toByteArray(Charsets.US_ASCII)).putInt(3).putInt(100).array())
      SodaModels.extractModel(archive, File(directory, "target")) { false }
    } finally {
      directory.deleteRecursively()
    }
  }

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
