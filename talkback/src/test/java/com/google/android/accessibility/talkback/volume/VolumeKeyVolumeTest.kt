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

package com.google.android.accessibility.talkback.volume

import android.media.AudioAttributes.USAGE_ALARM
import android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
import android.media.AudioAttributes.USAGE_ASSISTANT
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
import android.media.AudioManager.FLAG_PLAY_SOUND
import android.media.AudioManager.FLAG_SHOW_UI
import android.media.AudioManager.FLAG_VIBRATE
import android.media.AudioManager.MODE_IN_CALL
import android.media.AudioManager.MODE_IN_COMMUNICATION
import android.media.AudioManager.MODE_NORMAL
import android.media.AudioManager.MODE_RINGTONE
import android.media.AudioManager.STREAM_ACCESSIBILITY
import android.media.AudioManager.STREAM_ALARM
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioManager.STREAM_RING
import android.media.AudioManager.STREAM_VOICE_CALL
import android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
import android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE
import android.media.VolumeProvider.VOLUME_CONTROL_FIXED
import android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
import android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
import android.media.session.PlaybackState.STATE_BUFFERING
import android.media.session.PlaybackState.STATE_PAUSED
import android.media.session.PlaybackState.STATE_PLAYING
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.FLAG_FROM_KEY
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.PlaybackSpec
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.handlesVolumeKeys
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.isPlaybackActive
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.localSessionStream
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.selectActiveStream
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume.Companion.volumeFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Pure-function tests for [VolumeKeyVolume]; the session lookup itself is device-only. */
@RunWith(JUnit4::class)
class VolumeKeyVolumeTest {

  @Test
  fun volumeFlags_panelOnlyWithShowUi() {
    assertEquals(
      FLAG_SHOW_UI or FLAG_VIBRATE or FLAG_PLAY_SOUND or FLAG_FROM_KEY,
      volumeFlags(false, true),
    )
    assertEquals(FLAG_VIBRATE or FLAG_PLAY_SOUND or FLAG_FROM_KEY, volumeFlags(false, false))
    assertEquals(FLAG_VIBRATE or FLAG_FROM_KEY, volumeFlags(true, false))
  }

  @Test
  fun isPlaybackActive_playingAndBufferingOnly() {
    assertTrue(isPlaybackActive(STATE_PLAYING))
    assertTrue(isPlaybackActive(STATE_BUFFERING))
    assertFalse(isPlaybackActive(STATE_PAUSED))
    assertFalse(isPlaybackActive(null))
  }

  @Test
  fun handlesVolumeKeys_notFixedRemoteSessions() {
    assertTrue(handlesVolumeKeys(PLAYBACK_TYPE_LOCAL, VOLUME_CONTROL_FIXED))
    assertTrue(handlesVolumeKeys(PLAYBACK_TYPE_REMOTE, VOLUME_CONTROL_ABSOLUTE))
    assertFalse(handlesVolumeKeys(PLAYBACK_TYPE_REMOTE, VOLUME_CONTROL_FIXED))
  }

  @Test
  fun localSessionStream_sessionStreamUnlessMusicSilent() {
    assertEquals(STREAM_MUSIC, localSessionStream(STREAM_MUSIC, USE_DEFAULT_STREAM_TYPE, true))
    assertEquals(
      USE_DEFAULT_STREAM_TYPE,
      localSessionStream(STREAM_MUSIC, USE_DEFAULT_STREAM_TYPE, false),
    )
    assertEquals(STREAM_ALARM, localSessionStream(STREAM_ALARM, USE_DEFAULT_STREAM_TYPE, false))
  }

  @Test
  fun selectActiveStream_prioritizesCallAndRingtoneModes() {
    assertEquals(STREAM_VOICE_CALL, selectActiveStream(MODE_IN_CALL, emptyList()))
    assertEquals(STREAM_VOICE_CALL, selectActiveStream(MODE_IN_COMMUNICATION, emptyList()))
    assertEquals(STREAM_RING, selectActiveStream(MODE_RINGTONE, emptyList()))
  }

  @Test
  fun selectActiveStream_keepsAccessibilityWhenReadingAndFollowsAssistant() {
    val assistantStream = 11 // OEM/AOSP STREAM_ASSISTANT
    // When Meridian is reading alongside background media, adjust accessibility volume.
    assertEquals(
      STREAM_ACCESSIBILITY,
      selectActiveStream(
        MODE_NORMAL,
        listOf(PlaybackSpec(USAGE_MEDIA, STREAM_MUSIC)),
        accessibilitySpeaking = true,
      ),
    )
    assertEquals(
      STREAM_ACCESSIBILITY,
      selectActiveStream(
        MODE_NORMAL,
        listOf(PlaybackSpec(USAGE_ASSISTANCE_ACCESSIBILITY, STREAM_ACCESSIBILITY)),
      ),
    )
    // When an assistant is actively speaking, adjust its dedicated stream.
    assertEquals(
      assistantStream,
      selectActiveStream(
        MODE_NORMAL,
        listOf(
          PlaybackSpec(USAGE_ASSISTANCE_ACCESSIBILITY, STREAM_ACCESSIBILITY),
          PlaybackSpec(USAGE_ASSISTANT, assistantStream),
        ),
        accessibilitySpeaking = true,
      ),
    )
    assertEquals(
      USE_DEFAULT_STREAM_TYPE,
      selectActiveStream(
        MODE_NORMAL,
        listOf(PlaybackSpec(USAGE_ASSISTANT, USE_DEFAULT_STREAM_TYPE)),
      ),
    )
  }

  @Test
  fun selectActiveStream_prefersVoiceAndAlarmOverMediaAndReturnsNullWhenIdle() {
    assertEquals(
      STREAM_VOICE_CALL,
      selectActiveStream(
        MODE_NORMAL,
        listOf(
          PlaybackSpec(USAGE_MEDIA, STREAM_MUSIC),
          PlaybackSpec(USAGE_VOICE_COMMUNICATION, STREAM_VOICE_CALL),
        ),
      ),
    )
    assertEquals(
      STREAM_ALARM,
      selectActiveStream(
        MODE_NORMAL,
        listOf(
          PlaybackSpec(USAGE_MEDIA, STREAM_MUSIC),
          PlaybackSpec(USAGE_ALARM, STREAM_ALARM),
        ),
      ),
    )
    assertNull(selectActiveStream(MODE_NORMAL, emptyList(), accessibilitySpeaking = false))
  }
}
