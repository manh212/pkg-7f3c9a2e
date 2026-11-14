package com.google.android.accessibility.talkback.vietnameseime

import android.os.Looper
import android.os.SystemClock
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED
import com.google.android.accessibility.utils.output.SpeechController.STATUS_SPOKEN
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
  private var submitCount = 0
  private var stops = 0
  private val speech = VietnameseKeySpeech({ label, started, completed ->
    submitCount++
    if (voiceBusy) false else requests.add(Request(label, started, completed))
  }, { stops++ })

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
  fun commitReadsTheSameLabelAgainAtTheReleaseDeadline() {
    speech.explore("a", 0)
    requests.single().started()
    requests.single().completed(STATUS_SPOKEN)
    speech.release(80, waitsForCommit = true)
    speech.confirm("a", SystemClock.uptimeMillis() + 80)
    advance(79)
    assertEquals(listOf("a"), labels())

    advance(1)
    assertEquals(listOf("a", "a"), labels())
  }

  @Test
  fun consecutiveEditsKeepTheirOrderWithoutNewExploration() {
    speech.confirm("a", 0)
    speech.confirm("bê", 0)
    speech.confirm("Đã xóa bê", 0)
    assertEquals(listOf("a"), labels())

    requests[0].completed(STATUS_SPOKEN)
    requests[0].completed(STATUS_INTERRUPTED)
    assertEquals(listOf("a", "bê"), labels())
    requests[1].completed(STATUS_SPOKEN)
    assertEquals(listOf("a", "bê", "Đã xóa bê"), labels())
  }

  @Test
  fun newTouchReplacesAnEditWithNoCompletionAndClearsStaleEdits() {
    speech.confirm("a", 0)
    val previous = requests.single()
    previous.started()
    speech.confirm("bê", 0)
    speech.explore("Xóa", 30)
    advance(29)
    assertEquals(listOf("a"), labels())
    assertEquals(1, stops)

    advance(1)
    previous.completed(STATUS_INTERRUPTED)
    assertEquals(listOf("a", "Xóa"), labels())
    speech.release(0, waitsForCommit = true)
    speech.confirm("Đã xóa bê", 0)
    assertEquals(listOf("a", "Xóa", "Đã xóa bê"), labels())
  }

  @Test
  fun editWithNoCompletionExpiresAndAllowsLaterConfirmation() {
    speech.confirm("a", 0)
    advance(200)
    speech.confirm("bê", 0)
    advance(3799)
    assertEquals(listOf("a"), labels())

    advance(1)
    assertEquals(1, stops)
    assertEquals(listOf("a", "bê"), labels())
  }

  @Test
  fun previewSupersedesAnEditWaitingForItsReleaseDelay() {
    speech.confirm("a", SystemClock.uptimeMillis() + 100)
    speech.explore("bê", 0)
    assertEquals(listOf("bê"), labels())

    advance(100)
    assertEquals(listOf("bê"), labels())
  }

  @Test
  fun delayedEditFromPreviousReleaseDoesNotInterruptCurrentTouch() {
    speech.explore("a", 0)
    speech.release(0, waitsForCommit = true)
    speech.explore("bê", 0)
    speech.confirm("a", 0)
    assertEquals(listOf("a", "bê"), labels())

    speech.release(0, waitsForCommit = true)
    speech.confirm("bê", 0)
    assertEquals(listOf("a", "bê", "bê"), labels())
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
  fun interruptedBeforeStartIsRetriedWhileFingerIsStillDown() {
    speech.explore("a", 0)
    requests.single().completed(STATUS_INTERRUPTED)
    advance(80)

    assertEquals(listOf("a", "a"), labels())
  }

  @Test
  fun anotherFingerLiftingDoesNotDelayPendingReleaseSpeech() {
    speech.explore("a", 200)
    speech.release(50)
    advance(25)
    speech.release(50)
    advance(25)

    assertEquals(listOf("a"), labels())
  }

  @Test
  fun completionFromReplacedKeyCannotPreventFinalKeyFromRetrying() {
    speech.explore("a", 0)
    speech.explore("b", 0)
    speech.release(0)
    requests.first().completed(STATUS_INTERRUPTED)
    requests.last().completed(STATUS_INTERRUPTED)
    advance(80)

    assertEquals(listOf("a", "b", "b"), labels())
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
  fun retriesStopAfterCapWhenVoiceNeverAccepts() {
    voiceBusy = true
    speech.explore("a", 0)
    advance(16000)

    assertEquals(126, submitCount)
  }

  @Test
  fun doesNotRetryWhenRetryableIsFalse() {
    val neverRetry = VietnameseKeySpeech({ _, _, _ ->
      submitCount++
      false
    }, {}, { false })
    neverRetry.explore("a", 0)
    advance(500)

    assertEquals(1, submitCount)
  }

  @Test
  fun cancellationDropsPendingSpeech() {
    speech.explore("a", 200)
    speech.release(100)
    speech.cancel()
    advance(200)

    assertEquals(emptyList<String>(), labels())
  }

  private fun labels() = requests.map { it.label }

  private fun advance(milliseconds: Long) =
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
}
