package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.android.accessibility.utils.output.SpeechController.STATUS_ERROR
import com.google.android.accessibility.utils.output.SpeechController.STATUS_SPOKEN
import java.util.Locale

internal class VietnameseFallbackSpeech(context: Context) {
  private val appContext = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private var tts: TextToSpeech? = null
  private var starting = false
  private var ready = false
  private var pending: Pending? = null
  private var lastFailureAt = 0L
  private var idCounter = 0
  private val callbacks = HashMap<String, Callbacks>()

  private class Pending(
    val label: String,
    val started: (() -> Unit)?,
    val completed: ((Int) -> Unit)?,
  )

  private class Callbacks(
    val started: (() -> Unit)?,
    val completed: ((Int) -> Unit)?,
  )

  fun speak(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    if (ready) return speakNow(label, started, completed)
    pending = Pending(label, started, completed)
    startIfNeeded()
    return false
  }

  fun stop() {
    pending = null
    tts?.stop()
  }

  fun shutdown() {
    pending = null
    callbacks.clear()
    tts?.shutdown()
    tts = null
    ready = false
    starting = false
  }

  private fun startIfNeeded() {
    if (tts != null || starting) return
    if (SystemClock.uptimeMillis() - lastFailureAt < RETRY_BACKOFF_MS) return
    starting = true
    try {
      tts = TextToSpeech(appContext) { status ->
        main.post {
          starting = false
          if (tts == null) return@post
          if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(progressListener)
            tts?.setLanguage(Locale.forLanguageTag("vi-VN"))
            ready = true
            pending?.let { speakNow(it.label, it.started, it.completed) }
            pending = null
          } else {
            lastFailureAt = SystemClock.uptimeMillis()
            tts?.shutdown()
            tts = null
          }
        }
      }
    } catch (e: Exception) {
      starting = false
      lastFailureAt = SystemClock.uptimeMillis()
      tts = null
    }
  }

  private fun speakNow(label: String, started: (() -> Unit)?, completed: ((Int) -> Unit)?): Boolean {
    val engine = tts ?: return false
    val id = "vietnamese-${++idCounter}"
    callbacks[id] = Callbacks(started, completed)
    val result = engine.speak(label, TextToSpeech.QUEUE_FLUSH, null, id)
    if (result != TextToSpeech.SUCCESS) callbacks.remove(id)
    return result == TextToSpeech.SUCCESS
  }

  private val progressListener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) {
      main.post { callbacks[utteranceId]?.started?.invoke() }
    }

    override fun onDone(utteranceId: String) {
      main.post {
        callbacks.remove(utteranceId)?.completed?.invoke(STATUS_SPOKEN)
      }
    }

    override fun onError(utteranceId: String) {
      main.post {
        callbacks.remove(utteranceId)?.completed?.invoke(STATUS_ERROR)
      }
    }
  }

  private companion object {
    const val RETRY_BACKOFF_MS = 5000L
  }
}
