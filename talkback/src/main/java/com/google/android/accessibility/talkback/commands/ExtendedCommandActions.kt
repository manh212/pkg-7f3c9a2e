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

package com.google.android.accessibility.talkback.commands

import android.content.Context
import android.content.Intent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.directtouch.DirectTouchSettings
import com.google.android.accessibility.talkback.gesture.BackTapCommands
import com.google.android.accessibility.talkback.notificationlist.NotificationListActivity
import com.google.android.accessibility.talkback.notifications.NotificationHistoryFragment
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.talkback.preference.TalkBackSubSettings
import com.google.android.accessibility.talkback.quicksettings.QuickControl
import com.google.android.accessibility.talkback.quicksettings.QuickControls
import com.google.android.accessibility.talkback.recognition.LiveRecognitionActivity
import com.google.android.accessibility.talkback.recognition.RecognitionMode
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity
import java.util.function.Consumer

/**
 * Extended assignable commands exposed in the unified Commands list:
 * - Live Recognition (main screen + all 12 modes)
 * - Additional Meridian features (Direct Touch, Notification List/History, Back Tap toggle, Settings)
 * - Quick device controls (Flashlight, Wi-Fi, Bluetooth, DND, Sound Mode, Auto-rotate, Extra Dim,
 *   Battery Saver, Airplane Mode)
 */
object ExtendedCommandActions {

  const val ACTION_LIVE_RECOGNITION_OPEN = "EXTENDED_LIVE_RECOGNITION_OPEN"
  const val PREFIX_LIVE_RECOGNITION_MODE = "EXTENDED_LIVE_RECOGNITION_MODE:"

  const val ACTION_TOGGLE_DIRECT_TOUCH = "EXTENDED_TOGGLE_DIRECT_TOUCH"
  const val ACTION_OPEN_NOTIFICATION_LIST = "EXTENDED_OPEN_NOTIFICATION_LIST"
  const val ACTION_OPEN_NOTIFICATION_HISTORY = "EXTENDED_OPEN_NOTIFICATION_HISTORY"
  const val ACTION_TOGGLE_BACK_TAP = "EXTENDED_TOGGLE_BACK_TAP"
  const val ACTION_OPEN_MERIDIAN_SETTINGS = "EXTENDED_OPEN_MERIDIAN_SETTINGS"

  const val PREFIX_QUICK_CONTROL = "EXTENDED_QUICK_CONTROL:"

  /** Curated high-utility device quick controls exposed as direct commands. */
  val QUICK_DEVICE_CONTROLS: List<QuickControl> =
    listOf(
      QuickControl.FLASHLIGHT,
      QuickControl.WIFI,
      QuickControl.BLUETOOTH,
      QuickControl.DO_NOT_DISTURB,
      QuickControl.SOUND_MODE,
      QuickControl.AUTO_ROTATE,
      QuickControl.EXTRA_DIM,
      QuickControl.BATTERY_SAVER,
      QuickControl.AIRPLANE_MODE,
    )

  @Volatile private var cachedQuickControls: QuickControls? = null

  fun liveRecognitionModeAction(mode: RecognitionMode): String =
    "$PREFIX_LIVE_RECOGNITION_MODE${mode.name}"

  fun parseLiveRecognitionMode(action: String?): RecognitionMode? {
    if (action == null || !action.startsWith(PREFIX_LIVE_RECOGNITION_MODE)) return null
    val name = action.removePrefix(PREFIX_LIVE_RECOGNITION_MODE)
    return RecognitionMode.entries.firstOrNull { it.name == name }
  }

  fun quickControlAction(control: QuickControl): String =
    "$PREFIX_QUICK_CONTROL${control.name}"

  fun parseQuickControl(action: String?): QuickControl? {
    if (action == null || !action.startsWith(PREFIX_QUICK_CONTROL)) return null
    val name = action.removePrefix(PREFIX_QUICK_CONTROL)
    return QUICK_DEVICE_CONTROLS.firstOrNull { it.name == name }
  }

  /** Returns the human-readable label for any extended command action, or null if not ours. */
  @JvmStatic
  fun labelFor(context: Context, action: String?): String? {
    if (action.isNullOrEmpty()) return null
    if (action == ACTION_LIVE_RECOGNITION_OPEN) {
      return context.getString(R.string.command_live_recognition_open)
    }
    parseLiveRecognitionMode(action)?.let { mode ->
      return context.getString(R.string.command_live_recognition_mode_fmt, context.getString(mode.label))
    }
    when (action) {
      ACTION_TOGGLE_DIRECT_TOUCH -> return context.getString(R.string.command_toggle_direct_touch)
      ACTION_OPEN_NOTIFICATION_LIST -> return context.getString(R.string.command_open_notification_list)
      ACTION_OPEN_NOTIFICATION_HISTORY -> return context.getString(R.string.command_open_notification_history)
      ACTION_TOGGLE_BACK_TAP -> return context.getString(R.string.command_toggle_back_tap)
      ACTION_OPEN_MERIDIAN_SETTINGS -> return context.getString(R.string.command_open_meridian_settings)
    }
    parseQuickControl(action)?.let { control ->
      return if (control == QuickControl.SOUND_MODE) {
        context.getString(R.string.command_quick_sound_mode)
      } else {
        context.getString(R.string.command_quick_toggle_fmt, context.getString(control.title))
      }
    }
    return null
  }

  /**
   * Executes an extended command if [action] matches one of ours.
   * Returns true if handled, false otherwise.
   */
  @JvmStatic
  fun perform(context: Context, action: String?, speak: Consumer<String>): Boolean {
    if (action.isNullOrEmpty()) return false

    if (action == ACTION_LIVE_RECOGNITION_OPEN) {
      launchActivity(context, Intent(context, LiveRecognitionActivity::class.java))
      return true
    }
    parseLiveRecognitionMode(action)?.let { mode ->
      val intent =
        Intent(context, LiveRecognitionActivity::class.java).apply {
          putExtra(LiveRecognitionActivity.EXTRA_MODE, mode.name)
        }
      launchActivity(context, intent)
      return true
    }

    when (action) {
      ACTION_TOGGLE_DIRECT_TOUCH -> {
        val prefs = SharedPreferencesUtils.getSharedPreferences(context)
        val next = !DirectTouchSettings.isMasterEnabled(prefs)
        DirectTouchSettings.setMasterEnabled(prefs, next)
        val name = context.getString(R.string.title_pref_direct_touch)
        speak.accept(
          context.getString(
            if (next) R.string.command_feedback_enabled_fmt else R.string.command_feedback_disabled_fmt,
            name,
          )
        )
        return true
      }
      ACTION_OPEN_NOTIFICATION_LIST -> {
        launchActivity(context, Intent(context, NotificationListActivity::class.java))
        return true
      }
      ACTION_OPEN_NOTIFICATION_HISTORY -> {
        val intent =
          Intent(context, TalkBackSubSettings::class.java).apply {
            putExtra(PreferencesActivity.FRAGMENT_NAME, NotificationHistoryFragment::class.java.name)
          }
        launchActivity(context, intent)
        return true
      }
      ACTION_TOGGLE_BACK_TAP -> {
        val prefs = SharedPreferencesUtils.getSharedPreferences(context)
        val next = !prefs.getBoolean(BackTapCommands.PREF_BACK_TAP_ENABLED, true)
        prefs.edit().putBoolean(BackTapCommands.PREF_BACK_TAP_ENABLED, next).apply()
        val name = context.getString(R.string.title_pref_category_back_tap)
        speak.accept(
          context.getString(
            if (next) R.string.command_feedback_enabled_fmt else R.string.command_feedback_disabled_fmt,
            name,
          )
        )
        return true
      }
      ACTION_OPEN_MERIDIAN_SETTINGS -> {
        launchActivity(context, Intent(context, TalkBackPreferencesActivity::class.java))
        return true
      }
    }

    parseQuickControl(action)?.let { control ->
      performQuickControl(context, control, speak)
      return true
    }

    return false
  }

  private fun quickControls(context: Context): QuickControls {
    val existing = cachedQuickControls
    if (existing != null) return existing
    return synchronized(this) {
      cachedQuickControls ?: QuickControls(context.applicationContext).also { qc ->
        // Register callbacks (such as CameraManager.TorchCallback) so flashlight state stays accurate.
        qc.start {}
        cachedQuickControls = qc
      }
    }
  }

  private fun performQuickControl(
    context: Context,
    control: QuickControl,
    speak: Consumer<String>,
  ) {
    val controls = quickControls(context)
    val label = context.getString(control.title)

    if (!controls.isAvailable(control)) {
      speak.accept(context.getString(R.string.command_feedback_unavailable_fmt, label))
      return
    }

    if (control == QuickControl.SOUND_MODE) {
      val state = controls.choice(control)
      if (state == null || state.options.isEmpty()) {
        speak.accept(context.getString(R.string.command_feedback_unavailable_fmt, label))
        return
      }
      val currentIndex = state.options.indexOfFirst { it.value == state.current.value }
      val nextOption = state.options[(currentIndex + 1) % state.options.size]
      val change = controls.setChoice(control, nextOption.value) {}
      when (change) {
        QuickControls.Change.DONE, QuickControls.Change.STARTED ->
          speak.accept(context.getString(R.string.command_feedback_sound_mode_fmt, nextOption.label))
        QuickControls.Change.OPENED ->
          speak.accept(context.getString(R.string.command_feedback_opened_settings_fmt, label))
        QuickControls.Change.FAILED ->
          speak.accept(context.getString(R.string.command_feedback_unavailable_fmt, label))
      }
      return
    }

    val currentOn = controls.isOn(control)
    if (currentOn == null) {
      val opened = controls.openSettings(control)
      if (opened == QuickControls.Change.OPENED) {
        speak.accept(context.getString(R.string.command_feedback_opened_settings_fmt, label))
      } else {
        speak.accept(context.getString(R.string.command_feedback_unavailable_fmt, label))
      }
      return
    }

    val targetOn = !currentOn
    val change = controls.setSwitch(control, targetOn) {}
    when (change) {
      QuickControls.Change.DONE, QuickControls.Change.STARTED ->
        speak.accept(
          context.getString(
            if (targetOn) R.string.command_feedback_enabled_fmt else R.string.command_feedback_disabled_fmt,
            label,
          )
        )
      QuickControls.Change.OPENED ->
        speak.accept(context.getString(R.string.command_feedback_opened_settings_fmt, label))
      QuickControls.Change.FAILED ->
        speak.accept(context.getString(R.string.command_feedback_unavailable_fmt, label))
    }
  }

  private fun launchActivity(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    context.startActivity(intent)
  }
}
