/*
 * Copyright 2026 SandFly contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.android.accessibility.talkback.monitor

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import java.util.ArrayDeque

class FullScreenMonitor(
  private val context: Context,
  private val activeRoot: () -> AccessibilityNodeInfo?,
  private val canRead: () -> Boolean,
) {
  companion object {
    const val KEY_APPS = "pref_full_screen_monitor_apps"
    const val KEY_MANUAL = "pref_full_screen_monitor_manual"
    private const val BATCH_DELAY_MS = 180L
    private const val SCAN_BUDGET_MS = 25L
    private const val MAX_NODES = 240
    private const val MAX_RECENT = 400
    private const val MAX_EVENTS = 16
    private var manualRevision = 0

    @JvmStatic
    fun enabled(prefs: SharedPreferences, packageName: String): Boolean =
      prefs.getBoolean(KEY_MANUAL, false) ||
        prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().contains(packageName)

    @JvmStatic
    fun toggle(context: Context): Boolean {
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      val enabled = !prefs.getBoolean(KEY_MANUAL, false)
      prefs.edit().putBoolean(KEY_MANUAL, enabled).apply()
      manualRevision++
      return enabled
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private val prefs by lazy { SharedPreferencesUtils.getSharedPreferences(context) }
  private val pending = ArrayDeque<AccessibilityEvent>()
  private val recent = LinkedHashSet<String>()
  private val recentNotifications = LinkedHashSet<Pair<String, String>>()
  private var window: Pair<String, Int>? = null
  private var previousCounts = emptyMap<String, Int>()
  private var revision = manualRevision
  private var feedback: Pipeline.FeedbackReturner? = null
  private val readBatch = Runnable { flush() }

  fun onEvent(event: AccessibilityEvent, feedback: Pipeline.FeedbackReturner) {
    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> Unit
      else -> return
    }
    val packageName = event.packageName?.toString() ?: return
    if (!monitors(packageName) || event.isPassword || !canRead()) return
    if (revision != manualRevision) stop()
    this.feedback = feedback
    if (pending.isEmpty()) handler.postDelayed(readBatch, BATCH_DELAY_MS)
    if (pending.size == MAX_EVENTS) pending.removeFirst().recycle()
    pending.addLast(AccessibilityEvent.obtain(event))
  }

  fun stop() {
    handler.removeCallbacks(readBatch)
    feedback = null
    while (pending.isNotEmpty()) pending.removeFirst().recycle()
    window = null
    previousCounts = emptyMap()
    recent.clear()
    recentNotifications.clear()
    revision = manualRevision
  }

  private fun monitors(packageName: String): Boolean =
    packageName != context.packageName && packageName != "com.android.systemui" &&
      enabled(prefs, packageName)

  private fun flush() {
    if (revision != manualRevision || !canRead()) {
      stop()
      return
    }
    val pipeline = feedback ?: return
    feedback = null
    val events = pending.toList()
    pending.clear()
    try {
      readNotifications(events, pipeline)
      val root = activeRoot() ?: return
      try {
        val packageName = root.packageName?.toString() ?: return
        if (!monitors(packageName)) return
        val currentWindow = packageName to root.windowId
        val changes = events.filter {
          it.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            it.packageName?.toString() == packageName &&
            (it.windowId < 0 || currentWindow.second < 0 || it.windowId == currentWindow.second)
        }
        if (changes.isEmpty()) return
        val firstSnapshot = window != currentWindow
        if (firstSnapshot) {
          recent.clear()
          previousCounts = emptyMap()
          window = currentWindow
        }
        val newText = newVisibleText(collectAccessibleText(root), firstSnapshot)
        eventText(changes, root).filterTo(newText) { remember(it) }
        speak(pipeline, newText)
      } finally {
        root.recycle()
      }
    } finally {
      events.forEach { it.recycle() }
    }
  }

  private fun eventText(events: List<AccessibilityEvent>, root: AccessibilityNodeInfo): Set<String> {
    val lines = LinkedHashSet<String>()
    val deadline = SystemClock.uptimeMillis() + SCAN_BUDGET_MS
    for (event in events) {
      if (SystemClock.uptimeMillis() > deadline) break
      val source = event.source
      try {
        if (source?.isEditable == true || source?.isPassword == true) continue
        if (source != null || event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
          event.text.mapNotNullTo(lines, ::cleanText)
        }
        if (source != null && source != root) {
          lines.addAll(collectAccessibleText(source, deadline))
        }
      } finally {
        source?.recycle()
      }
    }
    return lines
  }

  private fun readNotifications(
    events: List<AccessibilityEvent>,
    pipeline: Pipeline.FeedbackReturner,
  ) {
    for (event in events) {
      if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) continue
      val packageName = event.packageName?.toString() ?: continue
      if (!monitors(packageName)) continue
      val text = event.text.mapNotNull(::cleanText).filter { recentNotifications.add(packageName to it) }
      while (recentNotifications.size > MAX_RECENT) {
        recentNotifications.remove(recentNotifications.first())
      }
      speak(pipeline, text)
    }
  }

  private fun newVisibleText(lines: List<String>, firstSnapshot: Boolean): MutableList<String> {
    val seen = mutableMapOf<String, Int>()
    val added = mutableListOf<String>()
    for (line in lines) {
      val count = (seen[line] ?: 0) + 1
      seen[line] = count
      val previous = previousCounts[line] ?: 0
      val isNew = remember(line)
      if (!firstSnapshot && (isNew || (previous > 0 && count > previous))) added.add(line)
    }
    previousCounts = seen
    return added
  }

  private fun remember(text: String): Boolean {
    val isNew = recent.add(text)
    if (recent.size > MAX_RECENT) recent.remove(recent.first())
    return isNew
  }

  private fun cleanText(text: CharSequence?): String? =
    text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(300)

  private fun collectAccessibleText(
    root: AccessibilityNodeInfo,
    deadline: Long = SystemClock.uptimeMillis() + SCAN_BUDGET_MS,
  ): List<String> {
    val nodes = ArrayDeque<AccessibilityNodeInfo>()
    val lines = mutableListOf<String>()
    nodes.add(root)
    var visited = 0
    try {
      while (nodes.isNotEmpty() && visited < MAX_NODES && SystemClock.uptimeMillis() <= deadline) {
        val node = nodes.removeFirst()
        visited++
        try {
          if (node.isPassword || node.isEditable || (node !== root && !node.isVisibleToUser)) continue
          val children = node.childCount
          if (children == 0 && node.isVisibleToUser) {
            val value = cleanText(node.contentDescription) ?: cleanText(node.text)
            if (value != null) lines.add(value)
          }
          val first = maxOf(0, children - (MAX_NODES - visited - nodes.size))
          for (index in children - 1 downTo first) {
            if (SystemClock.uptimeMillis() > deadline) break
            node.getChild(index)?.let(nodes::addLast)
          }
        } finally {
          if (node !== root) node.recycle()
        }
      }
    } finally {
      while (nodes.isNotEmpty()) {
        val node = nodes.removeFirst()
        if (node !== root) node.recycle()
      }
    }
    return lines.asReversed()
  }

  private fun speak(pipeline: Pipeline.FeedbackReturner, lines: List<String>) {
    for (line in lines) {
      pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.speech(
          line,
          SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
            .setFlags(FeedbackItem.FLAG_NO_HISTORY or
              FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE),
        ),
      )
    }
  }
}
