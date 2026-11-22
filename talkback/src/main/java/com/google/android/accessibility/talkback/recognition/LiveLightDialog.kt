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
      val label = when {
        lux < 10f -> R.string.live_recognition_light_dark
        lux < 100f -> R.string.live_recognition_light_dim
        else -> R.string.live_recognition_light_bright
      }
      val text = activity.getString(label)
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
}
