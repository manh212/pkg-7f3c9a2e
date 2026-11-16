package com.google.android.libraries.accessibility.utils.servicecompat

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.io.File
import java.util.concurrent.CountDownLatch

/** An AccessibilityService that is also a [LifecycleOwner] and a [SavedStateRegistryOwner]. */
abstract class AccessibilityServiceCompat :
  AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

  private val dispatcher = ServiceLifecycleDispatcher(this)

  private val stateBundle = Bundle()
  private val savedStateRegistryController = SavedStateRegistryController.create(this)

  @CallSuper
  override fun onCreate() {
    runOnMainSync {
      restoreSavedState()
      savedStateRegistryController.performAttach()
      savedStateRegistryController.performRestore(stateBundle)
      dispatcher.onServicePreSuperOnCreate()
    }
    super.onCreate()
  }

  @CallSuper
  override fun onServiceConnected() {
    runOnMainSync { dispatcher.onServicePreSuperOnBind() }
    super.onServiceConnected()
  }

  @CallSuper
  override fun onDestroy() {
    runOnMainSync {
      savedStateRegistryController.performSave(stateBundle)
      persistSavedState()
      dispatcher.onServicePreSuperOnDestroy()
    }
    super.onDestroy()
  }

  override val lifecycle: Lifecycle
    get() = dispatcher.lifecycle

  override val savedStateRegistry: SavedStateRegistry
    get() = savedStateRegistryController.savedStateRegistry

  private fun runOnMainSync(runnable: Runnable) {
    if (Looper.getMainLooper().isCurrentThread) {
      runnable.run()
    } else {
      val latch = CountDownLatch(1)
      Handler(Looper.getMainLooper())
          .post(
              {
                try {
                  runnable.run()
                } finally {
                  latch.countDown()
                }
              })
      latch.await()
    }
  }

  private fun savedStateFile(): File = File(filesDir, SAVED_STATE_FILE_NAME)

  private fun persistSavedState() {
    runCatching {
          val parcel = Parcel.obtain()
          try {
            stateBundle.writeToParcel(parcel, 0)
            savedStateFile().writeBytes(parcel.marshall())
          } finally {
            parcel.recycle()
          }
        }
        .onFailure { savedStateFile().delete() }
  }

  private fun restoreSavedState() {
    val file = savedStateFile()
    if (!file.exists()) {
      return
    }
    runCatching {
          val bytes = file.readBytes()
          val parcel = Parcel.obtain()
          try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)?.let { stateBundle.putAll(it) }
          } finally {
            parcel.recycle()
          }
        }
        .onFailure { file.delete() }
  }

  companion object {
    private const val SAVED_STATE_FILE_NAME = "accessibility_service_saved_state"
  }
}
