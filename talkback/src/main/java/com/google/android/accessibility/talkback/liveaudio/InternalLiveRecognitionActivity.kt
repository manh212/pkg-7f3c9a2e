package com.google.android.accessibility.talkback.liveaudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Source MainActivity's INTERNAL permission flow, with only two modes and two voice choices. */
class InternalLiveRecognitionActivity : ComponentActivity() {
    private lateinit var modeSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var startButton: Button

    private val projectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val projectionData = result.data
            if (result.resultCode != Activity.RESULT_OK || projectionData == null) {
                Toast.makeText(this, "Bạn chưa cấp quyền thu âm thanh nội bộ", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val selectedMode = if (modeSpinner.selectedItemPosition == 1)
                InternalLiveRecognitionService.MODE_TRANSCRIBE else InternalLiveRecognitionService.MODE_TRANSLATE
            val selectedVoice = if (selectedMode == InternalLiveRecognitionService.MODE_TRANSCRIBE ||
                voiceSpinner.selectedItemPosition == 1)
                InternalLiveRecognitionService.VOICE_READER else InternalLiveRecognitionService.VOICE_GEMINI
            val action = Intent(this, InternalLiveRecognitionService::class.java).apply {
                this.action = InternalLiveRecognitionService.ACTION_START
                putExtra(InternalLiveRecognitionService.EXTRA_MODE, selectedMode)
                putExtra(InternalLiveRecognitionService.EXTRA_VOICE, selectedVoice)
                putExtra(InternalLiveRecognitionService.EXTRA_PROJECTION_CODE, result.resultCode)
                putExtra(InternalLiveRecognitionService.EXTRA_PROJECTION_DATA, projectionData)
            }
            ContextCompat.startForegroundService(this, action)
            Toast.makeText(this, "Đã khởi động nhận dạng âm thanh nội bộ", Toast.LENGTH_SHORT).show()
            finish()
        }

    private val recordPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection()
            else Toast.makeText(this, "Cần quyền ghi âm để thu âm thanh nội bộ", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Nhận dạng và dịch âm thanh nội bộ"
        val prefs = SharedPreferencesUtils.getSharedPreferences(this)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(text: String) = TextView(this).also {
            it.text = text
            it.textSize = 17f
            it.setPadding(0, padding / 2, 0, padding / 3)
            stack.addView(it)
        }
        label("Chọn chế độ nhận dạng")
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@InternalLiveRecognitionActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Dịch trực tiếp, Gemini 3.5 Live Translate",
                    "Chuyển giọng nói thành văn bản, Gemini 3.5 Transcribe Live"))
            setSelection(if (prefs.getString(PREF_MODE, "") ==
                InternalLiveRecognitionService.MODE_TRANSCRIBE) 1 else 0)
            stack.addView(this)
        }
        label("Chọn giọng phát (chỉ áp dụng cho chế độ dịch)")
        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@InternalLiveRecognitionActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Giọng nói Gemini", "Giọng nói trình đọc màn hình"))
            setSelection(if (prefs.getString(PREF_VOICE, "") ==
                InternalLiveRecognitionService.VOICE_READER) 1 else 0)
            stack.addView(this)
        }
        label("Chế độ chép lời sử dụng giọng trình đọc màn hình. " +
            "Chế độ dịch có thể phát trực tiếp giọng Gemini hoặc đọc bản dịch bằng giọng Meridian.")
        label("Chức năng lấy âm thanh mà ứng dụng khác đang phát, không dùng micro. " +
            "Dữ liệu âm thanh sẽ được gửi tới dịch vụ Gemini trực tuyến. " +
            "Android có thể chặn thu âm thanh của một số ứng dụng.")
        label(if (GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY).isNotBlank())
            "Đã có Gemini API Key. Có thể bắt đầu."
        else "Chưa có Gemini API Key. Hãy cài đặt khóa API trong Nhận dạng/OCR.")
        startButton = Button(this).apply {
            text = if (InternalLiveRecognitionService.running) "Dừng nhận dạng âm thanh nội bộ"
                else "Bắt đầu nhận dạng âm thanh nội bộ"
            setOnClickListener {
                if (InternalLiveRecognitionService.running) {
                    startService(Intent(this@InternalLiveRecognitionActivity,
                        InternalLiveRecognitionService::class.java)
                        .setAction(InternalLiveRecognitionService.ACTION_STOP))
                    finish()
                } else {
                    val selectedMode = if (modeSpinner.selectedItemPosition == 1)
                        InternalLiveRecognitionService.MODE_TRANSCRIBE
                    else InternalLiveRecognitionService.MODE_TRANSLATE
                    prefs.edit()
                        .putString(PREF_MODE, selectedMode)
                        .putString(PREF_VOICE,
                            if (voiceSpinner.selectedItemPosition == 1)
                                InternalLiveRecognitionService.VOICE_READER
                            else InternalLiveRecognitionService.VOICE_GEMINI)
                        .apply()
                    if (GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY).isBlank()) {
                        Toast.makeText(this@InternalLiveRecognitionActivity,
                            "Hãy nhập Gemini API Key trong cài đặt Nhận dạng/OCR",
                            Toast.LENGTH_LONG).show()
                    } else if (ContextCompat.checkSelfPermission(this@InternalLiveRecognitionActivity,
                            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else requestProjection()
                }
            }
            stack.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Android's Spinner is directly accessible to touch exploration and screen readers.
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long) {
                voiceSpinner.isEnabled = position == 0
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        val scroll = ScrollView(this).apply { addView(stack) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::startButton.isInitialized) {
            startButton.text = if (InternalLiveRecognitionService.running)
                "Dừng nhận dạng âm thanh nội bộ" else "Bắt đầu nhận dạng âm thanh nội bộ"
        }
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Thu âm thanh nội bộ yêu cầu Android 10 trở lên", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private companion object {
        const val PREF_MODE = "pref_internal_live_audio_mode"
        const val PREF_VOICE = "pref_internal_live_audio_voice"
    }
}
