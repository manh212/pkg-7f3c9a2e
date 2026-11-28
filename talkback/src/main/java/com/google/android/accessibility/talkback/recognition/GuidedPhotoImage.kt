package com.google.android.accessibility.talkback.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform

@OptIn(markerClass = [TransformExperimental::class])
internal data class GuidedPhotoImage(val bitmap: Bitmap, val transform: OutputTransform) {
  companion object {
    fun read(image: ImageProxy): GuidedPhotoImage {
      val transform = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = true
      }.getOutputTransform(image)
      val raw = image.toBitmap()
      var cropped: Bitmap? = null
      try {
        val crop = image.cropRect
        cropped = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
        val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val bitmap = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotation, true)
        if (cropped !== bitmap && cropped !== raw) cropped.recycle()
        if (raw !== bitmap) raw.recycle()
        return GuidedPhotoImage(bitmap, transform)
      } catch (error: RuntimeException) {
        if (cropped !== raw) cropped?.recycle()
        raw.recycle()
        throw error
      }
    }

    fun focusPoint(box: PhotoBox, width: Int, height: Int, source: OutputTransform,
      target: OutputTransform): FloatArray = floatArrayOf(box.cx * width, box.cy * height).also {
      CoordinateTransform(source, target).mapPoints(it)
    }
  }
}
