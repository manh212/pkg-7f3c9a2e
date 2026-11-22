package com.google.android.accessibility.talkback.imagecaption

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TesseractSegmentsTest {
  @Test fun idleReleaseDoesNotWaitForOcrOnTheMainLooper() {
    val handler =
      OcrEngine::class.java.getDeclaredField("IDLE_HANDLER").apply { isAccessible = true }
        .get(null) as Handler
    assertNotSame(Looper.getMainLooper(), handler.looper)
  }

  @Test fun projectsWordBoundsFromPaddedLineToScreen() {
    val corners = floatArrayOf(100f, 200f, 300f, 200f, 300f, 264f, 100f, 264f)
    val word = intArrayOf(30, 14, 70, 34)
    assertEquals(Rect(120, 204, 160, 224),
      OcrEngine.projectWord(word, corners, 200, 400, 500))
  }

  @Test fun clipsProjectedWordBoundsToTheInputImage() {
    val corners = floatArrayOf(0f, 0f, 200f, 0f, 200f, 64f, 0f, 64f)
    val word = intArrayOf(0, 0, 30, 34)
    assertEquals(Rect(0, 0, 20, 24),
      OcrEngine.projectWord(word, corners, 200, 200, 64))
  }
}
