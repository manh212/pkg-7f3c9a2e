package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** Non-interactive status; screenshot OCR uses AccessibilityService, not overlay permission. */
internal class SubtitleOverlay(private val service: AccessibilityService) {
  private var window: TextView? = null
  private var hiddenForCapture = false
  private val manager by lazy { service.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

  fun show(message: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(service)) return
    val label = window ?: TextView(service).apply {
      textSize = 12f
      setTextColor(Color.WHITE)
      setBackgroundColor(0xCC202020.toInt())
      val padding = (8f * resources.displayMetrics.density).toInt()
      setPadding(padding, padding, padding, padding)
    }.also { view ->
      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 12
        y = 80
      }
      try {
        manager.addView(view, params)
        window = view
      } catch (e: RuntimeException) {
        LogUtils.w("SubtitleOverlay", "Unable to show status overlay: %s", e.message)
        return
      }
    }
    label.text = "Meridian: $message"
    label.visibility = if (hiddenForCapture) View.INVISIBLE else View.VISIBLE
  }

  fun hideForCapture() {
    hiddenForCapture = true
    window?.visibility = View.INVISIBLE
  }

  fun restoreAfterCapture() {
    hiddenForCapture = false
    window?.visibility = View.VISIBLE
  }

  fun dismiss() {
    hiddenForCapture = false
    window?.let {
      try { manager.removeViewImmediate(it) }
      catch (e: RuntimeException) { LogUtils.w("SubtitleOverlay", "Overlay removal failed: %s", e.message) }
    }
    window = null
  }
}
