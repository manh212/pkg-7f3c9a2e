package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Short vibration and keypress tone for the Vietnamese keyboard, following the
 * Braille keyboard's triggerFeedback pattern: a [Vibrator] pulse plus a
 * [ToneGenerator] beep on [AudioManager.STREAM_ACCESSIBILITY], with the same
 * typing values Braille uses (40 ms vibration, TONE_PROP_ACK for 65 ms).
 * Runs on the local vibrator and tone generator only, never through Meridian
 * speech, so it still fires while Meridian is muted, paused, or off. The tone
 * generator is created lazily on the first sound play and released with
 * [release], so the default-off case never allocates audio resources.
 */
internal open class VietnameseKeyFeedback(context: Context) {
  private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
  private var toneGenerator: ToneGenerator? = null

  open fun play(view: View, vibrate: Boolean, sound: Boolean) {
    if (!vibrate && !sound) return
    if (vibrate) vibrate(view)
    if (sound) playTone()
  }

  fun release() {
    toneGenerator?.let { runCatching { it.release() } }
    toneGenerator = null
  }

  private fun vibrate(view: View) {
    runCatching {
      view.performHapticFeedback(
        HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or
          HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
      )
    }
    if (vibrator?.hasVibrator() == true) {
      runCatching {
        vibrator.vibrate(
          VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE),
        )
      }
    }
  }

  private fun playTone() {
    val tone = toneGenerator ?: runCatching {
      ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, TONE_VOLUME).also { toneGenerator = it }
    }.getOrNull() ?: return
    runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS) }
  }

  companion object {
    private const val VIBRATE_MS = 40L
    private const val TONE_VOLUME = 85
    private const val TONE_DURATION_MS = 65
  }
}
