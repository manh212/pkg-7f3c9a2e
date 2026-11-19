package com.google.android.accessibility.utils.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.google.android.accessibility.utils.LatencyExperiments
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

class NavigationPcmCache(context: Context) {
  class PreparationNotifier(val flags: Int, val generation: Long) : SpeechCacheManager.LoadSpeechResultNotifier {
    override fun onFinished(speechInfo: SpeechCachePlayer.SpeechInfo?, success: Boolean?) {}
  }

  private data class Identity(val engine: String, val voice: String, val locale: String,
    val outputRate: Int, val configuration: Long)
  private data class Key(val identity: Identity, val text: String, val pitch: Int, val rate: Int)
  private enum class Work {
    QUEUED, WAITING_FOR_INPUT, WAITING_FOR_ENGINE, NO_VOICE, DIFFERENT_LANGUAGE,
    EXPIRED, SUPERSEDED, QUEUE_FULL, IDENTITY_MISS, TEXT_MISS
  }

  private class Request(
    val key: Key, val voice: Voice, val params: Bundle,
    val foreground: TextToSpeech, val epoch: Long, val generation: Long,
  ) {
    val id = "nav_pcm_" + nextId.incrementAndGet()
    val queuedAt = SystemClock.uptimeMillis()
    val bytes = ByteArrayOutputStream()
    var file: File? = null
    var timeout: Runnable? = null
    var rate = 0
    var encoding = 0
    var channels = 0
  }

  private val appContext = context.applicationContext
  private val thread = HandlerThread("NavigationPcm").apply { start() }
  private val handler = Handler(thread.looper)
  private val cache = PreparedPcmStore<Key>(MAX_CACHE_BYTES, 32)
  private val queue = ArrayDeque<Request>()
  private val epoch = AtomicLong()
  private val configuration = AtomicLong()
  private val hits = AtomicLong()
  private val misses = AtomicLong()
  private val prepared = AtomicLong()
  private val cancelled = AtomicLong()
  private val work = AtomicLongArray(Work.entries.size)
  @Volatile private var identity: Identity? = null
  @Volatile private var closed = false
  @Volatile private var foregroundStartedAt = 0L
  private var engine: TextToSpeech? = null
  private var engineName: String? = null
  private var ready = false
  private var pendingGeneration = Long.MIN_VALUE
  @Volatile private var active: Request? = null
  private val failures = AtomicLong()
  private var disabledUntil = 0L
  private var engineGeneration = 0L
  private val pump = Runnable { startNext() }

  fun prepare(
    foreground: TextToSpeech, engineName: String, text: CharSequence,
    locale: Locale, params: Bundle, outputRate: Int, generation: Long,
  ) {
    if (closed || !LatencyExperiments.preparePcm() || text.isEmpty() || text.length > 1200) return
    val requestedEpoch = epoch.get()
    val requestedConfiguration = configuration.get()
    val spoken = text.toString()
    val parameters = Bundle(params)
    handler.post {
      if (closed || requestedEpoch != epoch.get() || requestedConfiguration != configuration.get()
        || !LatencyExperiments.preparePcm() || generation < pendingGeneration) return@post
      val voice = try { foreground.voice } catch (failure: RuntimeException) { null }
      if (voice == null) {
        note(Work.NO_VOICE)
        return@post
      }
      if (voice.locale.language != locale.language) {
        note(Work.DIFFERENT_LANGUAGE)
        return@post
      }
      if (requestedConfiguration != configuration.get()) return@post
      val current = Identity(engineName, voice.name, locale.toLanguageTag(), outputRate, requestedConfiguration)
      if (identity != current) {
        cache.clear()
        identity = current
      }
      val key = Key(current, spoken, parameters.getInt("pitch", 100), parameters.getInt("rate", 100))
      if (generation != pendingGeneration) {
        // 1B: a focus move only passes the items behind the new focus. Keep the previous
        // generation's requests: its second-ahead item is the next item after one more swipe
        // in either direction, and it is already queued (the dedup below keeps it). Drop
        // only generations older than that.
        val iterator = queue.iterator()
        var dropped = 0
        while (iterator.hasNext()) {
          if (iterator.next().generation < generation - 1) {
            iterator.remove()
            dropped++
          }
        }
        if (dropped > 0) work.addAndGet(Work.SUPERSEDED.ordinal, dropped.toLong())
        pendingGeneration = generation
      }
      if (cache.contains(key) || active?.key == key || queue.any { it.key == key }) return@post
      if (queue.size >= 4) {
        // 1B: make room for the new generation by dropping the oldest request from an older
        // generation (already passed) instead of rejecting the new one outright.
        val oldest = queue.firstOrNull { it.generation < generation }
        if (oldest == null) {
          note(Work.QUEUE_FULL)
          return@post
        }
        queue.remove(oldest)
        note(Work.SUPERSEDED)
      }
      queue.addLast(Request(key, voice, parameters, foreground, requestedEpoch, generation))
      note(Work.QUEUED)
      schedule(0)
    }
  }

  fun find(
    text: CharSequence, engineName: String, locale: Locale?, params: Bundle, outputRate: Int,
  ): FloatArray? {
    if (!LatencyExperiments.preparePcm() || closed) return null
    val current = identity
    if (current == null || current.engine != engineName ||
      current.outputRate != outputRate || current.configuration != configuration.get() ||
      current.locale != locale?.toLanguageTag()) {
      misses.incrementAndGet()
      note(Work.IDENTITY_MISS)
      return null
    }
    val key = Key(current, text.toString(), params.getInt("pitch", 100), params.getInt("rate", 100))
    val result = cache.get(key)
    if (result == null) {
      misses.incrementAndGet()
      note(Work.TEXT_MISS)
    } else {
      hits.incrementAndGet()
    }
    return result
  }

  fun onInput() {
    val request = active ?: return
    handler.post {
      if (active === request) {
        // 1C: input — or a new foreground utterance, via onForegroundSpeech() — no longer
        // cancels the in-flight synthesis. The 60 ms quiet clock is reset elsewhere
        // (LatencyExperiments.onInput() stamps lastInput; onForegroundSpeech() stamps
        // foregroundStartedAt), so the pump still waits for quiet before STARTING the next
        // synthesis (shouldYield). Cancel only when the active request is from a generation
        // already passed: the same staleness rule 1B applies to the queue (drop generations
        // older than pendingGeneration - 1). The active synthesis therefore survives at most
        // two focus moves; a synthesis for a passed item may run to its length-scaled
        // timeout (Q8: 2–4 s) — the cost Kenneth accepted when approving 1C.
        if (request.generation < pendingGeneration - 1) {
          cancelActiveRequest()
        }
        schedule(INPUT_QUIET_MS)
      }
    }
  }

  fun onForegroundSpeech() {
    foregroundStartedAt = SystemClock.uptimeMillis()
    onInput()
  }

  private fun shouldYield(): Boolean =
    LatencyExperiments.preparationInputIsActive(INPUT_QUIET_MS) ||
      SystemClock.uptimeMillis() - foregroundStartedAt < INPUT_QUIET_MS

  private fun cancelActiveRequest() {
    val request = active ?: return
    active = null
    cancelled.incrementAndGet()
    engine?.stop()
    request.timeout?.let { handler.removeCallbacks(it) }
    request.file?.delete()
  }

  private fun cancelPreparation() {
    if (closed) return
    epoch.incrementAndGet()
    handler.post {
      queue.clear()
      handler.removeCallbacks(pump)
      cancelActiveRequest()
    }
  }

  fun invalidate() {
    configuration.incrementAndGet()
    identity = null
    cache.clear()
    cancelPreparation()
  }

  fun shutdown() {
    if (closed) return
    closed = true
    epoch.incrementAndGet()
    cache.clear()
    handler.post {
      handler.removeCallbacks(pump)
      active?.timeout?.let { handler.removeCallbacks(it) }
      active?.file?.delete()
      active = null
      queue.clear()
      engine?.stop()
      engine?.shutdown()
      engine = null
      thread.quitSafely()
    }
  }

  fun resetStatistics() {
    hits.set(0)
    misses.set(0)
    prepared.set(0)
    cancelled.set(0)
    failures.set(0)
    Work.entries.forEach { work.set(it.ordinal, 0) }
  }

  fun summary(): String =
    "Navigation PCM cache: hits=${hits.get()}, misses=${misses.get()}, " +
      "prepared=${prepared.get()}, cancelled=${cancelled.get()}, failures=${failures.get()}, bytes=${cache.byteCount()}\n" +
      "Navigation PCM work: " + Work.entries.joinToString { "${it.name}=${work.get(it.ordinal)}" }

  private fun note(reason: Work) {
    work.incrementAndGet(reason.ordinal)
  }

  private fun schedule(delay: Long) {
    handler.removeCallbacks(pump)
    handler.postDelayed(pump, delay)
  }

  private fun startNext() {
    if (closed || active != null || !LatencyExperiments.preparePcm()) return
    if (SystemClock.uptimeMillis() < disabledUntil) { queue.clear(); return }
    val now = SystemClock.uptimeMillis()
    while (queue.peekFirst()?.let { it.epoch != epoch.get() || now - it.queuedAt > MAX_PENDING_AGE_MS } == true) {
      queue.removeFirst()
      note(Work.EXPIRED)
    }
    val request = queue.peekFirst() ?: return
    if (shouldYield()) {
      note(Work.WAITING_FOR_INPUT)
      schedule(RETRY_MS)
      return
    }
    // 1A: the background pump no longer waits for the foreground engine to finish speaking.
    // It still yields while input is active (shouldYield above: 60 ms of input quiet), so a
    // swipe in progress is never slowed by background synthesis.
    if (engine == null || engineName != request.key.identity.engine) {
      engine?.shutdown()
      ready = false
      engineName = request.key.identity.engine
      val generation = ++engineGeneration
      engine = TextToSpeech(appContext, { status ->
        handler.post {
          if (closed || generation != engineGeneration) return@post
          ready = status == TextToSpeech.SUCCESS
          if (ready) {
            engine?.setOnUtteranceProgressListener(listener)
            schedule(0)
          } else {
            failures.incrementAndGet()
            disabledUntil = SystemClock.uptimeMillis() + 30_000
            queue.clear()
            engine?.shutdown()
            engine = null
          }
        }
      }, engineName)
      engine?.setOnUtteranceProgressListener(listener)
      return
    }
    if (!ready) return
    if (shouldYield()) {
      schedule(RETRY_MS)
      return
    }
    queue.removeFirst()
    if (engine?.setVoice(request.voice) != TextToSpeech.SUCCESS) { schedule(0); return }
    val file = try { File.createTempFile("nav-pcm-", ".wav", appContext.cacheDir) }
      catch (failure: java.io.IOException) { schedule(0); return }
    request.file = file
    active = request
    if (closed || request.epoch != epoch.get() || shouldYield()) {
      active = null
      request.file?.delete()
      request.file = null
      queue.addFirst(request)
      schedule(RETRY_MS)
      return
    }
    val params = Bundle(request.params).apply {
      putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
    }
    val result = engine?.synthesizeToFile(request.key.text, params, file, request.id)
    if (result != TextToSpeech.SUCCESS) {
      finish(request.id, false)
    } else {
      val timeout = Runnable {
        if (active === request) {
          engine?.stop()
          finish(request.id, false)
        }
      }
      request.timeout = timeout
      // Q8: the synthesis timeout scales with the text length (2 s + 2 ms/char, max 4 s),
      // so a long utterance is not cut off by the old flat 2.5 s limit.
      handler.postDelayed(timeout, synthesisTimeoutMs(request.key.text.length))
    }
  }

  private val listener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {}
    override fun onBeginSynthesis(id: String, rate: Int, encoding: Int, channels: Int) {
      handler.post {
        active?.takeIf { it.id == id }?.let {
          it.rate = rate; it.encoding = encoding; it.channels = channels
        }
      }
    }
    override fun onAudioAvailable(id: String, audio: ByteArray) {
      handler.post {
        active?.takeIf { it.id == id && it.epoch == epoch.get() }?.let {
          if (it.bytes.size() + audio.size <= 2_000_000) it.bytes.write(audio)
          else { engine?.stop(); finish(id, false) }
        }
      }
    }
    override fun onDone(id: String) { handler.post { finish(id, true) } }
    override fun onError(id: String) { handler.post { finish(id, false) } }
  }

  private fun finish(id: String, success: Boolean) {
    val request = active?.takeIf { it.id == id } ?: return
    active = null
    request.timeout?.let { handler.removeCallbacks(it) }
    if (!success) failures.incrementAndGet()
    try {
      if (success && request.epoch == epoch.get() && !closed &&
        SpatialSoundPlayer.isSupportedFormat(request.rate, request.channels) &&
        AudioDecoder.isSupportedEncoding(request.encoding)) {
        val decoded = AudioDecoder.FloatList()
        AudioDecoder.toFloats(ByteBuffer.wrap(request.bytes.toByteArray()), request.encoding, decoded)
        val samples = decoded.toArray()
        val duration = samples.size.toLong() * 1000 / request.channels / request.rate
        if (duration <= 6000 && samples.any { it != 0f }) {
          val resampler = SincResampler(request.rate, request.key.identity.outputRate, request.channels)
          val converted = resampler.process(samples) + resampler.flush()
          val stereo = LowLatencyAudio.toStereo(converted, request.channels)
          val size = stereo.size * 4
          if (size <= MAX_CACHE_BYTES && request.epoch == epoch.get()) {
            if (request.key.identity.configuration == configuration.get() && cache.put(request.key, stereo)) {
              prepared.incrementAndGet()
            }
          }
        }
      }
    } catch (failure: RuntimeException) {
      failures.incrementAndGet()
    } finally {
      request.file?.delete()
      schedule(0)
    }
  }

  companion object {
    private val nextId = AtomicLong()
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_PENDING_AGE_MS = 2_000L
    private const val INPUT_QUIET_MS = 60L
    private const val RETRY_MS = 24L

    // Q8: the background synthesis timeout scales with the text length — 2 s base plus
    // 2 ms per character, capped at 4 s — so a long utterance is not cut off by a flat
    // limit while a short one still fails fast. (prepare() already rejects text over
    // 1200 chars, so the cap is reached at 1000 chars.)
    private const val BASE_SYNTHESIS_TIMEOUT_MS = 2_000L
    private const val SYNTHESIS_TIMEOUT_PER_CHAR_MS = 2L
    private const val MAX_SYNTHESIS_TIMEOUT_MS = 4_000L

    fun synthesisTimeoutMs(textLength: Int): Long =
      minOf(
        MAX_SYNTHESIS_TIMEOUT_MS,
        BASE_SYNTHESIS_TIMEOUT_MS +
          SYNTHESIS_TIMEOUT_PER_CHAR_MS * textLength.coerceAtLeast(0)
      )
  }
}

