package com.google.android.accessibility.talkback.liveaudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Permission-only bridge from the existing Meridian subtitle gesture/menu.
 * No separate mode selector, start/stop button, playback UI or transcript display.
 */
class InternalLiveRecognitionActivity : ComponentActivity() {
    private val projectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val permission = result.data
            if (result.resultCode == Activity.RESULT_OK && permission != null) {
                ContextCompat.startForegroundService(this,
                    Intent(this, InternalLiveRecognitionService::class.java).apply {
                        action = InternalLiveRecognitionService.ACTION_START
                        putExtra(InternalLiveRecognitionService.EXTRA_PROJECTION_CODE, result.resultCode)
                        putExtra(InternalLiveRecognitionService.EXTRA_PROJECTION_DATA, permission)
                    })
            } else {
                Toast.makeText(this, "Chưa cấp quyền thu âm thanh nội bộ", Toast.LENGTH_LONG).show()
            }
            finish()
        }

    private val recordPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection()
            else {
                Toast.makeText(this, "Cần quyền ghi âm để thu âm thanh nội bộ", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore ActivityResult callbacks after Android recreates the consent Activity.
        if (savedInstanceState != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Thu âm thanh nội bộ cần Android 10", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val prefs = SharedPreferencesUtils.getSharedPreferences(this)
        if (GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY).isBlank()) {
            Toast.makeText(this, "Hãy nhập Gemini API Key trong cài đặt phụ đề",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else requestProjection()
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { finish(); return }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }
}
