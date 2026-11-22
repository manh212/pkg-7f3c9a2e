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
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.OnlineImageQuery
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

internal class LiveLotteryDialog(
  private val activity: ComponentActivity,
  private val speak: (String, Boolean) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val cameraWorker = Executors.newSingleThreadExecutor()
  private val lookupWorker = Executors.newSingleThreadExecutor()
  private val onlineQuery = OnlineImageQuery(activity)

  private var dialog: AlertDialog? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var preview: Preview? = null
  private var imageCapture: ImageCapture? = null
  private var previewView: PreviewView? = null
  private var flashBox: CheckBox? = null
  private var captureButton: Button? = null
  private var checkButton: Button? = null
  private var resultText: TextView? = null

  private var flashOn = false
  private var captureInFlight = false
  private var checkInFlight = false
  private var lastTicket: ParsedLotteryTicket? = null
  private var lookupFuture: Future<*>? = null
  private var dismissed = false
  @Volatile private var sessionToken = 0

  fun show() {
    val density = activity.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val column = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    column.addView(TextView(activity).apply {
      text = activity.getString(R.string.live_recognition_lottery_dialog_hint)
      textSize = 16f
    })

    val pv = PreviewView(activity).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    previewView = pv
    column.addView(pv, LinearLayout.LayoutParams(-1, (150 * density).toInt()))

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

    val capBtn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_lottery_capture)
      setOnClickListener { captureTicket() }
    }
    captureButton = capBtn
    column.addView(capBtn)

    val chkBtn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_lottery_check_result)
      visibility = View.GONE
      setOnClickListener { checkLastTicketResult() }
    }
    checkButton = chkBtn
    column.addView(chkBtn)

    val result = TextView(activity).apply {
      textSize = 17f
      setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
    }
    resultText = result
    val resultScroll = ScrollView(activity).apply { addView(result) }
    column.addView(resultScroll, LinearLayout.LayoutParams(-1, (150 * density).toInt()))

    val outerScroll = ScrollView(activity).apply { addView(column) }
    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.live_recognition_lottery_dialog_title)
      .setView(outerScroll)
      .setNegativeButton(R.string.live_recognition_close, null)
      .setOnDismissListener { cleanup() }
      .create()
      .also { it.show() }

    bindCamera()
  }

  fun dismiss() {
    dialog?.dismiss()
  }

  private fun captureTicket() {
    if (captureInFlight) return
    if (!onlineQuery.isReady()) {
      updateResult(activity.getString(R.string.live_recognition_lottery_online_required))
      return
    }
    val capture = imageCapture
    if (capture == null) {
      updateResult(activity.getString(R.string.live_recognition_camera_error))
      return
    }
    val token = ++sessionToken
    cancelLookup()
    lastTicket = null
    captureInFlight = true
    updateButtons()
    updateResult(activity.getString(R.string.live_recognition_please_wait))

    capture.takePicture(
      cameraWorker,
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          val jpeg = try {
            encodeForOnlineQuery(image)
          } catch (e: Throwable) {
            LogUtils.e(TAG, "Lottery capture encode failed: %s", e.javaClass.simpleName)
            null
          } finally {
            image.close()
          }
          handler.post {
            if (dismissed || sessionToken != token) return@post
            if (jpeg == null) {
              captureInFlight = false
              updateButtons()
              updateResult(activity.getString(R.string.live_recognition_camera_error))
              return@post
            }
            onlineQuery.ask(LotteryOnlinePrompt.PROMPT, jpeg, wantsJson = true) { response, error ->
              if (dismissed || sessionToken != token) return@ask
              captureInFlight = false
              if (error != null) {
                updateButtons()
                updateResult(error)
                return@ask
              }
              if (response == null) {
                updateButtons()
                return@ask
              }
              when (val outcome = LotteryOnlineResponseParser.parse(response)) {
                LotteryRecognitionOutcome.NotLottery -> {
                  lastTicket = null
                  updateButtons()
                  updateResult(activity.getString(R.string.live_recognition_lottery_not_ticket))
                }
                is LotteryRecognitionOutcome.Error -> {
                  lastTicket = null
                  updateButtons()
                  updateResult(outcome.message)
                }
                is LotteryRecognitionOutcome.Ticket -> {
                  lastTicket = outcome.ticket
                  updateButtons()
                  updateResult(outcome.ticket.speak())
                }
              }
            }
          }
        }

        override fun onError(exception: ImageCaptureException) {
          LogUtils.e(TAG, "Lottery capture error: %s", exception.message ?: exception.javaClass.simpleName)
          handler.post {
            if (!dismissed && sessionToken == token) {
              captureInFlight = false
              updateButtons()
              updateResult(activity.getString(R.string.live_recognition_camera_error))
            }
          }
        }
      },
    )
  }

  private fun checkLastTicketResult() {
    val ticket = lastTicket ?: return
    if (checkInFlight) return
    val token = sessionToken
    checkInFlight = true
    updateButtons()
    updateResult(activity.getString(R.string.live_recognition_lottery_checking))
    lookupFuture = lookupWorker.submit {
      val checkResult = LotteryResultChecker.checkOnline(ticket)
      handler.post {
        if (dismissed || sessionToken != token) return@post
        checkInFlight = false
        updateButtons()
        updateResult(checkResult.message)
      }
    }
  }

  private fun encodeForOnlineQuery(image: ImageProxy): String {
    val rotated = LiveTextRecognitionDialog.imageToRotatedBitmap(image)
    val maxSide = max(rotated.width, rotated.height)
    val scaled = if (maxSide > MAX_UPLOAD_DIM) {
      val ratio = MAX_UPLOAD_DIM.toFloat() / maxSide
      Bitmap.createScaledBitmap(
        rotated,
        max(1, (rotated.width * ratio).toInt()),
        max(1, (rotated.height * ratio).toInt()),
        true,
      ).also { if (it !== rotated) rotated.recycle() }
    } else {
      rotated
    }
    return try {
      ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
      }
    } finally {
      scaled.recycle()
    }
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
        val newCapture = ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .build()
        preview = newPreview
        imageCapture = newCapture
        boundCamera = provider.bindToLifecycle(
          activity,
          CameraSelector.DEFAULT_BACK_CAMERA,
          newPreview,
          newCapture,
        )
        applyTorch()
      } catch (e: Exception) {
        LogUtils.e(TAG, "Lottery camera failed: %s", e.javaClass.simpleName)
        updateResult(activity.getString(R.string.live_recognition_camera_error))
      }
    }, ContextCompat.getMainExecutor(activity))
  }

  private fun applyTorch() {
    val camera = boundCamera ?: return
    if (flashOn && !camera.cameraInfo.hasFlashUnit()) {
      flashOn = false
      flashBox?.isChecked = false
      updateResult(activity.getString(R.string.live_recognition_flash_unavailable))
      return
    }
    camera.cameraControl.enableTorch(flashOn)
  }

  private fun updateButtons() {
    captureButton?.isEnabled = !captureInFlight
    val hasTicket = lastTicket != null
    checkButton?.visibility = if (hasTicket) View.VISIBLE else View.GONE
    checkButton?.isEnabled = hasTicket && !captureInFlight && !checkInFlight
  }

  private fun updateResult(message: String) {
    resultText?.text = message
    speak(message, true)
  }

  private fun cancelLookup() {
    lookupFuture?.cancel(true)
    lookupFuture = null
    checkInFlight = false
  }

  private fun unbindCamera() {
    boundCamera?.cameraControl?.enableTorch(false)
    boundCamera = null
    val bound = listOfNotNull(preview, imageCapture).toTypedArray()
    if (bound.isNotEmpty()) cameraProvider?.unbind(*bound)
    preview = null
    imageCapture = null
  }

  private fun cleanup() {
    if (dismissed) return
    dismissed = true
    sessionToken++
    captureInFlight = false
    onlineQuery.cancel()
    cancelLookup()
    unbindCamera()
    cameraWorker.shutdownNow()
    lookupWorker.shutdownNow()
    onClosed()
  }

  companion object {
    private const val TAG = "LiveLotteryDialog"
    private const val MAX_UPLOAD_DIM = 1280
    private const val JPEG_QUALITY = 80
  }
}
