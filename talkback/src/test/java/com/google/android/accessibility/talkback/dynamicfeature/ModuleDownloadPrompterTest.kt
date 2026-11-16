/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.dynamicfeature

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.DownloadDialogResources
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.ImageCaptionPreferenceKeys
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.UninstallDialogResources
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType
import com.google.android.accessibility.talkback.imagecaption.Request
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleDownloadPrompterTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val fake = FakeDownloader()
  private val listener = RecordingDownloadStateListener()

  private fun prompter(legacy: Downloader? = null): TestPrompter {
    val prompter = TestPrompter(context, fake, legacy)
    prompter.setDownloadStateListener(listener)
    return prompter
  }

  private fun setPref(keyResId: Int, value: Boolean) {
    prefs.edit().putBoolean(context.getString(keyResId), value).apply()
  }

  private fun setShownTimes(times: Int) {
    prefs.edit()
      .putInt(context.getString(ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.downloadShownTimesKey), times)
      .apply()
  }

  @Test
  fun isModuleAvailableTrueWhenDownloaderReportsInstalled() {
    fake.installed["module_IMAGE_DESCRIPTION"] = true

    assertTrue(prompter().isModuleAvailable)
  }

  @Test
  fun isModuleAvailableFalseWhenNotInstalled() {
    assertFalse(prompter().isModuleAvailable)
  }

  @Test
  fun isLegacyModuleAvailableFalseWithoutLegacyDownloader() {
    fake.installed["module_IMAGE_DESCRIPTION"] = true

    assertFalse(prompter().isLegacyModuleAvailable)
  }

  @Test
  fun isLegacyModuleAvailableTrueWhenLegacyReportsInstalled() {
    val legacy = FakeDownloader()
    legacy.installed["module_IMAGE_DESCRIPTION"] = true

    assertTrue(prompter(legacy).isLegacyModuleAvailable)
  }

  @Test
  fun isModuleDownloadingReflectsDownloader() {
    fake.downloading["module_IMAGE_DESCRIPTION"] = true

    assertTrue(prompter().isModuleDownloading)
  }

  @Test
  fun isUninstalledReadsPreference() {
    setPref(ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.uninstalledKey, true)

    assertTrue(prompter().isUninstalled)
  }

  @Test
  fun needDownloadDialogFalseWhenModuleAvailable() {
    fake.installed["module_IMAGE_DESCRIPTION"] = true

    assertFalse(prompter().needDownloadDialog(ModuleDownloadPrompter.Requester.SETTINGS))
  }

  @Test
  fun needDownloadDialogFalseWhenDoNotShowAgain() {
    setPref(ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.doNotShowKey, true)

    assertFalse(prompter().needDownloadDialog(ModuleDownloadPrompter.Requester.MENU))
  }

  @Test
  fun needDownloadDialogFalseAfterThreeShownTimes() {
    setShownTimes(3)

    assertFalse(prompter().needDownloadDialog(ModuleDownloadPrompter.Requester.MENU))
  }

  @Test
  fun needDownloadDialogTrueOtherwise() {
    setShownTimes(2)

    assertTrue(prompter().needDownloadDialog(ModuleDownloadPrompter.Requester.MENU))
  }

  @Test
  fun installModuleDelegatesToDownloader() {
    val prompter = prompter()

    assertTrue(prompter.installModule())
    assertEquals(listOf(CaptionType.IMAGE_DESCRIPTION), fake.installedModules)
  }

  @Test
  fun returnFeedbackFalseWithoutPipeline() {
    val prompter = prompter()

    assertFalse(prompter.callReturnFeedback(Feedback.initializeIconDetection()))
  }

  @Test
  fun returnFeedbackTrueWithPipeline() {
    val prompter = prompter()
    val pipeline = FakePipeline()
    prompter.setPipeline(pipeline)

    assertTrue(prompter.callReturnFeedback(Feedback.initializeIconDetection()))
    assertEquals(1, pipeline.feedbackCount)
  }

  @Test
  fun installedStateMarksPrefsInitializesModuleAndNotifies() {
    val prompter = prompter()
    prompter.setPipeline(FakePipeline())
    fake.listener?.onStateUpdate(
      Downloader.DownloadState.create(
        ImmutableList.of("module_IMAGE_DESCRIPTION"),
        Downloader.DownloadStatus.INSTALLED,
        Downloader.DownloadErrorCode.NO_ERROR, 0L, 0L))

    assertTrue(prefs.getBoolean(context.getString(
      ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.installedKey), false))
    assertFalse(prefs.getBoolean(context.getString(
      ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.uninstalledKey), true))
    assertEquals(1, prompter.initCalls)
    assertEquals(1, listener.installed)
  }

  @Test
  fun failedStateNotifiesWithMappedErrorCode() {
    prompter()
    fake.listener?.onStateUpdate(
      Downloader.DownloadState.create(
        ImmutableList.of("module_IMAGE_DESCRIPTION"),
        Downloader.DownloadStatus.FAILED,
        Downloader.DownloadErrorCode.NETWORK_ERROR, 0L, 0L))

    assertEquals(Request.ERROR_NETWORK_ERROR, listener.failedCode)
  }

  @Test
  fun failedStateMapsInsufficientStorage() {
    prompter()
    fake.listener?.onStateUpdate(
      Downloader.DownloadState.create(
        ImmutableList.of("module_IMAGE_DESCRIPTION"),
        Downloader.DownloadStatus.FAILED,
        Downloader.DownloadErrorCode.INSUFFICIENT_STORAGE, 0L, 0L))

    assertEquals(Request.ERROR_INSUFFICIENT_STORAGE, listener.failedCode)
  }

  @Test
  fun stateForAnotherModuleIsIgnored() {
    val prompter = prompter()
    fake.listener?.onStateUpdate(
      Downloader.DownloadState.create(
        ImmutableList.of("module_ICON_LABEL"),
        Downloader.DownloadStatus.INSTALLED,
        Downloader.DownloadErrorCode.NO_ERROR, 0L, 0L))

    assertFalse(prefs.getBoolean(context.getString(
      ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION.installedKey), false))
    assertEquals(0, prompter.initCalls)
    assertEquals(0, listener.installed)
  }

  @Test
  fun shutdownUnregistersListener() {
    val prompter = prompter()
    prompter.shutdown()

    assertTrue(fake.unregisterCalled)
    assertEquals(null, fake.listener)
  }

  private class TestPrompter(
    context: Context,
    downloader: Downloader,
    legacy: Downloader?,
  ) : ModuleDownloadPrompter(
    context, downloader, legacy,
    ImmutableList.of(CaptionType.IMAGE_DESCRIPTION),
    ImageCaptionPreferenceKeys.IMAGE_DESCRIPTION,
    DownloadDialogResources.IMAGE_DESCRIPTION,
    UninstallDialogResources.IMAGE_DESCRIPTION,
  ) {
    var initCalls = 0

    override fun getDownloadSuccessfulHint(): Int =
      R.string.download_image_description_successful_hint

    override fun getDownloadingHint(): Int = R.string.downloading_image_description_hint

    override fun initModule(): Boolean {
      initCalls++
      return returnFeedback(Feedback.initializeIconDetection())
    }

    fun callReturnFeedback(feedback: Feedback.Part.Builder): Boolean = returnFeedback(feedback)
  }

  private class FakeDownloader : Downloader {
    val installed = mutableMapOf<String, Boolean>()
    val downloading = mutableMapOf<String, Boolean>()
    var listener: Downloader.DownloadStateUpdateListener? = null
    var unregisterCalled = false
    var installResult = true
    val installedModules = mutableListOf<CaptionType>()

    override fun getModuleName(captionType: CaptionType): String = "module_" + captionType.name

    override fun download(context: Context, vararg name: String) {}

    override fun uninstall(context: Context, vararg name: String) {}

    override fun isDownloading(name: String): Boolean = downloading[name] == true

    override fun isInstalled(name: String): Boolean = installed[name] == true

    override fun registerListener(listener: Downloader.DownloadStateUpdateListener) {
      this.listener = listener
    }

    override fun unregisterListener(listener: Downloader.DownloadStateUpdateListener) {
      if (this.listener === listener) this.listener = null
      unregisterCalled = true
    }

    override fun getDownloadStatus(name: String): Downloader.DownloadStatus? = null

    override fun updateDownloadStatus(name: String, status: Downloader.DownloadStatus) {}

    override fun updateAllDownloadStatus() {}

    override fun install(context: Context, captionType: CaptionType): Boolean {
      installedModules.add(captionType)
      return installResult
    }

    override fun initialize(context: Context) {}
  }

  private class RecordingDownloadStateListener : ModuleDownloadPrompter.DownloadStateListener {
    var installed = 0
    var failedCode: Int? = null
    var accepted = 0
    var rejected = 0
    var dismissed = 0

    override fun onInstalled() {
      installed++
    }

    override fun onFailed(errorCode: Int) {
      failedCode = errorCode
    }

    override fun onAccepted() {
      accepted++
    }

    override fun onRejected() {
      rejected++
    }

    override fun onDialogDismissed(queuedNode: AccessibilityNodeInfoCompat?) {
      dismissed++
    }
  }

  private class FakePipeline : Pipeline.FeedbackReturner {
    var feedbackCount = 0

    override fun returnFeedback(feedback: Feedback): Boolean {
      feedbackCount++
      return true
    }
  }
}
