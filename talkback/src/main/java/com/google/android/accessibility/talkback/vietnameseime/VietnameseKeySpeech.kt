package com.google.android.accessibility.talkback.vietnameseime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.accessibility.utils.output.SpeechController.STATUS_ERROR
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED

internal class VietnameseKeySpeech(
  private val submit: (String, () -> Unit, (Int) -> Unit) -> Boolean,
  private val stop: () -> Unit,
  private val retryable: () -> Boolean = { true },
) {
  private class Key(val label: String, val exploreAt: Long) {
    var released = false
    var releaseAt = 0L
    var started = false
    var finished = false
    var submitted = false
    var interruptions = 0
    var retries = 0
  }

  private val handler = Handler(Looper.getMainLooper())
  private var selected: Key? = null
  private val trySpeech = Runnable { selected?.let(::request) }

  fun explore(label: String, delay: Long) {
    handler.removeCallbacks(trySpeech)
    val previous = selected
    val key = Key(label, SystemClock.uptimeMillis() + delay)
    selected = key
    if (delay > 0 && previous?.submitted == true && !previous.started) stop()
    if (delay == 0L) request(key) else handler.postDelayed(trySpeech, delay)
  }

  fun release(delay: Long) {
    val key = selected ?: return
    if (key.released) return
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

  fun isCurrent(label: String): Boolean =
    selected?.let { it.label == label && !it.finished } == true

  private fun request(key: Key) {
    if (key.finished || key.started || key.submitted) return
    val target = if (key.released) key.releaseAt else key.exploreAt
    val remaining = target - SystemClock.uptimeMillis()
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
            handler.postDelayed(trySpeech, RETRY_MS)
          } else {
            key.finished = true
          }
        }
      },
    )
    if (!accepted) {
      key.submitted = false
      if (key.retries < MAX_SUBMIT_RETRIES && retryable()) {
        key.retries++
        handler.postDelayed(trySpeech, RETRY_MS)
      } else {
        key.finished = true
      }
    }
  }

  private companion object {
    const val RETRY_MS = 80L
    const val MAX_SUBMIT_RETRIES = 125
  }
}
