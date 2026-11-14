package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.accessibility.utils.output.FeedbackController

/**
 * Short vibration and per-key sounds for the Vietnamese keyboard, following the
 * Braille keyboard's triggerFeedback pattern: a [Vibrator] pulse whose amplitude
 * scales with the vibration percent, plus a short sound per key slot from a
 * dedicated [SoundPool]. Runs on the local vibrator and sound pool only, never
 * through Meridian speech, so it still fires while Meridian is muted, paused, or
 * off. The [ToneGenerator] stays as the degraded fallback when a key sound cannot
 * play. The pool and tone generator are created lazily and released with
 * [release], so the default-off case never allocates audio resources.
 */
internal open class VietnameseKeyFeedback(context: Context) {
  private val appContext: Context = context.applicationContext
  private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
  private var toneGenerator: ToneGenerator? = null
  private var pool: SoundPool? = null
  private val soundIds = HashMap<KeySound, Int>()
  private val soundStamps = HashMap<KeySound, Long>()

  open fun play(vibrate: Boolean, sound: Boolean, keySound: KeySound, vibratePercent: Int) {
    if (!vibrate && !sound) return
    if (vibrate) vibrate(vibratePercent)
    if (sound) playSound(keySound)
  }

  open fun setSounds(sounds: KeySounds) {
    for (keySound in listOf(sounds.space, sounds.normal, sounds.delete, sounds.enter)) {
      loadSound(keySound)
    }
  }

  fun release() {
    toneGenerator?.let { runCatching { it.release() } }
    toneGenerator = null
    pool?.let { runCatching { it.release() } }
    pool = null
    soundIds.clear()
    soundStamps.clear()
  }

  internal fun amplitudeForPercent(percent: Int): Int =
    if (percent <= 0) 0 else (percent * 255 / 100).coerceIn(1, 255)

  private fun vibrate(percent: Int) {
    val amplitude = amplitudeForPercent(percent)
    if (amplitude == 0) return
    if (vibrator?.hasVibrator() == true) {
      runCatching {
        vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, amplitude))
      }
    }
  }

  private fun loadSound(keySound: KeySound) {
    val pool =
      pool
        ?: SoundPool.Builder()
          .setMaxStreams(MAX_STREAMS)
          .setAudioAttributes(FeedbackController.feedbackAttributes())
          .build()
          .also { pool = it }
    val stamp = stampOf(keySound)
    val loaded = soundIds[keySound]
    if (loaded != null && soundStamps[keySound] == stamp) return
    if (loaded != null) pool.unload(loaded)
    val soundId =
      when (keySound) {
        is KeySound.Builtin -> pool.load(appContext, keySound.resId, 1)
        is KeySound.Custom -> pool.load(keySound.file.absolutePath, 1)
      }
    soundIds[keySound] = soundId
    soundStamps[keySound] = stamp
  }

  private fun stampOf(keySound: KeySound): Long =
    when (keySound) {
      is KeySound.Builtin -> keySound.resId.toLong()
      is KeySound.Custom -> keySound.file.lastModified()
    }

  private fun playSound(keySound: KeySound) {
    val pool = pool
    val soundId = soundIds[keySound]
    if (pool == null || soundId == null || soundId == 0) {
      playTone()
      return
    }
    if (pool.play(soundId, 1f, 1f, 1, 0, 1f) == 0) playTone()
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
    private const val MAX_STREAMS = 6
  }
}
