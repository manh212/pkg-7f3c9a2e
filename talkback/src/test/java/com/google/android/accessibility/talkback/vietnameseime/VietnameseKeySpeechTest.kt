package com.google.android.accessibility.talkback.vietnameseime

import android.os.Looper
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeySpeechTest {
  private data class Request(val label: String, val started: () -> Unit, val completed: (Int) -> Unit)

  private val requests = mutableListOf<Request>()
  private var voiceBusy = false
  private val speech = VietnameseKeySpeech({ label, started, completed ->
    if (voiceBusy) false else requests.add(Request(label, started, completed))
  }, {})

  @Test
  fun releaseReplacesUnelapsedExploreDelayWithoutDuplicatingStartedSpeech() {
    speech.explore("a", 200)
    advance(20)
    speech.release(30)
    advance(29)
    assertEquals(emptyList<String>(), labels())
    advance(1)
    requests.single().started()
    advance(200)

    assertEquals(listOf("a"), labels())
  }

  @Test
  fun releaseDoesNotRepeatSpeechThatAlreadyStartedOnTouch() {
    speech.explore("a", 0)
    requests.single().started()
    speech.release(30)
    advance(100)

    assertEquals(listOf("a"), labels())
  }

  @Test
  fun interruptedBeforeStartIsRetriedAfterRelease() {
    speech.explore("a", 0)
    speech.release(0)
    requests.single().completed(STATUS_INTERRUPTED)
    advance(80)

    assertEquals(listOf("a", "a"), labels())
  }

  @Test
  fun keepsOnlyLatestKeyWhileWaitingForOtherSpeech() {
    voiceBusy = true
    speech.explore("a", 0)
    speech.release(0)
    speech.explore("b", 0)
    speech.release(0)
    voiceBusy = false
    advance(80)

    assertEquals(listOf("b"), labels())
  }

  @Test
  fun cancellationDropsPendingSpeech() {
    speech.explore("a", 200)
    speech.release(100)
    speech.cancel(stopSpeech = true)
    advance(200)

    assertEquals(emptyList<String>(), labels())
  }

  private fun labels() = requests.map { it.label }

  private fun advance(milliseconds: Long) =
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
}
