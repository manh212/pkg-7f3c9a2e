package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.android.accessibility.utils.output.AudioTrackSpeechCachePlayer
import com.google.android.accessibility.utils.output.SpeechCachePlayer
import com.google.android.accessibility.utils.output.SpeechCachePlayer.SpeechInfo
import com.google.android.accessibility.utils.output.SpeechController.STATUS_ERROR
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED
import com.google.android.accessibility.utils.output.SpeechController.STATUS_SPOKEN
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.function.IntSupplier

internal class VietnameseFallbackSpeech(context: Context) {
  private val appContext = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val workerThread = HandlerThread("VietnameseFallbackSpeech").apply { start() }
  private val worker = Handler(workerThread.looper)
  private val player = AudioTrackSpeechCachePlayer(
    Executor { runnable -> main.post(runnable) },
    IntSupplier { AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY },
  )
  @Volatile private var tts: TextToSpeech? = null
  private var starting = false
  @Volatile private var ready = false
  @Volatile private var languageAvailable = false
  @Volatile private var closed = false
  @Volatile private var warmingUp = false
  @Volatile private var lastSpokeAt = 0L
  @Volatile private var liveSpeaking = false
  private var lastFailureAt = -RETRY_BACKOFF_MS
  private var initGeneration = 0
  private val initTimeout = Runnable {
    failInitialization("Fallback text-to-speech start timed out")
  }
  private var idCounter = 0
  private var synthesisIndex = 0
  private val callbacks = HashMap<String, Callbacks>()
  private val cacheCallbacks = HashMap<String, Callbacks>()
  private var pendingSpeak: Pending? = null
  private val labelsToCache = mutableSetOf<String>()
  private val synthesisAttempts = HashMap<String, Int>()
  private val pendingSynthesis = HashMap<String, SynthesisRequest>()
  private val resumeCache = Runnable { synthesizeNext() }
  private var cacheDirFile: File? = null

  private class Pending(
    val label: String,
    val started: (() -> Unit)?,
    val completed: ((Int) -> Unit)?,
  )

  private class Callbacks(
    val started: (() -> Unit)?,
    val completed: ((Int) -> Unit)?,
  )

  private class SynthesisRequest(val label: String, val file: File)

  fun warmUp(fixedLabels: List<String>) {
    warmingUp = true
    startIfNeeded()
    worker.post {
      labelsToCache.addAll(fixedLabels)
      synthesizeNext()
    }
  }

  fun speak(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    if (closed) return false
    if (ready) {
      lastSpokeAt = SystemClock.uptimeMillis()
      return speakNow(label, started, completed)
    }
    if (!startIfNeeded()) return false
    val previous = pendingSpeak
    pendingSpeak = Pending(label, started, completed)
    previous?.completed?.invoke(STATUS_INTERRUPTED)
    return true
  }

  fun stop() {
    warmingUp = false
    worker.removeCallbacks(resumeCache)
    val pending = pendingSpeak
    pendingSpeak = null
    val live = callbacks.values.toList()
    callbacks.clear()
    liveSpeaking = false
    val cached = cacheCallbacks.values.toList()
    cacheCallbacks.clear()
    player.stopSpeech()
    tts?.stop()
    pending?.completed?.invoke(STATUS_INTERRUPTED)
    live.forEach { it.completed?.invoke(STATUS_INTERRUPTED) }
    cached.forEach { it.completed?.invoke(STATUS_INTERRUPTED) }
  }

  fun shutdown() {
    closed = true
    main.removeCallbacksAndMessages(null)
    pendingSpeak = null
    callbacks.clear()
    cacheCallbacks.clear()
    worker.removeCallbacksAndMessages(null)
    tts?.shutdown()
    tts = null
    ready = false
    starting = false
    worker.post {
      pendingSynthesis.values.forEach { it.file.delete() }
      pendingSynthesis.clear()
      player.release()
      workerThread.quitSafely()
    }
  }

  private fun speakNow(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    if (player.hasCache(speechInfo(label))) {
      if (callbacks.isNotEmpty()) tts?.stop()
      return playCached(label, started, completed)
    }
    player.stopSpeech()
    return speakLive(label, started, completed)
  }

  private fun playCached(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    val id = CACHE_PLAYBACK_ID_PREFIX + (++idCounter)
    cacheCallbacks[id] = Callbacks(started, completed)
    if (!player.playSpeech(SpeechInfo(id, label, VIETNAMESE, Bundle()), playbackListener)) {
      cacheCallbacks.remove(id)
      return speakLive(label, started, completed)
    }
    return true
  }

  private fun speakLive(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    val engine = tts ?: return false
    val id = LIVE_ID_PREFIX + (++idCounter)
    callbacks[id] = Callbacks(started, completed)
    val result = engine.speak(label, TextToSpeech.QUEUE_FLUSH, null, id)
    if (result != TextToSpeech.SUCCESS) {
      callbacks.remove(id)
      return false
    }
    val replaced = callbacks.keys.filter { it != id }
    replaced.forEach { callbacks.remove(it)?.completed?.invoke(STATUS_INTERRUPTED) }
    liveSpeaking = true
    return true
  }

  private val playbackListener = SpeechCachePlayer.PlaybackStateListener { utteranceId, state ->
    main.post { handlePlaybackState(utteranceId, state) }
  }

  private fun handlePlaybackState(utteranceId: String, state: Int) {
    when (state) {
      SpeechCachePlayer.STATE_PLAYED -> cacheCallbacks[utteranceId]?.started?.invoke()
      SpeechCachePlayer.STATE_COMPLETED ->
        cacheCallbacks.remove(utteranceId)?.completed?.invoke(STATUS_SPOKEN)
      SpeechCachePlayer.STATE_STOPPED ->
        cacheCallbacks.remove(utteranceId)?.completed?.invoke(STATUS_INTERRUPTED)
      SpeechCachePlayer.STATE_ERROR ->
        cacheCallbacks.remove(utteranceId)?.completed?.invoke(STATUS_ERROR)
      else -> {}
    }
  }

  private fun startIfNeeded(): Boolean {
    if (closed) return false
    if (tts != null || starting) return true
    if (SystemClock.uptimeMillis() - lastFailureAt < RETRY_BACKOFF_MS) return false
    starting = true
    val generation = ++initGeneration
    try {
      tts = TextToSpeech(appContext) { status ->
        main.post {
          if (closed || !starting || generation != initGeneration) return@post
          val engine = tts ?: return@post
          if (status != TextToSpeech.SUCCESS) {
            failInitialization("Fallback text-to-speech failed to start")
            return@post
          }
          main.removeCallbacks(initTimeout)
          starting = false
          engine.setOnUtteranceProgressListener(progressListener)
          engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
          )
          val languageResult = engine.setLanguage(VIETNAMESE)
          languageAvailable = languageResult >= TextToSpeech.LANG_AVAILABLE
          if (!languageAvailable) {
            LogUtils.e(
              TAG, "Vietnamese voice is missing or unsupported, result=%s", languageResult)
          }
          ready = true
          val pending = pendingSpeak
          pendingSpeak = null
          if (pending != null) {
            lastSpokeAt = SystemClock.uptimeMillis()
            if (!speakNow(pending.label, pending.started, pending.completed)) {
              pending.completed?.invoke(STATUS_ERROR)
            }
          }
          worker.post { synthesizeNext() }
        }
      }
      main.postDelayed(initTimeout, INIT_TIMEOUT_MS)
      return true
    } catch (e: Exception) {
      LogUtils.e(TAG, e, "Fallback text-to-speech failed to start")
      failInitialization("Fallback text-to-speech initialization failed")
      return false
    }
  }

  private fun failInitialization(message: String) {
    if (!starting) return
    LogUtils.e(TAG, message)
    main.removeCallbacks(initTimeout)
    lastFailureAt = SystemClock.uptimeMillis()
    tts?.shutdown()
    tts = null
    starting = false
    val pending = pendingSpeak
    pendingSpeak = null
    pending?.completed?.invoke(STATUS_ERROR)
  }

  private val progressListener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) {
      main.post {
        if (utteranceId.startsWith(CACHE_SYNTHESIS_ID_PREFIX)) return@post
        callbacks[utteranceId]?.started?.invoke()
      }
    }

    override fun onDone(utteranceId: String) {
      main.post {
        if (utteranceId.startsWith(CACHE_SYNTHESIS_ID_PREFIX)) {
          worker.post { finishSynthesis(utteranceId, true) }
          return@post
        }
        completeLive(utteranceId, STATUS_SPOKEN)
      }
    }

    override fun onError(utteranceId: String) {
      main.post {
        if (utteranceId.startsWith(CACHE_SYNTHESIS_ID_PREFIX)) {
          worker.post { finishSynthesis(utteranceId, false) }
          return@post
        }
        completeLive(utteranceId, STATUS_ERROR)
      }
    }

    override fun onStop(utteranceId: String, interrupted: Boolean) {
      if (utteranceId.startsWith(CACHE_SYNTHESIS_ID_PREFIX)) {
        worker.post { finishSynthesis(utteranceId, false, retry = false) }
      } else {
        main.post { completeLive(utteranceId, STATUS_INTERRUPTED) }
      }
    }
  }

  private fun completeLive(utteranceId: String, status: Int) {
    val completed = callbacks.remove(utteranceId) ?: return
    liveSpeaking = callbacks.isNotEmpty()
    completed.completed?.invoke(status)
    worker.post(resumeCache)
  }

  private fun synthesizeNext() {
    if (closed || !warmingUp || !ready || !languageAvailable || liveSpeaking) return
    if (pendingSynthesis.isNotEmpty()) return
    val remaining = lastSpokeAt + CACHE_RESUME_DELAY_MS - SystemClock.uptimeMillis()
    if (remaining > 0) {
      worker.removeCallbacks(resumeCache)
      worker.postDelayed(resumeCache, remaining)
      return
    }
    val engine = tts ?: return
    val label = labelsToCache.firstOrNull { !player.hasCache(speechInfo(it)) } ?: return
    labelsToCache.remove(label)
    val index = ++synthesisIndex
    val utteranceId = CACHE_SYNTHESIS_ID_PREFIX + index
    val file = File(cacheDirectory(), "$utteranceId.wav")
    pendingSynthesis[utteranceId] = SynthesisRequest(label, file)
    worker.postDelayed({ finishSynthesis(utteranceId, false) }, SYNTHESIS_TIMEOUT_MS)
    if (engine.synthesizeToFile(label, null, file, utteranceId) != TextToSpeech.SUCCESS) {
      finishSynthesis(utteranceId, false)
    }
  }

  private fun finishSynthesis(utteranceId: String, success: Boolean, retry: Boolean = true) {
    val request = pendingSynthesis.remove(utteranceId) ?: return
    if (closed) {
      request.file.delete()
      return
    } else if (success) {
      val info = SpeechInfo(utteranceId, request.label, VIETNAMESE, Bundle(), request.file)
      if (!player.loadSpeechFileIntoMemory(info)) {
        LogUtils.w(TAG, "Failed to load a cached label")
        retrySynthesis(request.label)
      } else {
        synthesisAttempts.remove(request.label)
      }
    } else if (retry) {
      LogUtils.w(TAG, "Failed to synthesize a cached label")
      retrySynthesis(request.label)
    } else {
      labelsToCache.add(request.label)
    }
    request.file.delete()
    synthesizeNext()
  }

  private fun retrySynthesis(label: String) {
    val retries = synthesisAttempts.getOrDefault(label, 0)
    if (retries < MAX_SYNTHESIS_RETRIES) {
      synthesisAttempts[label] = retries + 1
      labelsToCache.add(label)
    }
  }

  private fun speechInfo(label: String): SpeechInfo = SpeechInfo(null, label, VIETNAMESE, Bundle())

  private fun cacheDirectory(): File {
    var directory = cacheDirFile
    if (directory == null) {
      directory = File(appContext.cacheDir, "vietnamese_keyboard_speech")
      if (!directory.isDirectory) directory.mkdirs()
      cacheDirFile = directory
    }
    return directory
  }

  private companion object {
    private const val TAG = "VietnameseFallbackSpeech"
    private const val RETRY_BACKOFF_MS = 5000L
    private const val INIT_TIMEOUT_MS = 10000L
    private const val SYNTHESIS_TIMEOUT_MS = 10000L
    private const val MAX_SYNTHESIS_RETRIES = 2
    private const val CACHE_RESUME_DELAY_MS = 350L
    private const val LIVE_ID_PREFIX = "vietnamese-"
    private const val CACHE_PLAYBACK_ID_PREFIX = "fallback-cache-"
    private const val CACHE_SYNTHESIS_ID_PREFIX = "fallback-synth-"
    private val VIETNAMESE = Locale.forLanguageTag("vi-VN")
  }
}
