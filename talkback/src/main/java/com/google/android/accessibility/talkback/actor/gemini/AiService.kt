/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.actor.gemini

import android.content.SharedPreferences
import java.net.URI
import java.net.URISyntaxException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An AI service with an OpenAI-compatible chat completions API, such as OpenAI, Mistral, Groq,
 * OpenRouter or Ollama, or the Anthropic Messages API, that Describe image and Describe screen use
 * instead of Gemini when the user chooses one in Automatic descriptions settings; see
 * [AiProvider]. Requests are built for Gemini, so they are turned into chat requests, and the
 * answers back into Gemini's.
 */
object AiService {
  private const val FINISH_STOP = "stop"
  private const val FINISH_LENGTH = "length"
  const val FINISH_EMPTY = "EMPTY"

  /** The address of the service in use, which is empty for Gemini. */
  fun address(prefs: SharedPreferences): String = AiProvider.active(prefs).addressOf(prefs)

  fun model(prefs: SharedPreferences): String = AiProvider.active(prefs).model(prefs)

  fun apiKey(prefs: SharedPreferences): String = AiProvider.active(prefs).apiKey(prefs)

  /** The host of the service's address, to name it to the user. */
  fun host(prefs: SharedPreferences): String =
    try {
      URI(address(prefs)).host ?: address(prefs)
    } catch (e: URISyntaxException) {
      address(prefs)
    }

  /** Whether requests go to a service other than Gemini: one is chosen and set up. */
  @JvmStatic
  fun isSetUp(prefs: SharedPreferences): Boolean =
    AiProvider.active(prefs).let {
      it != AiProvider.GEMINI && it != AiProvider.NONE && it.isReady(prefs)
    }

  /** Whether [address] can be used: an https address with a host. */
  fun isValidAddress(address: String): Boolean =
    try {
      val uri = URI(address.trim())
      "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty()
    } catch (e: URISyntaxException) {
      false
    }

  /** The chat completions address of the service at [address], such as .../v1/chat/completions. */
  fun chatUrl(address: String): String {
    val base = address.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
  }

  /** Whether [address] is Anthropic's API, which speaks the Messages API, not chat completions. */
  fun isAnthropic(address: String): Boolean =
    try {
      "api.anthropic.com".equals(URI(address.trim()).host, ignoreCase = true)
    } catch (e: URISyntaxException) {
      false
    }

  /** The request address of the service at [address]: /v1/messages for Anthropic, chat completions otherwise. */
  fun requestUrl(address: String): String {
    val base = address.trim().trimEnd('/')
    if (!isAnthropic(address)) return chatUrl(address)
    return if (base.endsWith("/messages")) base else "$base/messages"
  }

  /**
   * Turns a Gemini request, a text part and an image part in one user message, into an Anthropic
   * Messages request for [model].
   */
  @Throws(JSONException::class)
  fun toAnthropicRequest(geminiRequest: JSONObject, model: String): JSONObject {
    val content = JSONArray()
    val parts = geminiRequest.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
    for (i in 0 until parts.length()) {
      val part = parts.getJSONObject(i)
      if (part.has("text")) {
        content.put(JSONObject().put("type", "text").put("text", part.getString("text")))
      } else if (part.has("inlineData")) {
        val data = part.getJSONObject("inlineData")
        content.put(
          JSONObject()
            .put("type", "image")
            .put(
              "source",
              JSONObject()
                .put("type", "base64")
                .put("media_type", data.optString("mimeType", "image/jpeg"))
                .put("data", data.getString("data"))))
      }
    }
    return JSONObject()
      .put("model", model)
      .put("max_tokens", 4096)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
  }

  /**
   * Reads an Anthropic Messages answer as a Gemini answer. An answer cut off at the service's
   * length limit is still an answer. Returns a response without text when there was none.
   */
  @Throws(JSONException::class)
  fun parseAnthropicResponse(response: JSONObject, wantsJson: Boolean): DataFieldUtils.GeminiResponse {
    val raw =
      response.optJSONArray("content")?.let { content ->
        (0 until content.length())
          .mapNotNull { content.optJSONObject(it)?.takeIf { it.optString("type") == "text" }?.optString("text")?.ifEmpty { null } }
          .joinToString("")
      }.orEmpty()
    val text = if (wantsJson) LocalGemmaRunner.extractJson(raw) else raw.trim()
    val finish =
      when (response.optString("stop_reason")) {
        "end_turn", "stop_sequence" -> FINISH_STOP
        "max_tokens" -> FINISH_LENGTH
        else -> response.optString("stop_reason", FINISH_STOP).uppercase()
      }
    val builder = DataFieldUtils.GeminiResponse.builder()
    if (text.isNotEmpty()) builder.setText(text)
    val finished = finish == FINISH_STOP || finish == FINISH_LENGTH
    builder.setFinishReason(
      when {
        finished && text.isNotEmpty() -> DataFieldUtils.FINISH_REASON_STOP
        finished -> FINISH_EMPTY
        else -> finish
      })
    return builder.build()
  }

  /**
   * Turns a Gemini request, a text part and an image part in one user message, into a chat request
   * for [model]. JSON answers are asked for in the prompt only, since not every service accepts a
   * response format, and the service's own sampling settings are used.
   */
  @Throws(JSONException::class)
  fun toChatRequest(geminiRequest: JSONObject, model: String): JSONObject {
    val content = JSONArray()
    val parts = geminiRequest.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
    for (i in 0 until parts.length()) {
      val part = parts.getJSONObject(i)
      if (part.has("text")) {
        content.put(JSONObject().put("type", "text").put("text", part.getString("text")))
      } else if (part.has("inlineData")) {
        val data = part.getJSONObject("inlineData")
        val url = "data:${data.optString("mimeType", "image/jpeg")};base64,${data.getString("data")}"
        content.put(
          JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
        )
      }
    }
    // No temperature: Gemini's requests ask for 0, which recent Claude models refuse.
    return JSONObject()
      .put("model", model)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
  }

  /** Whether the Gemini [request] asks for a JSON answer. */
  fun wantsJson(request: JSONObject): Boolean =
    request.optJSONObject("generationConfig")?.optString("responseMimeType") == "application/json"

  /**
   * Reads a chat answer as a Gemini answer. An answer cut off at the service's length limit is still
   * an answer. Returns a response without text when there was none.
   */
  @Throws(JSONException::class)
  fun parseChatResponse(response: JSONObject, wantsJson: Boolean): DataFieldUtils.GeminiResponse {
    val choice = response.getJSONArray("choices").getJSONObject(0)
    val message = choice.optJSONObject("message")
    val raw = message?.let { textOf(it.opt("content")) }.orEmpty()
    val text = if (wantsJson) LocalGemmaRunner.extractJson(raw) else raw.trim()
    val finish = choice.optString("finish_reason", FINISH_STOP)
    val builder = DataFieldUtils.GeminiResponse.builder()
    if (text.isNotEmpty()) builder.setText(text)
    val finished = finish == FINISH_STOP || finish == FINISH_LENGTH
    builder.setFinishReason(
      when {
        finished && text.isNotEmpty() -> DataFieldUtils.FINISH_REASON_STOP
        // Not STOP, so that an empty answer counts as none, as an empty one from Gemini does.
        finished -> FINISH_EMPTY
        else -> finish.uppercase()
      }
    )
    return builder.build()
  }

  // Content is a string, or with some services a list of parts.
  private fun textOf(content: Any?): String =
    when (content) {
      is String -> content
      is JSONArray ->
        (0 until content.length())
          .mapNotNull { content.optJSONObject(it)?.optString("text")?.ifEmpty { null } }
          .joinToString("")
      else -> ""
    }
}
