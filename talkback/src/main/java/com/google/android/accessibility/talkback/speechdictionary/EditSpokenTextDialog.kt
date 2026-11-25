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

package com.google.android.accessibility.talkback.speechdictionary

import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.dialog.BaseDialog
import com.google.android.accessibility.talkback.notifications.Replacement
import com.google.android.accessibility.talkback.gesture.CopyTextUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * In-place accessibility overlay dialog letting users edit, shorten, or replace how Meridian
 * speaks a piece of text directly from the currently focused item, saving into [SpeechDictionary].
 */
class EditSpokenTextDialog
private constructor(
  context: Context,
  private val initialText: String,
  private val targetPackage: String,
  private val targetAppName: String,
  pipeline: Pipeline.FeedbackReturner?,
  private val onSpeak: (String) -> Unit,
) : BaseDialog(context, R.string.edit_spoken_text_dialog_title, pipeline) {

  private var findInput: EditText? = null
  private var replaceInput: EditText? = null
  private var appOnlyCheck: CheckBox? = null
  private var wholeWordCheck: CheckBox? = null

  init {
    setSoftInputMode(true)
    setRestoreFocus(true)
    setPositiveButtonStringRes(R.string.edit_spoken_text_save)
    setNegativeButtonStringRes(android.R.string.cancel)
  }

  override fun getMessageString(): String? = null

  override fun getCustomizedView(inflater: LayoutInflater): View {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val existing =
      if (initialText.isNotEmpty()) {
        SpeechDictionary.findMatchingEntry(SpeechDictionary.read(prefs), initialText, targetPackage)
      } else {
        null
      }

    val density = context.resources.displayMetrics.density
    val padH = (20 * density).toInt()
    val padV = (12 * density).toInt()
    val gap = (10 * density).toInt()

    val container =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
      }

    val findLabel =
      TextView(context).apply {
        text = context.getString(R.string.edit_spoken_text_original_label)
      }
    val findEdit =
      EditText(context).apply {
        hint = context.getString(R.string.edit_spoken_text_original_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 4
        setText(initialText)
        setSelection(text.length)
      }
    findInput = findEdit

    val replaceLabel =
      TextView(context).apply {
        text = context.getString(R.string.edit_spoken_text_replacement_label)
        setPadding(0, gap, 0, 0)
      }
    val replaceEdit =
      EditText(context).apply {
        hint = context.getString(R.string.edit_spoken_text_replacement_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 4
        if (existing != null) {
          setText(existing.replacement.replace)
          setSelection(text.length)
        }
      }
    replaceInput = replaceEdit

    container.addView(findLabel)
    container.addView(findEdit)
    container.addView(replaceLabel)
    container.addView(replaceEdit)

    if (targetPackage.isNotEmpty()) {
      val displayName = targetAppName.ifEmpty { targetPackage }
      val scopeCheck =
        CheckBox(context).apply {
          text = context.getString(R.string.edit_spoken_text_only_this_app, displayName)
          isChecked = existing == null || existing.app.isNotEmpty()
          setPadding(0, gap, 0, 0)
        }
      appOnlyCheck = scopeCheck
      container.addView(scopeCheck)
    }

    val wholeCheck =
      CheckBox(context).apply {
        text = context.getString(R.string.notification_whole_words)
        isChecked = existing?.replacement?.wholeWord ?: true
      }
    wholeWordCheck = wholeCheck
    container.addView(wholeCheck)

    val tipView =
      TextView(context).apply {
        text = context.getString(R.string.edit_spoken_text_tip)
        setPadding(0, gap, 0, 0)
        textSize = 13f
      }
    container.addView(tipView)

    return ScrollView(context).apply { addView(container) }
  }

  override fun handleDialogClick(buttonClicked: Int) {
    if (buttonClicked != DialogInterface.BUTTON_POSITIVE) return
    val findText = findInput?.text?.toString()?.trim().orEmpty()
    if (findText.isEmpty()) {
      onSpeak(context.getString(R.string.notification_enter_text))
      return
    }
    val replaceText = replaceInput?.text?.toString().orEmpty()
    val onlyThisApp = appOnlyCheck?.isChecked == true && targetPackage.isNotEmpty()
    val scopePkg = if (onlyThisApp) targetPackage else ""
    val scopeName = if (onlyThisApp) targetAppName.ifEmpty { targetPackage } else ""
    val wholeWord = wholeWordCheck?.isChecked ?: true

    val entry =
      DictionaryEntry(
        replacement = Replacement(findText, replaceText, regex = false, wholeWord = wholeWord),
        app = scopePkg,
        appName = scopeName,
      )
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    SpeechDictionary.saveAndApply(context, prefs, entry)

    val confirmation =
      if (scopeName.isNotEmpty()) {
        context.getString(R.string.edit_spoken_text_saved_for_app, scopeName)
      } else {
        context.getString(R.string.edit_spoken_text_saved_all_apps)
      }
    onSpeak(confirmation)
  }

  override fun handleDialogDismiss() {
    findInput = null
    replaceInput = null
    appOnlyCheck = null
    wholeWordCheck = null
  }

  companion object {
    /**
     * Opens the in-place spoken-text editor for [node] (or blank text if no focused text exists).
     */
    @JvmStatic
    fun show(
      context: Context,
      node: AccessibilityNodeInfoCompat?,
      pipeline: Pipeline.FeedbackReturner?,
      onSpeak: java.util.function.Consumer<String>,
    ): Boolean {
      val initialText = extractFocusedText(node)
      val targetPkg = resolveTargetPackage(context, node)
      val appName = if (targetPkg.isNotEmpty()) resolveAppLabel(context, targetPkg) else ""
      EditSpokenTextDialog(
          context = context,
          initialText = initialText,
          targetPackage = targetPkg,
          targetAppName = appName,
          pipeline = pipeline,
          onSpeak = onSpeak::accept,
        )
        .showDialog()
      return true
    }

    internal fun extractFocusedText(node: AccessibilityNodeInfoCompat?): String {
      if (node == null) return ""
      val items = CopyTextUtils.getFocusedTextItems(node)
      return items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    internal fun resolveTargetPackage(context: Context, node: AccessibilityNodeInfoCompat?): String {
      val own = context.packageName
      val fromNode = node?.packageName?.toString().orEmpty()
      if (fromNode.isNotEmpty() && fromNode != own) return fromNode
      val fromWindow = SpeechDictionary.currentApp()
      if (fromWindow.isNotEmpty() && fromWindow != own) return fromWindow
      return ""
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
      return try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
      } catch (_: PackageManager.NameNotFoundException) {
        packageName
      }
    }
  }
}
