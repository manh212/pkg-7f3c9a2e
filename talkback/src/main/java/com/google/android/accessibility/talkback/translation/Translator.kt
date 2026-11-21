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

package com.google.android.accessibility.talkback.translation

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.AiTextRequests
import com.google.android.accessibility.talkback.actor.gemini.GeminiFailure
import com.google.android.accessibility.talkback.translation.TranslationFailure.Kind
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.IOException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException

class Translator(context: Context) {
  private val appContext = context.applicationContext
  private val prefs: SharedPreferences = SharedPreferencesUtils.getSharedPreferences(appContext)
  private val handler = Handler(Looper.getMainLooper())

  private val worker =
    ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "translation").apply { isDaemon = true }
      }
      .apply { allowCoreThreadTimeOut(true) }

  private var request = 0
  private val countedOnDevice = AtomicBoolean(false)

  fun interface RequestHandle {
    fun cancel()
  }

  sealed interface Result {
    data class Done(val translation: Translation) : Result

    data class Failed(val failure: TranslationFailure, val service: TranslationService) : Result

    data class Downloading(val languages: List<String>) : Result
  }

  fun service(): TranslationService = TranslationSettings.service(prefs, OnDeviceTranslations.get() != null)

  fun isSetUp(service: TranslationService): Boolean =
    when (service) {
      TranslationService.ON_DEVICE -> OnDeviceTranslations.get() != null
      TranslationService.AI -> AiTextRequests.isSetUp(appContext)
      else -> TranslationSettings.missing(prefs, service) == null
    }

  fun translate(text: String, onResult: (Result) -> Unit) {
    val id = ++request
    val source = TranslationSettings.source(prefs)
    run(
      text,
      service(),
      source,
      TranslationSettings.target(prefs),
      if (source == null) TranslationSettings.secondTarget(prefs) else null,
      Downloads.WAIT,
    ) { result ->
      if (id == request || result is Result.Downloading) onResult(result)
    }
  }

  /**
   * Translates [text] into [to], from [from] or the language it's found to be in, with the chosen
   * service, waiting for on-device models to download. For scripts.
   */
  fun translateTo(text: String, from: String?, to: String?, onResult: (Result) -> Unit) {
    run(text, service(), from, to ?: TranslationSettings.target(prefs), second = null, Downloads.WAIT, report = onResult)
  }

  /**
   * Translates [text] from [from] into [to] with [service], for Automatic translation, alongside
   * [translate]. [downloads] says what happens when a language model isn't downloaded;
   * [onDownloaded] is told when models that [Downloads.START] started have all downloaded.
   */
  fun translateNow(
    text: String,
    service: TranslationService,
    from: String,
    to: String,
    downloads: Downloads,
    onDownloaded: () -> Unit,
    onResult: (Result) -> Unit,
  ): RequestHandle = run(text, service, from, to, second = null, downloads, onDownloaded, onResult)

  enum class Downloads {

    WAIT,

    START,

    NONE,
  }

  private fun run(
    text: String,
    service: TranslationService,
    source: String?,
    target: String,
    second: String?,
    downloads: Downloads,
    onDownloaded: () -> Unit = {},
    report: (Result) -> Unit,
  ): RequestHandle {
    val fail: (Kind, String) -> Unit = { kind, detail -> report(Result.Failed(TranslationFailure(kind, detail), service)) }
    if (!com.google.android.accessibility.talkback.licensing.DeviceLicenseManager.allows(appContext, "translation")) {
      fail(Kind.OTHER, "Giấy phép hiện tại không có tính năng dịch văn bản.")
      return RequestHandle {}
    }
    if (!isSetUp(service)) {
      fail(Kind.NOT_SET_UP, "")
      return RequestHandle {}
    }
    val input = text.take(TranslationApis.MAX_TEXT)
    return when (service) {
      TranslationService.ON_DEVICE -> onDevice(input, source, target, second, downloads, onDownloaded, report, fail)
      TranslationService.AI -> {
        val ask =
          Ask(this, AiTranslationPrompt.prompt(input, source, target, second)) { answer, failure ->
            when {
              answer != null -> report(Result.Done(AiTranslationPrompt.parse(answer, target)))
              failure != null -> fail(Kind.OTHER, failure.userMessage(appContext))
              else -> fail(Kind.OTHER, "")
            }
          }
        enqueue(appContext, ask)
        RequestHandle { cancelAsk(ask) }
      }
      else -> web(service, input, source, target, second, report)
    }
  }

  fun cancel() {
    request++
    cancelAsks(this)
  }

  fun shutdown() {
    cancel()
    worker.shutdownNow()
    if (countedOnDevice.compareAndSet(true, false) && onDeviceUsers.decrementAndGet() == 0) {
      OnDeviceTranslations.get()?.close()
    }
  }

  private fun web(
    service: TranslationService,
    text: String,
    source: String?,
    target: String,
    second: String?,
    report: (Result) -> Unit,
  ): RequestHandle {
    val key = TranslationSettings.apiKey(prefs, service)
    val region = TranslationSettings.microsoftRegion(prefs)
    val address = TranslationSettings.libreTranslateAddress(prefs)
    val locale = Locale.getDefault()
    val cancelled = AtomicBoolean(false)
    val future =
      worker.submit {
        if (cancelled.get()) return@submit
        val result =
          try {
            var translation = ask(service, key, region, address, locale, text, source, target)
            if (cancelled.get()) return@submit
            if (second != null && TranslationLanguages.same(translation.from, target)) {
              translation = ask(service, key, region, address, locale, text, translation.from, second)
            }
            Result.Done(translation)
          } catch (e: FailureException) {
            Result.Failed(e.failure, service)
          } catch (e: IOException) {
            LogUtils.w(TAG, "%s not reached: %s", service.id, e)
            Result.Failed(TranslationFailure(Kind.CONNECTION), service)
          } catch (e: Throwable) {
            LogUtils.w(TAG, "%s failed: %s", service.id, e)
            Result.Failed(TranslationFailure(Kind.OTHER), service)
          }
        if (!cancelled.get()) {
          handler.post { if (!cancelled.get()) report(result) }
        }
      }
    return RequestHandle {
      if (cancelled.compareAndSet(false, true)) {
        future.cancel(true)
        if (future is Runnable) worker.remove(future)
      }
    }
  }

  @Throws(IOException::class, FailureException::class)
  private fun ask(
    service: TranslationService,
    key: String,
    region: String,
    address: String,
    locale: Locale,
    text: String,
    from: String?,
    to: String,
  ): Translation {
    val request =
      when (service) {
        TranslationService.GOOGLE_CLOUD -> TranslationApis.googleCloud(key, text, from, to)
        TranslationService.DEEPL -> TranslationApis.deepL(key, text, from, to, locale)
        TranslationService.MICROSOFT -> TranslationApis.microsoft(key, region, text, from, to)
        else -> TranslationApis.libreTranslate(address, key, text, from, to)
      }
    val (status, body) = TranslationApis.send(request)
    if (status != 200) {
      val failure = TranslationApis.failure(status, body)
      LogUtils.w(TAG, "%s: HTTP %d %s", service.id, status, failure.detail)
      throw FailureException(failure)
    }
    return try {
      when (service) {
        TranslationService.GOOGLE_CLOUD -> TranslationApis.parseGoogleCloud(body, to)
        TranslationService.DEEPL -> TranslationApis.parseDeepL(body, to)
        TranslationService.MICROSOFT -> TranslationApis.parseMicrosoft(body, to)
        else -> TranslationApis.parseLibreTranslate(body, to)
      }
    } catch (e: JSONException) {
      LogUtils.w(TAG, "%s answer not read: %s", service.id, e)
      throw FailureException(TranslationFailure(Kind.OTHER, "The answer could not be read"))
    }
  }

  private fun onDevice(
    text: String,
    source: String?,
    target: String,
    second: String?,
    downloads: Downloads,
    onDownloaded: () -> Unit,
    report: (Result) -> Unit,
    fail: (Kind, String) -> Unit,
  ): RequestHandle {
    val models = OnDeviceTranslations.get()
    if (models == null) {
      fail(Kind.NOT_SET_UP, "")
      return RequestHandle {}
    }
    if (countedOnDevice.compareAndSet(false, true)) onDeviceUsers.incrementAndGet()
    val cancelled = AtomicBoolean(false)
    val guardedReport: (Result) -> Unit = { if (!cancelled.get()) report(it) }
    val guardedFail: (Kind, String) -> Unit = { kind, detail -> if (!cancelled.get()) fail(kind, detail) }
    val withSource: (String) -> Unit = { from ->
      if (!cancelled.get()) {
        val to = if (second != null && from == target) second else target
        val unsupported = listOf(from, to).firstOrNull { it !in models.languages }
        when {
          from == to -> guardedReport(Result.Done(Translation(text, from, to)))
          unsupported != null -> guardedFail(Kind.LANGUAGE, unsupported)
          else -> withModels(models, from, to, downloads, onDownloaded, guardedReport, guardedFail) {
            if (!cancelled.get()) {
              models.translate(text, from, to) { translated ->
                if (translated == null) guardedFail(Kind.OTHER, "")
                else guardedReport(Result.Done(Translation(translated, from, to)))
              }
            }
          }
        }
      }
    }
    if (source != null) {
      withSource(source)
    } else {
      models.detect(text) { detected, unavailable ->
        if (!cancelled.get()) {
          when {
            detected != null -> withSource(detected)
            unavailable -> guardedFail(Kind.DETECTION_DOWNLOADING, "")
            else -> guardedFail(Kind.NOT_DETECTED, "")
          }
        }
      }
    }
    return RequestHandle { cancelled.set(true) }
  }

  private fun withModels(
    models: OnDeviceTranslation,
    from: String,
    to: String,
    downloads: Downloads,
    onDownloaded: () -> Unit,
    report: (Result) -> Unit,
    fail: (Kind, String) -> Unit,
    translate: () -> Unit,
  ) {
    val needed = listOf(from, to, ENGLISH).distinct()
    if (needed.any { models.isDownloading(it) }) return fail(Kind.DOWNLOADING, "")
    models.downloaded { downloaded ->
      val missing = needed.filter { it !in downloaded }
      if (missing.isEmpty()) return@downloaded translate()
      if (downloads == Downloads.NONE) return@downloaded fail(Kind.DOWNLOADING, "")
      report(Result.Downloading(missing))
      val wifiOnly = TranslationSettings.wifiOnly(prefs)
      var left = missing.size
      var failed = false
      for (language in missing) {
        models.download(language, wifiOnly) { ok ->
          failed = failed || !ok
          if (--left > 0) return@download
          when {
            downloads == Downloads.START -> if (!failed) onDownloaded()
            failed -> fail(Kind.CONNECTION, "")
            else -> translate()
          }
        }
      }
      if (downloads == Downloads.START) fail(Kind.DOWNLOADING, "")
    }
  }

  private class FailureException(val failure: TranslationFailure) : Exception(failure.detail)

  private companion object {
    const val TAG = "Translator"
    const val ENGLISH = "en"
    const val IDLE_SECONDS = 30L

    private val onDeviceUsers = AtomicInteger(0)

    private var ai: AiTextRequests? = null

    fun ai(context: Context): AiTextRequests =
      ai ?: AiTextRequests(context) { speak(it) }.also { ai = it }

    /** An AI translation of [owner]'s, waiting for the AI service or being answered. */
    private class Ask(
      val owner: Translator,
      val prompt: String,
      val done: (String?, GeminiFailure?) -> Unit,
    ) {
      var ended = false
    }

    /**
     * AI translations of every translator, asked one at a time: the AI service answers one request
     * at a time, and a new one would cancel another translator's, such as a script's and automatic
     * translation's.
     */
    private val asks = ArrayDeque<Ask>()
    private var asking: Ask? = null

    private fun enqueue(context: Context, ask: Ask) {
      synchronized(asks) { asks.addLast(ask) }
      askNext(context.applicationContext)
    }

    private fun askNext(context: Context) {
      val ask =
        synchronized(asks) {
          if (asking != null) return
          asks.removeFirstOrNull()?.also { asking = it } ?: return
        }
      ai(context).ask(ask.prompt, json = true) { answer, failure -> end(context, ask, answer, failure) }
    }

    private fun end(context: Context, ask: Ask, answer: String?, failure: GeminiFailure?) {
      synchronized(asks) {
        if (ask.ended) return
        ask.ended = true
        if (asking === ask) asking = null
      }
      ask.done(answer, failure)
      askNext(context)
    }

    /**
     * Drops [owner]'s AI translations, waiting or being answered. They don't report, as [owner]
     * cancelled them.
     */
    private fun cancelAsk(ask: Ask) {
      val wasRunning: Boolean
      synchronized(asks) {
        if (ask.ended) return
        ask.ended = true
        asks.remove(ask)
        wasRunning = asking === ask
        if (wasRunning) asking = null
      }
      if (!wasRunning) return
      ai?.cancel()
      askNext(ask.owner.appContext)
    }

    private fun cancelAsks(owner: Translator) {
      val running: Ask?
      synchronized(asks) {
        val dropped = asks.filter { it.owner === owner }
        asks.removeAll(dropped)
        dropped.forEach { it.ended = true }
        running = asking?.takeIf { it.owner === owner }
        if (running != null) {
          running.ended = true
          asking = null
        }
      }
      if (running == null) return
      ai?.cancel()
      askNext(owner.appContext)
    }

    private fun speak(message: String) {
      val options =
        SpeechController.SpeakOptions.create()
          .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
          .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL or FeedbackItem.FLAG_NO_HISTORY)
      TalkBackService.getInstance()?.speechController?.speak(message, EVENT_ID_UNTRACKED, options)
    }
  }
}
