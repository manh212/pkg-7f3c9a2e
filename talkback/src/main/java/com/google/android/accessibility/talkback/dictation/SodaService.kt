package com.google.android.accessibility.talkback.dictation

import android.app.Service
import android.content.Intent
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

  private fun recognize(id: Int, audio: ParcelFileDescriptor, reply: Messenger) {
    val failure = AtomicBoolean(false)
    val stopped = CountDownLatch(1)
    val text = SodaTranscript()
    var engine: Soda? = null
    val answer = try {
      val models = SodaModels.get(this)
      if (!models.isInstalled()) throw IOException("SODA data unavailable")
      if (models.library.inputStream().use(ModelFiles::sha256) != SodaModels.LIBRARY_HASH) {
        throw IOException("SODA engine verification failed")
      }
      System.load(models.library.absolutePath)
      val native = Soda(
        onEvent = { data ->
          try {
            if (SodaWire.fields(data).any { it.number == 3 }) throw IOException("SODA recognition error")
            SodaWire.result(data)?.let(text::update)
          } catch (e: IOException) {
            failure.set(true)
            Log.w(TAG, "Invalid SODA event", e)
            stopped.countDown()
          }
        },
        onStopped = { stopped.countDown() },
      )
      engine = native
      val modelPath = models.languagePack.absolutePath + "/"
      val cache = java.io.File(cacheDir, "soda-runtime").apply { mkdirs() }
      SodaWire.requireSuccess(native.initialize(SodaWire.coreConfig(modelPath, cache.absolutePath + "/")))
      SodaWire.requireSuccess(native.start(SodaWire.captureConfig()))
      val buffer = ByteBuffer.allocateDirect(3200)
      val bytes = ByteArray(3200)
      ParcelFileDescriptor.AutoCloseInputStream(audio).use { input ->
        var total = 0L
        while (true) {
          if (Thread.currentThread().isInterrupted) throw InterruptedException()
          val count = input.read(bytes)
          if (count < 0) break
          total += count
          if (total > AudioCapture.MAX_SECONDS * 32000L || count % 2 != 0) throw IOException("Invalid PCM recording")
          buffer.clear()
          buffer.put(bytes, 0, count)
          buffer.flip()
          if (!native.addAudio(buffer, count)) throw IOException("SODA rejected audio")
        }
      }
      native.stop()
      if (!stopped.await(30, TimeUnit.SECONDS)) throw IOException("SODA finalization timed out")
      if (failure.get()) throw IOException("SODA result decoding failed")
      text.value()
    } catch (e: Exception) {
      Log.w(TAG, "SODA recognition failed", e)
      null
    } catch (e: LinkageError) {
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
        data = Bundle().apply { putString(TEXT, answer) }
      })
    } catch (e: RemoteException) {
      Log.w(TAG, "SODA client disconnected", e)
    }
  }

  companion object {
    private const val TAG = "SodaService"
    const val RECOGNIZE = 1
    const val RESULT = 2
    const val AUDIO = "audio"
    const val TEXT = "text"
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
