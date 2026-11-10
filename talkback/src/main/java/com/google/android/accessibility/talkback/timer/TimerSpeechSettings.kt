package com.google.android.accessibility.talkback.timer

import android.content.SharedPreferences
import android.media.AudioManager
import com.google.android.accessibility.utils.output.VoiceProfiles

/**
 * Voice and audio routing for the spoken clock and the stopwatch. Each has its own settings;
 * until the stopwatch's own are chosen, it speaks with the clock's, so an upgrade changes
 * nothing about how it sounds.
 */
object TimerSpeechSettings {
  const val KEY_PROFILE = "pref_timer_voice_profile"
  const val KEY_STREAM = "pref_timer_audio_stream"
  const val KEY_STOPWATCH_PROFILE = "pref_stopwatch_voice_profile"
  const val KEY_STOPWATCH_STREAM = "pref_stopwatch_audio_stream"

  @JvmStatic
  fun profileId(prefs: SharedPreferences): String = validProfile(prefs, prefs.getString(KEY_PROFILE, ""))

  @JvmStatic
  fun stopwatchProfileId(prefs: SharedPreferences): String =
    if (prefs.contains(KEY_STOPWATCH_PROFILE)) validProfile(prefs, prefs.getString(KEY_STOPWATCH_PROFILE, ""))
    else profileId(prefs)

  @JvmStatic
  fun stream(prefs: SharedPreferences): Int = streamOf(prefs.getString(KEY_STREAM, "accessibility"))

  @JvmStatic
  fun stopwatchStream(prefs: SharedPreferences): Int =
    if (prefs.contains(KEY_STOPWATCH_STREAM)) streamOf(prefs.getString(KEY_STOPWATCH_STREAM, "accessibility"))
    else stream(prefs)

  /** The stopwatch's stream setting as the settings list holds it. */
  fun stopwatchStreamValue(prefs: SharedPreferences): String =
    if (prefs.contains(KEY_STOPWATCH_STREAM)) prefs.getString(KEY_STOPWATCH_STREAM, "accessibility") ?: "accessibility"
    else prefs.getString(KEY_STREAM, "accessibility") ?: "accessibility"

  private fun validProfile(prefs: SharedPreferences, id: String?): String =
    id.orEmpty().takeIf { it in VoiceProfiles.ids(prefs) }.orEmpty()

  private fun streamOf(value: String?): Int =
    when (value) {
      "media" -> AudioManager.STREAM_MUSIC
      "ring" -> AudioManager.STREAM_RING
      else -> AudioManager.STREAM_ACCESSIBILITY
    }
}
