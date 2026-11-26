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
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.android.accessibility.utils.BatteryUsageTracker
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
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
    BatteryUsageTracker.onAudioDspWriteUs(durationUs)
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
  @Volatile private var aaudioStatus: String = "unattempted"
  @Volatile private var aaudioPermanentlyUnavailable = false
  @Volatile private var pendingAAudioRestore = false
  @Volatile private var everHadFastPath = false
  @Volatile private var track: AudioTrack? = null
  // Set when no track can be made, or the track stops taking audio and cannot be remade. Then audio
  // plays the usual way.
  @Volatile private var broken = false
  @Volatile private var brokenAtMs = 0L
  @Volatile private var consecutiveGiveUps = 0
  private val recoveryInFlight = AtomicBoolean(false)
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
  private var detouredForEarpiece = false
  // When the track was last sent to the media output again after it went to the earpiece.
  private var lastResend = 0L
  private val speechBuffer: FloatArray
  private val silentBurst: FloatArray
  private val speechDeclicker: SpeechDeclicker

  init {
    sampleRate =
      audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        ?: DEFAULT_RATE
    burstFrames =
      audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        ?: DEFAULT_BURST
    speechBuffer = FloatArray(burstFrames * 2)
    silentBurst = FloatArray(burstFrames * 2)
    speechDeclicker = SpeechDeclicker(sampleRate)
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
    private val fadeInFrames = maxOf(1, sampleRate * FADE_IN_MS / 1000)
    private val fadeOutFrames = maxOf(1, sampleRate * FADE_OUT_MS / 1000)
    private var fadeInStarted = false
    private var fadeInPos = 0
    internal var lastMixedFrames = 0
      private set
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

    /** Mixes this stream's next frames into [out], and returns whether it has more to play. */
    internal fun mixInto(out: FloatArray, frames: Int): Boolean {
      var written = 0
      val traceOnset = LatencyExperiments.Feature.TRACE.enabled()
      while (written < frames && pending.isNotEmpty()) {
        val head = pending.peekFirst()!!
        val available = head.size / 2 - headOffset
        val count = minOf(available, frames - written)
        val left = volume * minOf(1f, 1 - pan)
        val right = volume * minOf(1f, 1 + pan)
        for (i in 0 until count) {
          var sampleL = head[(headOffset + i) * 2] * left
          var sampleR = head[(headOffset + i) * 2 + 1] * right
          if (traceOnset && !soundStarted && (sampleL != 0f || sampleR != 0f)) {
            soundStarted = true
            soundStartingStreams += this
          }
          if (!fadeInStarted && (abs(sampleL) > EDGE_SILENCE_EPS || abs(sampleR) > EDGE_SILENCE_EPS)) {
            fadeInStarted = true
          }
          if (fadeInStarted && fadeInPos < fadeInFrames) {
            val gain = smoothstep(fadeInPos.toFloat() / fadeInFrames)
            sampleL *= gain
            sampleR *= gain
            fadeInPos++
          }
          if (ended) {
            val remaining = framesWritten - (framesPlayed + written + i)
            if (remaining <= fadeOutFrames) {
              val gain = smoothstep((remaining - 1L).coerceAtLeast(0L).toFloat() / fadeOutFrames)
              sampleL *= gain
              sampleR *= gain
            }
          }
          out[(written + i) * 2] += sampleL
          out[(written + i) * 2 + 1] += sampleR
        }
        written += count
        headOffset += count
        if (headOffset * 2 >= head.size) {
          pending.removeFirst()
          headOffset = 0
        }
      }
      lastMixedFrames = written
      if (started || written > 0) {
        recordGap(gaps.mix(written, frames, !ended, SystemClock.uptimeMillis()))
      }
      if (written > 0 && !started) {
        started = true
        startingStreams += this
        callbacks.execute { listener.onStarted(id) }
      }
      framesPlayed += written
      while (ranges.isNotEmpty() && ranges.peekFirst()!![0] <= framesPlayed) {
        val range = ranges.removeFirst()
        callbacks.execute { listener.onRange(id, range[1].toInt(), range[2].toInt()) }
      }
      if (ended && pending.isEmpty()) {
        finish(completed = true)
        return false
      }
      return true
    }

    /**
     * Whether this stream, at the front of the queue since [now] or before, has waited [STALL_MS]
     * without the engine giving it audio or finishing, such as after the engine was shut down or
     * frozen.
     */
    internal fun isStalled(now: Long): Boolean {
      if (headSince == 0L) headSince = now
      return !ended && pending.isEmpty() && now - maxOf(lastActivity, headSince) > STALL_MS
    }

    /**
     * Ends this stream for another way to play it, without reporting that it finished.
     * [playerFailed] tells whether the player, rather than the engine, is why.
     */
    internal fun abandon(playerFailed: Boolean) {
      recordGap(gaps.close(SpeechGapTracker.Kind.INTERRUPTED, SystemClock.uptimeMillis()))
      finished = true
      pending.clear()
      callbacks.execute {
        if (playerFailed) listener.onPlayerFailed(id) else listener.onStalled(id)
      }
    }

    internal fun finish(completed: Boolean) {
      if (finished) return
      recordGap(gaps.close(
        if (completed) SpeechGapTracker.Kind.TAIL else SpeechGapTracker.Kind.INTERRUPTED,
        SystemClock.uptimeMillis()))
      finished = true
      pending.clear()
      val noAudio = completed && framesWritten == 0L
      callbacks.execute {
        if (noAudio) listener.onNoAudio(id) else listener.onFinished(id, completed)
      }
    }

    /** Stops this stream, whether or not it has started playing. */
    fun stop() {
      synchronized(lock) {
        if (streams.remove(this)) finish(completed = false)
      }
    }

    /** Removes this stream without reporting anything, as if it had never been opened. */
    fun discard() {
      synchronized(lock) {
        recordGap(gaps.close(SpeechGapTracker.Kind.INTERRUPTED, SystemClock.uptimeMillis()))
        streams.remove(this)
        finished = true
        pending.clear()
      }
    }
  }

  /**
   * Opens a speech stream at [volume] from 0 to 2 and [pan] from -1, left, to 1, right. It plays
   * once the streams before it have finished.
   */
  fun openStream(
    id: String,
    volume: Float,
    pan: Float,
    equalizer: IntArray?,
    compressor: SpeechCompressor.Settings?,
    listener: StreamListener,
  ): SpeechStream {
    synchronized(lock) {
      val stream =
        SpeechStream(
          id,
          if (volume.isFinite()) volume.coerceIn(0f, VoiceProfiles.MAX_VOLUME / 100f) else 0f,
          if (pan.isFinite()) pan.coerceIn(-1f, 1f) else 0f,
          equalizer,
          compressor,
          listener,
        )
      if (released || broken) {
        // Shut down or given up on since it was got: said the usual way instead.
        stream.abandon(playerFailed = true)
        return stream
      }
      streams += stream
      wake()
      return stream
    }
  }

  /** Stops every speech stream, which each report that they were stopped. */
  fun stopStreams() {
    synchronized(lock) {
      held = false
      while (streams.isNotEmpty()) streams.removeFirst().finish(completed = false)
    }
  }

  /** The frames held speech has waiting, while held. */
  private fun heldFrames(): Long = streams.sumOf { it.pendingFrames() }

  /**
   * Holds speech where it is, mid-word if need be, so that [resume] carries on from exactly there,
   * and returns the ID of the speech stream held, or null if there is no speech to hold. The streams
   * keep receiving the engine's audio meanwhile, up to [MAX_HELD_MS]. Sounds still play.
   */
  fun hold(): String? =
    synchronized(lock) {
      val head = streams.peekFirst() ?: return null
      held = true
      holdFull = false
      head.id
    }

  /** Carries on playing held speech. */
  fun resume() {
    synchronized(lock) {
      held = false
      holdFull = false
      wake()
    }
  }

  /**
   * Stops everything and releases the track. Speech streams report that they were stopped. The
   * player cannot be used again: [get] makes a new one.
   */
  fun shutdown() {
    synchronized(lock) {
      if (released) return
      released = true
      held = false
      clips.clear()
      while (streams.isNotEmpty()) streams.removeFirst().finish(completed = false)
      if (thread == null) {
        nativeOutput?.close()
        nativeOutput = null
        track?.release()
        track = null
      } else {
        // The audio thread releases the track as it ends.
        interrupt(nativeOutput, track)
        lock.notifyAll()
      }
    }
  }

  /** The state of one player, read in one go for diagnostics. Buffer sizes are -1 with no track. */
  data class Snapshot(
    val usage: Int,
    val sampleRate: Int,
    val burstFrames: Int,
    val broken: Boolean,
    val released: Boolean,
    val audioThreadRunning: Boolean,
    val fastPath: Boolean,
    val bufferSizeInFrames: Int,
    val bufferCapacityInFrames: Int,
    val underrunCount: Int,
    val playState: Int,
    val routedDeviceType: Int?,
    val routedDeviceName: String?,
    val resumedShortages: String,
    val tailWaits: String,
    val interruptedShortages: String,
    val pendingShortages: Int,
    val dspWrites: String,
    val aaudioStatus: String = "unattempted",
  )

  /** This player's state now, for diagnostics. */
  fun snapshot(): Snapshot =
    synchronized(lock) {
      val nativeOut = nativeOutput
      val output = track
      var bufferSize = -1
      var bufferCapacity = -1
      var underrunCount = -1
      var playState = -1
      var deviceType: Int? = null
      var deviceName: String? = null
      if (nativeOut != null) {
        bufferSize = nativeOut.bufferSizeInFrames
        bufferCapacity = nativeOut.bufferCapacityInFrames
        underrunCount = nativeOut.xRunCount
        playState = nativeOut.playState
        val suffix = if (nativeOut.isMMapUsed) " [AAudio MMAP]" else " [AAudio]"
        val routed =
          audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.id == nativeOut.deviceId }
        if (routed != null) {
          deviceType = routed.type
          deviceName = (routed.productName?.toString() ?: "") + suffix
        } else {
          deviceName = suffix.trim()
        }
      } else if (output != null) {
        try {
          bufferSize = output.bufferSizeInFrames
          bufferCapacity = output.bufferCapacityInFrames
          underrunCount = output.underrunCount
          playState = output.playState
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            output.routedDevice?.let {
              deviceType = it.type
              deviceName = it.productName?.toString()
            }
          }
        } catch (e: IllegalStateException) {
          // Released while being read.
        }
      }
      Snapshot(
        usage = attributes.usage,
        sampleRate = sampleRate,
        burstFrames = burstFrames,
        broken = broken,
        released = released,
        audioThreadRunning = thread != null,
        fastPath = fastPath,
        bufferSizeInFrames = bufferSize,
        bufferCapacityInFrames = bufferCapacity,
        underrunCount = underrunCount,
        playState = playState,
        routedDeviceType = deviceType,
        routedDeviceName = deviceName,
        resumedShortages = gapSummary(SpeechGapTracker.Kind.RESUMED),
        tailWaits = gapSummary(SpeechGapTracker.Kind.TAIL),
        interruptedShortages = gapSummary(SpeechGapTracker.Kind.INTERRUPTED),
        pendingShortages = streams.count { it.gaps.waiting },
        dspWrites = dspWriteSummary(),
        aaudioStatus = aaudioStatus,
      )
    }

  /** Starts the audio thread and its watchdog if they are not running. Called with [lock] held. */
  private fun wake() {
    idleFrames = 0
    lastWrite = SystemClock.uptimeMillis()
    if (released || broken) {
      lock.notifyAll()
      return
    }
    if (thread != null) {
      ensureWatchdogLocked()
      lock.notifyAll()
      return
    }
    remakeTrack = false
    waking = false
    thread = Thread(::run, "LowLatencyAudio").apply { start() }
    ensureWatchdogLocked()
  }

  /** Schedules the stall watchdog if not already running. Called with [lock] held. */
  private fun ensureWatchdogLocked() {
    if (watchdog != null || released || broken) return
    // Separate from the audio thread, so that a stuck track cannot stop it.
    watchdog =
      WATCHDOG.scheduleWithFixedDelay(
        ::checkForStalls,
        WATCHDOG_MS,
        WATCHDOG_MS,
        TimeUnit.MILLISECONDS,
      )
  }

  /** Makes a write blocked on [nativeOut] or [output] return, so the audio thread can go on. */
  private fun interrupt(nativeOut: NativeAAudioOutput?, output: AudioTrack?) {
    nativeOut?.requestStop()
    try {
      output?.pause()
    } catch (e: IllegalStateException) {
      // Released already.
    }
  }

  /**
   * Drops a speech stream the engine stopped feeding, and gives up on the track if it stopped taking
   * audio, so that speech never waits in silence. Abandoned speech is said the usual way.
   */
  private fun checkForStalls() {
    synchronized(lock) {
      if (thread == null) return
      val now = SystemClock.uptimeMillis()
      val head = if (held) null else streams.peekFirst()
      if (head != null && head.isStalled(now)) {
        streams.removeFirst()
        head.abandon(playerFailed = false)
        LogUtils.w(TAG, "Speech %s got no audio in time", head.id)
      }
      val stuckFor = now - lastWrite
      val extra = if (waking) WAKE_STUCK_MS else 0L
      if (stuckFor > TRACK_STUCK_MS + extra) {
        LogUtils.e(TAG, "Track took no audio for %d ms", stuckFor)
        giveUp()
      } else if (stuckFor > WRITE_STUCK_MS + extra && !remakeTrack) {
        // Android stopped taking audio on this track, such as after its output changed.
        LogUtils.w(TAG, "Track took no audio for %d ms, making a new one", stuckFor)
        remakeTrack = true
        interrupt(nativeOutput, track)
      }
    }
  }

  internal fun isActivelyPlayingUsage(usage: Int): Boolean {
    if (attributes.usage != usage || released || broken) return false
    val nativePlaying = nativeOutput?.playState == AudioTrack.PLAYSTATE_PLAYING
    val trackPlaying =
      try {
        track?.playState == AudioTrack.PLAYSTATE_PLAYING
      } catch (_: IllegalStateException) {
        false
      }
    return nativePlaying || trackPlaying
  }

  /** Temporarily yields AAudio MMAP to AudioTrack while external audio is playing. */
  internal fun yieldAAudioForExternalAudio() {
    val needsOffThreadRemake =
      synchronized(lock) {
        pendingAAudioRestore = false
        if (released || broken || nativeOutput == null) return
        if (thread != null) {
          remakeTrack = true
          interrupt(nativeOutput, track)
          lock.notifyAll()
          false
        } else {
          true
        }
      }
    if (needsOffThreadRemake) ROUTES.execute { remake() }
  }

  /** Restores AAudio MMAP once external audio has finished. */
  internal fun restoreAAudioAfterExternalAudio() {
    val needsOffThreadRemake =
      synchronized(lock) {
        if (released || broken || aaudioPermanentlyUnavailable || nativeOutput != null) return
        if (thread == null) {
          pendingAAudioRestore = false
          true
        } else if (clips.isEmpty() && (streams.isEmpty() || held)) {
          pendingAAudioRestore = false
          remakeTrack = true
          lock.notifyAll()
          false
        } else {
          // Finish the current utterance cleanly on AudioTrack, then switch back as soon as idle.
          pendingAAudioRestore = true
          false
        }
      }
    if (needsOffThreadRemake) ROUTES.execute { remake() }
  }

  /**
   * Swaps an active AudioTrack back to native AAudio while [lock] is held. Returns true if a
   * backend swap was attempted (caller should re-enter the main loop to inspect updated state).
   */
  private fun tryRestoreAAudioLocked(): Boolean {
    if (!pendingAAudioRestore ||
      nativeOutput != null ||
      yieldAAudioForExternalAudio ||
      aaudioPermanentlyUnavailable) {
      return false
    }
    pendingAAudioRestore = false
    val oldTrack = track
    track = null
    primed = false
    oldTrack?.release()
    if (!openOutput()) {
      giveUp()
      end(nativeOutput, track)
    }
    return true
  }

  private fun remake() {
    synchronized(lock) {
      if (released || broken) return
      if (thread != null) {
        remakeTrack = true
        interrupt(nativeOutput, track)
        lock.notifyAll()
        return
      }
      nativeOutput?.close()
      nativeOutput = null
      track?.release()
      track = null
      primed = false
      if (!openOutput()) giveUp()
    }
  }

  /** Stops using the low-latency track: everything waiting plays the usual way. */
  private fun giveUp() {
    synchronized(lock) {
      broken = true
      brokenAtMs = SystemClock.uptimeMillis()
      consecutiveGiveUps++
      clips.clear()
      while (streams.isNotEmpty()) streams.removeFirst().abandon(playerFailed = true)
      if (thread == null) {
        nativeOutput?.close()
        nativeOutput = null
        track?.release()
        track = null
      } else {
        interrupt(nativeOutput, track)
        lock.notifyAll()
      }
    }
  }

  /**
   * Attempts to reopen a player that previously gave up (e.g., due to a transient failure during a
   * phone call or audio route transition). Returns true if the player is healthy again.
   */
  internal fun tryRecover(force: Boolean = false): Boolean {
    if (released || !broken) return !released && hasOutput()
    val now = SystemClock.uptimeMillis()
    if (!shouldAttemptRecovery(brokenAtMs, now, consecutiveGiveUps, force)) return false
    if (!recoveryInFlight.compareAndSet(false, true)) return false
    try {
      synchronized(lock) {
        if (released || !broken || thread != null) return !released && !broken && hasOutput()
        broken = false
        remakeTrack = false
        primed = false
        if (openOutput()) {
          LogUtils.i(TAG, "Recovered low-latency audio player (force=%b)", force)
          return true
        }
        broken = true
        brokenAtMs = SystemClock.uptimeMillis()
        if (!force) consecutiveGiveUps++
        return false
      }
    } finally {
      recoveryInFlight.set(false)
    }
  }

  /** Resets failure backoff and tries to recover a broken player after a call or device change. */
  internal fun recoverAfterRouteChange() {
    if (released) return
    if (!broken) {
      remake()
      return
    }
    consecutiveGiveUps = 0
    tryRecover(force = true)
  }

  private fun run() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val buffer = FloatArray(burstFrames * 2)
    while (true) {
      val currentNative: NativeAAudioOutput?
      val output: AudioTrack?
      val starts: List<SpeechStream>
      val soundStarts: List<SpeechStream>
      val burstAudible: Boolean
      synchronized(lock) {
        startingStreams.clear()
        soundStartingStreams.clear()
        currentNative = nativeOutput
        output = track
        val idle = clips.isEmpty() && (streams.isEmpty() || held)
        if (released || broken || (currentNative == null && output == null)) {
          end(currentNative, output)
          return
        }
        if ((idle || !primed) &&
          pendingAAudioRestore &&
          nativeOutput == null &&
          !yieldAAudioForExternalAudio &&
          !aaudioPermanentlyUnavailable) {
          pauseOutputLocked(currentNative, output)
          if (tryRestoreAAudioLocked()) {
            lastWrite = SystemClock.uptimeMillis()
            idleFrames = if (idle) idleLimit() else 0
            continue
          }
        }
        if (idle && idleFrames >= idleLimit()) {
          pauseOutputLocked(currentNative, output)
          watchdog?.cancel(false)
          watchdog = null
          val parkUntil = SystemClock.uptimeMillis() + THREAD_IDLE_EXIT_MS
          while (!released && !broken && !remakeTrack &&
            clips.isEmpty() && (streams.isEmpty() || held)) {
            val remaining = parkUntil - SystemClock.uptimeMillis()
            if (remaining <= 0L) {
              end(nativeOutput, track)
              return
            }
            try {
              lock.wait(remaining)
            } catch (e: InterruptedException) {
              break
            }
          }
          if (released || broken || (nativeOutput == null && track == null)) {
            end(nativeOutput, track)
            return
          }
          if (remakeTrack) {
            val oldNative = nativeOutput
            val oldTrack = track
            nativeOutput = null
            track = null
            primed = false
            oldNative?.close()
            oldTrack?.release()
            remakeTrack = false
            if (!openOutput()) {
              giveUp()
              end(nativeOutput, track)
              return
            }
            // If we only woke to swap backends while still idle, stay parked without waking the DAC.
            if (clips.isEmpty() && (streams.isEmpty() || held)) {
              continue
            }
          }
          lastWrite = SystemClock.uptimeMillis()
          idleFrames = 0
          ensureWatchdogLocked()
          continue
        }
        if (currentNative == null && output != null && !fastPath) {
          // Silence queued without the fast path would delay the next sound by the whole buffer, so
          // silence plays only until the audio before it has been heard. Then the track pauses.
          val audible = hasAudio()
          if (primed && !audible && silentFrames >= output.bufferSizeInFrames) pauseAndFlush(output)
          if (!primed && !readyToStart(output)) {
            if (idle) {
              if (tryRestoreAAudioLocked()) {
                lastWrite = SystemClock.uptimeMillis()
                idleFrames = idleLimit()
                continue
              }
              end(null, output)
              return
            }
            // Speech waiting for the engine. The thread stays, so the watchdog can drop a stall.
            lastWrite = SystemClock.uptimeMillis()
            lock.wait(REST_MS)
            continue
          }
          buffer.fill(0f)
          mix(buffer)
          // A stream that ended with nothing left finishes without starting the track.
          if (!primed && !audible) continue
          silentFrames = if (audible) 0 else silentFrames + burstFrames
        } else {
          buffer.fill(0f)
          mix(buffer)
        }
        primed = true
        starts = startingStreams.toList()
        soundStarts = soundStartingStreams.toList()
        burstAudible = buffer.any { it != 0f }
        if (idle) idleFrames += burstFrames
      }
      val wrote =
        if (currentNative != null) {
          !remakeTrack && playNative(currentNative) && writeNative(currentNative, buffer)
        } else {
          output != null && !remakeTrack && play(output) && write(output, buffer)
        }
      if (wrote) {
        consecutiveGiveUps = 0
        BatteryUsageTracker.onAudioBurstUs(burstAudible, burstFrames * 1_000_000L / sampleRate)
        val submittedAt = SystemClock.uptimeMillis()
        for (stream in starts) {
          callbacks.execute { stream.reportAudioSubmitted(submittedAt) }
        }
        for (stream in soundStarts) {
          callbacks.execute { Performance.getInstance().onFirstSoundSubmitted(stream.id, submittedAt) }
        }
        continue
      }
      if (released || broken) continue
      // Android stopped taking audio on this track, such as after its output changed. Make a new
      // one, or play the usual way from now on.
      synchronized(lock) {
        nativeOutput = null
        track = null
        primed = false
      }
      currentNative?.close()
      output?.release()
      if (currentNative != null) {
        refreshExternalPlaybackNow(audioManager)
      }
      val reopened = openOutput()
      synchronized(lock) {
        remakeTrack = false
        if (!reopened) giveUp()
      }
    }
  }

  /**
   * Ends the audio thread and its watchdog. The track is paused for the next sound, or released if
   * it will not be used again. Called with [lock] held.
   */
  private fun end(nativeOut: NativeAAudioOutput?, output: AudioTrack?) {
    thread = null
    watchdog?.cancel(false)
    watchdog = null
    if (released || broken) {
      nativeOutput = null
      track = null
      primed = false
      nativeOut?.close()
      output?.release()
      return
    }
    pauseOutputLocked(nativeOut, output)
  }

  private fun pauseOutputLocked(nativeOut: NativeAAudioOutput?, output: AudioTrack?) {
    speechDeclicker.reset()
    if (nativeOut != null) {
      primed = false
      nativeOut.pauseAndFlush()
    } else if (output != null) {
      pauseAndFlush(output)
    }
  }

  private fun playNative(output: NativeAAudioOutput): Boolean {
    if (output.playState != AudioTrack.PLAYSTATE_PLAYING) {
      if (!output.start()) return false
      starting = true
      // Prime the DAC/mixer with one silent burst so the hardware unmutes on silence.
      output.write(silentBurst, burstFrames)
    }
    return true
  }

  private fun writeNative(output: NativeAAudioOutput, buffer: FloatArray): Boolean {
    if (!output.write(buffer, burstFrames)) return false
    lastWrite = SystemClock.uptimeMillis()
    waking = false
    keepUpNative(output)
    return true
  }

  private fun keepUpNative(output: NativeAAudioOutput) {
    val count = output.xRunCount
    if (starting || settleWrites > 0) {
      starting = false
      if (settleWrites > 0) settleWrites--
      underruns = count
      return
    }
    if (count <= underruns) return
    underruns = count
    if (smallBufferEnabled && !outgrewSmallBuffer) {
      smallShortfalls++
      if (smallShortfalls < SMALL_BUFFER_SHORTFALL_LIMIT) return
      outgrewSmallBuffer = true
    }
    val size = output.bufferSizeInFrames
    val capacity = output.bufferCapacityInFrames
    if (size <= 0 || size >= capacity) return
    val step = maxOf(output.framesPerBurst, burstFrames)
    val grown = minOf(size + step, capacity)
    val actual = output.setBufferSizeInFrames(grown)
    if (actual > 0) {
      settleWrites = 2 * actual / maxOf(1, burstFrames)
      fastFrames = actual
      LogUtils.d(TAG, "AAudio ran short, now %d frames", actual)
    }
  }

  private fun hasOutput(): Boolean = nativeOutput != null || track != null

  /**
   * Opens the lowest-latency output available: native C++ AAudio (MMAP / LOW_LATENCY) first, then
   * falls back transparently to [AudioTrack].
   */
  private fun openOutput(): Boolean {
    prepareInitialDetour()
    if (yieldAAudioForExternalAudio) {
      aaudioStatus = "yielded_for_external_audio"
    } else {
      val opened =
        NativeAAudioOutput.open(
          sampleRate = sampleRate,
          androidUsage = attributes.usage,
          deviceId = detour?.id ?: 0,
          initialBursts = if (smallBufferEnabled && !outgrewSmallBuffer) 1 else QUEUED_BURSTS,
        )
      aaudioStatus = NativeAAudioOutput.lastOpenStatus
      if (opened != null) {
        if (opened.sampleRate == sampleRate) {
          aaudioPermanentlyUnavailable = false
          if (fastFrames > 0) {
            opened.setBufferSizeInFrames(fastFrames)
          }
          nativeOutput = opened
          track = null
          fastPath = true
          everHadFastPath = true
          underruns = opened.xRunCount
          BatteryUsageTracker.onAudioBackendSelected(
            if (opened.isMMapUsed) "AAudio MMAP" else "AAudio"
          )
          LogUtils.d(
            TAG,
            "Using native AAudio: %d Hz, burst=%d, buffer=%d/%d, mmap=%b",
            opened.sampleRate,
            opened.framesPerBurst,
            opened.bufferSizeInFrames,
            opened.bufferCapacityInFrames,
            opened.isMMapUsed,
          )
          return true
        }
        aaudioStatus = "sample_rate_mismatch(got=${opened.sampleRate},want=$sampleRate)"
        opened.close()
        aaudioPermanentlyUnavailable = true
      } else if (!NativeAAudioOutput.isAvailable) {
        aaudioPermanentlyUnavailable = true
      }
    }
    val fallback = createTrack()
    nativeOutput = null
    track = fallback
    if (fallback != null) {
      BatteryUsageTracker.onAudioBackendSelected(
        if (fastPath) "AudioTrack Fast" else "AudioTrack Slow"
      )
    }
    return fallback != null
  }

  /** Pauses the track and drops what it has queued. Called with [lock] held. */
  private fun pauseAndFlush(output: AudioTrack) {
    primed = false
    try {
      if (output.playState == AudioTrack.PLAYSTATE_PLAYING) {
        output.pause()
        output.flush()
      }
    } catch (e: IllegalStateException) {
      // Released already.
    }
  }

  /** Whether the next burst has any sound or speech in it. Called with [lock] held. */
  private fun hasAudio(): Boolean {
    if (clips.isNotEmpty()) return true
    val head = if (held) null else streams.peekFirst()
    return head != null && head.pendingFrames() > 0
  }

  /**
   * Whether there is enough to start the paused track on without the fast path, which waits for a
   * full buffer before it plays: a sound, which is all there at once, or speech that fills the
   * buffer, has ended, or has stopped coming for REST_MS. Called with [lock] held.
   */
  private fun readyToStart(output: AudioTrack): Boolean {
    if (clips.isNotEmpty()) return true
    if (held) return false
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
        if (fastPath) {
          // Prime the fast mixer with one silent burst (~5.3 ms at 48 kHz) so the DAC/amp
          // unmutes cleanly on silence rather than popping on the first speech sample.
          output.write(silentBurst, 0, silentBurst.size, AudioTrack.WRITE_BLOCKING)
        } else {
          waking = true
        }
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
      else if (LatencyExperiments.Feature.AUDIO_KEEP_WARM.enabled()) KEEP_WARM_IDLE_MS
      else IDLE_MS) / 1000

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
    speechBuffer.fill(0f)
    val head = if (held) null else streams.peekFirst()
    var speechWritten = 0
    var streamEnded = false
    if (head != null) {
      val hasMore = head.mixInto(speechBuffer, burstFrames)
      speechWritten = head.lastMixedFrames
      if (!hasMore) {
        streams.removeFirst()
        streamEnded = true
      }
    }
    speechDeclicker.processBurst(speechBuffer, burstFrames, head, speechWritten, streamEnded)
    for (i in out.indices) out[i] += speechBuffer[i]
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
              val audioTrack = routed as AudioTrack
              fitBuffer(audioTrack)
              val routedType = audioTrack.routedDevice?.type
              ROUTES.execute {
                LogUtils.d(TAG, "Track on %s", describe(audioTrack.routedDevice))
                followMedia()
                if (!fastPath &&
                  everHadFastPath &&
                  routedType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                  track === audioTrack &&
                  !released &&
                  !broken) {
                  LogUtils.d(TAG, "Track returned to speaker without fast path; recreating for low latency")
                  remake()
                }
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
      if (fast) everHadFastPath = true
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

  /**
   * Computes whether a newly created [AudioTrack] should be detoured to the media output (e.g.,
   * full stereo speaker instead of earpiece or mono speaker-safe during a notification) before
   * playback starts.
   */
  private fun prepareInitialDetour() {
    val am = audioManager ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || released || broken) return
    try {
      val ours = am.getAudioDevicesForAttributes(attributes).firstOrNull()?.type
      val media = am.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
      val send = sendToMedia(am.mode, ours, null, media?.type, stickyDetour, detouredForEarpiece)
      val earpieceRoute = ours == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
      stickyDetour =
        send && !isRestrictedSpeaker(ours) && ours != media?.type && (earpieceRoute || stickyDetour)
      detouredForEarpiece = send && (earpieceRoute || stickyDetour || detouredForEarpiece)
      detour = media.takeIf { send }
    } catch (_: RuntimeException) {
      // Fall back to default routing if AudioManager query fails.
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
          sendToMedia(am.mode, ours, routed, media?.type, stickyDetour, detouredForEarpiece)
        val earpieceRoute =
          ours == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            routed == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        stickyDetour =
          send && !isRestrictedSpeaker(ours) && ours != media?.type && (earpieceRoute || stickyDetour)
        detouredForEarpiece = send && (earpieceRoute || stickyDetour || detouredForEarpiece)
        media.takeIf { send }
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot find the media output: %s", e)
        return
      }
    detour = target
    try {
      if (output.preferredDevice?.id == target?.id) {
        if (target == null || !isRestrictedSpeaker(output.routedDevice?.type)) {
          return
        }
        // The track was sent to the media output while it still played there, which Android
        // ignores, and then went to the earpiece/speaker-safe with the rest of accessibility
        // audio. Setting the same device again is ignored too, so it is cleared first.
        val now = SystemClock.uptimeMillis()
        if (now - lastResend < ROUTE_SETTLE_MS) {
          recheckRoute(lastResend + ROUTE_SETTLE_MS)
          return
        }
        lastResend = now
        LogUtils.d(TAG, "Track went to restricted speaker, sending it to %s again", describe(target))
        output.setPreferredDevice(null)
      } else if (target == null) {
        LogUtils.d(TAG, "Track plays where Android routes it again")
      } else {
        LogUtils.d(
          TAG,
          "Accessibility audio is on restricted speaker outside a call, sending the track to %s",
          describe(target),
        )
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

  /**
   * Eliminates clicks and pops on the speech bus caused by abrupt stream cuts (interrupt/stop/
   * hold/underrun), stream switches, and TTS DC offset, without adding playback latency.
   */
  internal class SpeechDeclicker(sampleRate: Int) {
    private val decayFrames = maxOf(1, sampleRate * INTERRUPT_FADE_OUT_MS / 1000)
    private val dcCoeff = (1.0 - 2.0 * PI * DC_BLOCKER_HZ / maxOf(1, sampleRate)).toFloat()
    private var activeSource: Any? = null
    private var lastL = 0f
    private var lastR = 0f
    private var decayL = 0f
    private var decayR = 0f
    private var decayPos = decayFrames
    private var dcPrevInL = 0f
    private var dcPrevOutL = 0f
    private var dcPrevInR = 0f
    private var dcPrevOutR = 0f

    fun reset() {
      activeSource = null
      lastL = 0f
      lastR = 0f
      decayL = 0f
      decayR = 0f
      decayPos = decayFrames
      dcPrevInL = 0f
      dcPrevOutL = 0f
      dcPrevInR = 0f
      dcPrevOutR = 0f
    }

    fun processBurst(
      speechBuffer: FloatArray,
      frames: Int,
      source: Any?,
      speechWritten: Int,
      sourceEnded: Boolean,
    ) {
      // Capture the new stream's raw last sample before blending any prior stream's decay tail.
      val rawLastL = if (speechWritten > 0) speechBuffer[(speechWritten - 1) * 2] else 0f
      val rawLastR = if (speechWritten > 0) speechBuffer[(speechWritten - 1) * 2 + 1] else 0f

      // If the active speech stream stopped, switched, was held, or starved at the start of
      // this burst while still at a non-zero sample, smoothly ramp its last sample down to 0.
      if (source !== activeSource || speechWritten == 0) {
        armDecayFromLast()
        lastL = 0f
        lastR = 0f
        activeSource = if (speechWritten > 0 && !sourceEnded) source else null
      }

      // Blend any active de-click decay from frame 0 (crossfades cleanly with a new stream).
      var hadDecay = false
      if (decayPos < decayFrames) {
        hadDecay = true
        val limit = minOf(frames, decayFrames - decayPos)
        for (i in 0 until limit) {
          val gain = 1f - smoothstep((decayPos + 1).toFloat() / decayFrames)
          speechBuffer[i * 2] += decayL * gain
          speechBuffer[i * 2 + 1] += decayR * gain
          decayPos++
        }
      }

      if (speechWritten > 0) {
        lastL = rawLastL
        lastR = rawLastR
        if (speechWritten < frames) {
          // The stream finished or starved mid-burst: start decaying immediately at
          // speechWritten so there is no step drop to 0 inside this burst.
          if (abs(lastL) > EDGE_SILENCE_EPS || abs(lastR) > EDGE_SILENCE_EPS) {
            hadDecay = true
            val tailL = lastL
            val tailR = lastR
            val tailCount = minOf(frames - speechWritten, decayFrames)
            for (k in 0 until tailCount) {
              val gain = 1f - smoothstep((k + 1).toFloat() / decayFrames)
              speechBuffer[(speechWritten + k) * 2] += tailL * gain
              speechBuffer[(speechWritten + k) * 2 + 1] += tailR * gain
            }
            if (tailCount < decayFrames) {
              // Carry the remaining decay into the next burst (merging if an older decay still runs).
              if (decayPos < decayFrames) {
                val remGain = 1f - smoothstep(decayPos.toFloat() / decayFrames)
                decayL = decayL * remGain + tailL * (1f - smoothstep(tailCount.toFloat() / decayFrames))
                decayR = decayR * remGain + tailR * (1f - smoothstep(tailCount.toFloat() / decayFrames))
                decayPos = tailCount
              } else {
                decayL = tailL
                decayR = tailR
                decayPos = tailCount
              }
            }
          }
          lastL = 0f
          lastR = 0f
        }
        activeSource = if (sourceEnded) null else source
        if (sourceEnded && speechWritten == frames) {
          // If the stream ended on the exact last frame of the burst, arm decay for the next
          // burst only if its final sample was still non-zero.
          armDecayFromLast()
          lastL = 0f
          lastR = 0f
        }
      }

      val hasDcTail =
        abs(dcPrevOutL) >= EDGE_SILENCE_EPS ||
          abs(dcPrevOutR) >= EDGE_SILENCE_EPS ||
          dcPrevInL != 0f ||
          dcPrevInR != 0f
      if (speechWritten == 0 && !hadDecay && !hasDcTail) {
        dcPrevOutL = 0f
        dcPrevOutR = 0f
        return
      }

      for (i in 0 until frames) {
        val l = speechBuffer[i * 2]
        val yl = l - dcPrevInL + dcCoeff * dcPrevOutL
        dcPrevInL = l
        dcPrevOutL = yl
        speechBuffer[i * 2] = yl

        val r = speechBuffer[i * 2 + 1]
        val yr = r - dcPrevInR + dcCoeff * dcPrevOutR
        dcPrevInR = r
        dcPrevOutR = yr
        speechBuffer[i * 2 + 1] = yr
      }
      if (abs(dcPrevOutL) < DENORMAL) dcPrevOutL = 0f
      if (abs(dcPrevOutR) < DENORMAL) dcPrevOutR = 0f
    }

    private fun armDecayFromLast() {
      if (abs(lastL) > EDGE_SILENCE_EPS || abs(lastR) > EDGE_SILENCE_EPS) {
        decayL = lastL
        decayR = lastR
        decayPos = 0
      }
    }
  }

  companion object {
    private const val TAG = "LowLatencyAudio"
    internal const val FADE_IN_MS = 3
    internal const val FADE_OUT_MS = 4
    internal const val INTERRUPT_FADE_OUT_MS = 4
    internal const val DC_BLOCKER_HZ = 20.0
    internal const val EDGE_SILENCE_EPS = 1e-5f
    private const val DENORMAL = 1e-20f

    internal fun smoothstep(t: Float): Float {
      val x = t.coerceIn(0f, 1f)
      return x * x * (3f - 2f * x)
    }
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
    private const val IDLE_MS = 180
    internal const val KEEP_WARM_IDLE_MS = 450
    private const val THREAD_IDLE_EXIT_MS = 20_000L
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
    private fun isRestrictedSpeaker(type: Int?): Boolean =
      type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
          type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)

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
        !isRestrictedSpeaker(mediaOutput) &&
        (isRestrictedSpeaker(ours) ||
          isRestrictedSpeaker(routed) ||
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
      for (player in players) {
        if (player.broken) {
          ROUTES.execute { player.recoverAfterRouteChange() }
        } else {
          player.remake()
        }
      }
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
      stopWatchingPlayback()
    }

    private fun followMediaAll() {
      val check = Runnable {
        ROUTES.execute {
          val now = SystemClock.uptimeMillis()
          for (player in players()) {
            if (player.broken) {
              if (now - player.brokenAtMs >= ROUTE_SETTLE_MS) {
                player.recoverAfterRouteChange()
              }
            } else {
              player.followMedia()
            }
          }
        }
      }
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
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            onExternalPlaybackChanged(audioManager, hasExternalPlaybackNow(audioManager))
          }
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

    private const val AAUDIO_RESTORE_DELAY_MS = 350L
    private const val AAUDIO_RECHECK_DELAY_MS = 500L
    private val AAUDIO_RESTORE_TOKEN = Any()
    private val AAUDIO_RECHECK_TOKEN = Any()
    @Volatile private var yieldAAudioForExternalAudio = false

    private class PlaybackWatcher(
      val audioManager: AudioManager,
      val callback: AudioManager.AudioPlaybackCallback,
    )

    private var playbackWatcher: PlaybackWatcher? = null

    /**
     * Returns whether any non-accessibility audio playback (ringtone, alarm, notification, media,
     * game, call, etc.) is active outside Meridian's own playing tracks.
     */
    @VisibleForTesting
    @JvmStatic
    fun hasExternalPlayback(
      activeUsages: List<Int>,
      ownPlayingUsages: List<Int>,
      audioMode: Int,
    ): Boolean {
      if (audioMode == AudioManager.MODE_RINGTONE ||
        audioMode == AudioManager.MODE_IN_CALL ||
        audioMode == AudioManager.MODE_IN_COMMUNICATION) {
        return true
      }
      if (activeUsages.isEmpty()) return false
      val counts = HashMap<Int, Int>()
      for (usage in activeUsages) {
        if (usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY ||
          usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
          continue
        }
        counts[usage] = (counts[usage] ?: 0) + 1
      }
      if (counts.isEmpty()) return false
      for (ownUsage in ownPlayingUsages) {
        val current = counts[ownUsage] ?: continue
        if (current <= 1) counts.remove(ownUsage) else counts[ownUsage] = current - 1
      }
      return counts.isNotEmpty()
    }

    private fun hasExternalPlaybackNow(
      am: AudioManager,
      configs: List<AudioPlaybackConfiguration>? = null,
    ): Boolean {
      val list =
        configs
          ?: try {
            am.activePlaybackConfigurations
          } catch (_: RuntimeException) {
            return false
          }
      val usages = list.map { it.audioAttributes.usage }
      val ownUsages = ArrayList<Int>()
      for (player in players()) {
        val u = player.attributes.usage
        if (u != AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY &&
          u != AudioAttributes.USAGE_ASSISTANCE_SONIFICATION &&
          player.isActivelyPlayingUsage(u)) {
          ownUsages += u
        }
      }
      val mode = try { am.mode } catch (_: RuntimeException) { AudioManager.MODE_NORMAL }
      return hasExternalPlayback(usages, ownUsages, mode)
    }

    private fun scheduleExternalPlaybackRecheck(am: AudioManager) {
      routeHandler.removeCallbacksAndMessages(AAUDIO_RECHECK_TOKEN)
      routeHandler.postAtTime(
        {
          if (yieldAAudioForExternalAudio && !hasExternalPlaybackNow(am)) {
            onExternalPlaybackChanged(am, false)
          }
        },
        AAUDIO_RECHECK_TOKEN,
        SystemClock.uptimeMillis() + AAUDIO_RECHECK_DELAY_MS,
      )
    }

    private fun refreshExternalPlaybackNow(am: AudioManager?) {
      if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      if (hasExternalPlaybackNow(am)) {
        routeHandler.removeCallbacksAndMessages(AAUDIO_RESTORE_TOKEN)
        if (!yieldAAudioForExternalAudio) {
          yieldAAudioForExternalAudio = true
          LogUtils.d(TAG, "External audio active (detected on reopen); switching AAudio to AudioTrack")
        }
        scheduleExternalPlaybackRecheck(am)
      }
    }

    private fun onExternalPlaybackChanged(am: AudioManager, active: Boolean) {
      if (active) {
        routeHandler.removeCallbacksAndMessages(AAUDIO_RESTORE_TOKEN)
        if (!yieldAAudioForExternalAudio) {
          yieldAAudioForExternalAudio = true
          LogUtils.d(TAG, "External audio active; temporarily switching AAudio to AudioTrack")
          for (player in players()) player.yieldAAudioForExternalAudio()
        }
      } else if (yieldAAudioForExternalAudio) {
        routeHandler.removeCallbacksAndMessages(AAUDIO_RESTORE_TOKEN)
        routeHandler.postAtTime(
          {
            if (!hasExternalPlaybackNow(am)) {
              yieldAAudioForExternalAudio = false
              LogUtils.d(TAG, "External audio ended; restoring AAudio")
              for (player in players()) player.restoreAAudioAfterExternalAudio()
            }
          },
          AAUDIO_RESTORE_TOKEN,
          SystemClock.uptimeMillis() + AAUDIO_RESTORE_DELAY_MS,
        )
      }
    }

    private fun watchPlayback(context: Context) {
      if (playbackWatcher != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val am = context.getSystemService(AudioManager::class.java) ?: return
      yieldAAudioForExternalAudio = hasExternalPlaybackNow(am)
      val callback =
        object : AudioManager.AudioPlaybackCallback() {
          override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onExternalPlaybackChanged(am, hasExternalPlaybackNow(am, configs))
          }
        }
      try {
        am.registerAudioPlaybackCallback(callback, routeHandler)
        playbackWatcher = PlaybackWatcher(am, callback)
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot monitor external audio playback: %s", e)
      }
    }

    private fun stopWatchingPlayback() {
      val watcher = synchronized(instances) { playbackWatcher.also { playbackWatcher = null } }
      routeHandler.removeCallbacksAndMessages(AAUDIO_RESTORE_TOKEN)
      routeHandler.removeCallbacksAndMessages(AAUDIO_RECHECK_TOKEN)
      yieldAAudioForExternalAudio = false
      if (watcher != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { watcher.audioManager.unregisterAudioPlaybackCallback(watcher.callback) }
      }
    }

    /**
     * The player for [attributes]' usage, one for each kind of audio, or null if the device cannot
     * make a low-latency track, and audio should play the usual way.
     */
    private const val RECOVERY_COOLDOWN_MS = 10_000L
    private const val MAX_RECOVERY_COOLDOWN_MS = 60_000L
    private const val MAX_CONSECUTIVE_GIVE_UPS = 5

    /**
     * Returns whether a player that previously gave up should attempt automatic recovery at
     * [nowMs]. Uses exponential backoff capped at [MAX_RECOVERY_COOLDOWN_MS] and stops time-based
     * retries after [MAX_CONSECUTIVE_GIVE_UPS] consecutive failures unless [force] is true (e.g.,
     * after a phone call ends or audio output device changes).
     */
    @VisibleForTesting
    @JvmStatic
    fun shouldAttemptRecovery(
      brokenAtMs: Long,
      nowMs: Long,
      consecutiveGiveUps: Int,
      force: Boolean,
    ): Boolean {
      if (force) return true
      if (consecutiveGiveUps <= 0) return true
      if (consecutiveGiveUps > MAX_CONSECUTIVE_GIVE_UPS) return false
      val shift = (consecutiveGiveUps - 1).coerceIn(0, 4)
      val cooldownMs = minOf(RECOVERY_COOLDOWN_MS shl shift, MAX_RECOVERY_COOLDOWN_MS)
      return (nowMs - brokenAtMs) >= cooldownMs
    }

    @JvmStatic
    fun get(context: Context, attributes: AudioAttributes): LowLatencyAudio? =
      synchronized(instances) {
        watchRoutes(context.applicationContext)
        watchPlayback(context.applicationContext)
        val player =
          instances.getOrPut(attributes.usage) {
            LowLatencyAudio(context.applicationContext, attributes)
          }
        if (player.broken &&
          shouldAttemptRecovery(
            player.brokenAtMs,
            SystemClock.uptimeMillis(),
            player.consecutiveGiveUps,
            force = false,
          ) &&
          !player.recoveryInFlight.get()) {
          ROUTES.execute { player.tryRecover(force = false) }
        }
        player.takeIf { it.hasOutput() && !it.broken }
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
