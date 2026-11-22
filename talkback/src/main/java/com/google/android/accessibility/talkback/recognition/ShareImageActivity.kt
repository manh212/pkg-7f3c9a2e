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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Observer
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.OnlineImageQuery
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Receives an image shared from another app (ACTION_SEND / ACTION_VIEW) and lets the user choose
 * between extracting text (offline OCR or online AI) and getting a detailed visual description.
 */
class ShareImageActivity : ComponentActivity() {
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()

  private lateinit var prefs: SharedPreferences
  private lateinit var onlineQuery: OnlineImageQuery

  private var dialog: AlertDialog? = null
  private var resultText: TextView? = null
  private var offlineOcrButton: Button? = null
  private var onlineTextButton: Button? = null
  private var detailedDescButton: Button? = null
  private var copyButton: Button? = null

  private var sharedBitmap: Bitmap? = null
  private var cachedJpegBase64: String? = null
  private var latestResult: String = ""
  private var busy = false
  private var waitingOfflineGroup: OfflineModels.Group? = null
  private var downloadBucket = 0
  private var destroyed = false
  @Volatile private var requestToken = 0

  private val modelObserver = Observer<Map<OfflineModels.Group, OfflineModels.Status>> { states ->
    val group = waitingOfflineGroup ?: return@Observer
    if (destroyed) return@Observer
    val state = states[group]
    if (OfflineModels.isReady(this, group)) {
      waitingOfflineGroup = null
      downloadBucket = 0
      runOfflineTextOcr()
    } else if (state?.failed == true) {
      waitingOfflineGroup = null
      busy = false
      updateButtons()
      showMessage(getString(R.string.live_recognition_model_download_failed), speakInterrupt = true)
    } else if (state?.progress != null) {
      val bucket = state.progress / 25
      if (bucket > downloadBucket) {
        downloadBucket = bucket
        showMessage(
          getString(R.string.live_recognition_model_download_progress, bucket * 25),
          speakInterrupt = false,
        )
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = SharedPreferencesUtils.getSharedPreferences(this)
    onlineQuery = OnlineImageQuery(this)
    OfflineModels.status.observe(this, modelObserver)

    val uri = extractImageUri(intent)
    if (uri == null) {
      speakText(getString(R.string.share_image_load_error), interrupt = true)
      finish()
      return
    }

    showDialog()
    loadSharedBitmap(uri)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val uri = extractImageUri(intent) ?: return
    cancelPending()
    recycleBitmap()
    latestResult = ""
    updateButtons()
    loadSharedBitmap(uri)
  }

  private fun showDialog() {
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    column.addView(TextView(this).apply {
      text = getString(R.string.share_image_hint)
      textSize = 16f
    })

    val offlineBtn = Button(this).apply {
      text = getString(R.string.share_image_read_text_offline)
      isEnabled = false
      setOnClickListener { runOfflineTextOcr() }
    }
    offlineOcrButton = offlineBtn
    column.addView(offlineBtn)

    val onlineTxtBtn = Button(this).apply {
      text = getString(R.string.share_image_read_text_online)
      isEnabled = false
      setOnClickListener { runOnlineAnalysis(ONLINE_TEXT_PROMPT) }
    }
    onlineTextButton = onlineTxtBtn
    column.addView(onlineTxtBtn)

    val detailBtn = Button(this).apply {
      text = getString(R.string.share_image_describe_detailed)
      isEnabled = false
      setOnClickListener { runOnlineAnalysis(ONLINE_DETAILED_PROMPT) }
    }
    detailedDescButton = detailBtn
    column.addView(detailBtn)

    val cpBtn = Button(this).apply {
      text = getString(R.string.share_image_copy_result)
      isEnabled = false
      setOnClickListener { copyLatestResult() }
    }
    copyButton = cpBtn
    column.addView(cpBtn)

    val result = TextView(this).apply {
      textSize = 17f
      setTextIsSelectable(true)
      setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
      text = getString(R.string.share_image_loading)
    }
    resultText = result
    val resultScroll = ScrollView(this).apply { addView(result) }
    column.addView(resultScroll, LinearLayout.LayoutParams(-1, (220 * density).toInt()))

    val outerScroll = ScrollView(this).apply { addView(column) }
    dialog = AlertDialog.Builder(this)
      .setTitle(R.string.share_image_title)
      .setView(outerScroll)
      .setNegativeButton(R.string.live_recognition_close) { _, _ -> finish() }
      .setOnCancelListener { finish() }
      .setOnDismissListener {
        if (!isFinishing) finish()
      }
      .create()
      .also { it.show() }
  }

  private fun loadSharedBitmap(uri: Uri) {
    val token = ++requestToken
    busy = true
    updateButtons()
    showMessage(getString(R.string.share_image_loading), speakInterrupt = false)
    worker.execute {
      val decoded = decodeScaledBitmap(uri)
      handler.post {
        if (destroyed || requestToken != token) {
          decoded?.recycle()
          return@post
        }
        busy = false
        if (decoded == null) {
          showMessage(getString(R.string.share_image_load_error), speakInterrupt = true)
          updateButtons()
          return@post
        }
        sharedBitmap = decoded
        cachedJpegBase64 = null
        updateButtons()
        showMessage(getString(R.string.share_image_ready), speakInterrupt = true)
      }
    }
  }

  private fun decodeScaledBitmap(uri: Uri): Bitmap? {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          val w = info.size.width
          val h = info.size.height
          val longest = max(w, h)
          if (longest > MAX_DECODE_DIM) {
            val scale = MAX_DECODE_DIM.toFloat() / longest.toFloat()
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
          }
        }
      } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIM)
        val opts = BitmapFactory.Options().apply {
          inSampleSize = sample
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
      }
    } catch (e: Throwable) {
      LogUtils.e(TAG, "Failed to decode shared image: %s", e.javaClass.simpleName)
      null
    }
  }

  private fun useVietOcr(): Boolean =
    prefs.getString(PREF_TEXT_ENGINE, ENGINE_TESSERACT) == ENGINE_VIETOCR

  private fun runOfflineTextOcr() {
    val bmp = sharedBitmap ?: return
    val useViet = useVietOcr()
    val group = if (useViet) OfflineModels.Group.CAMERA_VIETOCR else OfflineModels.Group.CAMERA_OCR
    if (!OfflineModels.isReady(this, group)) {
      waitingOfflineGroup = group
      downloadBucket = 0
      busy = true
      updateButtons()
      OfflineModels.request(this, group)
      showMessage(getString(R.string.live_recognition_model_download_started), speakInterrupt = true)
      return
    }
    cancelPending()
    val token = ++requestToken
    busy = true
    updateButtons()
    showMessage(getString(R.string.live_recognition_please_wait), speakInterrupt = true)
    worker.execute {
      val prepared = try {
        DocumentAlignmentScanner.prepareForCameraOcr(bmp, upscaleSmallText = true)
      } catch (_: Throwable) {
        bmp
      }
      val text = try {
        TesseractOcr.recognizeCameraText(this, prepared, useViet)
      } catch (e: Throwable) {
        LogUtils.e(TAG, "Shared image OCR failed: %s", e.javaClass.simpleName)
        null
      } finally {
        if (prepared !== bmp) prepared.recycle()
      }
      handler.post {
        if (destroyed || requestToken != token) return@post
        busy = false
        if (text == null) {
          latestResult = ""
          showMessage(getString(R.string.live_recognition_ocr_error), speakInterrupt = true)
        } else if (text.isBlank()) {
          latestResult = ""
          showMessage(getString(R.string.share_image_no_text), speakInterrupt = true)
        } else {
          latestResult = text.trim()
          showMessage(latestResult, speakInterrupt = true)
        }
        updateButtons()
      }
    }
  }

  private fun runOnlineAnalysis(prompt: String) {
    val bmp = sharedBitmap ?: return
    if (!onlineQuery.isReady()) {
      showMessage(getString(R.string.live_recognition_lottery_online_required), speakInterrupt = true)
      return
    }
    cancelPending()
    val token = ++requestToken
    busy = true
    updateButtons()
    showMessage(getString(R.string.live_recognition_please_wait), speakInterrupt = true)
    worker.execute {
      val jpeg = cachedJpegBase64 ?: encodeBitmapToJpegBase64(bmp)?.also { cachedJpegBase64 = it }
      if (jpeg == null) {
        handler.post {
          if (destroyed || requestToken != token) return@post
          busy = false
          updateButtons()
          showMessage(getString(R.string.share_image_load_error), speakInterrupt = true)
        }
        return@execute
      }
      handler.post {
        if (destroyed || requestToken != token) return@post
        onlineQuery.ask(prompt, jpeg, wantsJson = false) { responseText, errorMessage ->
          if (destroyed || requestToken != token) return@ask
          busy = false
          if (!responseText.isNullOrBlank()) {
            latestResult = responseText.trim()
            showMessage(latestResult, speakInterrupt = true)
          } else if (!errorMessage.isNullOrBlank()) {
            latestResult = ""
            showMessage(errorMessage, speakInterrupt = true)
          }
          updateButtons()
        }
      }
    }
  }

  private fun copyLatestResult() {
    if (latestResult.isBlank()) return
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_image_title), latestResult))
    speakText(getString(R.string.share_image_copied), interrupt = true)
  }

  private fun updateButtons() {
    val hasImage = sharedBitmap != null && !busy
    offlineOcrButton?.isEnabled = hasImage
    onlineTextButton?.isEnabled = hasImage
    detailedDescButton?.isEnabled = hasImage
    copyButton?.isEnabled = latestResult.isNotBlank() && !busy
  }

  private fun showMessage(message: String, speakInterrupt: Boolean) {
    resultText?.text = message
    speakText(message, interrupt = speakInterrupt)
  }

  private fun speakText(text: String, interrupt: Boolean) {
    if (text.isBlank()) return
    if (TalkBackService.getInstance() != null) {
      LiveRecognition.speak(text, interrupt)
    } else {
      @Suppress("DEPRECATION")
      resultText?.announceForAccessibility(text)
    }
  }

  private fun cancelPending() {
    waitingOfflineGroup = null
    requestToken++
    onlineQuery.cancel()
  }

  private fun recycleBitmap() {
    cachedJpegBase64 = null
    sharedBitmap?.recycle()
    sharedBitmap = null
  }

  override fun onDestroy() {
    destroyed = true
    cancelPending()
    OfflineModels.status.removeObserver(modelObserver)
    dialog?.setOnDismissListener(null)
    dialog?.dismiss()
    dialog = null
    worker.shutdownNow()
    recycleBitmap()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "ShareImageActivity"
    private const val MAX_DECODE_DIM = 2048
    private const val MAX_UPLOAD_DIM = 1600
    private const val JPEG_QUALITY = 85
    private const val PREF_TEXT_ENGINE = "pref_live_recognition_text_engine"
    private const val ENGINE_TESSERACT = "tesseract"
    private const val ENGINE_VIETOCR = "vietocr"

    internal const val ONLINE_TEXT_PROMPT =
      "Hãy đọc và trích xuất toàn bộ văn bản xuất hiện trong hình ảnh này (tài liệu, tin nhắn, " +
        "biển báo, hóa đơn hoặc chữ viết khác). Giữ nguyên nội dung và dấu tiếng Việt chính xác, " +
        "trình bày gọn gàng dễ nghe. Chỉ trả về phần văn bản đọc được, không bình luận hay thêm " +
        "ký hiệu định dạng. Nếu không có văn bản nào, chỉ trả lời: Không có văn bản trong ảnh."

    internal const val ONLINE_DETAILED_PROMPT =
      "Hãy mô tả chi tiết hình ảnh này bằng tiếng Việt cho người khiếm thị: nêu rõ khung cảnh, " +
        "con người, đồ vật, màu sắc, bố cục và các chi tiết đáng chú ý. Nếu có văn bản quan trọng " +
        "trong ảnh thì nêu ngắn gọn. Trình bày tự nhiên, mạch lạc, không dùng ký hiệu định dạng thừa."

    internal fun extractImageUri(intent: Intent?): Uri? {
      if (intent == null) return null
      return when (intent.action) {
        Intent.ACTION_SEND -> {
          val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
          }
          stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
        Intent.ACTION_VIEW -> intent.data
        else -> null
      }
    }

    internal fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
      if (width <= 0 || height <= 0 || maxDim <= 0) return 1
      var sample = 1
      var longest = max(width, height)
      while (longest / 2 >= maxDim) {
        sample *= 2
        longest /= 2
      }
      return sample
    }

    private fun encodeBitmapToJpegBase64(bitmap: Bitmap): String? {
      val longest = max(bitmap.width, bitmap.height)
      val scaled = if (longest > MAX_UPLOAD_DIM) {
        val ratio = min(1f, MAX_UPLOAD_DIM.toFloat() / longest.toFloat())
        Bitmap.createScaledBitmap(
          bitmap,
          max(1, (bitmap.width * ratio).toInt()),
          max(1, (bitmap.height * ratio).toInt()),
          true,
        )
      } else {
        bitmap
      }
      return try {
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
      } catch (_: Throwable) {
        null
      } finally {
        if (scaled !== bitmap) scaled.recycle()
      }
    }
  }
}
