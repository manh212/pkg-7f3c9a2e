package com.google.android.accessibility.talkback.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SodaTranscriber(context: Context) : SpeechTranscriber {
  private val context = context.applicationContext
  private val cancelled = AtomicBoolean(false)
  private val completion = CountDownLatch(1)
  private val main = Handler(Looper.getMainLooper())

  fun transcribePcm(pcm: ByteArray): String? {
    check(Looper.myLooper() != Looper.getMainLooper())
    if (cancelled.get() || !SodaModels.supported() || !SodaModels.get(context).isInstalled()) return null
    val result = AtomicReference<String?>(null)
    val bound = AtomicBoolean(false)
    val sent = AtomicBoolean(false)
    val audio = File.createTempFile("soda-audio-", ".pcm", context.cacheDir)
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (cancelled.get() || completion.count == 0L || !sent.compareAndSet(false, true)) return
        try {
          val descriptor = ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
          descriptor.use {
            audio.delete()
            Messenger(service).send(Message.obtain(null, SodaService.RECOGNIZE).apply {
              arg1 = 1
              data = Bundle().apply { putParcelable(SodaService.AUDIO, it) }
              replyTo = Messenger(Handler(Looper.getMainLooper()) { reply ->
                if (reply.what == SodaService.RESULT && reply.arg1 == 1) {
                  result.set(reply.data.getString(SodaService.TEXT))
                  completion.countDown()
                }
                true
              })
            })
          }
        } catch (_: Exception) {
          completion.countDown()
        }
      }
      override fun onServiceDisconnected(name: ComponentName) { completion.countDown() }
      override fun onBindingDied(name: ComponentName) { completion.countDown() }
      override fun onNullBinding(name: ComponentName) { completion.countDown() }
    }
    try {
      audio.outputStream().use { it.write(pcm) }
      if (cancelled.get()) return null
      main.post {
        if (cancelled.get() || completion.count == 0L) return@post
        try {
          bound.set(context.bindService(Intent(context, SodaService::class.java), connection, Context.BIND_AUTO_CREATE))
          if (!bound.get()) completion.countDown()
        } catch (_: RuntimeException) {
          completion.countDown()
        }
      }
      val timeout = 90L + pcm.size / 32000L
      return if (completion.await(timeout, TimeUnit.SECONDS) && !cancelled.get()) result.get() else null
    } finally {
      completion.countDown()
      audio.delete()
      main.post {
        if (bound.getAndSet(false)) {
          try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
        }
      }
    }
  }

  override fun transcribe(samples: FloatArray, language: String): String? {
    if (language != "vi") return null
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    return transcribePcm(pcm.array())
  }

  override fun cancel() {
    cancelled.set(true)
    completion.countDown()
  }
}
