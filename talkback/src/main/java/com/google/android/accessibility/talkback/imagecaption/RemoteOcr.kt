/*
 * Copyright (C) 2026 The Meridian contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.imagecaption

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray

/**
 * The screen reader's side of [OcrService]: sends a screenshot to the `:ocr` process and waits
 * for the recognized lines.
 *
 * When that process stops mid-recognition — the native failure this split exists for — the call
 * returns null, exactly like a recognition that failed, and logs the stage the engine had reached
 * ([OcrBreadcrumb]) so a later diagnostics export names the native stage that was running. The
 * next call binds a fresh process.
 *
 * Calls block, so callers must be on a background thread, as every caller of [TesseractOcr]
 * already is. Stays bound while recognitions keep coming, and unbinds once idle a little longer
 * than the engine keeps itself loaded, so the process and its memory go away.
 */
object RemoteOcr {
  private const val TAG = "RemoteOcr"
  private const val CONNECT_TIMEOUT_SECONDS = 10L
  private const val RECOGNIZE_TIMEOUT_SECONDS = 120L

  /** A reply that never comes from the service: its process stopped. */
  private const val MSG_STOPPED = -1

  /** A little longer than the engine's own idle release (180 s in OcrEngine). */
  private const val IDLE_UNBIND_MS = 190_000L

  private val NEXT_ID = AtomicInteger(0)

  private class Reply(val what: Int, val json: String?)

  private val ipcThread = HandlerThread("ocr-client").apply { start() }
  private val ipc = Handler(ipcThread.looper)
  private val replies =
    Messenger(
      Handler(ipcThread.looper) { message ->
        pending[message.arg1]?.complete(Reply(message.what, message.data.getString(OcrService.KEY_RESULT_JSON)))
        true
      }
    )
  private val pending = ConcurrentHashMap<Int, CompletableFuture<Reply>>()
  // The binding generation each request was sent through, so a stopped process only ends its own.
  private val sentOn = ConcurrentHashMap<Int, Int>()
  @Volatile private var lastDiedBinding = 0

  private val lock = Any()
  // Guarded by lock.
  private var appContext: Context? = null
  private var service: Messenger? = null
  private var bound = false
  private var binding = 0
  private var connected = CountDownLatch(1)

  private val unbindIdle = Runnable { unbind() }

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        synchronized(lock) {
          service = Messenger(binder)
          connected.countDown()
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        LogUtils.w(TAG, "The OCR process stopped")
        stopAll()
      }

      override fun onBindingDied(name: ComponentName) {
        stopAll()
      }
    }

  /**
   * Recognizes the text in [bitmap] in the OCR process, or returns null when the recognition
   * failed or its process stopped.
   */
  @JvmStatic
  fun recognize(
    context: Context,
    bitmap: Bitmap,
    includeWords: Boolean,
  ): List<TesseractOcr.TextLine>? {
    val app = context.applicationContext
    synchronized(lock) { appContext = app }
    ipc.removeCallbacks(unbindIdle)
    val id = NEXT_ID.incrementAndGet()
    val future = CompletableFuture<Reply>()
    pending[id] = future
    var imageFile: File? = null
    try {
      val messenger = connect(app, id)
      imageFile = writeImage(app, bitmap, id)
      val request = Message.obtain(null, OcrService.MSG_RECOGNIZE)
      request.arg1 = id
      request.replyTo = replies
      request.data.putString(OcrService.KEY_IMAGE_PATH, imageFile.path)
      request.data.putBoolean(OcrService.KEY_INCLUDE_WORDS, includeWords)
      messenger.send(request)
      val reply =
        try {
          future.get(RECOGNIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
          LogUtils.w(TAG, "OCR did not answer within %d s", RECOGNIZE_TIMEOUT_SECONDS)
          return null
        }
      return when (reply.what) {
        OcrService.MSG_RESULT -> parse(reply.json)
        MSG_STOPPED -> {
          LogUtils.w(
            TAG,
            "OCR process stopped during stage: %s",
            OcrBreadcrumb.lastStage(app) ?: "unknown",
          )
          null
        }
        else -> null
      }
    } catch (e: RemoteException) {
      LogUtils.w(TAG, "OCR process stopped during stage: %s", OcrBreadcrumb.lastStage(app) ?: "unknown")
      return null
    } catch (e: Exception) {
      LogUtils.w(TAG, "Could not run OCR in its process: %s", e.message ?: e.toString())
      return null
    } finally {
      pending.remove(id)
      sentOn.remove(id)
      imageFile?.delete()
      ipc.postDelayed(unbindIdle, IDLE_UNBIND_MS)
    }
  }

  /** Binds to the service if needed, waits for it, and records the binding for [requestId]. */
  private fun connect(context: Context, requestId: Int): Messenger {
    val latch =
      synchronized(lock) {
        service?.let {
          sentOn[requestId] = binding
          return it
        }
        if (!bound) {
          binding++
          connected = CountDownLatch(1)
          // Not perceptible: when memory runs out, Android stops the OCR process before the
          // screen reader.
          val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE
            } else {
              Context.BIND_AUTO_CREATE
            }
          bound = context.bindService(Intent(context, OcrService::class.java), connection, flags)
          if (!bound) throw IllegalStateException("Could not start the OCR service")
        }
        connected
      }
    if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw IllegalStateException("The OCR service did not start")
    }
    return synchronized(lock) {
      val messenger = service ?: throw IllegalStateException("The OCR process stopped")
      sentOn[requestId] = binding
      messenger
    }
  }

  private fun writeImage(context: Context, bitmap: Bitmap, id: Int): File {
    val file = File(context.cacheDir, "ocr-$id.png")
    val copy =
      if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
      else null
    try {
      FileOutputStream(file).use { out ->
        if (!(copy ?: bitmap).compress(Bitmap.CompressFormat.PNG, 100, out)) {
          throw IllegalStateException("Could not write the screenshot for OCR")
        }
      }
    } finally {
      copy?.recycle()
    }
    return file
  }

  private fun parse(json: String?): List<TesseractOcr.TextLine>? {
    if (json == null) return null
    return try {
      val array = JSONArray(json)
      val lines = ArrayList<TesseractOcr.TextLine>(array.length())
      for (i in 0 until array.length()) {
        val line = array.getJSONObject(i)
        val wordArray = line.optJSONArray(OcrService.KEY_WORDS)
        val words = ArrayList<TesseractOcr.TextLine>()
        if (wordArray != null) {
          for (j in 0 until wordArray.length()) {
            val word = wordArray.getJSONObject(j)
            words.add(TesseractOcr.TextLine(word.getString(OcrService.KEY_TEXT), bounds(word)))
          }
        }
        lines.add(
          TesseractOcr.TextLine(line.getString(OcrService.KEY_TEXT), bounds(line), words)
        )
      }
      lines
    } catch (e: Exception) {
      LogUtils.w(TAG, "Could not read the OCR answer: %s", e.message ?: e.toString())
      null
    }
  }

  private fun bounds(item: org.json.JSONObject) =
    Rect(
      item.getInt(OcrService.KEY_LEFT),
      item.getInt(OcrService.KEY_TOP),
      item.getInt(OcrService.KEY_RIGHT),
      item.getInt(OcrService.KEY_BOTTOM),
    )

  private fun unbind() {
    synchronized(lock) {
      val context = appContext
      if (bound && context != null) {
        try {
          context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
          LogUtils.w(TAG, "Was not bound", e)
        }
      }
      bound = false
      service = null
      connected.countDown()
    }
  }

  /**
   * Drops the binding and ends the requests sent through it, because the process that was
   * answering them is gone. A request sent after this goes to a new process, and is left alone.
   */
  private fun stopAll() {
    val died =
      synchronized(lock) {
        unbind()
        binding
      }
    lastDiedBinding = maxOf(lastDiedBinding, died)
    for ((id, future) in pending) {
      val on = sentOn[id] ?: 0
      if (on in 1..died) future.complete(Reply(MSG_STOPPED, null))
    }
  }
}
