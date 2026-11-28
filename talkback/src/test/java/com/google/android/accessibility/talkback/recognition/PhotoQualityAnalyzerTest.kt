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
class PhotoQualityAnalyzerTest {
  @Test fun blankDarkFramesCannotBeMistakenForSharpImages() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLACK)
    val quality = PhotoQualityAnalyzer().analyze(bitmap, emptyList())
    assertEquals(0f, quality.brightness, .01f)
    assertEquals(0f, quality.sharpness, .01f)
    bitmap.recycle()
  }

  @Test fun detailedStableFramesPassQualityWhileMovementIsDetected() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    for (y in 0 until 120) for (x in 0 until 160) {
      bitmap.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.WHITE else Color.BLACK)
    }
    val analyzer = PhotoQualityAnalyzer()
    assertTrue(analyzer.analyze(bitmap, emptyList()).movement > 12f)
    val stable = analyzer.analyze(bitmap, emptyList())
    assertTrue(stable.brightness > GuidedPhotoController.MIN_BRIGHTNESS)
    assertTrue(stable.sharpness > GuidedPhotoController.MIN_SHARPNESS)
    assertEquals(0f, stable.movement, .01f)
    assertFalse(stable.focusReady)
    bitmap.eraseColor(Color.WHITE)
    assertTrue(analyzer.analyze(bitmap, emptyList()).movement > 12f)
    bitmap.recycle()
  }
}
