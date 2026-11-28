package com.google.android.accessibility.talkback.recognition

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidedPhotoFrameAnalyzerTest {
  @Test fun keepsTheImageAliveUntilDocumentAndQualityAnalysisFinish() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    var documentScanned = false
    val analyzer = GuidedPhotoFrameAnalyzer("tài liệu") { input ->
      assertFalse(input.isRecycled)
      assertEquals(Color.WHITE, input.getPixel(0, 0))
      documentScanned = true
      DocumentAlignmentResult(DocumentAlignmentState.NO_PAPER, "")
    }
    val result = analyzer.analyze(bitmap, emptyList()) {
      assertFalse(bitmap.isRecycled)
      emptyList()
    }
    assertTrue(documentScanned)
    assertEquals(255f, result.brightness, 1f)
    assertTrue(bitmap.isRecycled)
  }

  @Test fun releasesTheImageWhenDetectionFails() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    assertThrows(IllegalStateException::class.java) {
      GuidedPhotoFrameAnalyzer("tài liệu").analyze(bitmap, emptyList()) {
        throw IllegalStateException("Detector unavailable")
      }
    }
    assertTrue(bitmap.isRecycled)
  }
}
