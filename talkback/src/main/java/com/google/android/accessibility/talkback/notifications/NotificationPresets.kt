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

package com.google.android.accessibility.talkback.notifications

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Ready-made presets for notification speech formats, private formats, and link text so new users
 * can pick plain-language options instead of typing placeholder syntax. Power users can still pick
 * "Custom..." at the end of any list.
 */
object NotificationPresets {
  data class Choice(val value: String, val label: String)

  fun speechFormatChoices(context: Context): List<Choice> =
    listOf(
      Choice(
        NotificationSettings.DEFAULT_FORMAT,
        context.getString(R.string.notification_format_preset_default),
      ),
      Choice(
        "{app}, {title}, {text}",
        context.getString(R.string.notification_format_preset_app_title_text),
      ),
      Choice(
        "{title}, {text}",
        context.getString(R.string.notification_format_preset_title_text),
      ),
      Choice(
        "{app}, {content}",
        context.getString(R.string.notification_format_preset_app_content),
      ),
      Choice(
        "{app}, {title}, {text}, {time}",
        context.getString(R.string.notification_format_preset_with_time),
      ),
      Choice(
        "{app}, {title}",
        context.getString(R.string.notification_format_preset_app_title),
      ),
    )

  fun privateFormatChoices(context: Context): List<Choice> =
    listOf(
      Choice(
        NotificationSettings.DEFAULT_PRIVATE_FORMAT,
        context.getString(R.string.notification_private_preset_app),
      ),
      Choice(
        context.getString(R.string.notification_private_preset_new_from_value),
        context.getString(R.string.notification_private_preset_new_from),
      ),
      Choice(
        "{app}, {time}",
        context.getString(R.string.notification_private_preset_app_time),
      ),
    )

  fun urlTextChoices(context: Context): List<Choice> {
    val link = context.getString(R.string.notification_url_preset_link)
    val address = context.getString(R.string.notification_url_preset_address)
    val contains = context.getString(R.string.notification_url_preset_contains)
    return listOf(
      Choice(link, link),
      Choice(address, address),
      Choice(contains, contains),
    )
  }

  fun speechFormatSummary(
    context: Context,
    rawValue: String?,
    allowInheritDefault: Boolean = false,
  ): String {
    val value = rawValue.orEmpty().trim()
    if (value.isEmpty()) {
      return if (allowInheritDefault) {
        context.getString(R.string.notification_app_mode_default)
      } else {
        context.getString(R.string.notification_format_preset_default)
      }
    }
    return speechFormatChoices(context).firstOrNull { it.value == value }?.label
      ?: context.getString(R.string.notification_format_custom_summary, value)
  }

  fun privateFormatSummary(context: Context, rawValue: String?): String {
    val value = rawValue.orEmpty().trim().ifEmpty { NotificationSettings.DEFAULT_PRIVATE_FORMAT }
    return privateFormatChoices(context).firstOrNull { it.value == value }?.label
      ?: context.getString(R.string.notification_format_custom_summary, value)
  }

  fun urlTextSummary(context: Context, rawValue: String?): String {
    val value = rawValue.orEmpty().trim()
    return value.ifEmpty { context.getString(R.string.notification_url_preset_link) }
  }

  /**
   * Shows a single-choice dialog of ready-made [choices], optionally prefixed with an "As other
   * apps" entry (value `""`) and suffixed with a "Custom (type your own)..." option.
   */
  fun showPresetPicker(
    activity: Activity,
    title: String,
    currentValue: String,
    choices: List<Choice>,
    @StringRes customMessageRes: Int = 0,
    customHint: String = "",
    allowInheritDefault: Boolean = false,
    onSelected: (String) -> Unit,
  ) {
    val entries = mutableListOf<Choice>()
    if (allowInheritDefault) {
      entries += Choice("", activity.getString(R.string.notification_app_mode_default))
    }
    entries += choices
    val customIndex = entries.size
    val labels = entries.map { it.label } + activity.getString(R.string.notification_preset_custom)
    val effectiveCurrent =
      if (allowInheritDefault) currentValue.trim()
      else currentValue.trim().ifEmpty { entries.firstOrNull()?.value.orEmpty() }
    val matchedIndex = entries.indexOfFirst { it.value == effectiveCurrent }
    val checkedIndex = if (matchedIndex >= 0) matchedIndex else customIndex

    MaterialAlertDialogBuilder(activity)
      .setTitle(title)
      .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
        dialog.dismiss()
        if (which == customIndex) {
          showCustomInput(
            activity = activity,
            title = title,
            initialText = effectiveCurrent,
            message = if (customMessageRes != 0) activity.getString(customMessageRes) else "",
            hint = customHint,
            onSaved = onSelected,
          )
        } else {
          onSelected(entries[which].value)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showCustomInput(
    activity: Activity,
    title: String,
    initialText: String,
    message: String,
    hint: String,
    onSaved: (String) -> Unit,
  ) {
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    val container =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
      }
    val input =
      KeyboardSafeEditText(activity).apply {
        setText(initialText)
        if (hint.isNotEmpty()) this.hint = hint
        setSelection(text?.length ?: 0)
      }
    container.addView(input)
    val builder =
      MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          onSaved(input.text?.toString()?.trim().orEmpty())
        }
        .setNegativeButton(android.R.string.cancel, null)
    if (message.isNotEmpty()) {
      builder.setMessage(message)
    }
    builder.show()
  }
}
