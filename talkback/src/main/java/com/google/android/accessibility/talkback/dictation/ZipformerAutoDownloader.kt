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

package com.google.android.accessibility.talkback.dictation

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles

/**
 * Ensures the offline Vietnamese Zipformer dictation model is installed before recording. When
 * missing, starts downloading automatically (matching offline translation's first-use flow),
 * announces download status, and announces readiness when finished without auto-opening the mic.
 */
internal class ZipformerAutoDownloader(
  private val isInstalled: () -> Boolean,
  private val currentState: () -> ZipformerModels.State,
  private val percentOf: (ZipformerModels.State.Downloading) -> Int,
  private val startDownload: () -> Unit,
  private val addListener: (() -> Unit) -> Unit,
  private val removeListener: (() -> Unit) -> Unit,
  private val speak: (String) -> Unit,
  private val strings: Strings,
) {
  data class Strings(
    val autoDownloading: String,
    val downloadingProgress: (Int) -> String,
    val downloadReady: String,
    val downloadLowSpace: String,
    val downloadFailed: String,
  )

  private var activeListener: (() -> Unit)? = null

  /**
   * Returns {@code true} if the model is already installed and recording may proceed. Otherwise
   * triggers or reports the background download and returns {@code false}.
   */
  fun ensureInstalledOrDownload(): Boolean {
    if (isInstalled()) return true
    val state = currentState()
    if (state is ZipformerModels.State.Downloading) {
      attachCompletionListener()
      speak(strings.downloadingProgress(percentOf(state)))
      return false
    }
    attachCompletionListener()
    startDownload()
    if (currentState() is ZipformerModels.State.Downloading) {
      speak(strings.autoDownloading)
    }
    return false
  }

  fun detach() {
    activeListener?.let {
      removeListener(it)
      activeListener = null
    }
  }

  private fun attachCompletionListener() {
    if (activeListener != null) return
    val listener: () -> Unit = {
      when (val next = currentState()) {
        is ZipformerModels.State.Downloading -> {}
        is ZipformerModels.State.Idle -> {
          detach()
          if (isInstalled()) {
            speak(strings.downloadReady)
          }
        }
        is ZipformerModels.State.Failed -> {
          detach()
          val msg =
            if (next.failure == ModelFiles.Failure.LOW_SPACE) strings.downloadLowSpace
            else strings.downloadFailed
          speak(msg)
        }
      }
    }
    activeListener = listener
    addListener(listener)
  }

  companion object {
    fun create(context: Context, speak: (String) -> Unit): ZipformerAutoDownloader {
      val models = ZipformerModels.get(context)
      return ZipformerAutoDownloader(
        isInstalled = { models.isInstalled() },
        currentState = { models.state },
        percentOf = { models.percent(it) },
        startDownload = { models.startDownload() },
        addListener = { models.addListener(it) },
        removeListener = { models.removeListener(it) },
        speak = speak,
        strings =
          Strings(
            autoDownloading = context.getString(R.string.dictation_zipformer_auto_downloading),
            downloadingProgress = { pct ->
              context.getString(R.string.dictation_zipformer_downloading_progress, pct)
            },
            downloadReady = context.getString(R.string.dictation_zipformer_download_ready),
            downloadLowSpace = context.getString(R.string.dictation_zipformer_download_low_space),
            downloadFailed = context.getString(R.string.dictation_zipformer_download_failed),
          ),
      )
    }
  }
}
