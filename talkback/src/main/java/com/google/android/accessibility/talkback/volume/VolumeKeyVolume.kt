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

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.talkback.notificationlist.NotificationListService

/**
 * Changes the volume for a volume key press kept from Android, following whichever audio stream is
 * actively playing right now: call/VoIP, ringtone, alarm, assistant, Meridian's own accessibility
 * speech while reading, navigation, notification, cast/media session, or fallback stream.
 */
class VolumeKeyVolume(private val context: Context) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val sessionManager = context.getSystemService(MediaSessionManager::class.java)

  /** The session of the last press, used again while the key is held. */
  private var session: MediaController? = null

  /** The active stream locked on initial press, reused while the key is held. */
  private var lockedActiveStream: Int? = null

  /** Stream adjusted on the most recent non-held press, kept sticky across rapid taps. */
  private var recentAdjustedStream: Int? = null

  /** Uptime (ms) of the most recent volume adjustment for sticky rapid-tap locking. */
  private var recentAdjustUptimeMs: Long = Long.MIN_VALUE

  /** Clears rapid-tap sticky state (e.g., when switching to touch-held accessibility volume). */
  fun clearStickyStream() {
    recentAdjustedStream = null
    recentAdjustUptimeMs = Long.MIN_VALUE
  }

  /**
   * Resolves the contextual stream for a press at [nowMs], keeping rapid consecutive presses
   * locked on the same stream within [STICKY_STREAM_WINDOW_MS] unless a higher-priority
   * call/ringtone/alarm/assistant stream starts.
   */
  fun resolveContextualStreamSticky(
    fallbackStream: Int,
    accessibilitySpeaking: Boolean,
    nowMs: Long = SystemClock.uptimeMillis(),
  ): Int {
    val audio = audioManager ?: return fallbackStream
    val detected = detectActiveStream(audio, accessibilitySpeaking)
    val resolved =
      applyStickyStream(
        detected = detected,
        fallbackStream = fallbackStream,
        recentStream = recentAdjustedStream,
        recentUptimeMs = recentAdjustUptimeMs,
        nowMs = nowMs,
      )
    recentAdjustedStream = resolved
    recentAdjustUptimeMs = nowMs
    return resolved
  }

  /** Overload preserving 4-arg callers with [accessibilitySpeaking] = false. */
  fun adjust(keyCode: Int, repeated: Boolean, stream: Int, showUi: Boolean) {
    adjust(keyCode, repeated, stream, showUi, accessibilitySpeaking = false)
  }

  /**
   * Changes the volume as a press of [keyCode] does, [repeated] while the key is held, starting
   * from [stream]: prefers any live playback stream (call, ringtone, alarm, assistant, Meridian
   * speech when [accessibilitySpeaking], notification, media), then an active media session, and
   * finally [stream]. Android's volume panel shows only with [showUi].
   */
  fun adjust(
    keyCode: Int,
    repeated: Boolean,
    stream: Int,
    showUi: Boolean,
    accessibilitySpeaking: Boolean,
  ) {
    val audio = audioManager ?: return
    val direction = direction(keyCode)
    val flags = volumeFlags(repeated, showUi)
    val nowMs = SystemClock.uptimeMillis()
    if (!repeated) {
      val detected = detectActiveStream(audio, accessibilitySpeaking)
      val sticky =
        if (
          recentAdjustedStream != null &&
            nowMs >= recentAdjustUptimeMs &&
            (nowMs - recentAdjustUptimeMs) <= STICKY_STREAM_WINDOW_MS &&
            !isInterruptingPriorityStream(detected)
        ) {
          recentAdjustedStream
        } else {
          detected
        }
      lockedActiveStream = sticky
      session =
        if (sticky != null || prefersStream(stream, audio)) null else volumeSession()
    }
    val activeStream = lockedActiveStream
    if (activeStream != null) {
      recentAdjustedStream = activeStream
      recentAdjustUptimeMs = nowMs
      audio.adjustSuggestedStreamVolume(direction, activeStream, flags)
      return
    }
    recentAdjustedStream = stream
    recentAdjustUptimeMs = nowMs
    val session = session
    val info = session?.playbackInfo
    if (session == null || info == null) {
      audio.adjustSuggestedStreamVolume(direction, stream, flags)
      return
    }
    if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
      session.adjustVolume(direction, flags)
      return
    }
    audio.adjustSuggestedStreamVolume(
      direction,
      localSessionStream(info.audioAttributes.volumeControlStream, stream, audio.isMusicActive),
      flags,
    )
  }

  /** Whether Android changes [stream] rather than the session's volume: it is playing. */
  private fun prefersStream(stream: Int, audio: AudioManager): Boolean =
    stream == AudioManager.STREAM_MUSIC && audio.isMusicActive

  /** The session Android gives volume keys to, or null when none plays or none can be seen. */
  private fun volumeSession(): MediaController? {
    val manager = sessionManager ?: return null
    val sessions =
      try {
        manager.getActiveSessions(ComponentName(context, NotificationListService::class.java))
      } catch (e: SecurityException) {
        // Notification access is off.
        return null
      } catch (e: RuntimeException) {
        Log.w(TAG, "Media sessions unavailable", e)
        return null
      }
    // In Android's priority order.
    return sessions.firstOrNull {
      val info = it.playbackInfo ?: return@firstOrNull false
      isPlaybackActive(it.playbackState?.state) &&
        handlesVolumeKeys(info.playbackType, info.volumeControl)
    }
  }

  companion object {
    private const val TAG = "VolumeKeyVolume"

    /** Window (ms) during which rapid consecutive volume presses stay on the same stream. */
    @VisibleForTesting internal const val STICKY_STREAM_WINDOW_MS = 1_500L

    @JvmStatic
    internal fun isInterruptingPriorityStream(stream: Int?): Boolean =
      stream == AudioManager.STREAM_VOICE_CALL ||
        stream == AudioManager.STREAM_RING ||
        stream == AudioManager.STREAM_ALARM ||
        stream == AudioManager.USE_DEFAULT_STREAM_TYPE

    @JvmStatic
    internal fun applyStickyStream(
      detected: Int?,
      fallbackStream: Int,
      recentStream: Int?,
      recentUptimeMs: Long,
      nowMs: Long,
    ): Int {
      if (
        recentStream != null &&
          nowMs >= recentUptimeMs &&
          (nowMs - recentUptimeMs) <= STICKY_STREAM_WINDOW_MS &&
          !isInterruptingPriorityStream(detected)
      ) {
        return recentStream
      }
      return detected ?: fallbackStream
    }

    /** AudioManager.FLAG_FROM_KEY, hidden: the change is for a hardware key press. */
    @VisibleForTesting const val FLAG_FROM_KEY = 1 shl 12

    /** Lightweight snapshot of an active audio playback for pure-function stream resolution. */
    data class PlaybackSpec(val usage: Int, val volumeControlStream: Int)

    /**
     * Resolves the best contextual audio stream to adjust: follows any active call/VoIP, ringtone,
     * alarm, assistant, Meridian speech (when [accessibilitySpeaking] or accessibility playback is
     * active), notification, or media playback, falling back to [fallbackStream] when idle.
     */
    @JvmStatic
    fun resolveContextualStream(
      audio: AudioManager,
      fallbackStream: Int,
      accessibilitySpeaking: Boolean,
    ): Int = detectActiveStream(audio, accessibilitySpeaking) ?: fallbackStream

    /**
     * Detects the currently active audio stream from [AudioManager.getMode],
     * [AudioManager.getActivePlaybackConfigurations], and [accessibilitySpeaking], or returns
     * {@code null} when nothing is actively playing.
     */
    @JvmStatic
    fun detectActiveStream(audio: AudioManager, accessibilitySpeaking: Boolean): Int? {
      val mode =
        try {
          audio.mode
        } catch (_: RuntimeException) {
          AudioManager.MODE_NORMAL
        }
      val playbacks =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          try {
            audio.activePlaybackConfigurations.map { config ->
              val attrs = config.audioAttributes
              PlaybackSpec(attrs.usage, attrs.volumeControlStream)
            }
          } catch (_: RuntimeException) {
            emptyList()
          }
        } else {
          emptyList()
        }
      return selectActiveStream(mode, playbacks, accessibilitySpeaking)
    }

    /**
     * Pure-function stream selector: inspects [audioMode] first (calls/VoIP/ringtone), then active
     * [playbacks] and [accessibilitySpeaking] in priority order so any playing stream — including
     * assistant audio and Meridian's own reading speech — can be adjusted contextually.
     */
    @JvmStatic
    internal fun selectActiveStream(
      audioMode: Int,
      playbacks: List<PlaybackSpec>,
      accessibilitySpeaking: Boolean = false,
    ): Int? {
      when (audioMode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION -> return AudioManager.STREAM_VOICE_CALL
        AudioManager.MODE_RINGTONE -> return AudioManager.STREAM_RING
      }
      var bestPriority = Int.MAX_VALUE
      var bestStream: Int? = null
      for (spec in playbacks) {
        val (priority, stream) = classifyPlayback(spec, accessibilitySpeaking) ?: continue
        if (priority < bestPriority) {
          bestPriority = priority
          bestStream = stream
        }
      }
      // If Meridian is actively reading and no higher-priority call/ringtone/alarm/assistant is
      // active, prioritize STREAM_ACCESSIBILITY over background media/notifications.
      if (accessibilitySpeaking && 5 < bestPriority) {
        return AudioManager.STREAM_ACCESSIBILITY
      }
      return bestStream
    }

    private fun classifyPlayback(
      spec: PlaybackSpec,
      accessibilitySpeaking: Boolean,
    ): Pair<Int, Int>? {
      val directStream =
        spec.volumeControlStream.takeIf { it != AudioManager.USE_DEFAULT_STREAM_TYPE }
      return when (spec.usage) {
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING ->
          1 to (directStream ?: AudioManager.STREAM_VOICE_CALL)
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE ->
          2 to (directStream ?: AudioManager.STREAM_RING)
        AudioAttributes.USAGE_ALARM ->
          3 to (directStream ?: AudioManager.STREAM_ALARM)
        AudioAttributes.USAGE_ASSISTANT ->
          4 to (directStream ?: AudioManager.USE_DEFAULT_STREAM_TYPE)
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY ->
          // Only adjust accessibility volume when Meridian is actively reading real content
          // (not a volume-percentage announcement or lingering audio track).
          if (accessibilitySpeaking) {
            5 to (directStream ?: AudioManager.STREAM_ACCESSIBILITY)
          } else {
            null
          }
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ->
          6 to (directStream ?: AudioManager.STREAM_MUSIC)
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT ->
          7 to (directStream ?: AudioManager.STREAM_NOTIFICATION)
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME ->
          8 to (directStream ?: AudioManager.STREAM_MUSIC)
        else -> directStream?.let { 9 to it }
      }
    }

    @JvmStatic
    internal fun direction(keyCode: Int): Int =
      if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
        AudioManager.ADJUST_RAISE
      } else {
        AudioManager.ADJUST_LOWER
      }

    /** The flags of a volume change for a key press, [repeated] while the key is held. */
    @JvmStatic
    internal fun volumeFlags(repeated: Boolean, showUi: Boolean): Int {
      var flags = AudioManager.FLAG_VIBRATE or FLAG_FROM_KEY
      if (showUi) {
        flags = flags or AudioManager.FLAG_SHOW_UI
      }
      if (!repeated) {
        flags = flags or AudioManager.FLAG_PLAY_SOUND
      }
      return flags
    }

    /** Whether a session in playback [state] takes volume keys, as PlaybackState.isActive(). */
    @JvmStatic
    internal fun isPlaybackActive(state: Int?): Boolean =
      when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
      }

    /** Whether a session of [playbackType] and [volumeControl] changes volume for the keys. */
    @JvmStatic
    internal fun handlesVolumeKeys(playbackType: Int, volumeControl: Int): Boolean =
      playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
        volumeControl != VolumeProvider.VOLUME_CONTROL_FIXED

    /**
     * The stream to change for a local session that plays on [sessionStream]: that stream, unless
     * it is the music stream and no music plays, when Android uses [stream].
     */
    @JvmStatic
    internal fun localSessionStream(sessionStream: Int, stream: Int, musicActive: Boolean): Int =
      if (sessionStream == AudioManager.STREAM_MUSIC && !musicActive) stream else sessionStream
  }
}
