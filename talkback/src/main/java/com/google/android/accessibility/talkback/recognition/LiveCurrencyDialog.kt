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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
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
import androidx.lifecycle.Observer
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LiveCurrencyDialog(
  private val activity: ComponentActivity,
  private val speak: (String, Boolean) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val tracker = CurrencyTracker()
  private val announcements = RecognitionAnnouncements()
  private var recognizer: CurrencyRecognizer? = null

  private var dialog: AlertDialog? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  private var previewView: PreviewView? = null
  private var flashBox: CheckBox? = null
  private var toggleButton: Button? = null
  private var resultText: TextView? = null

  private var flashOn = false
  private var sumEnabled = false
  private var running = false
  private var waitingModel = false
  private var downloadBucket = 0
  private var dismissed = false
  @Volatile private var sessionToken = 0

  private val modelObserver = Observer<Map<OfflineModels.Group, OfflineModels.Status>> { states ->
    if (!waitingModel || dismissed) return@Observer
    val state = states[OfflineModels.Group.CURRENCY]
    if (OfflineModels.isReady(activity, OfflineModels.Group.CURRENCY)) {
      waitingModel = false
      downloadBucket = 0
      updateResult(activity.getString(R.string.live_recognition_model_download_ready), interrupt = false)
      if (running) bindCamera()
    } else if (state?.failed == true) {
      waitingModel = false
      running = false
      updateButtons()
      updateResult(activity.getString(R.string.live_recognition_model_download_failed), interrupt = true)
    } else if (state?.progress != null) {
      val bucket = state.progress / 25
      if (bucket > downloadBucket) {
        downloadBucket = bucket
        updateResult(
          activity.getString(R.string.live_recognition_model_download_progress, bucket * 25),
          interrupt = false,
        )
      }
    }
  }

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

    column.addView(CheckBox(activity).apply {
      text = activity.getString(R.string.live_recognition_currency_sum)
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        sumEnabled = checked
        if (!checked) tracker.resetTotal()
      }
    })

    column.addView(Button(activity).apply {
      text = activity.getString(R.string.live_recognition_currency_reset)
      setOnClickListener {
        tracker.resetTotal()
        updateResult(activity.getString(R.string.live_recognition_currency_total_reset), interrupt = true)
      }
    })

    val btn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_currency_stop)
      setOnClickListener { toggleRunning() }
    }
    toggleButton = btn
    column.addView(btn)

    val result = TextView(activity).apply {
      textSize = 17f
      setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
    }
    resultText = result
    column.addView(ScrollView(activity).apply { addView(result) }, LinearLayout.LayoutParams(-1, (120 * density).toInt()))

    OfflineModels.status.observe(activity, modelObserver)
    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.live_recognition_currency)
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
      tracker.reset()
      announcements.clear()
      updateButtons()
      bindCamera()
      updateResult(activity.getString(R.string.live_recognition_currency_stop), interrupt = true)
    } else {
      startRecognition()
    }
  }

  private fun startRecognition() {
    running = true
    tracker.reset()
    announcements.clear()
    updateButtons()
    if (!OfflineModels.isReady(activity, OfflineModels.Group.CURRENCY)) {
      waitingModel = true
      downloadBucket = 0
      OfflineModels.request(activity, OfflineModels.Group.CURRENCY)
      updateResult(activity.getString(R.string.live_recognition_model_download_started), interrupt = true)
      bindCamera()
      return
    }
    bindCamera()
  }

  private fun bindCamera() {
    if (dismissed) return
    val pv = previewView ?: return
    val token = ++sessionToken
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
      if (dismissed || sessionToken != token) return@addListener
      try {
        val provider = future.get()
        cameraProvider = provider
        unbindCamera()
        val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        preview = newPreview
        if (running && OfflineModels.isReady(activity, OfflineModels.Group.CURRENCY)) {
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
            if (dismissed || sessionToken != token || !running ||
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
              val model = recognizer ?: CurrencyRecognizer(activity).also { recognizer = it }
              val prediction = model.detect(bitmap)
              handler.post {
                if (dismissed || sessionToken != token || !running) return@post
                tracker.observe(prediction, now, sumEnabled)?.let { spoken ->
                  if (announcements.shouldSpeak("currency", spoken, now, CurrencyTracker.REPEAT_INTERVAL_MS)) {
                    updateResult(spoken, interrupt = false)
                  }
                }
              }
            } catch (e: Exception) {
              LogUtils.e(TAG, "Currency dialog failed: %s", e.javaClass.simpleName)
              handler.post {
                if (!dismissed && sessionToken == token) {
                  running = false
                  updateButtons()
                  updateResult(activity.getString(R.string.live_recognition_currency_error), interrupt = true)
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
        LogUtils.e(TAG, "Currency camera failed: %s", e.javaClass.simpleName)
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
      if (running) R.string.live_recognition_currency_stop else R.string.live_recognition_currency_start
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
    waitingModel = false
    OfflineModels.status.removeObserver(modelObserver)
    unbindCamera()
    worker.execute {
      recognizer?.close()
      recognizer = null
    }
    worker.shutdown()
    onClosed()
  }

  companion object {
    private const val TAG = "LiveCurrencyDialog"
    private const val FRAME_INTERVAL_MS = 450L
  }
}
