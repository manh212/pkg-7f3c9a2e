package com.google.android.accessibility.talkback.recognition

import com.google.android.accessibility.talkback.R
import org.junit.Assert.*
import org.junit.Test

class GuidedPhotoControllerTest {
  private val face = PhotoSubject("face:1", "khuôn mặt", PhotoBox(.3f, .25f, .7f, .75f), PhotoSubjectKind.FACE)
  private fun frame(vararg subjects: PhotoSubject = arrayOf(face)) =
    PhotoObservation(subjects.toList(), 150f, 100f, 0f, false)

  private class Run(val controller: GuidedPhotoController = GuidedPhotoController()) {
    var now = 0L
    val events = mutableListOf<Pair<Long, GuidedPhotoController.Signal>>()
    fun sample(observation: PhotoObservation, acknowledge: Boolean = true): List<GuidedPhotoController.Signal> {
      val result = controller.observe(observation, now)
      result.forEach { signal ->
        events.add(now to signal)
        if (acknowledge && signal is GuidedPhotoController.Signal.Say) {
          controller.started(signal.ticket, now)
          controller.spoken(signal.ticket, true)
        }
      }
      now += 250
      return result
    }
    fun sayings() = events.mapNotNull { it.second as? GuidedPhotoController.Signal.Say }
    fun captures() = events.filter { it.second == GuidedPhotoController.Signal.Capture }
    fun two() = sayings().last { it.message == R.string.guided_photo_two }
  }

  @Test fun prioritizesAllFacesOverCenteredObjectsAndFocusesOnAnActualFace() {
    val left = face.copy(box = PhotoBox(.02f, .2f, .22f, .5f))
    val right = face.copy(key = "face:2", box = PhotoBox(.75f, .2f, .95f, .5f))
    val objectInCenter = face.copy(key = "object:dog", kind = PhotoSubjectKind.OBJECT)
    val selected = GuidedPhotoController.select(listOf(left, objectInCenter, right))!!
    assertEquals(2, selected.faceCount)
    assertEquals(PhotoBox(.02f, .2f, .95f, .5f), selected.box)
    assertTrue(selected.focusBox in listOf(left.box, right.box))
    assertNull(GuidedPhotoController.select(listOf(face.copy(confidence = Float.NaN))))
    assertNull(GuidedPhotoController.select(listOf(face.copy(box = PhotoBox(Float.NaN, 0f, 1f, 1f)))))
  }

  @Test fun groupFacesOffCenterAndTrackingIdChurnStillCaptureOnce() {
    val run = Run()
    repeat(30) { index ->
      run.sample(frame(face.copy(key = "face:$index", box = PhotoBox(.03f, .1f, .2f, .4f)),
        face.copy(key = "face:${index + 50}", box = PhotoBox(.65f, .4f, .9f, .8f))))
    }
    assertEquals(listOf(R.string.guided_photo_found_faces, R.string.guided_photo_two, R.string.guided_photo_one),
      run.sayings().map { it.message })
    assertEquals(2, run.sayings().first().number)
    val start = run.events.single { it.second == run.two() }.first
    assertEquals(2_000L, run.captures().single().first - start)
  }

  @Test fun acceptsModerateDriftAndNoAutofocusSuccessWhenSubjectIsSharp() {
    val run = Run()
    repeat(25) { index ->
      val shift = (index % 10) * .006f
      run.sample(frame(face.copy(box = PhotoBox(.3f + shift, .25f, .7f + shift, .75f), facingCamera = false)))
    }
    assertEquals(1, run.captures().size)
    assertFalse(run.events.any { it.second == GuidedPhotoController.Signal.CancelCountdown })
  }

  @Test fun countdownUsesActualSpeechStartAndWaitsForBothCompletions() {
    val run = Run()
    repeat(3) { run.sample(frame()) }
    repeat(12) { run.sample(frame(), acknowledge = false) }
    val two = run.two()
    assertFalse(run.sayings().any { it.message == R.string.guided_photo_one })
    val actualStart = run.now
    run.controller.started(two.ticket, actualStart)
    run.controller.spoken(two.ticket, true)
    repeat(8) { run.sample(frame(), acknowledge = false) }
    assertTrue(run.captures().isEmpty())
    val one = run.sayings().single { it.message == R.string.guided_photo_one }
    run.controller.spoken(one.ticket, true)
    run.sample(frame())
    assertEquals(2_000L, run.captures().single().first - actualStart)
  }

  @Test fun waitsForSubjectAnnouncementBeforeCounting() {
    val run = Run()
    repeat(12) { run.sample(frame(), acknowledge = false) }
    assertEquals(1, run.sayings().size)
    run.controller.spoken(run.sayings().single().ticket, true)
    repeat(16) { run.sample(frame()) }
    assertEquals(1, run.captures().size)
  }

  @Test fun oneMissedFrameAndBriefQualityDipDoNotRestartTheCount() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    assertFalse(run.sample(frame().copy(movement = 100f)).contains(GuidedPhotoController.Signal.CancelCountdown))
    assertFalse(run.sample(frame(*emptyArray())).contains(GuidedPhotoController.Signal.CancelCountdown))
    repeat(12) { run.sample(frame()) }
    assertEquals(1, run.captures().size)
    assertEquals(1, run.sayings().count { it.message == R.string.guided_photo_two })
  }

  @Test fun missingLastFrameOrBadLastFrameNeverTriggersTheShutter() {
    for (bad in listOf(frame(*emptyArray()), frame().copy(brightness = 0f), frame().copy(ageMs = 3_000))) {
      val run = Run()
      repeat(11) { run.sample(frame()) }
      assertTrue(run.captures().isEmpty())
      assertFalse(run.sample(bad).contains(GuidedPhotoController.Signal.Capture))
    }
  }

  @Test fun sustainedClippingCancelsAndSpeaksSpecificDirectionsAfterDebounce() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val clipped = face.copy(box = PhotoBox(-.01f, .25f, .65f, .75f))
    repeat(4) { run.sample(frame(clipped)) }
    assertTrue(run.events.any { it.second == GuidedPhotoController.Signal.CancelCountdown })
    assertTrue(run.events.any { it.second == GuidedPhotoController.Signal.Guide(PhotoGuidance.LEFT) })
    repeat(15) { run.sample(frame()) }
    assertEquals(1, run.captures().size)
    assertEquals(2, run.sayings().count { it.message == R.string.guided_photo_two })
  }

  @Test fun sustainedGroupMembershipChangeRequiresNewAnnouncement() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val other = face.copy(key = "face:2", box = PhotoBox(.72f, .25f, .95f, .6f))
    repeat(20) { run.sample(frame(face, other)) }
    assertEquals(listOf(R.string.guided_photo_found, R.string.guided_photo_found_faces),
      run.sayings().filter { it.message in listOf(R.string.guided_photo_found, R.string.guided_photo_found_faces) }.map { it.message })
    assertEquals(1, run.captures().size)
  }

  @Test fun staleAnalysisAndLargeFrameGapsCannotFinishOldCountdown() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    repeat(4) { run.sample(frame().copy(ageMs = 2_001)) }
    assertTrue(run.captures().isEmpty())
    assertTrue(run.events.any { it.second == GuidedPhotoController.Signal.CancelCountdown })
    repeat(5) { run.sample(frame()) }
    run.now += 3_001
    assertTrue(run.sample(frame()).contains(GuidedPhotoController.Signal.CancelCountdown))
    assertTrue(run.captures().isEmpty())
  }

  @Test fun darkBlurAndStrongMotionBlockButPoseAndPerspectiveDoNot() {
    for (bad in listOf(frame().copy(brightness = 10f), frame().copy(sharpness = 0f),
      frame().copy(movement = 50f), frame().copy(movement = Float.NaN))) {
      val run = Run()
      repeat(20) { run.sample(bad) }
      assertTrue(run.captures().isEmpty())
      assertFalse(run.sayings().any { it.message == R.string.guided_photo_two })
    }
    val document = face.copy(kind = PhotoSubjectKind.DOCUMENT, documentState = DocumentAlignmentState.HOLD_FLAT)
    assertNull(GuidedPhotoController.guidance(document, frame(document), false))
    assertNull(GuidedPhotoController.guidance(face.copy(facingCamera = false), frame(), false))
  }

  @Test fun checksEveryFaceRegionInsteadOfBackgroundOrOnlyLargestFace() {
    val other = face.copy(key = "face:2", box = PhotoBox(.72f, .25f, .95f, .6f))
    val group = GuidedPhotoController.select(listOf(face, other))!!
    val clear = PhotoRegionQuality(face.box, 100f, 100f, 0f, 100, 100)
    val poor = clear.copy(box = other.box, sharpness = 0f)
    assertEquals(PhotoGuidance.BLUR, GuidedPhotoController.guidance(group,
      frame(face, other).copy(regions = listOf(clear, poor)), false))
    assertEquals(PhotoGuidance.PROCESSING, GuidedPhotoController.guidance(group,
      frame(face, other).copy(regions = listOf(clear)), false))
  }

  @Test fun stickyObjectSelectionDoesNotJumpBetweenNearbyItems() {
    val cup = face.copy(key = "object:cup", kind = PhotoSubjectKind.OBJECT, box = PhotoBox(.2f, .2f, .5f, .6f))
    val apple = cup.copy(key = "object:apple", box = PhotoBox(.4f, .4f, .7f, .8f))
    assertEquals(cup, GuidedPhotoController.select(listOf(apple, cup), cup))
  }

  @Test fun frontCameraReversesHorizontalClippingGuidanceOnly() {
    val left = face.copy(box = PhotoBox(-.01f, .25f, .4f, .75f))
    assertEquals(PhotoGuidance.LEFT, GuidedPhotoController.guidance(left, frame(left), false))
    assertEquals(PhotoGuidance.RIGHT, GuidedPhotoController.guidance(left, frame(left), true))
    val high = face.copy(box = PhotoBox(.3f, -.01f, .7f, .4f))
    assertEquals(PhotoGuidance.UP, GuidedPhotoController.guidance(high, frame(high), true))
    assertNull(GuidedPhotoController.guidance(face.copy(box = PhotoBox(.02f, .1f, .25f, .4f)), frame(), false))
  }

  @Test fun resetIgnoresLateSpeechCallbacksAndAllowsExactlyOneNewPhoto() {
    val run = Run()
    repeat(6) { run.sample(frame()) }
    val old = run.two().ticket
    run.controller.reset()
    repeat(6) { run.sample(frame()) }
    run.controller.started(old, run.now)
    run.controller.spoken(old, false)
    repeat(25) { run.sample(frame()) }
    assertEquals(1, run.captures().size)
  }
}
