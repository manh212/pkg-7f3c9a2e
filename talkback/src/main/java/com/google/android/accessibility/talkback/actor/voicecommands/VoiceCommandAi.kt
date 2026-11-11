/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
 * applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.Context
import com.google.android.accessibility.talkback.actor.gemini.AiTextRequests
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * The AI fallback for voice commands. When the user has turned it on and no built-in rule
 * matches a spoken sentence, the sentence is sent to the AI service together with the list
 * of speakable actions, and the service picks at most one result: an action, a percentage,
 * a countdown duration, or nothing. The pick runs at once; a failure, an empty pick and a
 * timeout all leave the command unrecognized, exactly as without the fallback.
 */
object VoiceCommandAi {

  const val PREF_KEY = "pref_voice_command_ai_fallback"

  /** What the AI made of one sentence. */
  sealed class Interpretation {
    class Action(val entry: VoiceCommandMatcher.Entry, val repeat: Int) : Interpretation()

    class Percent(val percent: VoiceCommandParameters.Percent) : Interpretation()

    class Alarm(val durationMillis: Long) : Interpretation()

    object None : Interpretation()
  }

  /**
   * Whether the fallback may run: the user turned its switch on and an AI service is set
   * up. Read on every command, so turning the switch takes effect at once.
   */
  @JvmStatic
  fun isEnabled(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getBoolean(PREF_KEY, false) &&
      AiTextRequests.isSetUp(context)

  private var requests: AiTextRequests? = null

  /**
   * Asks the AI to interpret [command] against [entries], the taught phrases first and the
   * catalog after them. [done] runs exactly once, with [Interpretation.None] on any
   * failure.
   */
  @JvmStatic
  fun interpret(
    context: Context,
    command: String,
    entries: List<VoiceCommandMatcher.Entry>,
    done: (Interpretation) -> Unit,
  ) {
    val ai = requests ?: AiTextRequests(context) {}.also { requests = it }
    ai.ask(buildPrompt(command, entries), /* json= */ true) { text, _ ->
      done(if (text == null) Interpretation.None else parseAnswer(text, entries))
    }
  }

  /** The prompt: the sentence, the numbered actions, and the only answer shapes allowed. */
  internal fun buildPrompt(
    command: String,
    entries: List<VoiceCommandMatcher.Entry>,
  ): String {
    val builder = StringBuilder()
    builder.append("Người dùng đang điều khiển điện thoại bằng giọng nói và đã nói: \"")
    builder.append(command.replace('"', '\''))
    builder.append("\"\n\nDưới đây là các hành động có thể thực hiện, đánh số từ 1:\n")
    entries.forEachIndexed { index, entry ->
      builder.append(index + 1).append(". ").append(entry.phrase).append('\n')
    }
    builder.append(
      "\nHãy chọn đúng một kết quả và trả lời duy nhất bằng một đối tượng JSON, không thêm" +
        " chữ nào khác:\n" +
        "- Nếu câu nói là một trong các hành động trên: {\"action\": số thứ tự}; nếu câu nói" +
        " yêu cầu lặp lại hành động thì thêm \"repeat\": số lần.\n" +
        "- Nếu câu nói đặt âm lượng giọng đọc theo phần trăm: {\"volume\": số từ 0 đến 100}.\n" +
        "- Nếu câu nói đặt âm lượng nhạc theo phần trăm: {\"media\": số từ 0 đến 100}.\n" +
        "- Nếu câu nói đặt tốc độ đọc theo phần trăm: {\"rate\": số từ 1 đến 999}.\n" +
        "- Nếu câu nói đặt báo thức đếm ngược: {\"alarmSeconds\": tổng số giây}.\n" +
        "- Nếu không kết quả nào phù hợp: {\"action\": 0}."
    )
    return builder.toString()
  }

  /** Reads one AI answer; anything unreadable or out of range is [Interpretation.None]. */
  internal fun parseAnswer(
    text: String,
    entries: List<VoiceCommandMatcher.Entry>,
  ): Interpretation {
    fun number(key: String): Int? =
      Regex("\"" + key + "\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    number("volume")?.let {
      if (it in 0..100) {
        return Interpretation.Percent(
          VoiceCommandParameters.Percent(VoiceCommandParameters.PercentKind.SPEECH_VOLUME, it)
        )
      }
    }
    number("media")?.let {
      if (it in 0..100) {
        return Interpretation.Percent(
          VoiceCommandParameters.Percent(VoiceCommandParameters.PercentKind.MEDIA_VOLUME, it)
        )
      }
    }
    number("rate")?.let {
      if (it in 1..999) {
        return Interpretation.Percent(
          VoiceCommandParameters.Percent(VoiceCommandParameters.PercentKind.SPEECH_RATE, it)
        )
      }
    }
    number("alarmSeconds")?.let {
      if (it in 1..359_999) {
        return Interpretation.Alarm(it * 1000L)
      }
    }
    val action = number("action") ?: return Interpretation.None
    if (action == 0 || action > entries.size) {
      return Interpretation.None
    }
    val repeat = number("repeat")?.coerceIn(2, 20) ?: 1
    return Interpretation.Action(entries[action - 1], repeat)
  }
}
