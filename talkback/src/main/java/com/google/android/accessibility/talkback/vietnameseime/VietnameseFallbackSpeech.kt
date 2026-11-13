package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
  @Volatile private var starting = false
  @Volatile private var ready = false
  @Volatile private var languageAvailable = false
  private var lastFailureAt = 0L
  private var idCounter = 0
  private var synthesisIndex = 0
  private val callbacks = HashMap<String, Callbacks>()
  private val cacheCallbacks = HashMap<String, Callbacks>()
  private val pendingSpeaks = ArrayDeque<Pending>()
  private val labelsToCache = mutableSetOf<String>()
  private val synthesisAttempts = HashMap<String, Int>()
  private val pendingSynthesis = ConcurrentHashMap<String, SynthesisRequest>()
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
    startIfNeeded()
    worker.post {
      labelsToCache.addAll(fixedLabels)
      synthesizeNext()
    }
  }

  fun speak(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    if (ready) return speakNow(label, started, completed)
    if (pendingSpeaks.size >= MAX_PENDING_SPEAKS) pendingSpeaks.removeFirst()
    pendingSpeaks.addLast(Pending(label, started, completed))
    startIfNeeded()
    return true
  }

  fun stop() {
    pendingSpeaks.clear()
    val live = callbacks.values.toList()
    callbacks.clear()
    val cached = cacheCallbacks.values.toList()
    cacheCallbacks.clear()
    player.stopSpeech()
    tts?.stop()
    live.forEach { it.completed?.invoke(STATUS_INTERRUPTED) }
    cached.forEach { it.completed?.invoke(STATUS_INTERRUPTED) }
  }

  fun shutdown() {
    pendingSpeaks.clear()
    callbacks.clear()
    cacheCallbacks.clear()
    worker.removeCallbacksAndMessages(null)
    pendingSynthesis.values.forEach { it.file.delete() }
    pendingSynthesis.clear()
    player.release()
    workerThread.quitSafely()
    tts?.shutdown()
    tts = null
    ready = false
    starting = false
  }

  private fun speakNow(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    if (player.hasCache(speechInfo(label))) {
      tts?.stop()
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
    val params = Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY.toString())
    }
    val result = engine.speak(label, TextToSpeech.QUEUE_FLUSH, params, id)
    if (result != TextToSpeech.SUCCESS) callbacks.remove(id)
    return result == TextToSpeech.SUCCESS
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

  private fun startIfNeeded() {
    if (tts != null || starting) return
    if (SystemClock.uptimeMillis() - lastFailureAt < RETRY_BACKOFF_MS) return
    starting = true
    try {
      main.postDelayed({ handleInitTimeout() }, INIT_TIMEOUT_MS)
      tts = TextToSpeech(appContext) { status ->
        main.post {
          starting = false
          if (tts == null) return@post
          if (status != TextToSpeech.SUCCESS) {
            LogUtils.e(TAG, "Fallback text-to-speech failed to start")
            lastFailureAt = SystemClock.uptimeMillis()
            tts?.shutdown()
            tts = null
            return@post
          }
          tts?.setOnUtteranceProgressListener(progressListener)
          val languageResult = tts?.setLanguage(VIETNAMESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
          languageAvailable = languageResult >= TextToSpeech.LANG_AVAILABLE
          if (!languageAvailable) {
            LogUtils.e(
              TAG, "Vietnamese voice is missing or unsupported, result=%s", languageResult)
          }
          ready = true
          drainPendingSpeaks()
          worker.post { synthesizeNext() }
        }
      }
    } catch (e: Exception) {
      LogUtils.e(TAG, e, "Fallback text-to-speech failed to start")
      starting = false
      lastFailureAt = SystemClock.uptimeMillis()
      tts = null
    }
  }

  private fun handleInitTimeout() {
    if (!starting) return
    LogUtils.e(TAG, "Fallback text-to-speech start timed out")
    lastFailureAt = SystemClock.uptimeMillis()
    tts?.shutdown()
    tts = null
    starting = false
  }

  private fun drainPendingSpeaks() {
    while (pendingSpeaks.isNotEmpty()) {
      val pending = pendingSpeaks.removeFirst()
      speakNow(pending.label, pending.started, pending.completed)
    }
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
        callbacks.remove(utteranceId)?.completed?.invoke(STATUS_SPOKEN)
      }
    }

    override fun onError(utteranceId: String) {
      main.post {
        if (utteranceId.startsWith(CACHE_SYNTHESIS_ID_PREFIX)) {
          worker.post { finishSynthesis(utteranceId, false) }
          return@post
        }
        callbacks.remove(utteranceId)?.completed?.invoke(STATUS_ERROR)
      }
    }
  }

  private fun synthesizeNext() {
    if (!ready || !languageAvailable) return
    if (pendingSynthesis.isNotEmpty()) return
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

  private fun finishSynthesis(utteranceId: String, success: Boolean) {
    val request = pendingSynthesis.remove(utteranceId) ?: return
    if (success) {
      val info = SpeechInfo(utteranceId, request.label, VIETNAMESE, Bundle(), request.file)
      if (!player.loadSpeechFileIntoMemory(info)) {
        LogUtils.w(TAG, "Failed to load a cached label")
        retrySynthesis(request.label)
      } else {
        synthesisAttempts.remove(request.label)
      }
    } else {
      LogUtils.w(TAG, "Failed to synthesize a cached label")
      retrySynthesis(request.label)
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
    private const val MAX_PENDING_SPEAKS = 16
    private const val LIVE_ID_PREFIX = "vietnamese-"
    private const val CACHE_PLAYBACK_ID_PREFIX = "fallback-cache-"
    private const val CACHE_SYNTHESIS_ID_PREFIX = "fallback-synth-"
    private val VIETNAMESE = Locale.forLanguageTag("vi-VN")
  }
}
