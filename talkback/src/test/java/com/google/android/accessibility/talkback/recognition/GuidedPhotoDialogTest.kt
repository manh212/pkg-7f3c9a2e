package com.google.android.accessibility.talkback.recognition

import android.os.Looper
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import java.io.RandomAccessFile
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

  @Test fun installedModelWaitsForResumeAndCameraFailureKeepsTheScreenOpen() {
    val owner = Robolectric.buildActivity(LiveRecognitionActivity::class.java).create().start()
    val activity = owner.get()
    val model = OfflineModels.file(activity, OfflineModels.objects)
    model.parentFile!!.mkdirs()
    RandomAccessFile(model, "rw").use { it.setLength(OfflineModels.objects.size) }
    var requests = 0
    val photo = GuidedPhotoDialog(activity, serviceReady = { true }, requestProvider = {
      requests++
      throw IllegalStateException("Camera provider unavailable")
    }) {}
    photo.show()
    assertEquals(0, requests)
    owner.resume().visible()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(1, requests)
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    assertFalse(activity.isFinishing)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertEquals(1, requests)
    photo.dismiss()
    model.delete()
    owner.pause().stop().destroy()
  }
}
