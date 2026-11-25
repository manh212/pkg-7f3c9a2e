package com.google.android.accessibility.talkback.dictation

import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerAutoDownloaderTest {

  private val strings =
    ZipformerAutoDownloader.Strings(
      autoDownloading = "auto-downloading",
      downloadingProgress = { pct -> "downloading-$pct%" },
      downloadReady = "ready",
      downloadLowSpace = "low-space",
      downloadFailed = "failed",
    )

  @Test
  fun installedModelReturnsTrueWithoutDownloadingOrSpeaking() {
    val spoken = mutableListOf<String>()
    var downloadsStarted = 0
    val downloader =
      ZipformerAutoDownloader(
        isInstalled = { true },
        currentState = { ZipformerModels.State.Idle },
        percentOf = { 0 },
        startDownload = { downloadsStarted++ },
        addListener = {},
        removeListener = {},
        speak = { spoken.add(it) },
        strings = strings,
      )

    assertTrue(downloader.ensureInstalledOrDownload())
    assertEquals(0, downloadsStarted)
    assertTrue(spoken.isEmpty())
  }

  @Test
  fun missingModelAutoStartsDownloadAndAnnouncesReadyOnCompletionWithoutStartingMic() {
    var installed = false
    var state: ZipformerModels.State = ZipformerModels.State.Idle
    val listeners = mutableSetOf<() -> Unit>()
    val spoken = mutableListOf<String>()
    var downloadsStarted = 0

    val downloader =
      ZipformerAutoDownloader(
        isInstalled = { installed },
        currentState = { state },
        percentOf = { 42 },
        startDownload = {
          downloadsStarted++
          state = ZipformerModels.State.Downloading(1024L)
          listeners.toList().forEach { it() }
        },
        addListener = { listeners.add(it) },
        removeListener = { listeners.remove(it) },
        speak = { spoken.add(it) },
        strings = strings,
      )

    assertFalse(downloader.ensureInstalledOrDownload())
    assertEquals(1, downloadsStarted)
    assertEquals(listOf("auto-downloading"), spoken)

    // Tapping again while downloading reports progress without starting a second download.
    assertFalse(downloader.ensureInstalledOrDownload())
    assertEquals(1, downloadsStarted)
    assertEquals(listOf("auto-downloading", "downloading-42%"), spoken)

    // Completing download announces ready and detaches listener.
    installed = true
    state = ZipformerModels.State.Idle
    listeners.toList().forEach { it() }
    assertEquals(listOf("auto-downloading", "downloading-42%", "ready"), spoken)
    assertTrue(listeners.isEmpty())
  }

  @Test
  fun synchronousLowSpaceFailureAnnouncesLowSpaceOnly() {
    var state: ZipformerModels.State = ZipformerModels.State.Idle
    val listeners = mutableSetOf<() -> Unit>()
    val spoken = mutableListOf<String>()

    val downloader =
      ZipformerAutoDownloader(
        isInstalled = { false },
        currentState = { state },
        percentOf = { 0 },
        startDownload = {
          state = ZipformerModels.State.Failed(ModelFiles.Failure.LOW_SPACE)
          listeners.toList().forEach { it() }
        },
        addListener = { listeners.add(it) },
        removeListener = { listeners.remove(it) },
        speak = { spoken.add(it) },
        strings = strings,
      )

    assertFalse(downloader.ensureInstalledOrDownload())
    assertEquals(listOf("low-space"), spoken)
    assertTrue(listeners.isEmpty())
  }
}
