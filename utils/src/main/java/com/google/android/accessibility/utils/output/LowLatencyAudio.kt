Warning: truncated output (original token count: 14913)
Total output lines: 1600

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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.Performance
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Plays Backtalk's sounds and speech on one low-latency audio track at the device's own sample
 * rate, so that Android can give it its fast path, which reaches the speaker tens of milliseconds
 * sooner than ordinary tracks. Short sounds are mixed together, and speech streams play one after
 * another, as they arrive from the engine.
 *
 * The track keeps running while there is something to play and for a moment after, so that the
 * next sound starts at once, then pauses. Its thread ends then, and nothing runs until the next
 * sound or speech. While an app records from the microphone, the track pauses as soon as what it
 * played has been heard: some apps, such as Gemini, don't take the user's voice while accessibility
 * audio plays, even silence. Speech and sounds that start while an app records play the usual
 * way instead, for the same reason.
 */
class LowLatencyAudio private constructor(context: Context, private val attributes: AudioAttributes) {
  /** Output frames per second. */
  val sampleRate: Int

  private val burstFrames: Int
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lock = java.lang.Object()
  private val clips = ArrayList<PlayingClip>()
  private val streams = ArrayDeque<SpeechStream>()
  private val startingStreams = ArrayList<SpeechStream>()
  private val soundStartingStreams = ArrayList<SpeechStream>()
  private val gapCounts = LongArray(SpeechGapTracker.Kind.values().size)
  private val gapTotalMs = LongArray(SpeechGapTracker.Kind.values().size)
  private val gapMaxMs = LongArray(SpeechGapTracker.Kind.values().size)

  fun resetGapStatistics() {
    synchronized(lock) {
      gapCounts.fill(0)
      gapTotalMs.fill(0)
      gapMaxMs.fill(0)
      streams.forEach { it.gaps.reset() }
    }
  }

  private fun recordGap(gap: SpeechGapTracker.Gap?) {
    if (gap == null) return
    val index = gap.kind.ordinal
    gapCounts[index]++
    gapTotalMs[index] += gap.durationMs
    gapMaxMs[index] = maxOf(gapMaxMs[index], gap.durationMs)
  }

  private var dspWriteCount = 0L
  private var dspWriteTotalUs = 0L
  private var dspWriteMaxUs = 0L

  private fun recordDspWrite(durationUs: Long) {
    dspWriteCount++
    dspWriteTotalUs += durationUs
    if (durationUs > dspWriteMaxUs) dspWriteMaxUs = durationUs
  }

  private fun dspWriteSummary(): String {
    if (dspWriteCount == 0L) return "0 writes"
    val avgMs = (dspWriteTotalUs / dspWriteCount) / 1000.0
    val maxMs = dspWriteMaxUs / 1000.0
    return String.format(
      java.util.Locale.ROOT,
      "%d writes, avg %.2f ms, max %.2f ms",
      dspWriteCount,
      avgMs,
      maxMs,
    )
  }

  private fun gapSummary(kind: SpeechGapTracker.Kind): String {
    val i = kind.ordinal
    return "${gapCounts[i]} episodes, ${gapTotalMs[i]} ms total, ${gapMaxMs[i]} ms maximum"
  }
  // Listener callbacks run here, in order, so that they never hold up the audio thread. Its thread
  // ends when there is nothing to report.
  private val callbacks = singleThreadExecutor("LowLatencyAudio callbacks")
  @Volatile private var nativeOutput: NativeAAudioOutput? = null
  @Volatile private var track: AudioTrack? = null
  // Set when no track can be made, or the track stops taking audio and cannot be remade. Then audio
  // plays the usual way.
  @Volatile private var broken = false
  // Set by shutdown. Nothing plays again, and the track is released.
  @Volatile private var released = false
  // When the track last took audio, to find a track that has stopped.
  @Volatile private var lastWrite = 0L
  // Set by the watchdog when the track has taken no audio for WRITE_STUCK_MS.
  @Volatile private var remakeTrack = false
  // Set when a paused track without the fast path starts, until its first write completes. An
  // output such as Bluetooth can take a second or more to wake from standby, which is not a stuck
  // track, so the watchdog gives it WAKE_STUCK_MS longer.
  @Volatile private var waking = false
  // Whether the track has the fast path, and its underrun count when its buffer was last sized.
  @Volatile private var fastPath = false
  @Volatile private var underruns = 0
  // The fast path's buffer size, once it has had to grow, kept for new tracks and outputs.
  @Volatile private var fastFrames = 0
  private var smallShortfalls = 0
  @Volatile private var outgrewSmallBuffer = false
  // Set when the track starts, until its first write completes. Running short before that is only
  // the track starting, not a buffer too small.
  @Volatile private var starting = false
  // Writes left before running short counts again after the buffer grew: the underruns of the same
  // starvation can be counted after it grew, and would grow it again.
  private var settleWrites = 0
  // The watchdog's checks, scheduled only while the audio thread runs.
  private var watchdog: ScheduledFuture<*>? = null
  private var thread: Thread? = null
  private var idleFrames = 0
  // Whether the track has audio queued since it last paused. Without the fast path, it pauses as
  // soon as its audio has been heard, after silentFrames of silence.
  private var primed = false
  private var silentFrames = 0
  // While held, speech streams keep what they have and keep receiving audio, but do not play.
  private var held = false
  // Whether this hold has filled up already, so it is reported once.
  private var holdFull = false
  @Volatile private var detour: AudioDeviceInfo? = null
  private var stickyDetour = false
  // When the track was last sent to the media output again after it went to the earpiece.
  private var lastResend = 0L

  init {
    sampleRate =
      audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        ?: DEFAULT_RATE
    burstFrames =
      audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        ?: DEFAULT_BURST
    openOutput()
  }

  /**
   * A short sound, as frames of [channels] channels, 1 or 2, at [rate]. A mono sound is kept mono,
   * which takes half the memory, and plays on both sides.
   */
  class Clip
  internal constructor(
    internal val frames: FloatArray,
    internal val rate: Int,
    internal val channels: Int = 2,
  ) {
    /** How many samples the clip holds. */
    val size: Int
      get() = frames.size

    /** Its length, channels, and the silence before its sound starts, for the log. */
    fun describe(): String {
      val total = frames.size / channels
      val firstSound =
        (0 until total).firstOrNull { frame ->
          (0 until channels).any { abs(frames[frame * channels + it]) > AUDIBLE }
        } ?: total
      return "${total * 1000L / rate} ms, $channels channels, " +
        "${firstSound * 1000L / rate} ms before its sound"
    }

    private companion object {
      // About -54 dB.
      const val AUDIBLE = 0.002f
    }
  }

  private class PlayingClip(val clip: Clip, val left: Float, val right: Float, val step: Double) {
    var position = 0.0
  }

  /** Converts decoded audio to a clip for this output. */
  fun prepare(decoded: AudioDecoder.Decoded): Clip {
    val resampled =
      SincResampler(decoded.sampleRate, sampleRate, decoded.channels).let {
        val out = it.process(decoded.samples)
        out + it.flush()
      }
    return if (decoded.channels == 1) {
      Clip(resampled, sampleRate, channels = 1)
    } else {
      Clip(toStereo(resampled, decoded.channels), sampleRate)
    }
  }

  /** Plays [clip] at [left] and [right] volumes from 0 to 1, and [rate], 1 for its own speed. */
  fun play(clip: Clip, left: Float, right: Float, rate: Float) {
    // A clip made for a player at another rate, before this one replaced it, still plays in tune.
    // A NaN rate would never reach the clip's end, and a NaN volume would fill every burst.
    val speed = if (rate.isFinite()) rate.toDouble().coerceIn(0.25, 4.0) else 1.0
    val step = speed * clip.rate / sampleRate
    synchronized(lock) {
      if (released || broken) return
      clips += PlayingClip(clip, finiteVolume(left), finiteVolume(right), step)
      wake()
    }
  }

  /** Called on a callback thread as a speech stream plays. */
  interface StreamListener {
    fun onAudioSubmitted(id: String, uptimeMs: Long)
    /** The stream's first sound reached the output. */
    fun onStarted(id: String)

    /** Speech reached the word range [start] to [end] of the text. */
    fun onRange(id: String, start: Int, end: Int)

    /** The stream played to its end, or was stopped before it, if not [completed]. */
    fun onFinished(id: String, completed: Boolean)

    /** The engine finished without giving any audio, so the stream played nothing. */
    fun onNoAudio(id: String)

    /**
     * The stream waited at the front of the queue for [STALL_MS] without the engine giving it any
     * audio or finishing, so it was dropped, to let the speech after it play.
     */
    fun onStalled(id: String)

    /**
     * The player stopped working, such as when its track could not be made again, so the stream
     * was dropped to play the usual way. Unlike [onStalled], the engine is not at fault.
     */
    fun onPlayerFailed(id: String)

    /**
     * The engine's audio has been digital silence for [SILENT_MS], as if it applied the zero volume
     * Backtalk gives it itself, so the stream plays nothing.
     */
    fun onSilent(id: String)

    /**
     * Held speech reached [MAX_HELD_MS] of audio, so the stream takes no more of it. The held
     * speech should be dropped and said again the usual way when it resumes.
     */
    fun onHoldFull(id: String)
  }

  /**
   * Speech from an engine, played after the streams opened before it. Audio is written to it as
   * the engine makes it.
   */
  inner class SpeechStream internal constructor(
    val id: String,
    private val volume: Float,
    private val pan: Float,
    private val equalizer: IntArray?,
    private val compressor: SpeechCompressor.Settings?,
    private val listener: StreamListener,
  ) {
    @Volatile private var resampler: SincResampler? = null
    private val decodeLock = Any()
    private val convertOutsideLock = LatencyExperiments.Feature.AUDIO_RESAMPLE.enabled()
    private var soundStarted = false
    private var filter: SpeechEqualizer.Filter? = null
    private var compressorFilter: SpeechCompressor.Filter? = null
    @Volatile private var inChannels = 1
    private var inRate = 0
    private var inFrames = 0L
    private var heardSound = false
    private var reportedSilent = false
    @Volatile private var inEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val pending = ArrayDeque<FloatArray>()
    private var headOffset = 0
    private var framesWritten = 0L
    private var framesPlayed = 0L
    private var started = false
    internal val gaps = SpeechGapTracker()

    internal fun reportAudioSubmitted(uptimeMs: Long) {
      listener.onAudioSubmitted(id, uptimeMs)
    }
    private var ended = false
    @Volatile private var finished = false
    // When the engine last gave this stream anything, or it reached the front of the queue.
    private var lastActivity = SystemClock.uptimeMillis()
    // When this stream reached the front of the queue, or 0 before.
    private var headSince = 0L
    // Word ranges by the output frame they start at.
    private val ranges = ArrayDeque<LongArray>()

    /** The engine's format, from the start of synthesis. */
    fun begin(rate: Int, encoding: Int, channels: Int) {
      val supported =
        AudioDecoder.isSupportedEncoding(encoding) &&
          SpatialSoundPlayer.isSupportedFormat(rate, channels)
      // Its filter takes a while to work out, so not while the audio thread waits for the lock.
      val built = if (supported) SincResampler(rate, sampleRate, channels) else null
      // Run equalizer and multiband compressor at the engine's native sample rate before
      // upsampling to the output rate, cutting per-sample DSP work roughly in half for 22-24 kHz TTS.
      val builtFilter =
        if (built != null && equalizer != null) SpeechEqualizer.Filter(equalizer, rate, channels)
        else null
      val builtCompressor =
        if (built != null && compressor != null)
          SpeechCompressor.Filter(compressor, rate, channels)
        else null
      synchronized(lock) {
        lastActivity = SystemClock.uptimeMillis()
        if (built == null) {
          // An engine can claim any format. One Backtalk cannot play is said the usual way.
          LogUtils.w(TAG, "Speech %s has unsupported format %d Hz %d ch %d", id, rate, channels, encoding)
          if (streams.remove(this)) abandon(playerFailed = false)
          return
        }
        inChannels = channels
        inRate = rate
        inEncoding = encoding
        resampler = built
        filter = builtFilter
        compressorFilter = builtCompressor
      }
    }

    /** A piece of the engine's audio. */
    fun write(audio: ByteArray) {
      val initialResampler = resampler
      if (initialResampler == null || finished) return
      val encoding = inEncoding
      val channels = inChannels
      val availableAt = SystemClock.uptimeMillis()
      val samples = AudioDecoder.FloatList()
      AudioDecoder.toFloats(ByteBuffer.wrap(audio), encoding, samples)
      val floats = samples.toArray()
      val hasSound = floats.any { it != 0f }
      val firstSound = if (convertOutsideLock) {
        synchronized(decodeLock) {
          val current = resampler ?: return
          if (finished) return
          val dspStartNs = SystemClock.elapsedRealtimeNanos()
          val converted = toStereo(current.process(filtered(floats)), channels)
          val dspUs = (SystemClock.elapsedRealtimeNanos() - dspStartNs) / 1000L
          synchronized(lock) {
            recordDspWrite(dspUs)
            appendDecoded(floats, converted, hasSound)
          }
        }
      } else {
        synchronized(lock) {
          val current = resampler ?: return
          if (finished) return
          val dspStartNs = SystemClock.elapsedRealtimeNanos()
          val converted = toStereo(current.process(filtered(floats)), inChannels)
          val dspUs = (SystemClock.elapsedRealtimeNanos() - dspStartNs) / 1000L
          recordDspWrite(dspUs)
          appendDecoded(floats, converted, hasSound)
        }
      }
      if (firstSound) Performance.getInstance().onFirstSoundPcm(id, availableAt)
    }

    private fun appendDecoded(floats: FloatArray, converted: FloatArray, hasSound: Boolean): Boolean {
      if (finished) return false
      lastActivity = SystemClock.uptimeMillis()
      if (held && heldFrames() >= sampleRate * MAX_HELD_MS / 1000) {
        if (!holdFull) {
          holdFull = true
          callbacks.execute { listener.onHoldFull(id) }
        }
        return false
      }
      if (framesWritten - framesPlayed >= sampleRate * MAX_QUEUED_MS / 1000) {
        if (streams.remove(this)) abandon(playerFailed = false)
        return false
      }
      inFrames += floats.size / inChannels
      val firstSound = !heardSound && hasSound
      if (hasSound) heardSound = true
      if (!heardSound && inRate > 0 && inFrames * 1000 / inRate >= SILENT_MS) reportSilent()
      add(converted)
      return firstSound
    }

    fun writePrepared(stereo: FloatArray) {
      val availableAt = SystemClock.uptimeMillis()
      val hasSound = stereo.any { it != 0f }
      synchronized(lock) {
        if (finished) return
        inChannels = 2
        inRate = sampleRate
        inFrames = stereo.size.toLong() / 2
        heardSound = hasSound
        lastActivity = availableAt
        add(stereo)
        ended = true
        wake()
      }
      if (hasSound) Performance.getInstance().onFirstSoundPcm(id, availableAt)
    }

    /**
     * The engine reached a word range, at [frame] of its audio, or at the end of the audio written
     * so far if [frame] is unknown.
     */
    fun range(start: Int, end: Int, frame: Int) {
      synchronized(lock) {
        if (finished) return
        val at =
          if (frame > 0 && inRate > 0) frame.toLong() * sampleRate / inRate else framesWritten
        ranges += longArrayOf(at, start.toLong(), end.toLong())
      }
    }

    private fun reportSilent() {
      if (reportedSilent) return
      reportedSilent = true
      callbacks.execute { listener.onSilent(id) }
    }

    /** The engine finished making the audio. */
    fun end() {
      if (convertOutsideLock) {
        synchronized(decodeLock) {
          val tail = resampler?.let { toStereo(it.flush(), inChannels) }
          synchronized(lock) {
            if (finished) return
            recordGap(gaps.close(SpeechGapTracker.Kind.TAIL, SystemClock.uptimeMillis()))
            if (tail != null) add(tail)
            ended = true
            wake()
          }
        }
        return
      }
      synchronized(lock) {
        if (finished) return
        recordGap(gaps.close(SpeechGapTracker.Kind.TAIL, SystemClock.uptimeMillis()))
        // A short utterance that is all silence, such as a pause or punctuation, says nothing
        // about whether the engine silenced its audio. Only SILENT_MS of silence does.
        resampler?.let { add(toStereo(it.flush(), inChannels)) }
        ended = true
        wake()
      }
    }

    private fun filtered(frames: FloatArray): FloatArray {
      val equalized = filter?.process(frames) ?: frames
      return compressorFilter?.process(equalized) ?: equalized
    }

    private fun add(frames: FloatArray) {
      if (frames.isEmpty()) return
      pending += frames
      framesWritten += frames.size / 2
      wake()
    }

    /**
     * Whether this stream has finished, been stopped or been dropped, and has reported it. The
     * engine's callbacks for it after that are to be ignored.
     */
    val isFinished: Boolean
      get() = synchronized(lock) { finished }

    /** Frames written and not yet played. */
    internal fun pendingFrames(): Long = framesWritten - framesPlayed

    /** Whether the engine finished making the audio. */
    internal val isEnded: Boolean
      get() = ended

    /** How long since the engine last gave this stream anything, at [now]. */
    internal fun quietFor(now: Long): Long = now - lastActivity

    /** Mixes this stream's nex…4913 tokens truncated…return false
    val head = streams.peekFirst() ?: return false
    val pending = head.pendingFrames()
    return head.isEnded ||
      pending >= output.bufferSizeInFrames ||
      (pending > 0 && head.quietFor(SystemClock.uptimeMillis()) >= REST_MS)
  }

  private fun play(output: AudioTrack): Boolean =
    try {
      if (output.playState != AudioTrack.PLAYSTATE_PLAYING) {
        output.play()
        starting = true
        if (!fastPath) waking = true
      }
      true
    } catch (e: IllegalStateException) {
      false
    }

  /**
   * Writes [buffer], waiting until the track has room, and returns whether the track took it all.
   * The thread sleeps while it waits, and wakes once for each burst played. A track that stops
   * taking audio is paused by the watchdog, which ends the wait.
   */
  private fun write(output: AudioTrack, buffer: FloatArray): Boolean {
    val count =
      try {
        output.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
      } catch (e: IllegalStateException) {
        return false
      }
    if (count != buffer.size) return false
    lastWrite = SystemClock.uptimeMillis()
    waking = false
    try {
      keepUp(output)
    } catch (e: IllegalStateException) {
      return false
    }
    return true
  }

  private fun idleLimit(): Int =
    sampleRate * (if (microphoneInUse) MICROPHONE_IDLE_MS
      else if (LatencyExperiments.Feature.AUDIO_KEEP_WARM.enabled()) 10_000 else IDLE_MS) / 1000

  /** Mixes the next burst of every sound and the speech stream at the head of the queue. */
  private fun mix(out: FloatArray) {
    val iterator = clips.iterator()
    while (iterator.hasNext()) {
      val playing = iterator.next()
      val frames = playing.clip.frames
      val channels = playing.clip.channels
      // A mono clip reads its one channel for both sides.
      val right = channels - 1
      val total = frames.size / channels
      for (i in 0 until burstFrames) {
        val position = playing.position
        val index = position.toInt()
        if (index >= total) break
        val next = minOf(index + 1, total - 1)
        val fraction = (position - index).toFloat()
        val at = index * channels
        val after = next * channels
        out[i * 2] += (frames[at] * (1 - fraction) + frames[after] * fraction) * playing.left
        out[i * 2 + 1] +=
          (frames[at + right] * (1 - fraction) + frames[after + right] * fraction) * playing.right
        playing.position += playing.step
      }
      if (!(playing.position < total)) iterator.remove()
    }
    val head = if (held) null else streams.peekFirst()
    if (head != null && !head.mixInto(out, burstFrames)) streams.removeFirst()
    // NaN passes coerceIn. In a float track it plays as a full-scale pop and then silence.
    for (i in out.indices) out[i] = out[i].let { if (it.isNaN()) 0f else it.coerceIn(-1f, 1f) }
  }

  private fun createTrack(): AudioTrack? =
    try {
      val minBytes =
        AudioTrack.getMinBufferSize(
          sampleRate,
          AudioFormat.CHANNEL_OUT_STEREO,
          AudioFormat.ENCODING_PCM_FLOAT,
        )
      AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        )
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(minBytes)
        .build()
        .also {
          detour?.let { device -> it.setPreferredDevice(device) }
          fitBuffer(it)
          // Moving to another output, such as Bluetooth, can lose or gain the fast path.
          it.addOnRoutingChangedListener(
            { routed ->
              fitBuffer(routed as AudioTrack)
              ROUTES.execute {
                LogUtils.d(TAG, "Track on %s", describe(routed.routedDevice))
                followMedia()
              }
            },
            mainHandler,
          )
        }
    } catch (e: RuntimeException) {
      LogUtils.e(TAG, "Cannot create a low-latency track: %s", e)
      null
    }

  /**
   * Queues two bursts on the fast path, so new sound waits no longer than that, or more if it ran
   * short before. The burst Android reports can be smaller than the fast mixer's period, such as 144
   * frames for a 192 frame period. Other outputs, such as Bluetooth without the fast path, mix a
   * much larger period at a time, so the buffer starts at SLOW_BUFFER_MS there. Both grow in
   * [keepUp] until the mixer stops running short.
   */
  private fun refitBuffer() {
    val nativeOut = nativeOutput
    if (nativeOut != null) {
      val burst = maxOf(nativeOut.framesPerBurst, burstFrames)
      val target = fastBufferFrames(burst, smallBufferEnabled && !outgrewSmallBuffer, fastFrames)
      nativeOut.setBufferSizeInFrames(target)
      return
    }
    val output = track ?: return
    fitBuffer(output)
  }

  private fun fitBuffer(output: AudioTrack) {
    try {
      val fast = output.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
      output.setBufferSizeInFrames(
        if (fast) fastBufferFrames(burstFrames, smallBufferEnabled && !outgrewSmallBuffer, fastFrames)
        else sampleRate * SLOW_BUFFER_MS / 1000
      )
      underruns = output.underrunCount
      fastPath = fast
      LogUtils.d(
        TAG,
        "Track at %d Hz, %d frames, low latency=%b",
        sampleRate,
        output.bufferSizeInFrames,
        fast,
      )
    } catch (e: IllegalStateException) {
      // Released already.
    }
  }

  private fun followMedia() {
    val am = audioManager ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || released || broken) return
    val output = track ?: return
    val target =
      try {
        val ours = am.getAudioDevicesForAttributes(attributes).firstOrNull()?.type
        val media = am.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
        val routed = output.routedDevice?.type
        val send =
          sendToMedia(am.mode, ours, routed, media?.type, stickyDetour, detour != null)
        stickyDetour =
          send && ours != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && ours != media?.type
        media.takeIf { send }
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot find the media output: %s", e)
        return
      }
    detour = target
    try {
      if (output.preferredDevice?.id == target?.id) {
        if (target == null || output.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
          return
        }
        // The track was sent to the media output while it still played there, which Android
        // ignores, and then went to the earpiece with the rest of accessibility audio. Setting
        // the same device again is ignored too, so it is cleared first.
        val now = SystemClock.uptimeMillis()
        if (now - lastResend < ROUTE_SETTLE_MS) {
          recheckRoute(lastResend + ROUTE_SETTLE_MS)
          return
        }
        lastResend = now
        LogUtils.d(TAG, "Track went to the earpiece, sending it to %s again", describe(target))
        output.setPreferredDevice(null)
      } else if (target == null) {
        LogUtils.d(TAG, "Track plays where Android routes it again")
      } else {
        LogUtils.d(TAG, "Accessibility audio is on the earpiece outside a call, sending the track to %s", describe(target))
      }
      output.setPreferredDevice(target)
    } catch (e: IllegalStateException) {
      // Released already.
    }
  }

  /** Checks the track's route again at [uptime], once however often it is asked. */
  private fun recheckRoute(uptime: Long) {
    routeHandler.removeCallbacksAndMessages(recheckToken)
    routeHandler.postAtTime({ ROUTES.execute { followMedia() } }, recheckToken, uptime)
  }

  private val recheckToken = Any()

  /**
   * Grows the buffer after the mixer ran short, which otherwise pops, or crackles and slows audio
   * down. A full buffer delays new sound by its length, so it grows only as far as it has to: a
   * burst at a time on the fast path, and double without it.
   */
  private fun keepUp(output: AudioTrack) {
    val count = output.underrunCount
    if (starting || settleWrites > 0) {
      starting = false
      if (settleWrites > 0) settleWrites--
      underruns = count
      return
    }
    if (count <= underruns) return
    underruns = count
    if (fastPath && smallBufferEnabled && !outgrewSmallBuffer) {
      smallShortfalls++
      if (smallShortfalls < SMALL_BUFFER_SHORTFALL_LIMIT) return
      outgrewSmallBuffer = true
    }
    val size = output.bufferSizeInFrames
    if (size >= output.bufferCapacityInFrames) return
    val grown = if (fastPath) size + burstFrames else size * 2
    output.setBufferSizeInFrames(minOf(grown, output.bufferCapacityInFrames))
    settleWrites = 2 * output.bufferSizeInFrames / burstFrames
    if (fastPath) fastFrames = output.bufferSizeInFrames
    LogUtils.d(TAG, "Track ran short, now %d frames", output.bufferSizeInFrames)
  }

  companion object {
    private const val TAG = "LowLatencyAudio"
    private const val DEFAULT_RATE = 48_000
    private const val DEFAULT_BURST = 192
    private const val QUEUED_BURSTS = 2
    @JvmStatic
    var smallBufferEnabled: Boolean = false
      set(value) {
        if (field == value) return
        field = value
        for (player in players()) player.refitBuffer()
      }
    const val SMALL_BUFFER_SHORTFALL_LIMIT = 3

    @JvmStatic
    fun fastBufferFrames(burstFrames: Int, smallBuffer: Boolean, rememberedFrames: Int): Int =
      if (smallBuffer) burstFrames else maxOf(burstFrames * QUEUED_BURSTS, rememberedFrames)

    // Two periods of a typical mixer without the fast path.
    private const val SLOW_BUFFER_MS = 40
    // How long the audio thread waits for the engine's speech at a time, well within WRITE_STUCK_MS.
    private const val REST_MS = 100L
    private const val IDLE_MS = 2_000
    // While an app records, long enough for the audio already queued to be heard.
    private const val MICROPHONE_IDLE_MS = 100
    // How long the callback thread is kept with nothing to do.
    private const val THREAD_KEEP_ALIVE_MS = 5_000L

    /** The most audio held speech keeps, at about 384 KB a second. */
    const val MAX_HELD_MS = 10_000L

    /** The most audio a speech stream may have waiting to play while not held. */
    private const val MAX_QUEUED_MS = 30_000L

    /** How long a stream at the front of the queue waits for audio before it is dropped. */
    const val STALL_MS = 4_000L

    /** How much digital silence from the engine means it silenced the audio itself. */
    const val SILENT_MS = 500L

    /** How often the watchdog looks for stalled speech and a stuck track. */
    private const val WATCHDOG_MS = 250L

    /** How long the track may take no audio before it is remade. */
    private const val WRITE_STUCK_MS = 300L

    /** How long the track may take no audio before the watchdog gives up on it. */
    private const val TRACK_STUCK_MS = 1_500L

    /** How much longer a track without the fast path may take to wake from standby. */
    private const val WAKE_STUCK_MS = 2_000L

    private val instances = HashMap<Int, LowLatencyAudio>()

    private val MEDIA_ATTRIBUTES by lazy {
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    }

    private const val ROUTE_SETTLE_MS = 1_000L

    private val routeHandler by lazy { Handler(Looper.getMainLooper()) }

    private val ROUTES = singleThreadExecutor("LowLatencyAudio routes")

    /**
     * Whether a track should play on the media output, of type [mediaOutput], rather than where
     * Android routes it: outside a call, while Android routes accessibility audio ([ours]) or the
     * track itself ([routed]) to the earpiece. [sticky] keeps it there after the track went to the
     * earpiece, until a call starts or the media output goes. A track already sent there
     * ([detoured]) stays while Android routes accessibility audio to the media output as well.
     */
    @VisibleForTesting
    @JvmStatic
    fun sendToMedia(
      mode: Int,
      ours: Int?,
      routed: Int?,
      mediaOutput: Int?,
      sticky: Boolean,
      detoured: Boolean = false,
    ): Boolean =
      mode == AudioManager.MODE_NORMAL &&
        mediaOutput != null &&
        mediaOutput != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
        (ours == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
          routed == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
          sticky ||
          // While other audio plays on the media output, One UI routes accessibility audio there
          // too, and back to the earpiece when it stops.
          (detoured && ours == mediaOutput))

    private fun describe(device: AudioDeviceInfo?): String =
      if (device == null) "no output" else "${device.productName} (type ${device.type})"

    // Runs every player's watchdog while its audio thread runs. Its thread ends when none is.
    private val WATCHDOG =
      ScheduledThreadPoolExecutor(1) { Thread(it, "LowLatencyAudio watchdog") }
        .apply {
          setKeepAliveTime(THREAD_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
          allowCoreThreadTimeOut(true)
          removeOnCancelPolicy = true
        }

    /** An executor running tasks in order on one thread, which ends when it has nothing to do. */
    private fun singleThreadExecutor(name: String): Executor =
      ThreadPoolExecutor(
          1,
          1,
          THREAD_KEEP_ALIVE_MS,
          TimeUnit.MILLISECONDS,
          LinkedBlockingQueue(),
        ) {
          Thread(it, name)
        }
        .apply { allowCoreThreadTimeOut(true) }

    /** The players made so far, without making new ones. */
    /** Whether an app is recording from the microphone, set by [setMicrophoneInUse]. */
    @Volatile private var microphoneInUse = false

    /**
     * Whether an app is recording from the microphone. Speech and sounds then play the usual way:
     * some apps, such as Gemini, don't hear the user while the accessibility service itself plays
     * audio, but do while the speech engine plays it.
     */
    @JvmStatic fun isMicrophoneInUse(): Boolean = microphoneInUse

    /**
     * Tells the players whether an app is recording from the microphone, so that while one is,
     * their tracks pause soon after each sound rather than running on in silence.
     */
    @JvmStatic
    fun setMicrophoneInUse(inUse: Boolean) {
      microphoneInUse = inUse
    }

    @JvmStatic
    fun players(): List<LowLatencyAudio> = synchronized(instances) { instances.values.toList() }

    @JvmStatic
    fun remakeTracks() {
      val players = synchronized(instances) { instances.values.toList() }
      LogUtils.d(TAG, "A call ended, making %d low-latency tracks again", players.size)
      for (player in players) player.remake()
    }

    @JvmStatic fun isAvoidingEarpiece(): Boolean = players().any { it.detour != null }

    /** Stops every speech stream of every player made so far, without making new ones. */
    @JvmStatic
    fun stopAllStreams() {
      val players = synchronized(instances) { instances.values.toList() }
      for (player in players) player.stopStreams()
    }

    /**
     * Shuts down every player, releasing their tracks, for when the setting is turned off or the
     * service stops. Players are made again when next needed.
     */
    @JvmStatic
    fun shutdownAll() {
      val players = synchronized(instances) { instances.values.toList().also { instances.clear() } }
      for (player in players) player.shutdown()
      stopWatchingRoutes()
    }

    private fun followMediaAll() {
      val check = Runnable { ROUTES.execute { for (player in players()) player.followMedia() } }
      routeHandler.removeCallbacksAndMessages(FOLLOW_MEDIA_TOKEN)
      routeHandler.post(check)
      routeHandler.postAtTime(check, FOLLOW_MEDIA_TOKEN, SystemClock.uptimeMillis() + ROUTE_SETTLE_MS)
    }

    private val FOLLOW_MEDIA_TOKEN = Any()

    @Volatile private var inCall = false

    private class RouteWatcher(
      val audioManager: AudioManager,
      val modeListener: AudioManager.OnModeChangedListener,
      val communicationListener: AudioManager.OnCommunicationDeviceChangedListener,
      val deviceCallback: AudioDeviceCallback,
    )

    private var routeWatcher: RouteWatcher? = null

    private fun watchRoutes(context: Context) {
      if (routeWatcher != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
      val audioManager = context.getSystemService(AudioManager::class.java) ?: return
      inCall = isCallMode(audioManager.mode)
      val modeListener =
        AudioManager.OnModeChangedListener { mode ->
          val wasInCall = inCall
          inCall = isCallMode(mode)
          if (wasInCall && mode == AudioManager.MODE_NORMAL) remakeTracks()
          followMediaAll()
        }
      val communicationListener =
        AudioManager.OnCommunicationDeviceChangedListener { followMediaAll() }
      val deviceCallback =
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            followMediaAll()

          override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            followMediaAll()
        }
      try {
        audioManager.addOnModeChangedListener({ it.run() }, modeListener)
        audioManager.addOnCommunicationDeviceChangedListener({ it.run() }, communicationListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, routeHandler)
        routeWatcher = RouteWatcher(audioManager, modeListener, communicationListener, deviceCallback)
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot follow the audio mode and outputs: %s", e)
      }
    }

    private fun stopWatchingRoutes() {
      val watcher = synchronized(instances) { routeWatcher.also { routeWatcher = null } } ?: return
      routeHandler.removeCallbacksAndMessages(FOLLOW_MEDIA_TOKEN)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { watcher.audioManager.removeOnModeChangedListener(watcher.modeListener) }
        runCatching {
          watcher.audioManager.removeOnCommunicationDeviceChangedListener(watcher.communicationListener)
        }
        runCatching { watcher.audioManager.unregisterAudioDeviceCallback(watcher.deviceCallback) }
      }
    }

    private fun isCallMode(mode: Int): Boolean =
      mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

    /**
     * The player for [attributes]' usage, one for each kind of audio, or null if the device cannot
     * make a low-latency track, and audio should play the usual way.
     */
    @JvmStatic
    fun get(context: Context, attributes: AudioAttributes): LowLatencyAudio? =
      synchronized(instances) {
        watchRoutes(context.applicationContext)
        instances
          .getOrPut(attributes.usage) { LowLatencyAudio(context.applicationContext, attributes) }
          .takeIf { it.hasOutput() && !it.broken }
      }

    /** [volume] from 0 to 1, or 0 when it is NaN or infinite. */
    internal fun finiteVolume(volume: Float): Float =
      if (volume.isFinite()) volume.coerceIn(0f, 1f) else 0f

    /** Turns interleaved audio with [channels] channels into stereo frames. */
    @JvmStatic
    fun toStereo(samples: FloatArray, channels: Int): FloatArray =
      when (channels) {
        2 -> samples
        1 -> FloatArray(samples.size * 2) { samples[it / 2] }
        else -> {
          val frames = samples.size / channels
          FloatArray(frames * 2) { i -> samples[(i / 2) * channels + (i % 2)] }
        }
      }
  }
}
