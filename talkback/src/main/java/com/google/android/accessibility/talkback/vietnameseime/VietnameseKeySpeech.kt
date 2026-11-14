package com.google.android.accessibility.talkback.vietnameseime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class VietnameseKeySpeech(
  private val submit: (String, () -> Unit, () -> Unit) -> Boolean,
  private val stop: () -> Unit,
  private val retryable: () -> Boolean = { true },
) {
  private class Key(val label: String, val speakAt: Long) {
    var released = false
    var releaseAt = 0L
    var started = false
    var finished = false
    var submitted = false
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
    val interruptEdit = confirmed.firstOrNull()?.submitted == true
    confirmed.clear()
    if (interruptEdit) stop()
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
    if (selected?.released == false) return
    if (confirmed.lastOrNull()?.submitted == false) confirmed.removeLast()
    confirmed.addLast(Key(label, maxOf(SystemClock.uptimeMillis(), speakAt)))
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
      val editExpiresAt = edit?.let(::editDeadline) ?: Long.MAX_VALUE
      if (edit != null && now >= editExpiresAt) {
        finish(edit)
        if (edit.submitted) stop()
        continue
      }
      if (edit?.submitted == true) {
        handler.postAtTime(trySpeech, editExpiresAt)
        return
      }
      val editDue = edit?.let { minOf(dueAt(it), editExpiresAt) } ?: Long.MAX_VALUE
      val previewExpiresAt = selected
        ?.takeIf { it.released && !it.started && !it.finished }
        ?.let { it.releaseAt + SUBMISSION_TIMEOUT_MS } ?: Long.MAX_VALUE
      if (now >= previewExpiresAt) {
        val wasSubmitted = lastPreview === selected
        selected = null
        if (wasSubmitted) stopPreview()
        continue
      }
      val preview = selected?.takeUnless { it.finished || it.started || it.submitted }
      val previewDue = preview?.let(::dueAt) ?: Long.MAX_VALUE
      val key = if (editDue <= now) edit else preview?.takeIf { previewDue <= now && previewDue < editDue }
      if (key == null) {
        scheduleNext(minOf(editDue, previewDue, previewExpiresAt))
        return
      }
      if (key === edit) selected?.finished = true
      lastPreview = key.takeUnless { it === edit }
      key.submitted = true
      var completed = false
      val accepted = submit(
        key.label,
        { key.started = true },
        completion@{
          if (completed) return@completion
          completed = true
          key.submitted = false
          if (lastPreview === key) lastPreview = null
          if (key.finished || (selected !== key && confirmed.firstOrNull() !== key)) return@completion
          if (finish(key)) requestNext()
        },
      )
      if (accepted) {
        if (!completed) {
          scheduleNext(if (key === edit) editDeadline(key) else minOf(editDue, previewExpiresAt))
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

  private fun editDeadline(key: Key): Long =
    key.speakAt + (if (key.started) EDIT_COMPLETION_TIMEOUT_MS else SUBMISSION_TIMEOUT_MS)

  private fun scheduleNext(at: Long) {
    if (at != Long.MAX_VALUE) handler.postAtTime(trySpeech, at)
  }

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
    const val SUBMISSION_TIMEOUT_MS = 700L
    const val EDIT_COMPLETION_TIMEOUT_MS = 4000L
  }
}
