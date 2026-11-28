package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class GuidedPhotoDialogTest {
  @Test fun opensAndCleansUpWithoutFinishingTheSettingsActivity() {
    val owner = Robolectric.buildActivity(LiveRecognitionActivity::class.java).setup()
    val activity = owner.get()
    val model = OfflineModels.file(activity, OfflineModels.objects)
    model.parentFile!!.mkdirs()
    RandomAccessFile(model, "rw").use { it.setLength(OfflineModels.objects.size) }
    var closed = 0
    val photo = GuidedPhotoDialog(activity) { closed++ }
    photo.show()
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    assertFalse(activity.isFinishing)
    photo.dismiss()
    photo.dismiss()
    assertEquals(1, closed)
    assertFalse(activity.isFinishing)
    model.delete()
    owner.pause().stop().destroy()
  }

  @Test fun cleanupAlsoWorksBeforeTheWindowHasOpened() {
    val owner = Robolectric.buildActivity(LiveRecognitionActivity::class.java).setup()
    var closed = 0
    val photo = GuidedPhotoDialog(owner.get()) { closed++ }
    photo.dismiss()
    photo.dismiss()
    assertEquals(1, closed)
    owner.pause().stop().destroy()
  }
}
