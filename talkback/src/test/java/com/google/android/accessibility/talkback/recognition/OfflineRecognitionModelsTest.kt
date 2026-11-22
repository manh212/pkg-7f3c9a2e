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
  @Test fun modelWeightsAreAbsentFromTheApk() {
    val context = RuntimeEnvironment.getApplication()
    fun files(path: String): List<String> = context.assets.list(path).orEmpty().flatMap { name ->
      val child = if (path.isEmpty()) name else "$path/$name"
      if (context.assets.list(child).isNullOrEmpty()) listOf(child) else files(child)
    }
    assertTrue(files("").none { path ->
      listOf(".onnx", ".tflite", ".traineddata", ".pth", ".pt", ".task").any(path::endsWith)
    })
  }

  @Test fun featureGroupsDownloadOnlyTheirRequiredModels() {
    val camera = OfflineModels.models(OfflineModels.Group.CAMERA_OCR)
    assertTrue(camera.containsAll(listOf(OfflineModels.encoder, OfflineModels.decoder, OfflineModels.vocabulary)))
    assertFalse(camera.contains(OfflineModels.vietnamese))
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
}
