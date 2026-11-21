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
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.Performance.PreparedSpeechWork
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    EXPIRED, SUPERSEDED, QUEUE_FULL, IDENTITY_MISS, TEXT_MISS,
    // Q11f: the failure-cause split for the single `failures` total. `failures` is still
    // counted exactly as before; each cause below is counted alongside it at its own site.
    FAILURE_SYNTH, FAILURE_TIMEOUT, FAILURE_ON_ERROR, FAILURE_OVERSIZE, FAILURE_EXCEPTION
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

  /**
   * Hashes of keys ever queued for preparation, so a TEXT_MISS can say whether its text was
   * ever prepared. Q11a. Keyed by [Key.hashCode] (identity + text + pitch + rate), which is
   * what [find] recomputes for the lookup, so the two agree. Cleared whenever the store is
   * cleared (identity change, [invalidate], [shutdown], [resetStatistics]) and when it
   * reaches [MAX_EVER_QUEUED_KEYS], so it stays bounded. A concurrent set: [prepare] writes
   * it on the handler thread while [find] reads it on the speaking thread. Diagnostics only.
   */
  private val everQueuedKeys = ConcurrentHashMap.newKeySet<Int>()

  fun prepare(
    foreground: TextToSpeech, engineName: String, text: CharSequence,
    locale: Locale, params: Bundle, outputRate: Int, generation: Long,
  ) {
    if (closed || !LatencyExperiments.preparePcm() || text.isEmpty() || text.length > 1200) {
      // Q11d: only the too-long text is a counted drop. The same early return also covers
      // closed / feature-off / empty, which are not counted: an empty text is not a
      // preparation that could ever have hit, and a disabled feature prepares nothing.
      if (text.length > 1200 && !closed && LatencyExperiments.preparePcm()) {
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.PREPARE_DROP_TEXT_TOO_LONG)
      }
      return
    }
    val requestedEpoch = epoch.get()
    val requestedConfiguration = configuration.get()
    val spoken = text.toString()
    val parameters = Bundle(params)
    handler.post {
      // Q11d: the guard below used to return silently for every cause. Split it so each
      // drop is counted once, by its first matching cause: a closed cache or a disabled
      // feature is never counted; a passed generation beats an epoch mismatch.
      if (closed || !LatencyExperiments.preparePcm()) return@post
      if (generation < pendingGeneration) {
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.PREPARE_DROP_STALE_GENERATION)
        return@post
      }
      if (requestedEpoch != epoch.get() || requestedConfiguration != configuration.get()) {
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.PREPARE_DROP_EPOCH)
        return@post
      }
      val voice = try { foreground.voice } catch (failure: RuntimeException) { null }
      if (voice == null) {
        note(Work.NO_VOICE)
        return@post
      }
      if (voice.locale.language != locale.language) {
        note(Work.DIFFERENT_LANGUAGE)
        return@post
      }
      if (requestedConfiguration != configuration.get()) {
        // Q11d: the second epoch/configuration guard site — same counter as the first.
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.PREPARE_DROP_EPOCH)
        return@post
      }
      val current = Identity(engineName, voice.name, locale.toLanguageTag(), outputRate, requestedConfiguration)
      if (identity != current) {
        cache.clear()
        everQueuedKeys.clear()
        identity = current
      }
      val key = Key(current, spoken, parameters.getInt("pitch", 100), parameters.getInt("rate", 100))
      if (generation != pendingGeneration) {
        // Drop all stale generations immediately on a new focus move so rapid swipes never build
        // up a background TTS backlog that competes with the active utterance.
        val iterator = queue.iterator()
        var dropped = 0
        while (iterator.hasNext()) {
          if (iterator.next().generation < generation) {
            iterator.remove()
            dropped++
          }
        }
        if (dropped > 0) work.addAndGet(Work.SUPERSEDED.ordinal, dropped.toLong())
        pendingGeneration = generation
      }
      // Q11d: a key already cached, being synthesized, or queued is dropped silently.
      if (cache.contains(key) || active?.key == key || queue.any { it.key == key }) {
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.PREPARE_DROP_DEDUP)
        return@post
      }
      while (queue.size >= 2) {
        // Replace-oldest policy keeps the background TTS queue strictly bounded to 2 items.
        queue.removeFirst()
        note(Work.SUPERSEDED)
      }
      queue.addLast(Request(key, voice, parameters, foreground, requestedEpoch, generation))
      // Q11a: remember that this key was queued, so a later TEXT_MISS can say whether
      // its text was ever prepared. Bounded by MAX_EVER_QUEUED_KEYS (see everQueuedKeys).
      if (everQueuedKeys.size >= MAX_EVER_QUEUED_KEYS) everQueuedKeys.clear()
      everQueuedKeys.add(key.hashCode())
      note(Work.QUEUED)
      schedule(0)
    }
  }

  /**
   * Looks up prepared PCM for [text]. [chunkIndex] is the chunk of the fragment being
   * spoken (0 for a single-chunk utterance), threaded from
   * FailoverTextToSpeech.speakInChunks so a TEXT_MISS can say which chunk missed. The
   * fragment index is NOT threaded: it is known only in
   * SpeechControllerImpl.processNextFragmentInternal and reaching here would need changing
   * the public FailoverTextToSpeech.speak() signatures, so the fragment slice is sized
   * instead by the FRAGMENTS_PREPARED / FRAGMENTS_SPOKEN counters (Q11b).
   */
  fun find(
    text: CharSequence, engineName: String, locale: Locale?, params: Bundle, outputRate: Int,
    chunkIndex: Int,
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
      // Q11a: classify the miss without logging raw text. The hash is diagnostics-only
      // (String.hashCode, not crypto). everQueued splits the miss into "never prepared"
      // (false) vs "prepared then dropped / expired / evicted / stale" (true); a
      // prepared-but-different text — the transform bucket — shows up as everQueued
      // false for the spoken text while its prepared twin was queued under another hash.
      LogUtils.d(TAG,
        "NavigationPcmCache TEXT_MISS textHash=%d pitch=%d rate=%d chunk=%d everQueued=%s",
        text.toString().hashCode(), params.getInt("pitch", 100), params.getInt("rate", 100),
        chunkIndex, everQueuedKeys.contains(key.hashCode()))
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
    everQueuedKeys.clear()
    cancelPreparation()
  }

  fun shutdown() {
    if (closed) return
    closed = true
    epoch.incrementAndGet()
    cache.clear()
    everQueuedKeys.clear()
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
    everQueuedKeys.clear()
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
            note(Work.FAILURE_SYNTH)
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
      finish(request.id, false, Work.FAILURE_SYNTH)
    } else {
      val timeout = Runnable {
        if (active === request) {
          engine?.stop()
          finish(request.id, false, Work.FAILURE_TIMEOUT)
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
          else { engine?.stop(); finish(id, false, Work.FAILURE_OVERSIZE) }
        }
      }
    }
    override fun onDone(id: String) { handler.post { finish(id, true) } }
    override fun onError(id: String) { handler.post { finish(id, false, Work.FAILURE_ON_ERROR) } }
  }

  private fun finish(id: String, success: Boolean, failureCause: Work? = null) {
    val request = active?.takeIf { it.id == id } ?: return
    active = null
    request.timeout?.let { handler.removeCallbacks(it) }
    // Q11f: the `failures` total is counted exactly as before; the cause is counted
    // alongside it. Every current false path passes its cause; null is only the
    // default for a future caller.
    if (!success) {
      failures.incrementAndGet()
      failureCause?.let { note(it) }
    }
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
      // Q11f: decoding / resampling threw after a successful synthesis.
      failures.incrementAndGet()
      note(Work.FAILURE_EXCEPTION)
    } finally {
      request.file?.delete()
      schedule(0)
    }
  }

  companion object {
    private const val TAG = "NavigationPcmCache"
    private val nextId = AtomicLong()
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    // Q11a: bound for everQueuedKeys (see the field). Cleared on identity change and
    // when it reaches this size, so a long session cannot grow it without limit.
    private const val MAX_EVER_QUEUED_KEYS = 8192
    private const val MAX_PENDING_AGE_MS = 1_200L
    // Khởi chạy tổng hợp PCM nền sớm hơn ngay sau khi dừng ngón tay để giảm WAITING_FOR_INPUT
    // và tăng tỷ lệ trúng cache PCM (rút ngắn COMPOSED -> FIRST_PCM về ~2-5 ms khi trúng cache).
    private const val INPUT_QUIET_MS = 35L
    private const val RETRY_MS = 12L

    // Khôi phục ngưỡng timeout theo hợp đồng kiểm thử NavigationPcmCacheTest (2s + 2ms/ký tự, tối
    // đa 4s) để giảm FAILURE_TIMEOUT trên các tin nhắn tiếng Việt dài.
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

