package com.google.android.accessibility.talkback.recognition

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal class GuidedPhotoOutput private constructor(
  private val context: Context,
  internal val uri: Uri?,
  internal val file: File?,
  internal val stream: OutputStream?,
) {
  fun options(frontCamera: Boolean): ImageCapture.OutputFileOptions {
    val builder = if (stream != null) ImageCapture.OutputFileOptions.Builder(stream)
      else ImageCapture.OutputFileOptions.Builder(requireNotNull(file))
    return builder.setMetadata(ImageCapture.Metadata().apply {
      isReversedHorizontal = frontCamera
    }).build()
  }

  fun complete() {
    stream?.close()
    if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
      if (context.contentResolver.update(uri, values, null, null) != 1) {
        throw IOException("Photo publication failed")
      }
    } else {
      val saved = requireNotNull(file)
      if (!saved.isFile || saved.length() == 0L) throw IOException("Empty captured photo")
      MediaScannerConnection.scanFile(context, arrayOf(saved.absolutePath), arrayOf("image/jpeg"), null)
    }
  }

  fun discard() {
    runCatching { stream?.close() }
    runCatching {
      if (uri != null) context.contentResolver.delete(uri, null, null)
      else file?.delete()
    }
  }

  companion object {
    fun prepare(context: Context): GuidedPhotoOutput {
      val name = "Meridian-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) +
        "-" + UUID.randomUUID().toString().take(8) + ".jpg"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, name)
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Meridian")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: throw IOException("Cannot create photo")
        try {
          val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open photo")
          return GuidedPhotoOutput(context.applicationContext, uri, null, stream)
        } catch (error: Exception) {
          runCatching { resolver.delete(uri, null, null) }
          throw error
        }
      }
      val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Meridian")
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create photo directory")
      return GuidedPhotoOutput(context.applicationContext, null, File(directory, name), null)
    }
  }
}
