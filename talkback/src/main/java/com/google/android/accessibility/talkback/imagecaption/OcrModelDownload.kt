package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import androidx.lifecycle.MutableLiveData
import java.io.File

internal object OcrModelDownload {
  data class Status(val progress: Int? = null, val failed: Boolean = false)

  val status = MutableLiveData(Status())

  fun update(value: OfflineModels.Status) {
    status.postValue(Status(value.progress, value.failed))
  }

  @JvmStatic fun directory(context: Context) = File(context.filesDir, "offline-ocr-v1")

  @JvmStatic fun detectorFile(context: Context) = OfflineModels.file(context, OfflineModels.detector)

  @JvmStatic fun isReady(context: Context): Boolean =
    OfflineModels.isReady(context, OfflineModels.Group.SCREEN_OCR)

  @JvmStatic fun autoStart(context: Context) {
    if (!OfflineModels.state(OfflineModels.Group.SCREEN_OCR).failed) start(context)
  }

  @JvmStatic fun start(context: Context) = OfflineModels.request(context, OfflineModels.Group.SCREEN_OCR)
}
