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

package com.google.android.accessibility.talkback.recognition

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LivePointAndSpeakDialog(
  private val activity: ComponentActivity,
  private val prefs: SharedPreferences,
  private val speak: (String, Boolean) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val announcements = RecognitionAnnouncements()

  private var dialog: AlertDialog? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  private var previewView: PreviewView? = null
  private var flashBox: CheckBox? = null
  private var toggleButton: Button? = null
  private var resultText: TextView? = null
  @Volatile private var describer: FrameDescriber? = null

  private var pointAbove = true
  private var flashOn = false
  private var running = false
  private var dismissed = false
  @Volatile private var sessionToken = 0

  fun show() {
    val density = activity.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val column = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    val pv = PreviewView(activity).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    previewView = pv
    column.addView(pv, LinearLayout.LayoutParams(-1, (140 * density).toInt()))

    val above = RadioButton(activity).apply {
      id = View.generateViewId()
      setText(R.string.live_recognition_point_above)
      isChecked = true
    }
    column.addView(RadioGroup(activity).apply {
      addView(above)
      addView(RadioButton(activity).apply {
        id = View.generateViewId()
        setText(R.string.live_recognition_point_below)
      })
      setOnCheckedChangeListener { _, checked -> pointAbove = checked == above.id }
    })

    val flash = CheckBox(activity).apply {
      text = activity.getString(R.string.live_recognition_flash)
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        flashOn = checked
        applyTorch()
      }
    }
    flashBox = flash
    column.addView(flash)

    val btn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_point_stop)
      setOnClickListener { toggleRunning() }
    }
    toggleButton = btn
    column.addView(btn)

    val result = TextView(activity).apply {
      textSize = 17f
      setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
    }
    resultText = result
    column.addView(ScrollView(activity).apply { addView(result) }, LinearLayout.LayoutParams(-1, (130 * density).toInt()))

    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.live_recognition_point)
      .setView(ScrollView(activity).apply { addView(column) })
      .setNegativeButton(R.string.live_recognition_close, null)
      .setOnDismissListener { cleanup() }
      .create()
      .also { it.show() }

    startRecognition()
  }

  fun dismiss() {
    dialog?.dismiss()
  }

  private fun toggleRunning() {
    if (running) {
      running = false
      sessionToken++
      describer?.close()
      describer = null
      announcements.clear()
      updateButtons()
      bindCamera()
      updateResult(activity.getString(R.string.live_recognition_point_stop), interrupt = true)
    } else {
      startRecognition()
    }
  }

  private fun startRecognition() {
    running = true
    announcements.clear()
    updateButtons()
    val token = ++sessionToken
    describer?.close()
    describer = createDescriber(token)
    if (describer == null) {
      running = false
      updateButtons()
      return
    }
    bindCamera(token)
  }

  private fun createDescriber(token: Int): FrameDescriber? {
    val language = Locale.getDefault().toLanguageTag()
    val onResult = { text: String ->
      if (!dismissed && sessionToken == token && running) {
        val now = SystemClock.elapsedRealtime()
        if (announcements.shouldSpeak("point", text, now)) {
          updateResult(text, interrupt = false)
        }
      }
    }
    fun stopAi(message: String?) {
      if (dismissed || sessionToken != token) return
      running = false
      describer?.close()
      describer = null
      updateButtons()
      if (message != null) updateResult(message, interrupt = true)
    }
    return when (val backend = AiBackend.of(activity, prefs)) {
      AiBackend.GEMINI -> {
        resultText?.setText(R.string.live_recognition_connecting)
        GeminiLiveSession(
          GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY),
          language,
          onReady = { if (!dismissed && sessionToken == token) resultText?.setText(R.string.live_recognition_camera_on) },
          onResult = onResult,
          onFailure = { stopAi(activity.getString(it)) },
        )
      }
      AiBackend.ON_DEVICE, AiBackend.SERVICE -> {
        val questions = LiveRecognition.imageQuestions(activity)
        val plain = backend == AiBackend.ON_DEVICE && AiBackend.usesPlainPrompts(activity)
        RequestDescriber(
          prompt = { modes, above ->
            if (plain) GeminiLiveProtocol.plainPrompt(modes, above, language)
            else GeminiLiveProtocol.imagePrompt(modes, above, language)
          },
          ask = questions::ask,
          cancel = questions::cancel,
          minIntervalMs = if (backend == AiBackend.ON_DEVICE) 0 else GeminiLiveSession.REQUEST_INTERVAL_MS,
          maxResultAgeMs = 30_000L,
          onResult = onResult,
          onFailure = { stopAi(it?.userMessage(activity)) },
        )
      }
      AiBackend.NONE -> {
        updateResult(activity.getString(R.string.ai_service_not_set_up), interrupt = true)
        null
      }
    }
  }

  private fun bindCamera(existingToken: Int? = null) {
    if (dismissed) return
    val pv = previewView ?: return
    val token = existingToken ?: ++sessionToken
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
      if (dismissed || sessionToken != token) return@addListener
      try {
        val provider = future.get()
        cameraProvider = provider
        unbindCamera()
        val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        preview = newPreview
        if (running && describer != null) {
          val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
              ResolutionSelector.Builder()
                .setResolutionStrategy(
                  ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          var lastFrameMs = 0L
          newAnalysis.setAnalyzer(worker) { frame ->
            val now = SystemClock.elapsedRealtime()
            val d = describer
            if (dismissed || sessionToken != token || !running || d == null || !d.canRequest() ||
                now - lastFrameMs < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
              frame.close()
              return@setAnalyzer
            }
            lastFrameMs = now
            val bitmap = try {
              LiveTextRecognitionDialog.imageToRotatedBitmap(frame)
            } catch (_: Throwable) {
              null
            } finally {
              frame.close()
            }
            if (bitmap == null) {
              processing.set(false)
              return@setAnalyzer
            }
            try {
              val jpeg = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
                Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
              }
              handler.post {
                if (!dismissed && sessionToken == token && running) {
                  describer?.describe(jpeg, setOf(RecognitionMode.POINT_AND_SPEAK), pointAbove)
                }
              }
            } finally {
              bitmap.recycle()
              processing.set(false)
            }
          }
          analysis = newAnalysis
          boundCamera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, newPreview, newAnalysis)
        } else {
          analysis = null
          boundCamera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, newPreview)
        }
        applyTorch()
      } catch (e: Exception) {
        LogUtils.e(TAG, "Point and speak camera failed: %s", e.javaClass.simpleName)
        updateResult(activity.getString(R.string.live_recognition_camera_error), interrupt = true)
      }
    }, ContextCompat.getMainExecutor(activity))
  }

  private fun applyTorch() {
    val camera = boundCamera ?: return
    if (flashOn && !camera.cameraInfo.hasFlashUnit()) {
      flashOn = false
      flashBox?.isChecked = false
      updateResult(activity.getString(R.string.live_recognition_flash_unavailable), interrupt = true)
      return
    }
    camera.cameraControl.enableTorch(flashOn)
  }

  private fun updateButtons() {
    toggleButton?.text = activity.getString(
      if (running) R.string.live_recognition_point_stop else R.string.live_recognition_point_start
    )
  }

  private fun updateResult(message: String, interrupt: Boolean) {
    resultText?.text = message
    speak(message, interrupt)
  }

  private fun unbindCamera() {
    boundCamera?.cameraControl?.enableTorch(false)
    boundCamera = null
    analysis?.clearAnalyzer()
    val bound = listOfNotNull(preview, analysis).toTypedArray()
    if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
    preview = null
    analysis = null
  }

  private fun cleanup() {
    if (dismissed) return
    dismissed = true
    sessionToken++
    running = false
    describer?.close()
    describer = null
    unbindCamera()
    worker.shutdownNow()
    onClosed()
  }

  companion object {
    private const val TAG = "LivePointDialog"
    private const val FRAME_INTERVAL_MS = 1_000L
  }
}
