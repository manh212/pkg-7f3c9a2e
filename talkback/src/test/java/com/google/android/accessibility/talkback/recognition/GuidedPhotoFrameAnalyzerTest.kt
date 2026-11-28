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

  @Test fun facesBypassObjectAndDocumentModelsEntirely() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    val face = PhotoSubject("face:1", "khuôn mặt", PhotoBox(.2f, .2f, .8f, .8f), PhotoSubjectKind.FACE)
    val analyzer = GuidedPhotoFrameAnalyzer("tài liệu") { error("Document model must not run for faces") }
    val result = analyzer.analyze(bitmap, listOf(face)) { error("Object model must not run for faces") }
    assertEquals(listOf(face), result.subjects)
    assertEquals(1, result.regions.size)
    assertTrue(bitmap.isRecycled)
  }

  @Test fun recognizedObjectsDoNotWaitForDocumentAnalysis() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    val cup = PhotoSubject("object:cup", "cốc", PhotoBox(.2f, .2f, .8f, .8f), PhotoSubjectKind.OBJECT)
    val result = GuidedPhotoFrameAnalyzer("tài liệu") { error("Document model must not run for a cup") }
      .analyze(bitmap, emptyList()) { listOf(cup) }
    assertEquals(listOf(cup), result.subjects)
    assertTrue(bitmap.isRecycled)
  }

  @Test fun unavailableDocumentLibraryDoesNotCloseFacePhotography() {
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    val result = GuidedPhotoFrameAnalyzer("tài liệu") { throw UnsatisfiedLinkError("OpenCV unavailable") }
      .analyze(bitmap, emptyList()) { emptyList() }
    assertTrue(result.subjects.isEmpty())
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
