package com.google.android.accessibility.talkback.gesture

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.os.UserManagerCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.HomeScreenPages
import com.google.android.libraries.accessibility.utils.log.LogUtils
import org.json.JSONArray
import org.json.JSONException

internal class RecentAppHistory(saved: List<String> = emptyList()) {
  var packages: List<String> = saved.filter(String::isNotBlank).distinct().take(2)
    private set

  fun shown(packageName: String): Boolean {
    if (packageName.isBlank() || packages.firstOrNull() == packageName) return false
    packages = (listOf(packageName) + packages.filterNot { it == packageName }).take(2)
    return true
  }

  fun otherThan(packageName: String?): String? = packages.firstOrNull { it != packageName }

  fun remove(packageName: String) {
    packages = packages.filterNot { it == packageName }
  }
}

class RecentAppSwitcher @JvmOverloads constructor(
  private val service: TalkBackService,
  private val backend: Backend = AndroidBackend(service),
  private val onFailure: () -> Unit = {
    service.feedbackController?.playAuditory(R.raw.complete, null)
  },
) {
  data class Window(val packageName: String, val type: Int)

  interface Backend {
    fun foregroundWindow(): Window?
    fun isHome(packageName: String): Boolean
    fun launchIntent(packageName: String): Intent?
    fun launch(intent: Intent)
  }

  private class AndroidBackend(private val service: TalkBackService) : Backend {
    override fun foregroundWindow(): Window? {
      val windows = service.windows
      val window = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive }
        ?: return null
      val root = window.root ?: return null
      return try {
        root.packageName?.toString()?.let { Window(it, window.type) }
      } finally {
        root.recycle()
      }
    }

    override fun isHome(packageName: String): Boolean =
      HomeScreenPages.isHomePackage(service, packageName)

    override fun launchIntent(packageName: String): Intent? =
      service.packageManager.getLaunchIntentForPackage(packageName)

    override fun launch(intent: Intent) = service.startActivity(intent)
  }

  private val handler = Handler(Looper.getMainLooper())
  private var preferences: SharedPreferences? = null
  private var history = RecentAppHistory()
  private var foregroundPackage: String? = null
  private var pendingTarget: String? = null
  private var pendingSource: String? = null
  private var closed = false

  private val verifySwitch = Runnable {
    refresh()
    if (pendingTarget != null) {
      cancelPending()
      if (isAvailable()) {
        LogUtils.w(TAG, "The requested app did not become foreground")
        onFailure()
      }
    }
  }

  fun isAvailable(): Boolean =
    !closed && UserManagerCompat.isUserUnlocked(service) &&
      service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      refresh()
    }
  }

  fun refresh() {
    if (!isAvailable() || !load()) return
    try {
      val window = backend.foregroundWindow() ?: return
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
        window.packageName == AppGestures.SYSTEM_UI || window.packageName == "android") return
      val packageName = window.packageName
      if (backend.isHome(packageName)) {
        foregroundPackage = packageName
        cancelPending()
        return
      }
      if (packageName == foregroundPackage && packageName == history.packages.firstOrNull()) {
        if (pendingTarget == packageName) cancelPending()
        return
      }
      if (backend.launchIntent(packageName) == null) return
      foregroundPackage = packageName
      if (history.shown(packageName)) save()
      if (pendingTarget == packageName ||
        (pendingTarget != null && pendingSource != packageName)) cancelPending()
    } catch (e: RuntimeException) {
      LogUtils.w(TAG, "Cannot observe the foreground app (%s)", e.javaClass.simpleName)
    }
  }

  fun switchToPrevious(): Boolean {
    if (!isAvailable() || !load()) return false
    refresh()
    if (pendingTarget != null) return true
    val current = foregroundPackage ?: history.packages.firstOrNull()
    val target = history.otherThan(current) ?: return false
    try {
      val intent = backend.launchIntent(target)
      if (intent == null) {
        history.remove(target)
        save()
        return false
      }
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      pendingSource = current
      pendingTarget = target
      handler.postDelayed(verifySwitch, SWITCH_TIMEOUT_MS)
      backend.launch(intent)
      return true
    } catch (e: RuntimeException) {
      cancelPending()
      LogUtils.w(TAG, "Cannot switch to the previous app (%s)", e.javaClass.simpleName)
      return false
    }
  }

  fun close() {
    closed = true
    cancelPending()
  }

  private fun cancelPending() {
    handler.removeCallbacks(verifySwitch)
    pendingSource = null
    pendingTarget = null
  }

  private fun load(): Boolean {
    if (preferences != null) return true
    return try {
      val storage = service.getSharedPreferences(STORE, Context.MODE_PRIVATE)
      val saved = try {
        val values = JSONArray(storage.getString(ENTRIES, "[]"))
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
      } catch (e: JSONException) {
        emptyList()
      }
      history = RecentAppHistory(saved)
      preferences = storage
      true
    } catch (e: RuntimeException) {
      LogUtils.w(TAG, "Cannot load the recent app pair (%s)", e.javaClass.simpleName)
      false
    }
  }

  private fun save() {
    preferences?.edit()?.putString(ENTRIES, JSONArray(history.packages).toString())?.apply()
  }

  companion object {
    private const val TAG = "RecentAppSwitcher"
    private const val STORE = "recent_app_pair"
    private const val ENTRIES = "packages"
    private const val SWITCH_TIMEOUT_MS = 2_000L
  }
}
