package com.google.android.accessibility.talkback.recognition

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(markerClass = [TransformExperimental::class])
@SuppressLint("RestrictedApi")
class GuidedPhotoImageTest {
  private fun transform(matrix: Matrix) = OutputTransform(matrix, Size(400, 300))
  private val face = PhotoBox(.1f, .2f, .3f, .4f)

  @Test fun focusUsesOffCenterFaceCoordinatesInsteadOfPreviewCenter() {
    val identity = transform(Matrix())
    assertArrayEquals(floatArrayOf(80f, 90f),
      GuidedPhotoImage.focusPoint(face, 400, 300, identity, identity), .01f)
  }

  @Test fun mirroredPreviewFocusMatchesTheVisibleFace() {
    val source = transform(Matrix())
    val target = transform(Matrix().apply {
      setScale(-1f, 1f)
      postTranslate(400f, 0f)
    })
    assertArrayEquals(floatArrayOf(320f, 90f),
      GuidedPhotoImage.focusPoint(face, 400, 300, source, target), .01f)
  }

  @Test fun rotatedSourceAndScaledPreviewShareTheSameFocusPoint() {
    val sourceMatrix = Matrix().apply { setRotate(90f); postTranslate(300f, 0f) }
    val source = transform(sourceMatrix)
    val target = transform(Matrix().apply { setScale(2f, 2f); postTranslate(10f, 20f) })
    val rotatedFace = PhotoBox(.65f, .1f, .75f, .3f)
    assertArrayEquals(floatArrayOf(170f, 200f),
      GuidedPhotoImage.focusPoint(rotatedFace, 300, 400, source, target), .01f)
  }
}
