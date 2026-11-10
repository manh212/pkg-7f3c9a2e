package com.google.android.accessibility.talkback.timer

import android.media.AudioManager
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerSpeechSettingsTest {
  private val prefs = SharedPreferencesUtils.getSharedPreferences(RuntimeEnvironment.getApplication())

  @Test fun streamSettings() {
    prefs.edit().remove(TimerSpeechSettings.KEY_STREAM).commit()
    assertEquals(AudioManager.STREAM_ACCESSIBILITY, TimerSpeechSettings.stream(prefs))
    prefs.edit().putString(TimerSpeechSettings.KEY_STREAM, "media").commit()
    assertEquals(AudioManager.STREAM_MUSIC, TimerSpeechSettings.stream(prefs))
    prefs.edit().putString(TimerSpeechSettings.KEY_STREAM, "ring").commit()
    assertEquals(AudioManager.STREAM_RING, TimerSpeechSettings.stream(prefs))
    prefs.edit().putString(TimerSpeechSettings.KEY_STREAM, "unknown").commit()
    assertEquals(AudioManager.STREAM_ACCESSIBILITY, TimerSpeechSettings.stream(prefs))
  }

  @Test fun missingProfileFallsBackToDefault() {
    prefs.edit().putString(TimerSpeechSettings.KEY_PROFILE, "missing").commit()
    assertEquals("", TimerSpeechSettings.profileId(prefs))
  }

  @Test fun stopwatchStreamFallsBackToTheSharedOneUntilChosen() {
    prefs.edit().remove(TimerSpeechSettings.KEY_STOPWATCH_STREAM)
      .putString(TimerSpeechSettings.KEY_STREAM, "ring").commit()
    assertEquals(AudioManager.STREAM_RING, TimerSpeechSettings.stopwatchStream(prefs))
    assertEquals("ring", TimerSpeechSettings.stopwatchStreamValue(prefs))
    prefs.edit().putString(TimerSpeechSettings.KEY_STOPWATCH_STREAM, "media").commit()
    assertEquals(AudioManager.STREAM_MUSIC, TimerSpeechSettings.stopwatchStream(prefs))
    assertEquals("media", TimerSpeechSettings.stopwatchStreamValue(prefs))
  }

  @Test fun stopwatchProfileFallsBackToTheSharedOneUntilChosen() {
    prefs.edit().putString(TimerSpeechSettings.KEY_PROFILE, "missing")
      .remove(TimerSpeechSettings.KEY_STOPWATCH_PROFILE).commit()
    assertEquals(
      TimerSpeechSettings.profileId(prefs),
      TimerSpeechSettings.stopwatchProfileId(prefs),
    )
    prefs.edit().putString(TimerSpeechSettings.KEY_STOPWATCH_PROFILE, "missing").commit()
    assertEquals("", TimerSpeechSettings.stopwatchProfileId(prefs))
  }
}
