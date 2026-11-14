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
  private val retryable: () -> Boolean = { true },
) {
  private class Key(val label: String, val speakAt: Long) {
    var released = false
    var releaseAt = 0L
    var started = false
    var finished = false
    var submitted = false
    var interruptions = 0
    var retries = 0
    var retryAt = 0L
  }

  private val handler = Handler(Looper.getMainLooper())
  private var selected: Key? = null
  private val confirmed = ArrayDeque<Key>()
  private var lastPreview: Key? = null
  private val trySpeech = Runnable { requestNext() }

  fun explore(label: String, delay: Long) {
    selected = Key(label, SystemClock.uptimeMillis() + delay)
    if (delay > 0 && lastPreview?.started == false) stopPreview()
    requestNext()
  }

  fun release(delay: Long, waitsForCommit: Boolean = false) {
    if (waitsForCommit) {
      clearPreview()
      return
    }
    val key = selected ?: return
    if (key.released) return
    key.released = true
    key.releaseAt = SystemClock.uptimeMillis() + delay
    if (confirmed.isEmpty()) requestNext()
  }

  fun confirm(label: String, speakAt: Long) {
    confirmed.addLast(Key(label, speakAt))
    requestNext()
  }

  fun clearPreview() {
    selected = null
    stopPreview()
    requestNext()
  }

  fun cancel() {
    selected = null
    lastPreview = null
    confirmed.clear()
    handler.removeCallbacks(trySpeech)
    stop()
  }

  private fun requestNext() {
    handler.removeCallbacks(trySpeech)
    while (true) {
      val now = SystemClock.uptimeMillis()
      val edit = confirmed.firstOrNull()
      if (edit != null && (edit.started || edit.submitted)) return
      val editDue = edit?.let(::dueAt) ?: Long.MAX_VALUE
      val preview = selected?.takeUnless { it.finished || it.started || it.submitted }
      val previewDue = preview?.let(::dueAt) ?: Long.MAX_VALUE
      val key = if (editDue <= now) edit else preview?.takeIf { previewDue <= now && previewDue < editDue }
      if (key == null) {
        val nextDue = minOf(editDue, previewDue)
        if (nextDue != Long.MAX_VALUE) handler.postDelayed(trySpeech, nextDue - now)
        return
      }
      if (key === edit) selected?.finished = true
      lastPreview = key.takeUnless { it === edit }
      key.submitted = true
      var completed = false
      val accepted = submit(
        key.label,
        { key.started = true },
        completion@{ status ->
          if (completed) return@completion
          completed = true
          key.submitted = false
          if (lastPreview === key) lastPreview = null
          if (key.finished || (selected !== key && confirmed.firstOrNull() !== key)) return@completion
          val confirmedKey = confirmed.firstOrNull() === key
          val shouldRetry = if (confirmedKey) status != STATUS_SPOKEN
            else !key.started && (status == STATUS_INTERRUPTED || status == STATUS_ERROR)
          if (shouldRetry && key.interruptions < 2) {
            key.interruptions++
            key.retryAt = SystemClock.uptimeMillis() + RETRY_MS
            requestNext()
          } else {
            if (finish(key)) requestNext()
          }
        },
      )
      if (accepted) {
        if (key !== edit && edit != null && editDue > now) {
          handler.postDelayed(trySpeech, editDue - now)
        }
        return
      }
      key.submitted = false
      if (lastPreview === key) lastPreview = null
      if (key.retries < MAX_SUBMIT_RETRIES && retryable()) {
        key.retries++
        key.retryAt = SystemClock.uptimeMillis() + RETRY_MS
      } else {
        finish(key)
      }
    }
  }

  private fun dueAt(key: Key): Long =
    maxOf(if (key.released) key.releaseAt else key.speakAt, key.retryAt)

  private fun stopPreview() {
    if (lastPreview == null) return
    lastPreview = null
    stop()
  }

  private fun finish(key: Key): Boolean {
    key.finished = true
    if (confirmed.firstOrNull() !== key) return false
    confirmed.removeFirst()
    return true
  }

  private companion object {
    const val RETRY_MS = 80L
    const val MAX_SUBMIT_RETRIES = 125
  }
}
