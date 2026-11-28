package com.google.android.accessibility.talkback.dictation

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import com.google.android.libraries.assistant.soda.Soda
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SodaService : Service() {
  private val worker = Executors.newSingleThreadExecutor { Thread(it, "soda-recognizer") }
  private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
    if (message.what == RECOGNIZE) {
      val reply = message.replyTo
      val audio = message.data.getParcelable<ParcelFileDescriptor>(AUDIO)
      val id = message.arg1
      if (reply != null && audio != null) worker.execute { recognize(id, audio, reply) }
      else audio?.close()
    }
    true
  })

  override fun onBind(intent: Intent): IBinder = messenger.binder

  override fun onDestroy() {
    worker.shutdownNow()
    super.onDestroy()
    Process.killProcess(Process.myPid())
  }

  private fun recordError(detail: String?) {
    try {
      val file = File(cacheDir, "soda-last-error.txt")
      if (detail == null) file.delete() else file.writeText(detail.take(512))
    } catch (_: IOException) {
    }
  }

  private fun ensureLibraryLoaded(models: SodaModels) {
    if (libraryLoaded) return
    if (models.library.inputStream().use(ModelFiles::sha256) != SodaModels.LIBRARY_HASH) {
      throw IOException("SODA engine verification failed")
    }
    System.load(models.library.absolutePath)
    libraryLoaded = true
  }

  private fun pushAudio(
    native: Soda,
    buffer: ByteBuffer,
    length: Int,
    stopped: CountDownLatch,
  ) {
    for (attempt in 0 until ADD_AUDIO_RETRIES) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException()
      if (native.addAudio(buffer, length)) return
      if (stopped.count == 0L) throw IOException("SODA stopped before accepting audio")
      Thread.sleep(ADD_AUDIO_BACKOFF_MS)
    }
    throw IOException("SODA rejected audio")
  }

  private fun recognize(id: Int, audio: ParcelFileDescriptor, reply: Messenger) {
    val failure = AtomicBoolean(false)
    val started = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    val text = SodaTranscript()
    var engine: Soda? = null
    var errorDetail: String? = null
    val answer = try {
      val models = SodaModels.get(this)
      if (!models.isInstalled()) throw IOException("SODA data unavailable")
      ensureLibraryLoaded(models)
      val native = Soda(
        onEvent = { data ->
          try {
            SodaWire.result(data)?.let(text::update)
          } catch (e: IOException) {
            failure.set(true)
            errorDetail = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Invalid SODA event", e)
            stopped.countDown()
          }
        },
        onStopped = { stopped.countDown() },
        onStarted = { started.countDown() },
      )
      engine = native
      val modelPath = models.languagePack.absolutePath + "/"
      val cache = File(cacheDir, "soda-runtime").apply { mkdirs() }
      SodaWire.requireSuccess(
        native.initialize(SodaWire.coreConfig(modelPath, cache.absolutePath + "/", Build.MODEL.orEmpty()))
      )
      SodaWire.requireSuccess(native.start(SodaWire.captureConfig()))
      started.await(START_WAIT_MS, TimeUnit.MILLISECONDS)
      val buffer = ByteBuffer.allocateDirect(CHUNK_BYTES)
      val bytes = ByteArray(CHUNK_BYTES)
      var carry = 0
      ParcelFileDescriptor.AutoCloseInputStream(audio).use { input ->
        var total = 0L
        while (true) {
          if (Thread.currentThread().isInterrupted) throw InterruptedException()
          val count = input.read(bytes, carry, bytes.size - carry)
          if (count < 0) break
          total += count
          if (total > AudioCapture.MAX_SECONDS * 32000L) throw IOException("Invalid PCM recording")
          val available = carry + count
          val send = available and 1.inv()
          if (send > 0) {
            buffer.clear()
            buffer.put(bytes, 0, send)
            buffer.flip()
            pushAudio(native, buffer, send, stopped)
          }
          carry = available - send
          if (carry == 1) bytes[0] = bytes[send]
        }
        if (carry != 0) throw IOException("Invalid PCM recording")
      }
      // Append short trailing silence so the streaming endpointer and punctuation model flush cleanly.
      val silence = ByteArray(CHUNK_BYTES)
      repeat(TRAILING_SILENCE_CHUNKS) {
        if (stopped.count > 0L) {
          buffer.clear()
          buffer.put(silence)
          buffer.flip()
          pushAudio(native, buffer, CHUNK_BYTES, stopped)
        }
      }
      // Signal MIC_END_OF_DATA in-order at the end of the audio stream (matching SodaAudioPusher).
      if (stopped.count > 0L) {
        buffer.clear()
        buffer.flip()
        native.addAudio(buffer, 0)
      }
      if (!stopped.await(FINALIZE_WAIT_SECONDS, TimeUnit.SECONDS)) {
        native.stop()
        if (!stopped.await(FINALIZE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          throw IOException("SODA finalization timed out")
        }
      }
      if (failure.get()) throw IOException("SODA result decoding failed")
      recordError(null)
      text.value()
    } catch (e: Exception) {
      errorDetail = "${e.javaClass.simpleName}: ${e.message}"
      recordError(errorDetail)
      Log.w(TAG, "SODA recognition failed", e)
      null
    } catch (e: LinkageError) {
      errorDetail = "${e.javaClass.simpleName}: ${e.message}"
      recordError(errorDetail)
      Log.w(TAG, "SODA library unavailable", e)
      null
    } finally {
      audio.close()
      try {
        engine?.close()
      } catch (e: Exception) {
        Log.w(TAG, "SODA cleanup failed", e)
      } catch (e: LinkageError) {
        Log.w(TAG, "SODA cleanup unavailable", e)
      }
    }
    try {
      reply.send(Message.obtain(null, RESULT).apply {
        arg1 = id
        data = Bundle().apply {
          putString(TEXT, answer)
          if (errorDetail != null) putString(ERROR, errorDetail)
        }
      })
    } catch (e: RemoteException) {
      Log.w(TAG, "SODA client disconnected", e)
    }
  }

  companion object {
    private const val TAG = "SodaService"
    private const val CHUNK_BYTES = 3200
    private const val TRAILING_SILENCE_CHUNKS = 5
    private const val START_WAIT_MS = 2_000L
    private const val ADD_AUDIO_RETRIES = 20
    private const val ADD_AUDIO_BACKOFF_MS = 25L
    private const val FINALIZE_WAIT_SECONDS = 15L
    @Volatile private var libraryLoaded = false
    const val RECOGNIZE = 1
    const val RESULT = 2
    const val AUDIO = "audio"
    const val TEXT = "text"
    const val ERROR = "error"
  }
}

internal class SodaTranscript {
  private val committed = ArrayList<String>()
  private var partial = ""
  @Synchronized fun update(result: SodaWire.Result) {
    if (result.final) {
      if (result.text.isNotEmpty()) committed += result.text
      partial = ""
    } else {
      partial = result.text
    }
  }
  @Synchronized fun value(): String = (committed + partial).filter { it.isNotEmpty() }.joinToString(" ")
}
