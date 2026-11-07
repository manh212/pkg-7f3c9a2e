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

package com.google.android.accessibility.talkback.gesture

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.AccessibilityEventListener
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils

class BackTapCommands(
  private val context: Context,
  private val perform: (String) -> Unit,
) : AccessibilityEventListener {
  private val sensors = context.getSystemService(SensorManager::class.java)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val doubleKey = context.getString(R.string.pref_shortcut_back_tap_double_key)
  private val tripleKey = context.getString(R.string.pref_shortcut_back_tap_triple_key)

  private var used = false
  private var tripleUsed = false
  private var sensitivity = BackTapDetector.Sensitivity.MEDIUM
  private var screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
  private var allowed = false

  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var detector: BackTapDetector? = null
  private var sensorListener: SensorEventListener? = null

  fun reload(prefs: SharedPreferences) {
    val gestureSet = gestureSet(prefs)
    tripleUsed = isUsed(prefs, tripleKey, gestureSet)
    used = tripleUsed || isUsed(prefs, doubleKey, gestureSet)
    val value =
      prefs.getString(
        context.getString(R.string.pref_back_tap_sensitivity_key),
        context.getString(R.string.pref_back_tap_sensitivity_default),
      )
    sensitivity =
      BackTapDetector.Sensitivity.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: BackTapDetector.Sensitivity.MEDIUM
    update()
  }

  fun setAllowed(allowed: Boolean) {
    this.allowed = allowed
    update()
  }

  fun onScreenChanged(on: Boolean) {
    screenOn = on
    update()
  }

  fun shutdown() {
    allowed = false
    stop()
  }

  override fun getEventTypes(): Int =
    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END

  override fun onAccessibilityEvent(event: AccessibilityEvent, eventId: EventId?) {
    val handler = handler ?: return
    val detector = detector ?: return
    val start = event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
    val ageMs = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
    val timeNs = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
    handler.post { if (start) detector.onTouchStart(timeNs) else detector.onTouchEnd(timeNs) }
  }

  private fun update() {
    if (used && screenOn && allowed) start() else stop()
  }

  private fun start() {
    val running = detector
    if (running != null) {
      configure(running)
      return
    }
    val sensors = sensors ?: return
    val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    val thread = HandlerThread("BackTap").apply { start() }
    val handler = Handler(thread.looper)
    val detector =
      BackTapDetector(
        object : BackTapDetector.Listener {
          override fun onTaps(count: Int) {
            val key = if (count == 3) tripleKey else doubleKey
            mainHandler.post {
              if (sensorListener != null) {
                perform(key)
              }
            }
          }

          override fun onJolt(description: String) {
            LogUtils.d(TAG, "%s", description)
          }
        }
      )
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          val values = event.values
          if (values.size >= 3) {
            detector.onSample(event.timestamp, values[0], values[1], values[2])
          }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
      }
    configure(detector)
    if (
      !sensors.registerListener(listener, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, handler)
    ) {
      LogUtils.w(TAG, "The accelerometer can't be read for back taps")
      thread.quitSafely()
      return
    }
    this.thread = thread
    this.handler = handler
    this.detector = detector
    sensorListener = listener
  }

  private fun configure(detector: BackTapDetector) {
    val threshold = sensitivity.threshold
    val waitForTriple = tripleUsed
    val apply = {
      detector.threshold = threshold
      detector.waitForTriple = waitForTriple
    }
    handler?.post(apply) ?: apply()
  }

  private fun stop() {
    sensorListener?.let { sensors?.unregisterListener(it) }
    sensorListener = null
    thread?.quitSafely()
    thread = null
    handler = null
    detector = null
  }

  private fun isUsed(prefs: SharedPreferences, key: String, gestureSet: Int): Boolean {
    if (GestureProfiles.usesGesture(key) || AppGestures.usesGesture(key)) {
      return true
    }
    val unassigned = context.getString(R.string.shortcut_value_unassigned)
    val value =
      prefs.getString(GestureShortcutMapping.getPrefKeyWithGestureSet(key, gestureSet), unassigned)
    return !value.isNullOrEmpty() && value != unassigned
  }

  private fun gestureSet(prefs: SharedPreferences): Int =
    if (
      GestureShortcutMapping.isGestureSetEnabled(
        context,
        prefs,
        R.string.pref_multiple_gesture_set_key,
        R.bool.pref_multiple_gesture_set_default,
      )
    ) {
      SharedPreferencesUtils.getIntFromStringPref(
        prefs,
        context.resources,
        R.string.pref_gesture_set_key,
        R.string.pref_gesture_set_value_default,
      )
    } else {
      0
    }

  private companion object {
    const val TAG = "BackTapCommands"

    const val SAMPLING_PERIOD_US = 2_500

    const val MAX_REPORT_LATENCY_US = 100_000
  }
}
