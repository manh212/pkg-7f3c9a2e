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
 * do not always return Vietnamese diacritics. Numbers are read as digits or as Vietnamese
 * number words, which [VietnameseNumbers] turns into digits first.
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
    increase: List<String>,
    decrease: List<String>,
    at: String,
    dayParts: List<String>,
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
    val increaseWords: Set<String> = increase.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val decreaseWords: Set<String> = decrease.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
    val atTokens = tokens(at)
    val dayPartWords: Set<String> = dayParts.map { plain(it) }.filter { it.isNotEmpty() }.toSet()

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
          context.getString(R.string.voice_param_increase).split(','),
          context.getString(R.string.voice_param_decrease).split(','),
          context.getString(R.string.voice_param_at),
          context.getString(R.string.voice_param_day_parts).split(','),
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

  /** A spoken change to a percentage: [delta] points up or down from the current value. */
  class RelativePercent(val kind: PercentKind, val delta: Int)

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
    val tokens = tokens(VietnameseNumbers.convert(plain(command)))
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

  /**
   * The spoken rise or fall of a volume or the speech rate, in percentage points, or
   * null when the command changes none. No spoken number means a step of ten points.
   */
  @JvmStatic
  fun parseRelativePercent(command: String, keywords: Keywords): RelativePercent? {
    val words = tokens(VietnameseNumbers.convert(plain(command)))
    if (words.size < 2) return null
    val sign =
      when (words[0]) {
        in keywords.increaseWords -> 1
        in keywords.decreaseWords -> -1
        else -> return null
      }
    val rest = words.drop(1)
    val targets =
      listOf(
        keywords.mediaVolumeTokens to PercentKind.MEDIA_VOLUME,
        keywords.volumeTokens to PercentKind.SPEECH_VOLUME,
        keywords.speechRateTokens to PercentKind.SPEECH_RATE,
      )
    for ((targetTokens, kind) in targets) {
      if (!rest.startsWith(targetTokens)) continue
      val tail = rest.drop(targetTokens.size)
      val step =
        when {
          tail.isEmpty() -> 10
          else -> {
            val number = tail[0].toIntOrNull() ?: return null
            val after = tail.drop(1)
            if (after.isNotEmpty() && after != keywords.percentTokens) return null
            number
          }
        }
      if (step !in 1..999) return null
      return RelativePercent(kind, sign * step)
    }
    return null
  }

  /**
   * The time from [nowMillis] to the clock time a command names ("báo thức lúc 7 giờ
   * 30 tối"), in milliseconds, or null when the command names no clock time. A time
   * that has already passed today means the same time tomorrow.
   */
  @JvmStatic
  fun parseClockAlarmMillis(command: String, keywords: Keywords, nowMillis: Long): Long? {
    val words = tokens(VietnameseNumbers.convert(plain(command)))
    var rest =
      when {
        words.startsWith(keywords.alarmTokens) -> words.drop(keywords.alarmTokens.size)
        words.startsWith(keywords.countdownTokens) -> words.drop(keywords.countdownTokens.size)
        else -> return null
      }
    if (rest.startsWith(keywords.atTokens)) {
      rest = rest.drop(keywords.atTokens.size)
    }
    var index = 0
    val hour = rest.getOrNull(index)?.toIntOrNull() ?: return null
    index++
    if (listOf(rest.getOrNull(index)) != keywords.hoursTokens) return null
    index++
    var minute = 0
    rest.getOrNull(index)?.toIntOrNull()?.let { spokenMinute ->
      minute = spokenMinute
      index++
      if (listOf(rest.getOrNull(index)) == keywords.minutesTokens) {
        index++
      }
    }
    var dayPart: String? = null
    if (index < rest.size) {
      if (rest[index] !in keywords.dayPartWords) return null
      dayPart = rest[index]
      index++
    }
    if (index != rest.size || hour !in 0..23 || minute !in 0..59) return null
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = nowMillis
    calendar.set(java.util.Calendar.HOUR_OF_DAY, clockHour(hour, dayPart))
    calendar.set(java.util.Calendar.MINUTE, minute)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis <= nowMillis) {
      calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    val duration = calendar.timeInMillis - nowMillis
    return if (duration in 1..MAX_ALARM_MILLIS) duration else null
  }

  /* The 24-hour-clock hour a spoken hour means next to its part of the day. */
  private fun clockHour(hour: Int, dayPart: String?): Int =
    when (dayPart) {
      "sang" -> if (hour == 12) 0 else hour
      "trua" ->
        when (hour) {
          12 -> 12
          11 -> 11
          in 1..6 -> hour + 12
          else -> hour
        }
      "chieu" -> if (hour in 1..6) hour + 12 else hour
      "toi" -> if (hour in 1..11) hour + 12 else hour
      "dem" ->
        when (hour) {
          12 -> 0
          11 -> 23
          else -> hour
        }
      "am" -> if (hour == 12) 0 else hour
      "pm" -> if (hour in 1..11) hour + 12 else hour
      else -> hour
    }

  /** The countdown duration in [command] in milliseconds, or null when it sets no alarm. */
  @JvmStatic
  fun parseDurationMillis(command: String, keywords: Keywords): Long? {
    val tokens = tokens(VietnameseNumbers.convert(plain(command)))
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
    val tokens = tokens(VietnameseNumbers.convert(plain(command)))
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

/**
 * Turns Vietnamese number words into digits inside a copy of the diacritic-free text, so the
 * parsers read "năm mươi" like "50" and "hai mươi mốt" like "21". Only the words of a
 * well-formed number are replaced; a word that starts no number is left alone. Numbers run
 * from 1 to 999,999: a unit, the tens forms "mười"/"mươi", "<unit> trăm" hundreds with "lẻ"
 * before a lone final unit, and "<number> nghìn" thousands followed by the same forms
 * again. A bare "không" is not read as zero — it is too often a negation — while a spoken
 * digit 0 already works.
 */
internal object VietnameseNumbers {

  private val units =
    mapOf(
      "mot" to 1,
      "hai" to 2,
      "ba" to 3,
      "bon" to 4,
      "nam" to 5,
      "sau" to 6,
      "bay" to 7,
      "tam" to 8,
      "chin" to 9,
    )

  /* In the final position of a tens group, 1, 4 and 5 have their own forms: mốt, tư, lăm. */
  private val afterTens =
    mapOf(
      "mot" to 1,
      "hai" to 2,
      "ba" to 3,
      "tu" to 4,
      "bon" to 4,
      "lam" to 5,
      "nam" to 5,
      "sau" to 6,
      "bay" to 7,
      "tam" to 8,
      "chin" to 9,
    )

  fun convert(text: String): String {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return text
    val result = mutableListOf<String>()
    var index = 0
    while (index < words.size) {
      val number = numberAt(words, index)
      if (number != null) {
        result.add(number.first.toString())
        index += number.second
      } else {
        result.add(words[index])
        index++
      }
    }
    return result.joinToString(" ")
  }

  /** The value and word count of the number starting at [start], or null when none starts. */
  private fun numberAt(words: List<String>, start: Int): Pair<Int, Int>? {
    val first = underThousandAt(words, start) ?: return null
    val thousandWord = words.getOrNull(start + first.second)
    if (thousandWord != "nghin" && thousandWord != "ngan") {
      return first
    }
    var index = start + first.second + 1
    var low = underThousandAt(words, index)
    val afterLe = words.getOrNull(index + 1)
    if (low == null &&
      (words.getOrNull(index) == "le" || words.getOrNull(index) == "linh") &&
      afterLe != null &&
      units.containsKey(afterLe)) {
      low = units.getValue(afterLe) to 2
    }
    if (low != null) {
      index += low.second
    }
    return (first.first * 1000 + (low?.first ?: 0)) to (index - start)
  }

  /** The value and word count of the 1-to-999 number starting at [start], if one starts. */
  private fun underThousandAt(words: List<String>, start: Int): Pair<Int, Int>? {
    var index = start
    var value = 0
    var consumed = false
    if (index + 1 < words.size &&
      units.containsKey(words[index]) &&
      words[index + 1] == "tram") {
      value += units.getValue(words[index]) * 100
      index += 2
      consumed = true
      if (index + 1 < words.size &&
        (words[index] == "le" || words[index] == "linh") &&
        units.containsKey(words[index + 1])) {
        return (value + units.getValue(words[index + 1])) to (index + 2 - start)
      }
    }
    when {
      index < words.size && words[index] == "muoi" -> {
        value += 10
        index++
        afterTens[words.getOrNull(index)]?.let {
          value += it
          index++
        }
        consumed = true
      }
      index + 1 < words.size &&
        units.containsKey(words[index]) &&
        words[index + 1] == "muoi" -> {
        value += units.getValue(words[index]) * 10
        index += 2
        afterTens[words.getOrNull(index)]?.let {
          value += it
          index++
        }
        consumed = true
      }
    }
    if (consumed) {
      return value to (index - start)
    }
    return units[words.getOrNull(start)]?.let { it to 1 }
  }
}

/** Runs the effects of the parameterized voice commands that no existing actor covers. */
object VoiceCommandActions {

  /** The media stream volume as a percentage of its range, for relative changes. */
  @JvmStatic
  fun getMediaVolumePercent(context: Context): Int {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return 0
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    if (max <= min) return 0
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    return ((current - min) * 100 + (max - min) / 2) / (max - min)
  }

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
