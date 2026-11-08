package com.google.android.accessibility.talkback.subtitles

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleImageProcessorTest {
  @Test fun enhancedImageCoordinatesMapBackToScreenshot() {
    val screenshot = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
    screenshot.eraseColor(Color.WHITE)
    val prepared = SubtitleImageProcessor.prepare(screenshot, enhance = true)
    try {
      assertTrue(screenshot.isRecycled)
      assertEquals(2f, prepared.scaleX, 0.001f)
      assertEquals(2f, prepared.scaleY, 0.001f)
      assertEquals(400, prepared.bitmap.width)
      assertEquals(Rect(130, 210, 170, 230),
        prepared.screenBounds(Rect(60, 20, 140, 60), Rect(100, 200, 300, 280)))
    } finally { prepared.bitmap.recycle() }
  }

  @Test fun normalPassDoesNotChangeTheOriginalImage() {
    val screenshot = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val prepared = SubtitleImageProcessor.prepare(screenshot, enhance = false)
    assertSame(screenshot, prepared.bitmap)
    assertFalse(screenshot.isRecycled)
    assertEquals(1f, prepared.scaleX, 0.001f)
    screenshot.recycle()
  }

  @Test fun uniformDarkFramesAreDetectedWithoutStoringPixels() {
    val image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
    try {
      image.eraseColor(Color.BLACK)
      assertTrue(SubtitleImageProcessor.inspect(image).nearlyUniformDark)
      image.eraseColor(Color.WHITE)
      assertFalse(SubtitleImageProcessor.inspect(image).nearlyUniformDark)
    } finally { image.recycle() }
  }
}
