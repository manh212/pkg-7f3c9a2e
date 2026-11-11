/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.Context
import android.media.AudioManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.timer.CountdownAlarm

/**
 * Reads the numbers spoken inside a voice command: a percentage for a volume or the speech rate,
 * a duration for a countdown alarm, and a repeat count after an action's name.
 *
 * The words come from string resources, so each language brings its own. Matching runs on the
 * diacritic-free normalized form of both the command and the words, because speech recognizers
 * do not always return Vietnamese diacritics. Only digits are read as numbers.
 */
object VoiceCommandParameters {

  /** The keyword phrases of one language, already normalized without diacritics. */
  class Keywords(
    volume: String,
    mediaVolume: String,
    speechRate: String,
    percent: String,
    alarm: String,
    countdown: String,
    hours: String,
    minutes: String,
    seconds: String,
    times: String,
    yesAnswers: List<String>,
  ) {
    val volumeTokens = tokens(volume)
    val mediaVolumeTokens = tokens(mediaVolume)
    val speechRateTokens = tokens(speechRate)
    val percentTokens = tokens(percent)
    val alarmTokens = tokens(alarm)
    val countdownTokens = tokens(countdown)
    val hoursTokens = tokens(hours)
    val minutesTokens = tokens(minutes)
    val secondsTokens = tokens(seconds)
    val timesTokens = tokens(times)
    val yesAnswers: Set<String> = yesAnswers.map { plain(it) }.filter { it.isNotEmpty() }.toSet()

    companion object {
      @JvmStatic
      fun from(context: Context): Keywords =
        Keywords(
          context.getString(R.string.voice_param_volume),
          context.getString(R.string.voice_param_media_volume),
          context.getString(R.string.voice_param_speech_rate),
          context.getString(R.string.voice_param_percent),
          context.getString(R.string.voice_param_alarm),
          context.getString(R.string.voice_param_countdown),
          context.getString(R.string.voice_param_hours),
          context.getString(R.string.voice_param_minutes),
          context.getString(R.string.voice_param_seconds),
          context.getString(R.string.voice_param_times),
          context.getString(R.string.voice_commands_yes_answers).split(','),
        )
    }
  }

  enum class PercentKind {
    SPEECH_VOLUME,
    MEDIA_VOLUME,
    SPEECH_RATE,
  }

  /** A spoken percentage and what it sets. */
  class Percent(val kind: PercentKind, val value: Int)

  /** A spoken repeat count and the remaining command text it applies to. */
  class Repeat(val text: String, val count: Int)

  private const val MAX_REPEAT = 20

  /** The longest countdown a voice command may set: 99 hours, 59 minutes, 59 seconds. */
  private const val MAX_ALARM_MILLIS = 359_999_000L

  private fun plain(text: String): String =
    VoiceCommandMatcher.stripDiacritics(VoiceCommandMatcher.normalize(text))

  private fun tokens(text: String): List<String> =
    plain(text).split(' ').filter { it.isNotEmpty() }

  /** Whether [command] is one of the spoken answers that confirm a dangerous command. */
  @JvmStatic
  fun isYesAnswer(command: String, keywords: Keywords): Boolean =
    plain(command) in keywords.yesAnswers

  /** The percentage in [command], or null when the command sets no percentage. */
  @JvmStatic
  fun parsePercent(command: String, keywords: Keywords): Percent? {
    val tokens = tokens(command)
    percentAfter(tokens, keywords.mediaVolumeTokens, keywords)?.let {
      if (it in 0..100) return Percent(PercentKind.MEDIA_VOLUME, it)
      return null
    }
    percentAfter(tokens, keywords.volumeTokens, keywords)?.let {
      if (it in 0..100) return Percent(PercentKind.SPEECH_VOLUME, it)
      return null
    }
    percentAfter(tokens, keywords.speechRateTokens, keywords)?.let {
      if (it in 1..999) return Percent(PercentKind.SPEECH_RATE, it)
      return null
    }
    return null
  }

  /** The number right after [prefix] in [tokens], with an optional percent word after it. */
  private fun percentAfter(
    tokens: List<String>,
    prefix: List<String>,
    keywords: Keywords,
  ): Int? {
    if (tokens.size <= prefix.size || tokens.subList(0, prefix.size) != prefix) return null
    val number = tokens[prefix.size].toIntOrNull() ?: return null
    val rest = tokens.drop(prefix.size + 1)
    if (rest.isNotEmpty() && rest != keywords.percentTokens) return null
    return number
  }

  /** The countdown duration in [command] in milliseconds, or null when it sets no alarm. */
  @JvmStatic
  fun parseDurationMillis(command: String, keywords: Keywords): Long? {
    val tokens = tokens(command)
    val rest =
      when {
        tokens.startsWith(keywords.alarmTokens) -> tokens.drop(keywords.alarmTokens.size)
        tokens.startsWith(keywords.countdownTokens) -> tokens.drop(keywords.countdownTokens.size)
        else -> return null
      }
    if (rest.isEmpty() || rest.size % 2 != 0) return null
    var totalSeconds = 0L
    val seenUnits = mutableSetOf<List<String>>()
    var index = 0
    while (index < rest.size) {
      val number = rest[index].toLongOrNull() ?: return null
      if (number < 0) return null
      val unit = listOf(rest[index + 1])
      val multiplier =
        when (unit) {
          keywords.hoursTokens -> 3600L
          keywords.minutesTokens -> 60L
          keywords.secondsTokens -> 1L
          else -> return null
        }
      if (!seenUnits.add(unit)) return null
      totalSeconds += number * multiplier
      index += 2
    }
    if (totalSeconds <= 0) return null
    val millis = totalSeconds * 1000
    return if (millis <= MAX_ALARM_MILLIS) millis else null
  }

  /** The repeat count at the end of [command], or null when the command repeats nothing. */
  @JvmStatic
  fun parseRepeat(command: String, keywords: Keywords): Repeat? {
    val tokens = tokens(command)
    if (tokens.size <= keywords.timesTokens.size + 1) return null
    if (tokens.takeLast(keywords.timesTokens.size) != keywords.timesTokens) return null
    val count = tokens[tokens.size - keywords.timesTokens.size - 1].toIntOrNull() ?: return null
    if (count < 2) return null
    val text = tokens.dropLast(keywords.timesTokens.size + 1).joinToString(" ")
    if (text.isEmpty()) return null
    return Repeat(text, minOf(count, MAX_REPEAT))
  }

  private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix
}

/** Runs the effects of the parameterized voice commands that no existing actor covers. */
object VoiceCommandActions {

  /** Sets the media stream volume to [percent] of its range. */
  @JvmStatic
  fun setMediaVolumePercent(context: Context, percent: Int) {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    val index = min + ((max - min) * percent + 50) / 100
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(min, max), 0)
  }

  /** Starts an unnamed countdown alarm of [durationMillis]; it speaks its duration when it rings. */
  @JvmStatic
  fun startCountdownAlarm(context: Context, durationMillis: Long) {
    CountdownAlarm.add(context, "", durationMillis)
  }
}
