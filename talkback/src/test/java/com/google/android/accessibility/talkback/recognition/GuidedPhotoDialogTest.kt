package com.google.android.accessibility.talkback.recognition

import android.os.Looper
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
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
    var closed = 0
    val photo = GuidedPhotoDialog(activity, requestObjects = {}) { closed++ }
    photo.show()
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    assertFalse(activity.isFinishing)
    photo.dismiss()
    photo.dismiss()
    assertEquals(1, closed)
    assertFalse(activity.isFinishing)
    owner.pause().stop().destroy()
  }

  @Test fun cleanupAlsoWorksBeforeTheWindowHasOpened() {
    val owner = Robolectric.buildActivity(LiveRecognitionActivity::class.java).setup()
    var closed = 0
    val photo = GuidedPhotoDialog(owner.get(), requestObjects = {}) { closed++ }
    photo.dismiss()
    photo.dismiss()
    assertEquals(1, closed)
    owner.pause().stop().destroy()
  }

  @Test fun cameraStartsWithoutObjectDownloadAfterResumeAndFailureKeepsTheScreenOpen() {
    val owner = Robolectric.buildActivity(LiveRecognitionActivity::class.java).create().start()
    val activity = owner.get()
    var requests = 0
    assertFalse(OfflineModels.isReady(activity, OfflineModels.Group.GUIDED_PHOTO))
    var downloads = 0
    val photo = GuidedPhotoDialog(activity, requestObjects = { downloads++ }, serviceReady = { true }, requestProvider = {
      requests++
      throw IllegalStateException("Camera provider unavailable")
    }) {}
    photo.show()
    assertEquals(1, downloads)
    assertEquals(0, requests)
    owner.resume().visible()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(1, requests)
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    assertFalse(activity.isFinishing)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertEquals(1, requests)
    photo.dismiss()
    owner.pause().stop().destroy()
  }
}
