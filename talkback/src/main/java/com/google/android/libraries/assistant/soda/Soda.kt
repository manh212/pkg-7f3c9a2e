package com.google.android.libraries.assistant.soda

import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
class Soda(private val onEvent: (ByteArray) -> Unit, private val onStopped: () -> Unit) : AutoCloseable {
  private var resources = nativeCreateSharedResources(this)
  private var instance = nativeConstruct(resources)

  init {
    check(resources != 0L && instance != 0L) { "SODA allocation failed" }
  }

  fun initialize(config: ByteArray): ByteArray? = nativeInit(instance, config)
  fun start(config: ByteArray): ByteArray? = nativeStartCapture(instance, config)
  fun addAudio(audio: ByteBuffer, bytes: Int): Boolean = nativeAddAudio(instance, audio, bytes.toLong())
  fun stop() = nativeStopCapture(instance)

  override fun close() {
    if (instance != 0L) {
      nativeDelete(instance)
      instance = 0
    }
    if (resources != 0L) {
      nativeDeleteSharedResources(this, resources)
      resources = 0
    }
  }

  protected fun handleSodaEvent(event: ByteArray) = onEvent(event)
  protected fun handleStart() = Unit
  protected fun handleStop(reason: Int) = onStopped()
  protected fun handleShutdown() = Unit

  private external fun nativeConstruct(sharedResources: Long): Long
  private external fun nativeInit(instance: Long, config: ByteArray): ByteArray?
  private external fun nativeStartCapture(instance: Long, config: ByteArray): ByteArray?
  private external fun nativeAddAudio(instance: Long, audio: ByteBuffer, bytes: Long): Boolean
  private external fun nativeStopCapture(instance: Long)
  private external fun nativeDelete(instance: Long)

  companion object {
    @JvmStatic private external fun nativeCreateSharedResources(callback: Any): Long
    @JvmStatic private external fun nativeDeleteSharedResources(callback: Any, resources: Long): Boolean
  }
}
