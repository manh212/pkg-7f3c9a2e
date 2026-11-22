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

package com.google.android.accessibility.talkback.actor.gemini

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONObject

class OnlineImageQuery(context: Context) {
  private val appContext = context.applicationContext
  private val prefs: SharedPreferences = SharedPreferencesUtils.getSharedPreferences(appContext)
  private val geminiPerformer = GeminiRestRequestPerformer(appContext)
  private val servicePerformer = AiServiceRequestPerformer(appContext)

  fun activeProvider(): AiProvider = AiProvider.active(prefs)

  fun isReady(): Boolean {
    val provider = activeProvider()
    return provider != AiProvider.NONE && provider.isReady(prefs)
  }

  fun ask(
    prompt: String,
    jpeg: String,
    wantsJson: Boolean = true,
    done: (text: String?, errorMessage: String?) -> Unit,
  ) {
    val provider = activeProvider()
    if (provider == AiProvider.NONE || !provider.isReady(prefs)) {
      done(null, "Chưa thiết lập dịch vụ AI trực tuyến trong cài đặt Mô tả hình ảnh.")
      return
    }
    val parts =
      JSONArray()
        .put(JSONObject().put("text", prompt))
        .put(
          JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", "image/jpeg").put("data", jpeg),
          )
        )
    val request =
      JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
    if (wantsJson) {
      request.put(
        "generationConfig",
        JSONObject()
          .put("responseMimeType", "application/json")
          .put("temperature", 0.0),
      )
    }

    val callback =
      object : GeminiRestRequestPerformer.GeminiRestResponseCallback {
        override fun onResponse(response: DataFieldUtils.GeminiResponse) {
          val text = response.text()?.trim().orEmpty()
          if (text.isEmpty()) {
            done(null, "Dịch vụ AI không trả về kết quả.")
          } else {
            done(text, null)
          }
        }

        override fun onFailure(failure: GeminiFailure) {
          done(null, failure.userMessage(appContext))
        }

        override fun onCancelled() {
          done(null, null)
        }
      }

    if (provider == AiProvider.GEMINI) {
      servicePerformer.cancelExistingRequestIfNeeded()
      val urls = GeminiModel.chain(prefs).map { "$GEMINI_BASE_URL$it:generateContent" }
      val apiKey = GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY)
      geminiPerformer.performRequest(urls, apiKey, request, callback)
    } else {
      geminiPerformer.cancelExistingRequestIfNeeded()
      servicePerformer.performRequest(
        provider.addressOf(prefs),
        provider.model(prefs),
        provider.apiKey(prefs),
        request,
        callback,
      )
    }
  }

  fun cancel() {
    geminiPerformer.cancelExistingRequestIfNeeded()
    servicePerformer.cancelExistingRequestIfNeeded()
  }

  companion object {
    private const val GEMINI_BASE_URL =
      "https://generativelanguage.googleapis.com/v1beta/models/"
  }
}
