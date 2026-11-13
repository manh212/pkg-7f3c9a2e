package com.google.android.accessibility.talkback.vietnameseime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.accessibility.utils.output.SpeechController.STATUS_ERROR
import com.google.android.accessibility.utils.output.SpeechController.STATUS_INTERRUPTED
import com.google.android.accessibility.utils.output.SpeechController.STATUS_SPOKEN

internal class VietnameseKeySpeech(
  private val submit: (String, () -> Unit, (Int) -> Unit) -> Boolean,
  private val stop: () -> Unit,
) {
  private class Key(val label: String) {
    var released = false
    var releaseAt = 0L
    var started = false
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
    if (!key.started && !key.submitted) {
      if (delay == 0L) request(key) else handler.postDelayed(trySpeech, delay)
    }
  }

  fun cancel(stopSpeech: Boolean) {
    selected = null
    handler.removeCallbacks(trySpeech)
    if (stopSpeech) stop()
  }

  private fun request(key: Key) {
    if (key.started || key.submitted) return
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
        if (status == STATUS_SPOKEN) key.started = true
        key.submitted = false
        if ((status == STATUS_INTERRUPTED || status == STATUS_ERROR) && selected === key && key.released &&
          !key.started && key.interruptions++ < 2) {
          handler.postDelayed(trySpeech, RETRY_MS)
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
