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

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs offline text recognition in its own process, `:ocr`, for [RemoteOcr].
 *
 * The recognition stack (the PP-OCR detector on ONNX Runtime, OpenCV and Tesseract) is native
 * code. On some phones it can kill the process it runs in, and when that process was the screen
 * reader's, the reader died with it — repeatedly, because the failure sits on the automatic
 * caption path. Here a native failure only ends this process; the reader logs where it happened
 * (see [OcrBreadcrumb]) and treats the recognition as failed.
 */
class OcrService : Service() {
  private lateinit var ipcThread: HandlerThread
  private lateinit var messenger: Messenger

  override fun onCreate() {
    super.onCreate()
    ipcThread = HandlerThread("ocr-ipc").apply { start() }
    messenger =
      Messenger(
        Handler(ipcThread.looper) {
          handle(it)
          true
        }
      )
  }

  override fun onBind(intent: Intent): IBinder = messenger.binder

  override fun onDestroy() {
    ipcThread.quitSafely()
    super.onDestroy()
  }

  private fun handle(message: Message) {
    if (message.what != MSG_RECOGNIZE) return
    val id = message.arg1
    val data = Bundle(message.data)
    val replyTo = message.replyTo ?: return
    worker.execute { recognize(id, data, replyTo) }
  }

  private fun recognize(id: Int, data: Bundle, replyTo: Messenger) {
    val imageFile = data.getString(KEY_IMAGE_PATH)?.let { File(it) }
    val reply =
      try {
        val bitmap = imageFile?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap == null) {
          Message.obtain(null, MSG_FAILED)
        } else {
          try {
            val lines =
              OcrEngine.recognize(
                applicationContext,
                bitmap,
                data.getBoolean(KEY_INCLUDE_WORDS, false),
              )
            if (lines == null) {
              Message.obtain(null, MSG_FAILED)
            } else {
              Message.obtain(null, MSG_RESULT).apply {
                this.data.putString(KEY_RESULT_JSON, toJson(lines))
              }
            }
          } finally {
            bitmap.recycle()
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Offline OCR failed", e)
        Message.obtain(null, MSG_FAILED)
      } finally {
        imageFile?.delete()
      }
    reply.arg1 = id
    try {
      replyTo.send(reply)
    } catch (e: RemoteException) {
      Log.w(TAG, "The screen reader is gone", e)
    }
  }

  private fun toJson(lines: List<TesseractOcr.TextLine>): String {
    val array = JSONArray()
    for (line in lines) {
      val words = JSONArray()
      for (word in line.words) {
        words.put(
          JSONObject()
            .put(KEY_TEXT, word.text)
            .put(KEY_LEFT, word.bounds.left)
            .put(KEY_TOP, word.bounds.top)
            .put(KEY_RIGHT, word.bounds.right)
            .put(KEY_BOTTOM, word.bounds.bottom)
        )
      }
      array.put(
        JSONObject()
          .put(KEY_TEXT, line.text)
          .put(KEY_LEFT, line.bounds.left)
          .put(KEY_TOP, line.bounds.top)
          .put(KEY_RIGHT, line.bounds.right)
          .put(KEY_BOTTOM, line.bounds.bottom)
          .put(KEY_WORDS, words)
          .put(KEY_CONFIDENCE, line.confidence)
      )
    }
    return array.toString()
  }

  companion object {
    private const val TAG = "OcrService"

    // One recognition at a time, like the engine lock did when the engine lived in the reader.
    private val worker =
      Executors.newSingleThreadExecutor { Thread(it, "ocr").apply { isDaemon = true } }

    /** Asks for a recognition. arg1 is the request ID, replyTo gets the answer. */
    const val MSG_RECOGNIZE = 1

    /** The recognition ran; [KEY_RESULT_JSON] holds the lines (possibly none). */
    const val MSG_RESULT = 2

    /** The recognition could not run or found nothing usable. */
    const val MSG_FAILED = 3

    const val KEY_IMAGE_PATH = "image_path"
    const val KEY_INCLUDE_WORDS = "include_words"
    const val KEY_RESULT_JSON = "result_json"
    const val KEY_TEXT = "text"
    const val KEY_LEFT = "left"
    const val KEY_TOP = "top"
    const val KEY_RIGHT = "right"
    const val KEY_BOTTOM = "bottom"
    const val KEY_WORDS = "words"
    const val KEY_CONFIDENCE = "confidence"
  }
}
