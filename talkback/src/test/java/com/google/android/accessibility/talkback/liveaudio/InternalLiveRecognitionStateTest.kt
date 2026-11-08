package com.google.android.accessibility.talkback.liveaudio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The subtitle gesture is a true toggle while Android's capture consent is visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternalLiveRecognitionStateTest {
    @After fun reset() { InternalLiveRecognitionService.clearPendingStart() }

    @Test fun consentRequestCanBeCancelledBeforeForegroundServiceStarts() {
        InternalLiveRecognitionService.markPendingStart()
        assertTrue(InternalLiveRecognitionService.hasPendingStart())
        InternalLiveRecognitionService.clearPendingStart()
        assertFalse(InternalLiveRecognitionService.hasPendingStart())
    }

    @Test fun requestStartsDisabledUntilTheUserChoosesOnlineSubtitleRecognition() {
        InternalLiveRecognitionService.clearPendingStart()
        assertFalse(InternalLiveRecognitionService.hasPendingStart())
        InternalLiveRecognitionService.markPendingStart()
        assertTrue(InternalLiveRecognitionService.hasPendingStart())
    }
}
