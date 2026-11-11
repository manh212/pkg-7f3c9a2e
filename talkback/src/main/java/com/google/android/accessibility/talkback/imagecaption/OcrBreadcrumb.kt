/*
 * Copyright (C) 2026 The Meridian contributors
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

package com.google.android.accessibility.talkback.imagecaption

import android.content.Context
import java.io.File

/**
 * The stage the OCR process most recently entered, kept in a small file both processes can reach.
 *
 * The OCR engine runs native libraries in the `:ocr` process. If one of them kills that process,
 * nothing there can report where it died, so the engine notes each stage here before entering it.
 * When a recognition call loses its process, [RemoteOcr] reads the note back and logs it, which
 * tells later diagnostics which native stage was running. Marking the stage already marked costs
 * nothing, so hot paths may mark freely.
 */
object OcrBreadcrumb {
  const val STAGE_DETECTOR_CREATE = "detector-create"
  const val STAGE_TESSERACT_INIT = "tesseract-init"
  const val STAGE_READY = "ready"
  const val STAGE_OPENCV = "opencv-convert"
  const val STAGE_DETECT = "detect"
  const val STAGE_RECOGNIZE = "recognize-lines"
  const val STAGE_DONE = "done"

  private const val FILE_NAME = "ocr-stage.txt"

  @Volatile private var lastMarked: String? = null

  @JvmStatic
  fun mark(context: Context, stage: String) {
    if (stage == lastMarked) return
    lastMarked = stage
    try {
      file(context).writeText(stage)
    } catch (_: Exception) {
      // The note is best-effort diagnostics; recognition must never fail over it.
    }
  }

  /** The last stage noted, or null when no note can be read. */
  @JvmStatic
  fun lastStage(context: Context): String? =
    try {
      file(context).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
      null
    }

  private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
