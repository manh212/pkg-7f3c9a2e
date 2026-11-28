package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.R
import org.junit.Assert.*
import org.junit.Test

class GuidedPhotoControllerTest {
  private val centered = PhotoSubject("face:1", "khuôn mặt", PhotoBox(.3f, .25f, .7f, .75f), PhotoSubjectKind.FACE)
  private fun frame(subject: PhotoSubject? = centered) =
    PhotoObservation(listOfNotNull(subject), 150f, 100f, 0f, true)

  private class Run(val controller: GuidedPhotoController = GuidedPhotoController()) {
    var now = 0L
    val events = mutableListOf<Pair<Long, GuidedPhotoController.Signal>>()
    fun sample(observation: PhotoObservation, acknowledge: Boolean = true): List<GuidedPhotoController.Signal> {
      val result = controller.observe(observation, now)
      result.forEach { signal ->
        events.add(now to signal)
        if (acknowledge && signal is GuidedPhotoController.Signal.Say) controller.spoken(signal.ticket, true)
      }
      now += 250
      return result
    }
  }

  @Test fun choosesTheCenteredSubjectInsteadOfTheMostConfidentOne() {
    val edge = centered.copy(key = "object:dog", box = PhotoBox(.02f, .2f, .25f, .8f), confidence = .99f)
    val middle = centered.copy(confidence = .7f)
    assertEquals(middle, GuidedPhotoController.select(listOf(edge, middle)))
    assertNull(GuidedPhotoController.select(listOf(centered.copy(box = PhotoBox(Float.NaN, 0f, 1f, 1f)))))
    assertNull(GuidedPhotoController.select(listOf(centered.copy(confidence = .2f))))
  }

  @Test fun announcesThenCountsTwoOneAndCapturesOnlyAfterTwoSeconds() {
    val run = Run()
    repeat(30) { run.sample(frame()) }
    val spoken = run.events.mapNotNull { (_, event) -> (event as? GuidedPhotoController.Signal.Say)?.message }
    assertEquals(listOf(R.string.guided_photo_found, R.string.guided_photo_two, R.string.guided_photo_one), spoken)
    val start = run.events.single { (_, e) -> e is GuidedPhotoController.Signal.Say && e.message == R.string.guided_photo_two }.first
    val end = run.events.single { it.second == GuidedPhotoController.Signal.Capture }.first
    assertEquals(2_000L, end - start)
  }

  @Test fun waitsForTheSubjectAnnouncementToActuallyFinishSpeaking() {
    val run = Run()
    repeat(16) { run.sample(frame(), acknowledge = false) }
    assertEquals(1, run.events.count { it.second is GuidedPhotoController.Signal.Say })
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
    val ticket = (run.events.first { it.second is GuidedPhotoController.Signal.Say }.second as GuidedPhotoController.Signal.Say).ticket
    run.controller.spoken(ticket, true)
    repeat(14) { run.sample(frame()) }
    assertEquals(1, run.events.count { it.second == GuidedPhotoController.Signal.Capture })
  }

  @Test fun neverCapturesUntilBothCountdownNumbersHaveBeenSpoken() {
    val run = Run()
    repeat(4) { run.sample(frame()) }
    repeat(20) { run.sample(frame(), acknowledge = false) }
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
    val two = run.events.map { it.second }.filterIsInstance<GuidedPhotoController.Signal.Say>()
      .single { it.message == R.string.guided_photo_two }
    run.controller.spoken(two.ticket, true)
    repeat(8) { run.sample(frame(), acknowledge = false) }
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
    val one = run.events.map { it.second }.filterIsInstance<GuidedPhotoController.Signal.Say>()
      .single { it.message == R.string.guided_photo_one }
    run.controller.spoken(one.ticket, true)
    assertTrue(run.sample(frame()).contains(GuidedPhotoController.Signal.Capture))
  }

  @Test fun leavingTheFrameCancelsTheCountdownAndRestartsTheFullDelay() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val bad = centered.copy(box = PhotoBox(.16f, .25f, .56f, .75f))
    val events = run.sample(frame(bad))
    assertTrue(events.contains(GuidedPhotoController.Signal.CancelCountdown))
    assertTrue(events.any { it is GuidedPhotoController.Signal.Guide && it.guidance == PhotoGuidance.LEFT })
    val returnedAt = run.now
    repeat(15) { run.sample(frame()) }
    val captureAt = run.events.single { it.second == GuidedPhotoController.Signal.Capture }.first
    assertTrue(captureAt - returnedAt >= 2_000)
  }

  @Test fun changingSubjectRequiresANewAnnouncementAndCountdown() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val apple = centered.copy(key = "object:apple", label = "đồ ăn, quả táo", kind = PhotoSubjectKind.OBJECT)
    assertTrue(run.sample(frame(apple)).contains(GuidedPhotoController.Signal.CancelCountdown))
    repeat(15) { run.sample(frame(apple)) }
    val found = run.events.map { it.second }.filterIsInstance<GuidedPhotoController.Signal.Say>()
      .filter { it.message == R.string.guided_photo_found }
    assertEquals(listOf("khuôn mặt", "đồ ăn, quả táo"), found.map { it.argument })
    assertEquals(1, run.events.count { it.second == GuidedPhotoController.Signal.Capture })
  }

  @Test fun missingOrStaleFramesCannotCompleteAnOldCountdown() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    run.now += 1_500
    assertTrue(run.sample(frame()).contains(GuidedPhotoController.Signal.CancelCountdown))
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
    assertTrue(run.sample(frame(null)).any { it is GuidedPhotoController.Signal.Guide })
    repeat(12) { run.sample(frame(null)) }
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
  }

  @Test fun darkBlurredMovingAndUnfocusedFramesPreventCapture() {
    for (bad in listOf(frame().copy(brightness = 10f), frame().copy(sharpness = 0f),
      frame().copy(movement = 20f), frame().copy(focusReady = false))) {
      val run = Run()
      repeat(20) { run.sample(bad) }
      assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
      assertFalse(run.events.any { it.second is GuidedPhotoController.Signal.Say &&
        (it.second as GuidedPhotoController.Signal.Say).message == R.string.guided_photo_two })
    }
  }

  @Test fun cumulativeDriftCancelsEvenWhenEachFrameMovesOnlyALittle() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    var cancelled = false
    for (step in 1..7) {
      val shift = step * .01f
      val moved = centered.copy(box = PhotoBox(.3f + shift, .25f, .7f + shift, .75f))
      val events = run.sample(frame(moved))
      cancelled = events.contains(GuidedPhotoController.Signal.CancelCountdown) || cancelled
    }
    assertTrue(cancelled)
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.Capture })
  }

  @Test fun frontCameraReversesHorizontalGuidanceWithoutReversingVerticalGuidance() {
    val left = centered.copy(box = PhotoBox(.02f, .25f, .4f, .75f))
    assertEquals(PhotoGuidance.LEFT, GuidedPhotoController.guidance(left, frame(left), false))
    assertEquals(PhotoGuidance.RIGHT, GuidedPhotoController.guidance(left, frame(left), true))
    val high = centered.copy(box = PhotoBox(.3f, .01f, .7f, .4f))
    assertEquals(PhotoGuidance.UP, GuidedPhotoController.guidance(high, frame(high), true))
  }

  @Test fun documentGeometryAndFacePoseAreRequiredBeforeCounting() {
    val document = centered.copy(key = "document", kind = PhotoSubjectKind.DOCUMENT,
      documentState = DocumentAlignmentState.HOLD_FLAT)
    assertEquals(PhotoGuidance.FLAT, GuidedPhotoController.guidance(document, frame(document), false))
    val cropped = document.copy(documentState = DocumentAlignmentState.MOVE_RIGHT)
    assertEquals(PhotoGuidance.RIGHT, GuidedPhotoController.guidance(cropped, frame(cropped), false))
    val face = centered.copy(facingCamera = false)
    assertEquals(PhotoGuidance.FACE_FORWARD, GuidedPhotoController.guidance(face, frame(face), false))
  }

  @Test fun resetIgnoresLateSpeechCallbacksAndAllowsExactlyOneNewPhoto() {
    val run = Run()
    repeat(5) { run.sample(frame()) }
    val old = run.events.map { it.second }.filterIsInstance<GuidedPhotoController.Signal.Say>().last().ticket
    run.controller.reset()
    run.controller.spoken(old, true)
    repeat(40) { run.sample(frame()) }
    assertEquals(1, run.events.count { it.second == GuidedPhotoController.Signal.Capture })
  }

  @Test fun interruptedOldSpeechCannotResetANewCountdown() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val old = run.events.map { it.second }.filterIsInstance<GuidedPhotoController.Signal.Say>()
      .last { it.message == R.string.guided_photo_two }.ticket
    run.controller.reset()
    repeat(6) { run.sample(frame()) }
    run.controller.spoken(old, false)
    repeat(7) { run.sample(frame()) }
    assertEquals(1, run.events.count { it.second == GuidedPhotoController.Signal.Capture })
  }
}
