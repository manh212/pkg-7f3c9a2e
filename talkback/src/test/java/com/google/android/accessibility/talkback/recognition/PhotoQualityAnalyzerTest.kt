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
  private val subject = PhotoSubject("face:1", "khuôn mặt", PhotoBox(.25f, .25f, .75f, .75f), PhotoSubjectKind.FACE)

  private fun image(background: Int = Color.BLACK, exposure: Int = 0, phase: Int = 0): Bitmap =
    Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
      eraseColor(background)
      for (y in 60 until 180) for (x in 80 until 240) {
        val value = (if ((x / 8 + y / 8 + phase) % 2 == 0) 160 else 40) + exposure
        setPixel(x, y, Color.rgb(value, value, value))
      }
    }

  @Test fun blankDarkSubjectCannotBeMistakenForASharpImage() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLACK)
    val quality = PhotoQualityAnalyzer().analyze(bitmap, listOf(subject))
    assertEquals(0f, quality.brightness, .01f)
    assertEquals(0f, quality.sharpness, .01f)
    assertFalse(bitmap.isRecycled)
    bitmap.recycle()
  }

  @Test fun darkAndMovingBackgroundDoesNotBlockAClearStableSubject() {
    val analyzer = PhotoQualityAnalyzer()
    val first = image()
    val second = image(Color.WHITE)
    assertTrue(analyzer.analyze(first, listOf(subject)).movement > 28f)
    val stable = analyzer.analyze(second, listOf(subject))
    assertTrue(stable.brightness > GuidedPhotoController.MIN_BRIGHTNESS)
    assertTrue(stable.sharpness > GuidedPhotoController.MIN_SHARPNESS)
    assertEquals(0f, stable.movement, .01f)
    assertEquals(160, stable.regions.single().pixelWidth)
    assertNull(GuidedPhotoController.guidance(subject, stable, false))
    first.recycle()
    second.recycle()
  }

  @Test fun exposureAdjustmentIsNotMistakenForMovementButRealChangesAre() {
    val analyzer = PhotoQualityAnalyzer()
    val first = image()
    val brighter = image(exposure = 30)
    val changed = image(exposure = 30, phase = 1)
    analyzer.analyze(first, listOf(subject))
    assertEquals(0f, analyzer.analyze(brighter, listOf(subject)).movement, .1f)
    assertTrue(analyzer.analyze(changed, listOf(subject)).movement > 28f)
    listOf(first, brighter, changed).forEach { it.recycle() }
  }

  @Test fun measuresSmallGroupFacesAtTheirOwnResolution() {
    val bitmap = image()
    val small = subject.copy(box = PhotoBox(.3f, .3f, .45f, .5f))
    val result = PhotoQualityAnalyzer().analyze(bitmap, listOf(subject, small))
    assertEquals(2, result.regions.size)
    assertTrue(result.regions.all { it.sharpness > GuidedPhotoController.MIN_SHARPNESS })
    assertEquals(48, result.regions.last().pixelWidth)
    bitmap.recycle()
  }
}
