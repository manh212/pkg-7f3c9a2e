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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationReader
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.output.SpeechControllerImpl

/**
 * The countdown alarms' two dialogs, opened by their assignable actions from anywhere: one
 * adds an alarm — a name and an exact number of hours, minutes and seconds, typed in — and
 * one lists the alarms still to fire so each can be cancelled without opening the settings.
 */
object CountdownAlarmDialogs {

  /** Opens the add dialog: a name field, then hours, minutes and seconds typed as numbers. */
  @JvmStatic
  fun showAdd(context: Context, speaker: SpeechControllerImpl?, eventId: EventId) {
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val padding = (24 * context.resources.displayMetrics.density).toInt()
    layout.setPadding(padding, padding / 2, padding, 0)
    val name = field(context, layout, R.string.countdown_alarm_name_title, InputType.TYPE_CLASS_TEXT)
    val hours =
      field(context, layout, R.string.countdown_alarm_hours_title, InputType.TYPE_CLASS_NUMBER)
    val minutes =
      field(context, layout, R.string.countdown_alarm_minutes_title, InputType.TYPE_CLASS_NUMBER)
    val seconds =
      field(context, layout, R.string.countdown_alarm_seconds_title, InputType.TYPE_CLASS_NUMBER)
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(R.string.countdown_alarm_title)
        .setView(layout)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.countdown_alarm_add, null)
        .create()
    dialog.setOnShowListener {
      name.requestFocus()
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val totalSeconds =
          durationSeconds(hours.text.toString(), minutes.text.toString(), seconds.text.toString())
        if (totalSeconds == null) {
          speak(context, speaker, eventId, R.string.countdown_alarm_invalid)
        } else {
          NotificationReader.addCountdownAlarm(name.text.toString(), totalSeconds * 1000L)
          dialog.dismiss()
        }
      }
    }
    showOverlay(dialog)
  }

  /** Opens the list of alarms still to fire; choosing one offers to cancel it. */
  @JvmStatic
  fun showRunning(context: Context, speaker: SpeechControllerImpl?, eventId: EventId) {
    val now = System.currentTimeMillis()
    val running = NotificationReader.countdownAlarms().filter { it.deadlineMillis > now }
    if (running.isEmpty()) {
      speak(context, speaker, eventId, R.string.countdown_alarm_none_running)
      return
    }
    val labels =
      running
        .map { entry ->
          val label =
            entry.name.ifEmpty { Stopwatch.formatElapsed(context, entry.durationMillis) }
          label +
            ", " +
            context.getString(
              R.string.countdown_alarm_remaining,
              Stopwatch.formatElapsed(context, entry.deadlineMillis - now),
            )
        }
        .toTypedArray()
    val listDialog =
      AlertDialog.Builder(context)
        .setTitle(R.string.countdown_alarm_running_category)
        .setItems(labels) { _, which ->
          val entry = running[which]
          val label =
            entry.name.ifEmpty { Stopwatch.formatElapsed(context, entry.durationMillis) }
          val confirmDialog =
            AlertDialog.Builder(context)
              .setMessage(context.getString(R.string.countdown_alarm_cancel_confirm, label))
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(R.string.countdown_alarm_cancel_action) { _, _ ->
                NotificationReader.cancelCountdownAlarm(entry.id)
                if (NotificationReader.countdownAlarms().any { it.deadlineMillis > now }) {
                  showRunning(context, speaker, eventId)
                }
              }
              .create()
          showOverlay(confirmDialog)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    showOverlay(listDialog)
  }

  /**
   * The typed countdown in seconds, or null when it is not usable: hours up to 99, minutes
   * and seconds up to 59, and the whole longer than zero. Empty fields count as zero.
   */
  fun durationSeconds(hours: String, minutes: String, seconds: String): Long? {
    val h = if (hours.isBlank()) 0 else hours.toIntOrNull() ?: return null
    val m = if (minutes.isBlank()) 0 else minutes.toIntOrNull() ?: return null
    val s = if (seconds.isBlank()) 0 else seconds.toIntOrNull() ?: return null
    if (h !in 0..99 || m !in 0..59 || s !in 0..59) return null
    val total = h * 3600L + m * 60L + s
    return if (total > 0) total else null
  }

  /**
   * Shows a dialog as an accessibility overlay. These dialogs are opened from the
   * accessibility service, whose context has no window token: a plain show() throws
   * BadTokenException and kills the process, so the overlay type is set first, exactly as
   * the clipboard history dialog does.
   */
  private fun showOverlay(dialog: AlertDialog) {
    dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    dialog.show()
  }

  private fun field(
    context: Context,
    layout: LinearLayout,
    labelResource: Int,
    inputType: Int,
  ): EditText {
    val edit =
      EditText(context).apply {
        id = android.view.View.generateViewId()
        this.inputType = inputType
        isSingleLine = true
        hint = context.getString(labelResource)
      }
    layout.addView(
      TextView(context).apply {
        text = context.getString(labelResource)
        labelFor = edit.id
      }
    )
    layout.addView(edit)
    return edit
  }

  private fun speak(
    context: Context,
    speaker: SpeechControllerImpl?,
    eventId: EventId,
    textResource: Int,
  ) {
    speaker?.speak(
      context.getString(textResource),
      eventId,
      SpeakOptions.create()
        .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL),
    )
  }
}
