/*
 * Copyright 2026 Backtalk contributors
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

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.commands.CommandBindings
import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.talkback.notifications.NotificationReader
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.widget.DialogUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.function.Consumer

/**
 * The dialog that asks for the label and the press type of a "Tự nhấn trên màn hình" action,
 * used when the action is picked for a gesture and when one is added from the management list.
 * The label can be typed, or taken from the node in focus on the current screen. When [initial]
 * is already one of these actions, the dialog opens with its label and press type filled in,
 * so the same dialog edits an existing one.
 */
object TapTextEditor {

  /** Shows the dialog, and calls [onSave] with the composed action value when it is saved. */
  @JvmStatic
  fun show(context: Context, initial: String?, onSave: Consumer<String>): AlertDialog {
    val parsed = TapTextActions.parse(initial)
    val density = context.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val layout = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    val label = EditText(context).apply {
      setSingleLine(false)
      setHint(R.string.tap_text_label_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      setText(parsed?.label.orEmpty())
      setSelection(text?.length ?: 0)
    }
    layout.addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val focused = TalkBackService.getInstance()?.let { SmartContextGestures.focusedText(it) }.orEmpty()
    if (focused.isNotEmpty()) {
      val pick = Button(context).apply {
        setText(R.string.tap_text_pick_from_screen)
        setOnClickListener {
          label.setText(focused)
          label.setSelection(focused.length)
        }
      }
      layout.addView(pick, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val pressLabel = TextView(context).apply { setText(R.string.tap_text_press_type) }
    layout.addView(pressLabel)
    val press = Spinner(context).apply {
      adapter = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_item,
        arrayOf(
          context.getString(R.string.tap_text_tap),
          context.getString(R.string.tap_text_long_press),
        ),
      ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
      setSelection(if (parsed?.longPress == true) 1 else 0)
    }
    layout.addView(press, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val scroll = ScrollView(context).apply { addView(layout) }
    val dialog = MaterialAlertDialogBuilder(context)
      .setTitle(R.string.tap_text_title)
      .setView(scroll)
      .setPositiveButton(R.string.smart_context_save, null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        val text = label.text.toString().trim()
        if (text.isEmpty()) {
          label.error = context.getString(R.string.tap_text_label_required)
          return@setOnClickListener
        }
        onSave.accept(TapTextActions.valueOf(text, press.selectedItemPosition == 1))
        dialog.dismiss()
      }
    }
    // A dialog shown from the service is a system dialog, not an activity window.
    if (context is AccessibilityService) {
      dialog.window?.let(DialogUtils::setWindowTypeToDialog)
    }
    dialog.show()
    label.requestFocus()
    return dialog
  }

  /**
   * Adds a "Tự nhấn trên màn hình" action from the app in front, following
   * SmartContextGestureSettingsFragment.quickAdd: the label is taken from the node in focus on
   * the current screen, so the user adds it while looking at the thing to tap. The scope is
   * asked first (this app, or the whole device), then the label and press type, then the
   * gesture that runs it. With no node in focus, it only says so and does nothing.
   */
  @JvmStatic
  fun quickAdd(service: TalkBackService) {
    val text = SmartContextGestures.focusedText(service)
    if (text.isBlank()) {
      say(service, service.getString(R.string.tap_text_no_focused_item))
      return
    }
    val packageName = SmartContextGestures.currentPackage(service)
    // The scope is asked first. There is no app to save into when the current window has no
    // package, so only the device-wide choice is offered then.
    val scopes = ArrayList<Pair<String, Boolean>>()
    if (packageName.isNotEmpty()) {
      scopes.add(service.getString(R.string.tap_text_scope_current_app) to true)
    }
    scopes.add(service.getString(R.string.tap_text_scope_device) to false)
    display(
      service,
      MaterialComponentUtils.alertDialogBuilder(service)
        .setTitle(R.string.tap_text_add)
        .setItems(scopes.map { it.first }.toTypedArray()) { _, choice ->
          val perApp = scopes[choice].second
          show(service, TapTextActions.valueOf(text, false)) { composed ->
            chooseSlot(service, composed) { slot ->
              saveTapText(service, packageName, perApp, slot, composed)
            }
          }
        },
    )
  }

  /** Asks which gesture runs the new action, from the Gestures screen's own slots. */
  private fun chooseSlot(
    service: TalkBackService,
    composed: String,
    then: (CommandBindings.GestureSlot) -> Unit,
  ) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val slots = gestureSlots(service)
    val names = slots.map { slot ->
      val current = slot.value(service, prefs)
      "${slot.name}: ${Commands.label(service, current) ?: current}"
    }.toTypedArray()
    display(
      service,
      MaterialComponentUtils.alertDialogBuilder(service)
        .setTitle(R.string.tap_text_choose_gesture)
        .setItems(names) { _, index -> then(slots[index]) },
    )
  }

  /** The gestures that can be assigned, in the order of the Gestures screen. */
  private fun gestureSlots(service: TalkBackService): List<CommandBindings.GestureSlot> {
    val manager = PreferenceManager(service)
    val screen = manager.inflateFromResource(service, R.xml.gesture_preferences, null)
    return CommandBindings.gestures(service, screen)
  }

  /** Saves the composed action under [slot], in the app or in the current gesture profile. */
  private fun saveTapText(
    service: TalkBackService,
    packageName: String,
    perApp: Boolean,
    slot: CommandBindings.GestureSlot,
    value: String,
  ) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val baseKey = GestureShortcutMapping.getPrefKeyWithGestureSet(slot.key, 0)
    if (perApp) {
      val all = AppGestures.read(prefs)
      val app = all[packageName]
      val name = app?.name ?: NotificationReader.appName(service, packageName)
      val gestures = app?.gestures.orEmpty() + (baseKey to value)
      AppGestures.write(
        prefs,
        all + (packageName to AppGestures.App(name, gestures, app?.keys.orEmpty())),
      )
      // AppGestures.write only writes the JSON; refresh the service's cache so the new
      // gesture runs at once instead of waiting for the next reload.
      AppGestures.load(prefs)
    } else {
      val activeId = GestureProfiles.activeId(prefs)
      val key = if (activeId.isEmpty()) baseKey else GestureProfiles.rowKey(activeId, baseKey)
      GestureListPreference.save(service, key, value)
    }
  }

  /** Shows a dialog built for [context], as a system dialog when it runs in the service. */
  private fun display(context: Context, builder: AlertDialog.Builder): AlertDialog {
    val dialog = builder.create()
    if (context is AccessibilityService) {
      dialog.window?.let(DialogUtils::setWindowTypeToDialog)
    }
    dialog.show()
    return dialog
  }

  private fun say(service: TalkBackService, message: String) {
    service.speechController?.speak(
      message,
      Performance.EVENT_ID_UNTRACKED,
      SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE),
    )
  }
}
