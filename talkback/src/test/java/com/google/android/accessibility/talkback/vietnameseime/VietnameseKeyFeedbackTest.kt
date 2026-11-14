package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.os.Vibrator
import com.google.android.accessibility.talkback.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The per-key feedback: vibration amplitude scaling and sound loading without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeyFeedbackTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val vibrator: Vibrator = context.getSystemService(Vibrator::class.java)

  private fun newFeedback() = VietnameseKeyFeedback(context)

  private fun builtinSounds() =
    KeySounds(
      space = KeySound.Builtin(R.raw.lib_new_space),
      normal = KeySound.Builtin(R.raw.lib_new_click),
      delete = KeySound.Builtin(R.raw.lib_new_tick),
      enter = KeySound.Builtin(R.raw.lib_new_clock),
    )

  @Test
  fun amplitudeForPercent_zero_isOff() {
    assertEquals(0, newFeedback().amplitudeForPercent(0))
  }

  @Test
  fun amplitudeForPercent_mapsPercentToAmplitude() {
    val feedback = newFeedback()

    assertEquals(2, feedback.amplitudeForPercent(1))
    assertEquals(63, feedback.amplitudeForPercent(25))
    assertEquals(127, feedback.amplitudeForPercent(50))
    assertEquals(191, feedback.amplitudeForPercent(75))
    assertEquals(255, feedback.amplitudeForPercent(100))
  }

  @Test
  fun amplitudeForPercent_clampsOutOfRange() {
    val feedback = newFeedback()

    assertEquals(255, feedback.amplitudeForPercent(101))
    assertEquals(255, feedback.amplitudeForPercent(1000))
    assertEquals(0, feedback.amplitudeForPercent(-1))
    assertEquals(0, feedback.amplitudeForPercent(-100))
  }

  @Test
  fun play_bothTogglesOff_vibratesNothing() {
    newFeedback().play(
      vibrate = false,
      sound = false,
      KeySound.Builtin(R.raw.lib_new_click),
      100,
    )

    val shadow = shadowOf(vibrator)
    assertFalse(shadow.isVibrating)
    assertEquals(0L, shadow.milliseconds)
  }

  @Test
  @Config(sdk = [30])
  fun play_vibrate_firesFortyMillisecondPulse() {
    newFeedback().play(
      vibrate = true,
      sound = false,
      KeySound.Builtin(R.raw.lib_new_click),
      50,
    )

    val shadow = shadowOf(vibrator)
    assertTrue(shadow.isVibrating)
    assertEquals(40L, shadow.milliseconds)
  }

  @Test
  @Config(sdk = [30])
  fun play_vibrateZeroPercent_vibratesNothing() {
    newFeedback().play(
      vibrate = true,
      sound = false,
      KeySound.Builtin(R.raw.lib_new_click),
      0,
    )

    val shadow = shadowOf(vibrator)
    assertFalse(shadow.isVibrating)
    assertEquals(0L, shadow.milliseconds)
  }

  @Test
  fun release_safeToCallTwiceAndBeforeAnyUse() {
    val feedback = newFeedback()

    feedback.release()
    feedback.release()
  }

  @Test
  fun release_afterPlay_playFallsBackWithoutCrashing() {
    val feedback = newFeedback()
    feedback.setSounds(builtinSounds())
    feedback.play(
      vibrate = true,
      sound = true,
      KeySound.Builtin(R.raw.lib_new_click),
      100,
    )

    feedback.release()
    feedback.release()

    feedback.play(
      vibrate = true,
      sound = true,
      KeySound.Builtin(R.raw.lib_new_click),
      100,
    )
  }

  @Test
  fun setSounds_builtinSounds_playDoesNotCrash() {
    val feedback = newFeedback()
    feedback.setSounds(builtinSounds())

    for (keySound in
      listOf(
        KeySound.Builtin(R.raw.lib_new_space),
        KeySound.Builtin(R.raw.lib_new_click),
        KeySound.Builtin(R.raw.lib_new_tick),
        KeySound.Builtin(R.raw.lib_new_clock),
      )) {
      feedback.play(vibrate = false, sound = true, keySound, 100)
    }
  }

  @Test
  fun setSounds_missingCustomFile_playDoesNotCrash() {
    val feedback = newFeedback()
    val missing = File(context.filesDir, "missing.ogg")
    val sounds =
      KeySounds(
        space = KeySound.Custom(missing),
        normal = KeySound.Custom(missing),
        delete = KeySound.Custom(missing),
        enter = KeySound.Custom(missing),
      )
    feedback.setSounds(sounds)

    feedback.play(vibrate = false, sound = true, KeySound.Custom(missing), 100)
  }

  @Test
  fun setSounds_calledTwiceWithSameSounds_doesNotCrash() {
    val feedback = newFeedback()
    val sounds = builtinSounds()

    feedback.setSounds(sounds)
    feedback.setSounds(sounds)

    feedback.play(vibrate = false, sound = true, KeySound.Builtin(R.raw.lib_new_click), 100)
  }

  @Test
  fun setSounds_customFileChanged_reloadsWithoutCrashing() {
    val file = File(context.cacheDir, "key.ogg")
    file.writeBytes(byteArrayOf(1, 2, 3))
    val feedback = newFeedback()
    val sounds =
      KeySounds(
        space = KeySound.Custom(file),
        normal = KeySound.Custom(file),
        delete = KeySound.Custom(file),
        enter = KeySound.Custom(file),
      )
    feedback.setSounds(sounds)

    assertTrue(file.setLastModified(file.lastModified() + 5000))
    feedback.setSounds(sounds)

    feedback.play(vibrate = false, sound = true, KeySound.Custom(file), 100)
  }
}
