package com.google.android.accessibility.talkback.vietnameseime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.accessibility.utils.output.SpeechController.STATUS_ERROR
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED

internal class VietnameseKeySpeech(
  private val submit: (String, () -> Unit, (Int) -> Unit) -> Boolean,
  private val stop: () -> Unit,
) {
  private class Key(val label: String) {
    var released = false
    var releaseAt = 0L
    var started = false
    var finished = false
    var submitted = false
    var interruptions = 0
  }

  private val handler = Handler(Looper.getMainLooper())
  private var selected: Key? = null
  private val trySpeech = Runnable { selected?.let(::request) }

  fun explore(label: String, delay: Long) {
    handler.removeCallbacks(trySpeech)
    val previous = selected
    val key = Key(label)
    selected = key
    if (delay > 0 && previous?.submitted == true && !previous.started) stop()
    if (delay == 0L) request(key) else handler.postDelayed(trySpeech, delay)
  }

  fun release(delay: Long) {
    val key = selected ?: return
    key.released = true
    key.releaseAt = SystemClock.uptimeMillis() + delay
    handler.removeCallbacks(trySpeech)
    if (!key.finished && !key.started && !key.submitted) {
      if (delay == 0L) request(key) else handler.postDelayed(trySpeech, delay)
    }
  }

  fun cancel(stopSpeech: Boolean) {
    selected = null
    handler.removeCallbacks(trySpeech)
    if (stopSpeech) stop()
  }

  private fun request(key: Key) {
    if (key.finished || key.started || key.submitted) return
    val remaining = key.releaseAt - SystemClock.uptimeMillis()
    if (remaining > 0) {
      handler.postDelayed(trySpeech, remaining)
      return
    }
    key.submitted = true
    val accepted = submit(
      key.label,
      { key.started = true },
      { status ->
        key.submitted = false
        if (selected === key) {
          val interrupted = status == STATUS_INTERRUPTED || status == STATUS_ERROR
          if (interrupted && !key.started && key.interruptions < 2) {
            key.interruptions++
            if (key.released) handler.postDelayed(trySpeech, RETRY_MS)
          } else {
            key.finished = true
          }
        }
      },
    )
    if (!accepted) {
      key.submitted = false
      handler.postDelayed(trySpeech, RETRY_MS)
    }
  }

  private companion object {
    const val RETRY_MS = 80L
  }
}
