package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.imagecaption.OcrModelDownload
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRecognitionModelsTest {
  @Test fun packagedVietnameseModelMatchesTheAccuracyOrientedSource() {
    val context = RuntimeEnvironment.getApplication()
    val bytes = context.assets.open("offline-ocr-v1/tessdata/vie.traineddata").use { it.readBytes() }
    assertEquals(12_435_550, bytes.size)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 255) }
    assertEquals("b6b49293d95d0b6dbd8780174627e82c75be957b6f4ed9862155540d6b00bb45", hash)
    assertTrue(OcrModelDownload.installBundled(context))
    assertTrue(OcrModelDownload.isReady(context))
  }
}
