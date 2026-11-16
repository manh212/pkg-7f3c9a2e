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
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MddDownloaderTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val downloader = MddDownloader.getInstance(RuntimeEnvironment.getApplication())

  @Test
  fun moduleNamesMatchBundledDownloader() {
    assertEquals(
      "fake_image_description_module_name",
      downloader.getModuleName(CaptionType.IMAGE_DESCRIPTION))
    assertEquals(
      "fake_icon_detection_module_name", downloader.getModuleName(CaptionType.ICON_LABEL))
  }

  @Test
  fun bundledModulesReportInstalled() {
    assertTrue(downloader.isInstalled("fake_image_description_module_name"))
    assertTrue(downloader.isInstalled("fake_icon_detection_module_name"))
  }

  @Test
  fun installReturnsTrue() {
    assertTrue(downloader.install(context, CaptionType.IMAGE_DESCRIPTION))
  }

  @Test
  fun downloadWithNoModuleNamesDoesNotCrash() {
    downloader.download(context)
  }
}
