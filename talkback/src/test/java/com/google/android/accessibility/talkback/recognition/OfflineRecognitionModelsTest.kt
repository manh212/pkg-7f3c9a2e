package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRecognitionModelsTest {
  @Test fun downloadedModelWeightsAreAbsentAndOnlyApprovedFaceWeightsAreBundled() {
    val context = RuntimeEnvironment.getApplication()
    fun files(path: String): List<String> = context.assets.list(path).orEmpty().flatMap { name ->
      val child = if (path.isEmpty()) name else "$path/$name"
      if (context.assets.list(child).isNullOrEmpty()) listOf(child) else files(child)
    }
    val weights = files("").filter { path ->
      listOf(".onnx", ".tflite", ".traineddata", ".pth", ".pt", ".task").any(path::endsWith)
    }.toSet()
    val faceWeights = setOf(
      "models_bundled/fssd_25_8bit_gray_v2.tflite",
      "models_bundled/fssd_25_8bit_v2.tflite",
      "models_bundled/fssd_medium_8bit_gray_v5.tflite",
      "models_bundled/fssd_medium_8bit_v5.tflite",
    )
    assertEquals(faceWeights, weights)
  }

  @Test fun featureGroupsDownloadOnlyTheirRequiredModels() {
    val camera = OfflineModels.models(OfflineModels.Group.CAMERA_OCR)
    assertEquals(listOf(OfflineModels.detector, OfflineModels.vietnamese), camera)
    assertEquals(OfflineModels.models(OfflineModels.Group.SCREEN_OCR), camera)
    assertFalse(camera.any { it in listOf(OfflineModels.encoder, OfflineModels.decoder, OfflineModels.vocabulary) })
    assertEquals(listOf(OfflineModels.currency), OfflineModels.models(OfflineModels.Group.CURRENCY))
    assertEquals(listOf(OfflineModels.objects), OfflineModels.models(OfflineModels.Group.OBJECTS))
    OfflineModels.Group.entries.flatMap(OfflineModels::models).forEach { model ->
      assertTrue(model.url.startsWith("https://"))
      assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
      assertTrue(model.size > 0)
    }
  }

  @Test fun partialAndWrongSizedModelsNeverCountAsReady() {
    val context = RuntimeEnvironment.getApplication()
    val target = OfflineModels.file(context, OfflineModels.currency)
    target.parentFile!!.mkdirs()
    target.delete()
    RandomAccessFile(File(target.path + ".part"), "rw").use { it.setLength(OfflineModels.currency.size) }
    assertFalse(OfflineModels.isReady(context, OfflineModels.Group.CURRENCY))
    RandomAccessFile(target, "rw").use { it.setLength(1) }
    assertFalse(OfflineModels.isReady(context, OfflineModels.Group.CURRENCY))
    RandomAccessFile(target, "rw").use { it.setLength(OfflineModels.currency.size) }
    assertTrue(OfflineModels.isReady(context, OfflineModels.Group.CURRENCY))
    assertFalse(OfflineModels.isReady(context, OfflineModels.Group.CAMERA_OCR))
  }

  @Test fun cameraRequiresLegacyVietnameseDataEvenIfVietOcrWasPreviouslyDownloaded() {
    val context = RuntimeEnvironment.getApplication()
    val oldData = OfflineModels.file(context, OfflineModels.vietnamese)
    val installed = listOf(OfflineModels.detector, OfflineModels.encoder,
      OfflineModels.decoder, OfflineModels.vocabulary)
    oldData.delete()
    try {
      installed.forEach { model ->
        OfflineModels.file(context, model).let { file ->
          file.parentFile!!.mkdirs()
          RandomAccessFile(file, "rw").use { it.setLength(model.size) }
        }
      }
      assertFalse(OfflineModels.isReady(context, OfflineModels.Group.CAMERA_OCR))
      oldData.parentFile!!.mkdirs()
      RandomAccessFile(oldData, "rw").use { it.setLength(OfflineModels.vietnamese.size) }
      assertTrue(OfflineModels.isReady(context, OfflineModels.Group.CAMERA_OCR))
      assertTrue(OfflineModels.isReady(context, OfflineModels.Group.SCREEN_OCR))
      listOf(OfflineModels.encoder, OfflineModels.decoder, OfflineModels.vocabulary).forEach {
        OfflineModels.file(context, it).delete()
      }
      assertTrue(OfflineModels.isReady(context, OfflineModels.Group.CAMERA_OCR))
    } finally {
      oldData.delete()
      installed.forEach { OfflineModels.file(context, it).delete() }
    }
  }
}
