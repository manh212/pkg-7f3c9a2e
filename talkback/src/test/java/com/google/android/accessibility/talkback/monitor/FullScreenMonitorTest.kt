package com.google.android.accessibility.talkback.monitor

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.eventprocessor.BackgroundEventGate
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.SpeechController
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FullScreenMonitorTest {
  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun selectedAppsStayMonitoredWhenManualModeTurnsOff() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit()
      .putBoolean(FullScreenMonitor.KEY_MANUAL, false)
      .putStringSet(FullScreenMonitor.KEY_APPS, setOf("com.example.chat"))
      .commit()
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.chat"))
    assertFalse(FullScreenMonitor.enabled(prefs, "com.example.other"))
    assertTrue(FullScreenMonitor.toggle(context))
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.other"))
    assertFalse(FullScreenMonitor.toggle(context))
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.chat"))
    assertFalse(FullScreenMonitor.enabled(prefs, "com.example.other"))
  }

  @Test
  fun readsEveryNewLineAcrossBatchesEvenWhenStrictProcessingDropsTheEvents() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putBoolean(FullScreenMonitor.KEY_MANUAL, true).commit()
    val messages = mutableListOf("Nội dung có sẵn")
    val spoken = mutableListOf<String>()
    val monitor = FullScreenMonitor(context, { root(messages) }) { true }
    val pipeline = Pipeline.FeedbackReturner { feedback ->
      val speech = feedback.failovers().first().speech()!!
      assertEquals(SpeechController.QUEUE_MODE_QUEUE, speech.options()!!.mQueueMode)
      spoken.add(speech.text().toString())
      true
    }
    val gate = BackgroundEventGate(BackgroundEventGate.Receiver { _, _ -> })
    gate.setProcessingLevel("very_low")

    try {
      send(monitor, pipeline, gate)
      assertTrue(spoken.isEmpty())
      messages.addAll(listOf("Tin nhắn một", "Tin nhắn hai", "Tin nhắn ba", "Tin nhắn bốn"))
      send(monitor, pipeline, gate)
      send(monitor, pipeline, gate)
      messages.add("Tin nhắn năm")
      send(monitor, pipeline, gate)
      messages.add("Tin nhắn năm")
      send(monitor, pipeline, gate)
      assertEquals(
        listOf("Tin nhắn một", "Tin nhắn hai", "Tin nhắn ba", "Tin nhắn bốn",
          "Tin nhắn năm", "Tin nhắn năm"),
        spoken,
      )
    } finally {
      monitor.stop()
    }
  }

  @Test
  fun discardsQueuedChangesWhenMonitoringTurnsOff() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putBoolean(FullScreenMonitor.KEY_MANUAL, true).commit()
    val messages = mutableListOf("Nội dung có sẵn")
    val spoken = mutableListOf<String>()
    val monitor = FullScreenMonitor(context, { root(messages) }) { true }
    val pipeline = Pipeline.FeedbackReturner { feedback ->
      spoken.add(feedback.failovers().first().speech()!!.text().toString())
      true
    }
    val gate = BackgroundEventGate(BackgroundEventGate.Receiver { _, _ -> })
    gate.setProcessingLevel("very_low")

    try {
      send(monitor, pipeline, gate)
      messages.add("Tin chưa đọc")
      send(monitor, pipeline, gate, wait = false)
      assertFalse(FullScreenMonitor.toggle(context))
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
      assertTrue(spoken.isEmpty())
    } finally {
      monitor.stop()
    }
  }

  private fun send(
    monitor: FullScreenMonitor,
    pipeline: Pipeline.FeedbackReturner,
    gate: BackgroundEventGate,
    wait: Boolean = true,
  ) {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    try {
      event.packageName = "com.example.chat"
      event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
      monitor.onEvent(event, pipeline)
      assertFalse(gate.onAccessibilityEvent(event))
    } finally {
      event.recycle()
    }
    if (wait) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
  }

  private fun root(messages: List<String>): AccessibilityNodeInfo =
    AccessibilityNodeInfo.obtain().apply {
      packageName = "com.example.chat"
      isVisibleToUser = true
      for (message in messages) {
        shadowOf(this).addChild(AccessibilityNodeInfo.obtain().apply {
          text = message
          isVisibleToUser = true
        })
      }
      ReflectionHelpers.callInstanceMethod<Any>(
        this, "setSealed", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
}
