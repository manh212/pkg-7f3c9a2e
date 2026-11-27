/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.utils.output

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import android.media.AudioManager.MODE_IN_CALL
import android.media.AudioManager.MODE_IN_COMMUNICATION
import android.media.AudioManager.MODE_NORMAL
import com.google.android.accessibility.utils.output.LowLatencyAudio.Companion.isHeadphoneDeviceType
import com.google.android.accessibility.utils.output.LowLatencyAudio.Companion.sendToMedia
import com.google.android.accessibility.utils.output.LowLatencyAudio.Companion.shouldAttemptRecovery
import com.google.android.accessibility.utils.output.LowLatencyAudio.Companion.shouldRecreateForSpeakerFastPath
import com.google.android.accessibility.utils.output.LowLatencyAudio.Companion.shouldRunAudioCalibration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LowLatencyAudioRouteTest {
  @Test
  fun earpieceAfterCallWithEarbuds_goesToEarbuds() {
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_EARPIECE, null, TYPE_BLUETOOTH_A2DP, false))
  }

  @Test
  fun earpieceAfterCallWithoutEarbuds_goesToSpeaker() {
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_EARPIECE, null, TYPE_BUILTIN_SPEAKER, false))
  }

  @Test
  fun trackOnEarpieceWhileAndroidReportsEarbuds_goesToEarbuds() {
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BLUETOOTH_A2DP, TYPE_BUILTIN_EARPIECE, TYPE_BLUETOOTH_A2DP, false))
  }

  @Test
  fun keptAfterTrackLeftTheEarpiece_whileSticky() {
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, true))
  }

  @Test
  fun androidRoutesToMedia_leftAlone() {
    assertFalse(sendToMedia(MODE_NORMAL, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, false))
    assertFalse(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_SPEAKER, null, TYPE_BUILTIN_SPEAKER, false))
  }

  @Test
  fun duringCall_leftAlone() {
    assertFalse(sendToMedia(MODE_IN_CALL, TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_EARPIECE, TYPE_BLUETOOTH_A2DP, true))
    assertFalse(sendToMedia(MODE_IN_COMMUNICATION, TYPE_BLUETOOTH_SCO, null, TYPE_BLUETOOTH_A2DP, false))
  }

  @Test
  fun mediaOnEarpieceOrUnknown_leftAlone() {
    assertFalse(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_EARPIECE, null, TYPE_BUILTIN_EARPIECE, false))
    assertFalse(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_EARPIECE, null, null, true))
  }

  @Test
  fun speakerSafeDuringNotification_goesToFullStereoSpeaker() {
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_SPEAKER_SAFE, null, TYPE_BUILTIN_SPEAKER, false))
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_SPEAKER_SAFE, TYPE_BUILTIN_SPEAKER, false))
    assertTrue(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_SPEAKER_SAFE, null, TYPE_BLUETOOTH_A2DP, false))
    assertFalse(sendToMedia(MODE_NORMAL, TYPE_BUILTIN_SPEAKER_SAFE, null, TYPE_BUILTIN_SPEAKER_SAFE, false))
    assertFalse(sendToMedia(MODE_IN_CALL, TYPE_BUILTIN_SPEAKER_SAFE, null, TYPE_BUILTIN_SPEAKER, false))
  }

  @Test
  fun recoveryBackoff_waitsExponentiallyAndStopsAtCapUnlessForced() {
    // First giveUp: 10s cooldown
    assertFalse(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 10_999L, consecutiveGiveUps = 1, force = false))
    assertTrue(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 11_000L, consecutiveGiveUps = 1, force = false))
    // Second giveUp: 20s cooldown
    assertFalse(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 20_999L, consecutiveGiveUps = 2, force = false))
    assertTrue(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 21_000L, consecutiveGiveUps = 2, force = false))
    // Fourth giveUp: capped at 60s
    assertFalse(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 60_999L, consecutiveGiveUps = 4, force = false))
    assertTrue(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 61_000L, consecutiveGiveUps = 4, force = false))
    // Beyond max consecutive failures: time-based retries stop, but explicit route/call recovery still works
    assertFalse(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 600_000L, consecutiveGiveUps = 6, force = false))
    assertTrue(shouldAttemptRecovery(brokenAtMs = 1_000L, nowMs = 1_001L, consecutiveGiveUps = 6, force = true))
  }

  @Test
  fun audioCalibration_runsOnFirstLaunchAndAfterUpdatesOnly() {
    assertTrue(shouldRunAudioCalibration(null, "100|fp1|48000", 0))
    assertTrue(shouldRunAudioCalibration("100|fp1|48000", "101|fp1|48000", 1))
    assertTrue(shouldRunAudioCalibration("100|fp1|48000", "100|fp2|48000", 1))
    assertTrue(shouldRunAudioCalibration("100|fp1|48000", "100|fp1|48000", 0))
    assertFalse(shouldRunAudioCalibration("100|fp1|48000", "100|fp1|48000", 5))
    assertFalse(shouldRunAudioCalibration("100|fp1|48000", "100|fp1|48000", -1))
  }

  @Test
  fun speakerFastPathRecreate_avoidsLoopDuringExternalMediaAndFreshSpeaker() {
    // Yielding for TikTok/media must not loop recreating AudioTrack
    assertFalse(
      shouldRecreateForSpeakerFastPath(
        fastPath = false,
        everHadFastPath = true,
        previousRoutedType = TYPE_BLUETOOTH_A2DP,
        routedType = TYPE_BUILTIN_SPEAKER,
        yieldingForExternalAudio = true,
      )
    )
    // Starting directly on speaker (null or already speaker) must not loop
    assertFalse(
      shouldRecreateForSpeakerFastPath(
        fastPath = false,
        everHadFastPath = true,
        previousRoutedType = null,
        routedType = TYPE_BUILTIN_SPEAKER,
        yieldingForExternalAudio = false,
      )
    )
    assertFalse(
      shouldRecreateForSpeakerFastPath(
        fastPath = false,
        everHadFastPath = true,
        previousRoutedType = TYPE_BUILTIN_SPEAKER,
        routedType = TYPE_BUILTIN_SPEAKER,
        yieldingForExternalAudio = false,
      )
    )
    // Returning from Bluetooth/earpiece to speaker without fast path SHOULD recreate once
    assertTrue(
      shouldRecreateForSpeakerFastPath(
        fastPath = false,
        everHadFastPath = true,
        previousRoutedType = TYPE_BLUETOOTH_A2DP,
        routedType = TYPE_BUILTIN_SPEAKER,
        yieldingForExternalAudio = false,
      )
    )
  }

  @Test
  fun headphoneDeviceTypes_routeToAudioTrackInsteadOfAAudio() {
    assertTrue(isHeadphoneDeviceType(TYPE_WIRED_HEADSET))
    assertTrue(isHeadphoneDeviceType(TYPE_WIRED_HEADPHONES))
    assertTrue(isHeadphoneDeviceType(TYPE_USB_HEADSET))
    assertTrue(isHeadphoneDeviceType(TYPE_USB_DEVICE))
    assertTrue(isHeadphoneDeviceType(TYPE_BLUETOOTH_A2DP))
    assertTrue(isHeadphoneDeviceType(TYPE_BLUETOOTH_SCO))
    assertTrue(isHeadphoneDeviceType(TYPE_HEARING_AID))
    assertTrue(isHeadphoneDeviceType(TYPE_BLE_HEADSET))
    assertFalse(isHeadphoneDeviceType(TYPE_BUILTIN_SPEAKER))
    assertFalse(isHeadphoneDeviceType(TYPE_BUILTIN_EARPIECE))
    assertFalse(isHeadphoneDeviceType(TYPE_BUILTIN_SPEAKER_SAFE))
    assertFalse(isHeadphoneDeviceType(null))
  }
}
