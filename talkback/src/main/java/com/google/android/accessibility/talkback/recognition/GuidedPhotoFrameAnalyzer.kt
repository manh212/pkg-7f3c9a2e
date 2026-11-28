package com.google.android.accessibility.talkback.recognition

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

internal class GuidedPhotoFrameAnalyzer(
  private val documentLabel: String,
  private val scanDocument: (Bitmap) -> DocumentAlignmentResult = DocumentAlignmentScanner::analyze,
) {
  private val quality = PhotoQualityAnalyzer()

  fun analyze(
    bitmap: Bitmap,
    faces: List<PhotoSubject>,
    detectObjects: (MPImage) -> List<PhotoSubject>,
  ): PhotoObservation = BitmapImageBuilder(bitmap).build().use { image ->
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val subjects = (if (faces.isNotEmpty()) faces else detectObjects(image)).toMutableList()
    if (faces.isEmpty() && (subjects.isEmpty() || subjects.all { it.key == "object:book" })) {
      val document = try { scanDocument(bitmap) }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
      document?.observedCorners?.let { corners ->
        val box = PhotoBox(corners.minOf { it.x }.toFloat() / width,
          corners.minOf { it.y }.toFloat() / height,
          corners.maxOf { it.x }.toFloat() / width, corners.maxOf { it.y }.toFloat() / height)
        subjects.removeAll { it.key == "object:book" && it.box.overlaps(box) >= .3f }
        subjects.add(PhotoSubject("document", documentLabel, box,
          PhotoSubjectKind.DOCUMENT, documentState = document.state))
      }
    }
    quality.analyze(bitmap, subjects)
  }
}
