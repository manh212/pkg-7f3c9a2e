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

import android.media.AudioTrack
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * JNI wrapper around the native C++ AAudio MMAP low-latency output stream (`libmeridian_aaudio.so`).
 *
 * When the native library is unavailable (e.g., JVM unit tests) or the device HAL does not grant
 * `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`, [open] returns `null` so [LowLatencyAudio] transparently
 * falls back to its `AudioTrack` backend.
 */
internal class NativeAAudioOutput private constructor(@Volatile private var handle: Long) {
  private val closeLock = Any()

  val sampleRate: Int = nativeGetSampleRate(handle)
  val framesPerBurst: Int = nativeGetFramesPerBurst(handle)
  val isMMapUsed: Boolean = nativeIsMMapUsed(handle)
  val deviceId: Int = nativeGetDeviceId(handle)

  val bufferSizeInFrames: Int
    get() = synchronized(closeLock) { if (handle != 0L) nativeGetBufferSizeInFrames(handle) else -1 }

  val bufferCapacityInFrames: Int
    get() =
      synchronized(closeLock) { if (handle != 0L) nativeGetBufferCapacityInFrames(handle) else -1 }

  val xRunCount: Int
    get() = synchronized(closeLock) { if (handle != 0L) nativeGetXRunCount(handle) else 0 }

  val playState: Int
    get() =
      synchronized(closeLock) {
        if (handle != 0L && nativeIsPlaying(handle)) AudioTrack.PLAYSTATE_PLAYING
        else AudioTrack.PLAYSTATE_PAUSED
      }

  fun start(): Boolean = synchronized(closeLock) { handle != 0L && nativeStart(handle) }

  /** Writes [numFrames] stereo float frames; returns true if all frames were accepted. */
  fun write(samples: FloatArray, numFrames: Int): Boolean {
    val current = handle
    if (current == 0L) return false
    val written = nativeWrite(current, samples, numFrames, WRITE_TIMEOUT_NANOS)
    return written == numFrames
  }

  fun setBufferSizeInFrames(frames: Int): Int =
    synchronized(closeLock) {
      if (handle != 0L) nativeSetBufferSizeInFrames(handle, frames) else -1
    }

  fun pauseAndFlush() {
    synchronized(closeLock) {
      if (handle != 0L) nativePauseAndFlush(handle)
    }
  }

  /** Unblocks any in-flight blocking [write] so the audio thread can remake or exit. */
  fun requestStop() {
    val current = handle
    if (current != 0L) nativeStop(current)
  }

  fun close() {
    val toClose: Long
    synchronized(closeLock) {
      toClose = handle
      handle = 0L
    }
    if (toClose != 0L) {
      nativeClose(toClose)
    }
  }

  companion object {
    private const val TAG = "NativeAAudioOutput"
    private const val WRITE_TIMEOUT_NANOS = 1_000_000_000L

    val isAvailable: Boolean by lazy {
      try {
        System.loadLibrary("meridian_aaudio")
        true
      } catch (e: UnsatisfiedLinkError) {
        LogUtils.d(TAG, "libmeridian_aaudio not available; using AudioTrack: %s", e.message)
        false
      } catch (e: SecurityException) {
        LogUtils.w(TAG, "Cannot load libmeridian_aaudio: %s", e.message)
        false
      }
    }

    fun open(
      sampleRate: Int,
      androidUsage: Int,
      deviceId: Int = 0,
      initialBursts: Int = 2,
    ): NativeAAudioOutput? {
      if (!isAvailable) return null
      val handle = nativeOpen(sampleRate, androidUsage, deviceId, initialBursts)
      if (handle == 0L) return null
      return NativeAAudioOutput(handle)
    }

    @JvmStatic
    private external fun nativeOpen(
      sampleRate: Int,
      androidUsage: Int,
      deviceId: Int,
      initialBursts: Int,
    ): Long

    @JvmStatic private external fun nativeStart(handle: Long): Boolean

    @JvmStatic
    private external fun nativeWrite(
      handle: Long,
      samples: FloatArray,
      numFrames: Int,
      timeoutNanos: Long,
    ): Int

    @JvmStatic private external fun nativePauseAndFlush(handle: Long)

    @JvmStatic private external fun nativeStop(handle: Long)

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeGetSampleRate(handle: Long): Int

    @JvmStatic private external fun nativeGetFramesPerBurst(handle: Long): Int

    @JvmStatic private external fun nativeGetBufferSizeInFrames(handle: Long): Int

    @JvmStatic private external fun nativeGetBufferCapacityInFrames(handle: Long): Int

    @JvmStatic
    private external fun nativeSetBufferSizeInFrames(handle: Long, requestedFrames: Int): Int

    @JvmStatic private external fun nativeGetXRunCount(handle: Long): Int

    @JvmStatic private external fun nativeGetDeviceId(handle: Long): Int

    @JvmStatic private external fun nativeIsPlaying(handle: Long): Boolean

    @JvmStatic private external fun nativeIsMMapUsed(handle: Long): Boolean
  }
}
