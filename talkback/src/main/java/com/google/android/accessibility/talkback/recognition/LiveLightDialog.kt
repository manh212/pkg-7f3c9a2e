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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.monitor.RecentUserInput
import kotlin.math.ln
import kotlin.math.roundToInt

internal class LiveLightDialog(
  private val activity: ComponentActivity,
  private val speak: (String, Boolean) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val lightTone = LightToneGenerator()
  private val announcements = RecognitionAnnouncements()

  private var dialog: AlertDialog? = null
  private var toggleButton: Button? = null
  private var resultText: TextView? = null
  private var toneEnabled = false
  private var running = false
  private var dismissed = false

  private val sensorListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (dismissed || !running) return
      val lux = event.values.firstOrNull() ?: return
      if (!lux.isFinite() || lux < 0) return
      val now = SystemClock.elapsedRealtime()
      if (toneEnabled && TalkBackService.getInstance()?.speechController?.isSpeaking == false &&
          !RecentUserInput.isRecent()) {
        lightTone.playForLux(lux, now)
      }
      val percent = lightPercentForLux(lux)
      val text = activity.getString(R.string.live_recognition_light_percent, percent)
      resultText?.text = text
      if (announcements.shouldSpeak("light", text, now)) {
        speak(text, false)
      }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
  }

  fun show() {
    val density = activity.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val column = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    column.addView(CheckBox(activity).apply {
      text = activity.getString(R.string.live_recognition_light_tone)
      minHeight = (48 * density).toInt()
      setOnCheckedChangeListener { _, checked ->
        toneEnabled = checked
        if (!checked) lightTone.reset()
      }
    })

    val btn = Button(activity).apply {
      text = activity.getString(R.string.live_recognition_light_stop)
      setOnClickListener { toggleRunning() }
    }
    toggleButton = btn
    column.addView(btn)

    val result = TextView(activity).apply {
      textSize = 18f
      setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
    }
    resultText = result
    column.addView(result)

    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.live_recognition_light)
      .setView(ScrollView(activity).apply { addView(column) })
      .setNegativeButton(R.string.live_recognition_close, null)
      .setOnDismissListener { cleanup() }
      .create()
      .also { it.show() }

    startSensor()
  }

  fun dismiss() {
    dialog?.dismiss()
  }

  private fun toggleRunning() {
    if (running) {
      stopSensor()
      updateButtons()
      val msg = activity.getString(R.string.live_recognition_light_stop)
      resultText?.text = msg
      speak(msg, true)
    } else {
      startSensor()
    }
  }

  private fun startSensor() {
    val sensors = activity.getSystemService(SensorManager::class.java)
    val sensor = LiveRecognition.lightSensor(activity)
    if (sensors == null || sensor == null ||
        !sensors.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
      running = false
      updateButtons()
      val msg = activity.getString(R.string.live_recognition_light_unavailable)
      resultText?.text = msg
      speak(msg, true)
      return
    }
    running = true
    announcements.clear()
    updateButtons()
  }

  private fun stopSensor() {
    running = false
    activity.getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
    lightTone.reset()
  }

  private fun updateButtons() {
    toggleButton?.text = activity.getString(
      if (running) R.string.live_recognition_light_stop else R.string.live_recognition_light_start
    )
  }

  private fun cleanup() {
    if (dismissed) return
    dismissed = true
    stopSensor()
    onClosed()
  }

  companion object {
    private const val MAX_LUX = 1000f

    /**
     * Maps ambient illuminance (lux) onto a perceptual 0..100% scale rounded to 5% steps
     * so small sensor jitter does not spam announcements.
     */
    internal fun lightPercentForLux(lux: Float): Int {
      if (!lux.isFinite() || lux <= 0f) return 0
      if (lux >= MAX_LUX) return 100
      val ratio = ln(1.0 + lux.toDouble()) / ln(1.0 + MAX_LUX.toDouble())
      val rawPercent = (ratio * 100.0).roundToInt().coerceIn(0, 100)
      return (((rawPercent + 2) / 5) * 5).coerceIn(0, 100)
    }
  }
}
