package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VietnameseSpellModelsTest {
  private val content = ByteArray(4096) { (it * 13).toByte() }
  private val spec = VietnameseSpellModels.Spec("model.onnx", content.size.toLong(), ModelFiles.sha256(content.inputStream()))

  private fun downloader(status: Int = 200, body: ByteArray = content) = ModelDownloader { url ->
    object : HttpURLConnection(url) {
      override fun connect() {}
      override fun disconnect() {}
      override fun usingProxy() = false
      override fun getResponseCode() = status
      override fun getInputStream() = ByteArrayInputStream(body)
    }
  }

  @Test fun downloadsOnlyTheFilesRequiredByTheActualBartphoRuntime() {
    assertEquals(setOf("encoder_model.onnx", "decoder_model.onnx", "sentencepiece.bpe.model", "dict.txt", "config.json"),
      VietnameseSpellModels.files.map { it.name }.toSet())
    assertTrue(VietnameseSpellModels.totalBytes in 199_000_000L..200_000_000L)
    assertTrue(VietnameseSpellModels.files.all { it.url.contains(VietnameseSpellModels.REVISION) && it.sha256.length == 64 })
  }

  @Test fun neverTreatsOldOrUnverifiedModelFilesAsReady() {
    val directory = Files.createTempDirectory("old-spell").toFile()
    directory.resolve("encoder_model_quantized.onnx").writeBytes(content)
    directory.resolve("decoder_model_quantized.onnx").writeBytes(content)
    assertFalse(VietnameseSpellModels.ready(directory))
    directory.deleteRecursively()
  }

  @Test fun installsVerifiedFilesAndRecordsCompletionOnlyAfterSuccess() {
    val directory = Files.createTempDirectory("spell-install").toFile()
    val progress = mutableListOf<Long>()
    assertEquals(ModelDownloader.Result.Done,
      VietnameseSpellModels.install(directory, downloader(), { false }, { progress.add(it) }, listOf(spec)))
    assertArrayEquals(content, directory.resolve(spec.name).readBytes())
    assertEquals(VietnameseSpellModels.REVISION, directory.resolve("installed-revision").readText())
    assertEquals(content.size.toLong(), progress.last())
    directory.deleteRecursively()
  }

  @Test fun corruptDownloadsAndHttpErrorsDoNotBecomeInstalledModels() {
    for (download in listOf(downloader(404), downloader(body = ByteArray(content.size)))) {
      val directory = Files.createTempDirectory("bad-spell").toFile()
      assertTrue(VietnameseSpellModels.install(directory, download, { false }, {}, listOf(spec)) is ModelDownloader.Result.Failed)
      assertFalse(directory.resolve(spec.name).exists())
      assertFalse(directory.resolve("installed-revision").exists())
      directory.deleteRecursively()
    }
  }

  @Test fun cancellationNeverCreatesAnInstallationReceipt() {
    val directory = Files.createTempDirectory("cancel-spell").toFile()
    assertEquals(ModelDownloader.Result.Cancelled,
      VietnameseSpellModels.install(directory, downloader(), { true }, {}, listOf(spec)))
    assertFalse(directory.resolve("installed-revision").exists())
    directory.deleteRecursively()
  }

  @Test fun retryResumesTheVerifiedFileFromItsPartialDownload() {
    val directory = Files.createTempDirectory("resume-spell").toFile()
    val target = directory.resolve(spec.name)
    ModelFiles.partFile(target).writeBytes(content.copyOfRange(0, 1024))
    var range: String? = null
    val download = ModelDownloader { url ->
      object : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
          range = getRequestProperty("Range")
          return 206
        }
        override fun getInputStream() = ByteArrayInputStream(content.copyOfRange(1024, content.size))
      }
    }
    assertEquals(ModelDownloader.Result.Done,
      VietnameseSpellModels.install(directory, download, { false }, {}, listOf(spec)))
    assertEquals("bytes=1024-", range)
    assertArrayEquals(content, target.readBytes())
    assertFalse(ModelFiles.partFile(target).exists())
    directory.deleteRecursively()
  }
}
