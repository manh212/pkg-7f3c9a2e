package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrencyModelAssetTest {
  @Test fun currencyUsesTheVerifiedDownloadInsteadOfAnApkAsset() {
    val context = RuntimeEnvironment.getApplication()
    OfflineModels.file(context, OfflineModels.currency).delete()
    assertFalse(CurrencyRecognizer.isAvailable(context))
    assertFalse(context.assets.list("").orEmpty().contains("vn_currency_yolo11n.tflite"))
    assertEquals(3_027_064L, OfflineModels.currency.size)
    assertEquals("b3d3727f9ca1fb41789071e5590f7677bca83e9dd61505b322580c52251fddd9", OfflineModels.currency.sha256)
    assertEquals(
      "https://raw.githubusercontent.com/manh212/meridian-data/master/models/vn_currency_yolo11n.tflite",
      OfflineModels.currency.url)
  }
}
