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

package com.google.android.accessibility.talkback.preference.base

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.talkback.selector.CustomReadingControls
import com.google.android.accessibility.talkback.selector.SelectorGroupManager
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils

class CustomReadingControlsFragment : TalkbackBaseFragment() {

  override fun getTitle(): CharSequence = getText(R.string.custom_reading_controls_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    setPreferenceScreen(preferenceManager.createPreferenceScreen(requireContext()))
    refresh()
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val screen = preferenceScreen
    screen.removeAll()

    val createPref =
      Preference(context).apply {
        key = "custom_reading_control_create"
        title = getString(R.string.custom_reading_control_create)
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
          showEditorDialog(existing = null)
          true
        }
      }
    screen.addPreference(createPref)

    val controls = CustomReadingControls.list(prefs)
    val category =
      PreferenceCategory(context).apply {
        key = "custom_reading_controls_list"
        title = getString(R.string.custom_reading_controls_title)
        isIconSpaceReserved = false
      }
    screen.addPreference(category)

    if (controls.isEmpty()) {
      category.addPreference(
        Preference(context).apply {
          key = "custom_reading_controls_empty"
          summary = getString(R.string.custom_reading_control_empty)
          isSelectable = false
          isIconSpaceReserved = false
        }
      )
      return
    }

    for (control in controls) {
      val upLabel = Commands.label(context, control.upAction) ?: control.upAction
      val downLabel = Commands.label(context, control.downAction) ?: control.downAction
      category.addPreference(
        Preference(context).apply {
          key = "custom_rc_${control.id}"
          title = control.name
          summary = getString(R.string.custom_reading_control_summary_fmt, upLabel, downLabel)
          isIconSpaceReserved = false
          setOnPreferenceClickListener {
            showItemActionsDialog(control)
            true
          }
        }
      )
    }
  }

  private fun showItemActionsDialog(control: CustomReadingControls.Control) {
    val context = requireContext()
    val items =
      arrayOf(
        getString(R.string.custom_reading_control_edit),
        getString(R.string.custom_reading_control_delete),
      )
    AlertDialog.Builder(context)
      .setTitle(control.name)
      .setItems(items) { _, which ->
        if (which == 0) {
          showEditorDialog(existing = control)
        } else {
          confirmDelete(control)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmDelete(control: CustomReadingControls.Control) {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    AlertDialog.Builder(context)
      .setTitle(R.string.custom_reading_control_delete)
      .setMessage(getString(R.string.custom_reading_control_delete_confirm, control.name))
      .setPositiveButton(android.R.string.ok) { _, _ ->
        CustomReadingControls.delete(prefs, control.id)
        SelectorGroupManager.removeCustomControlFromGroups(context, control.itemId)
        refresh()
        PreferencesActivityUtils.announceText(
          getString(R.string.custom_reading_control_deleted, control.name),
          context,
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showEditorDialog(existing: CustomReadingControls.Control?) {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    var selectedUp = existing?.upAction ?: ""
    var selectedDown = existing?.downAction ?: ""

    val padding = (16 * resources.displayMetrics.density).toInt()
    val container =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
      }

    val nameInput =
      EditText(context).apply {
        hint = getString(R.string.custom_reading_control_name_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setText(existing?.name ?: "")
      }
    container.addView(nameInput)

    val upTitle =
      TextView(context).apply {
        text = getString(R.string.custom_reading_control_swipe_up_label)
        setPadding(0, padding, 0, padding / 4)
      }
    container.addView(upTitle)

    val upButton = Button(context)
    fun refreshUpButton() {
      upButton.text =
        if (selectedUp.isEmpty()) getString(R.string.custom_reading_control_not_selected)
        else Commands.label(context, selectedUp) ?: selectedUp
    }
    refreshUpButton()
    upButton.setOnClickListener {
      pickCommandFromCatalog(
        context,
        getString(R.string.custom_reading_control_swipe_up_label),
      ) { cmd ->
        selectedUp = cmd.value
        refreshUpButton()
      }
    }
    container.addView(upButton)

    val downTitle =
      TextView(context).apply {
        text = getString(R.string.custom_reading_control_swipe_down_label)
        setPadding(0, padding, 0, padding / 4)
      }
    container.addView(downTitle)

    val downButton = Button(context)
    fun refreshDownButton() {
      downButton.text =
        if (selectedDown.isEmpty()) getString(R.string.custom_reading_control_not_selected)
        else Commands.label(context, selectedDown) ?: selectedDown
    }
    refreshDownButton()
    downButton.setOnClickListener {
      pickCommandFromCatalog(
        context,
        getString(R.string.custom_reading_control_swipe_down_label),
      ) { cmd ->
        selectedDown = cmd.value
        refreshDownButton()
      }
    }
    container.addView(downButton)

    val scroll = ScrollView(context).apply { addView(container) }
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(
          if (existing == null) getString(R.string.custom_reading_control_create)
          else existing.name
        )
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
          PreferencesActivityUtils.announceText(
            getString(R.string.custom_reading_control_name_required),
            context,
          )
          nameInput.error = getString(R.string.custom_reading_control_name_required)
          return@setOnClickListener
        }
        if (selectedUp.isEmpty() || selectedDown.isEmpty()) {
          PreferencesActivityUtils.announceText(
            getString(R.string.custom_reading_control_commands_required),
            context,
          )
          return@setOnClickListener
        }
        val saved =
          if (existing == null) {
            CustomReadingControls.create(prefs, name, selectedUp, selectedDown)
          } else {
            existing.copy(name = name, upAction = selectedUp, downAction = selectedDown).also {
              CustomReadingControls.save(prefs, it)
            }
          }
        SelectorGroupManager.ensureCustomControlInUserGroup(context, saved.itemId)
        refresh()
        PreferencesActivityUtils.announceText(
          getString(R.string.custom_reading_control_saved, saved.name),
          context,
        )
        dialog.dismiss()
      }
    }
    dialog.show()
  }

  private fun pickCommandFromCatalog(
    context: Context,
    title: String,
    onPicked: (Commands.Command) -> Unit,
  ) {
    val categories = Commands.categories(context).filter { it.commands.isNotEmpty() }
    val labels = mutableListOf<String>()
    labels.add(context.getString(R.string.custom_reading_control_search_command))
    for (cat in categories) {
      labels.add(
        context.getString(
          R.string.custom_reading_control_pick_category_fmt,
          context.getString(cat.titleRes),
          cat.commands.size,
        )
      )
    }

    AlertDialog.Builder(context)
      .setTitle(title)
      .setItems(labels.toTypedArray()) { _, which ->
        if (which == 0) {
          showCommandSearchDialog(context, title, categories, onPicked)
        } else {
          val category = categories[which - 1]
          val cmdLabels = category.commands.map { it.label }.toTypedArray()
          AlertDialog.Builder(context)
            .setTitle(context.getString(category.titleRes))
            .setItems(cmdLabels) { _, cmdIndex -> onPicked(category.commands[cmdIndex]) }
            .setNeutralButton(R.string.custom_reading_control_back_to_categories) { _, _ ->
              pickCommandFromCatalog(context, title, onPicked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showCommandSearchDialog(
    context: Context,
    title: String,
    categories: List<Commands.Category>,
    onPicked: (Commands.Command) -> Unit,
  ) {
    val input =
      EditText(context).apply {
        hint = context.getString(R.string.custom_reading_control_search_command)
        inputType = InputType.TYPE_CLASS_TEXT
      }
    val padding = (16 * resources.displayMetrics.density).toInt()
    val allCommands =
      categories.flatMap { category ->
        val categoryTitle = context.getString(category.titleRes)
        category.commands.map { command -> command to "$categoryTitle — ${command.label}" }
      }
    var visibleCommands = emptyList<Commands.Command>()
    val adapter =
      ArrayAdapter(context, android.R.layout.simple_list_item_1, mutableListOf<String>())
    val results =
      ListView(context).apply {
        this.adapter = adapter
        layoutParams =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (320 * resources.displayMetrics.density).toInt(),
          )
      }
    val noResults =
      TextView(context).apply {
        text = context.getString(R.string.custom_reading_control_no_match)
        visibility = View.GONE
        setPadding(padding, padding, padding, padding)
      }
    val container =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        addView(input)
        addView(results)
        addView(noResults)
      }
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(title)
        .setView(container)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    fun updateResults(query: CharSequence?) {
      val normalizedQuery = SearchMatcher.normalize(query)
      val matches =
        allCommands.filter { (_, label) ->
          normalizedQuery.isEmpty() ||
            SearchMatcher.matchesNormalized(normalizedQuery, SearchMatcher.normalize(label))
        }
      visibleCommands = matches.map { it.first }
      adapter.clear()
      adapter.addAll(matches.map { it.second })
      noResults.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
    }
    results.setOnItemClickListener { _, _, position, _ ->
      visibleCommands.getOrNull(position)?.let { command ->
        dialog.dismiss()
        onPicked(command)
      }
    }
    input.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(
          text: CharSequence?,
          start: Int,
          count: Int,
          after: Int,
        ) = Unit

        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(text: Editable?) {
          updateResults(text)
        }
      }
    )
    updateResults(input.text)
    dialog.setOnShowListener { input.requestFocus() }
    dialog.show()
  }
}
