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
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SearchView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.BindingEntry
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.ResetScope
import com.google.android.accessibility.talkback.commands.UnifiedCommandBindings.TargetKind
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.talkback.gesture.CustomMenus
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.talkback.gesture.TapTextActions
import com.google.android.accessibility.talkback.gesture.TapTextEditor
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture
import com.google.android.accessibility.talkback.keyboard.KeyComboManager
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.talkback.preference.base.SearchMatcher
import com.google.android.accessibility.talkback.preference.base.TalkBackGestureShortcutPreferenceFragment
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.text.Collator
import java.util.Locale

/**
 * Unified Commands List: single-window interface with top Search, More Options menu, A-Z group
 * filter dropdown ("Tất cả" default), and flat command list. Tapping any command opens add-binding
 * directly; editing and removing existing bindings live in Accessibility Custom Actions and
 * long-press.
 */
class CommandsFragment : TalkbackBaseFragment() {

  private data class GroupFilterOption(
    val id: Int,
    val title: String,
    val totalCount: Int,
    val displayLabel: String,
  )

  private var allGestureSlots: List<CommandBindings.GestureSlot> = emptyList()
  internal var showAssignedOnly: Boolean = false
    private set
  private var searchQuery: String = ""
  private var selectedCommandValue: String? = null
  private var selectedFilterId: Int = FILTER_ALL
  private var search: SearchView? = null
  private var moreOptionsButton: Button? = null
  private var groupFilterSpinner: Spinner? = null
  private var filterOptions: List<GroupFilterOption> = emptyList()
  private var updatingSpinner: Boolean = false
  private var categories: List<Commands.Category> = emptyList()
  private var bindingIndex: Map<String, List<BindingEntry>> = emptyMap()
  private var tapTextCommands: List<Commands.Command> = emptyList()
  private var searchableCommands: Map<String, String> = emptyMap()
  private var resultsCategory: AccessibilitySuitePreferenceCategory? = null
  private var noResultsPref: AccessibilitySuitePreference? = null
  private val commandRows = LinkedHashMap<String, CommandRowPreference>()
  private val searchHandler = Handler(Looper.getMainLooper())
  private val pendingSearchRunnable = Runnable {
    if (isAdded) {
      renderCommands()
    }
  }
  private var keyComboManager: KeyComboManager? = null

  override fun getTitle(): CharSequence = getString(R.string.title_pref_commands)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    allGestureSlots = readGestures(this) + CommandBindings.arrowKeys(requireContext())
    searchQuery =
      savedInstanceState?.getString(ARG_SEARCH_QUERY)
        ?: arguments?.getString(ARG_SEARCH_QUERY)
        ?: arguments?.getString(TalkBackGestureShortcutPreferenceFragment.ACTION_SEARCH_QUERY)
        ?: ""
    showAssignedOnly = savedInstanceState?.getBoolean(STATE_ASSIGNED_ONLY) ?: false
    selectedFilterId = savedInstanceState?.getInt(STATE_SELECTED_FILTER, FILTER_ALL) ?: FILTER_ALL
    selectedCommandValue =
      if (savedInstanceState != null) savedInstanceState.getString(ARG_COMMAND_VALUE)
      else arguments?.getString(ARG_COMMAND_VALUE)
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val preferences = super.onCreateView(inflater, container, savedInstanceState)
    val context = requireContext()
    val horizontalPad = (16 * context.resources.displayMetrics.density).toInt()
    val verticalPad = (6 * context.resources.displayMetrics.density).toInt()

    // 1. Search (first)
    val searchView =
      SearchView(context).apply {
        setIconifiedByDefault(true)
        setQueryHint(getString(R.string.commands_search_title))
        isSaveFromParentEnabled = false
        setQuery(searchQuery, false)
        clearFocus()
        setOnQueryTextListener(
          object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
              searchHandler.removeCallbacks(pendingSearchRunnable)
              if (searchQuery != query) {
                searchQuery = query
                selectedCommandValue = null
              }
              renderCommands()
              dismissKeyboard()
              return true
            }

            override fun onQueryTextChange(query: String): Boolean {
              if (searchQuery != query) {
                searchQuery = query
                selectedCommandValue = null
                searchHandler.removeCallbacks(pendingSearchRunnable)
                searchHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_MS)
              }
              return true
            }
          }
        )
      }
    search = searchView

    // 2. More Options button (second)
    val moreBtn =
      Button(context).apply {
        text = getString(R.string.commands_more_options)
        contentDescription = getString(R.string.commands_more_options)
        isAllCaps = false
        setOnClickListener { showMoreOptionsMenu(this) }
      }
    moreOptionsButton = moreBtn

    // 3. Group Filter Dropdown (third)
    val spinner =
      Spinner(context, Spinner.MODE_DROPDOWN).apply {
        contentDescription = getString(R.string.commands_category_filter_label)
        setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
        onItemSelectedListener =
          object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
              if (updatingSpinner) return
              val chosen = filterOptions.getOrNull(position) ?: return
              if (selectedFilterId != chosen.id) {
                selectedFilterId = chosen.id
                selectedCommandValue = null
                renderCommands()
              }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
          }
      }
    groupFilterSpinner = spinner

    return LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      addView(
        searchView,
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
      )
      addView(
        moreBtn,
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
          setMargins(horizontalPad, verticalPad / 2, horizontalPad, verticalPad / 2)
        },
      )
      addView(
        spinner,
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
          setMargins(horizontalPad, 0, horizontalPad, verticalPad)
        },
      )
      addView(
        preferences,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
      )
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(ARG_SEARCH_QUERY, searchQuery)
    outState.putBoolean(STATE_ASSIGNED_ONLY, showAssignedOnly)
    outState.putInt(STATE_SELECTED_FILTER, selectedFilterId)
    outState.putString(ARG_COMMAND_VALUE, selectedCommandValue)
  }

  override fun onDestroyView() {
    searchHandler.removeCallbacks(pendingSearchRunnable)
    search?.setOnQueryTextListener(null)
    search = null
    moreOptionsButton?.setOnClickListener(null)
    moreOptionsButton = null
    groupFilterSpinner?.onItemSelectedListener = null
    groupFilterSpinner = null
    super.onDestroyView()
  }

  override fun onResume() {
    super.onResume()
    rebuild()
  }

  internal fun setShowAssignedOnlyForTest(enabled: Boolean) {
    showAssignedOnly = enabled
    renderCommands()
  }

  internal fun selectGroupFilterForTest(filterId: Int) {
    selectedFilterId = filterId
    selectedCommandValue = null
    val screen = preferenceScreen
    screen.removeAll()
    commandRows.clear()
    val resultsCat =
      AccessibilitySuitePreferenceCategory(context).apply {
        key = "pref_commands_results"
        title = getString(R.string.commands_results_header, 0)
        isIconSpaceReserved = false
      }
    screen.addPreference(resultsCat)
    resultsCategory = resultsCat

    val emptyRow =
      AccessibilitySuitePreference(context).apply {
        key = "pref_commands_no_results"
        isPersistent = false
        isIconSpaceReserved = false
        isSelectable = false
        title = getString(R.string.commands_no_results)
        isVisible = false
      }
    resultsCat.addPreference(emptyRow)
    noResultsPref = emptyRow

    val allCommands = staticCommands + tapTextCommands
    for (cmd in allCommands) {
      val row = createCommandRow(cmd, bindingIndex[cmd.value].orEmpty(), prefs, manager)
      resultsCat.addPreference(row)
      commandRows[cmd.value] = row
    }

    updateFilterSpinner()
    renderCommands()
  }

  private fun showMoreOptionsMenu(anchor: View) {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manager = keyComboManager ?: CommandBindings.keyComboManager(context)
    val popup = PopupMenu(context, anchor)
    val menu = popup.menu

    menu.add(0, MENU_FILTER_ASSIGNED, 0, getString(R.string.commands_filter_assigned_only)).apply {
      isCheckable = true
      isChecked = showAssignedOnly
    }
    menu.add(0, MENU_ADD_TAP_TEXT, 1, getString(R.string.tap_text_add))
    menu.add(0, MENU_RESET_DEFAULTS, 2, getString(R.string.commands_reset_defaults))

    popup.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        MENU_FILTER_ASSIGNED -> {
          showAssignedOnly = !showAssignedOnly
          renderCommands()
          true
        }
        MENU_ADD_TAP_TEXT -> {
          TapTextEditor.show(context, null) { composedValue ->
            val label = TapTextActions.label(context, composedValue) ?: composedValue
            val cmd = Commands.Command(composedValue, label, keyboardAction = null)
            UnifiedCommandDialogs.showChooseTargetDialog(
              fragment = this@CommandsFragment,
              context = context,
              prefs = prefs,
              allGestureSlots = allGestureSlots,
              keyComboManager = manager,
              command = cmd,
              onChanged = { rebuild() },
            )
          }
          true
        }
        MENU_RESET_DEFAULTS -> {
          UnifiedCommandDialogs.showResetDialog(
            context = context,
            prefs = prefs,
            allGestureSlots = allGestureSlots,
            keyComboManager = manager,
            onChanged = { rebuild() },
          )
          true
        }
        else -> false
      }
    }
    popup.show()
  }

  private fun rebuild() {
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manager = CommandBindings.keyComboManager(context)
    keyComboManager = manager
    categories = Commands.categories(context)
    val staticCommands = categories.flatMap { it.commands }
    bindingIndex =
      UnifiedCommandBindings.buildBindingIndex(
        context,
        prefs,
        allGestureSlots,
        manager,
        staticCommands,
      )
    tapTextCommands = UnifiedCommandBindings.collectTapTextCommands(context, bindingIndex)
    searchableCommands = buildMap {
      for (category in categories) {
        for (command in category.commands) {
          put(command.value, SearchMatcher.normalize(
            command.label + " " + getString(category.titleRes) + " " +
              bindingIndex[command.value].orEmpty().joinToString(" ") { it.displayLabel }
          ))
        }
      }
      for (command in tapTextCommands) {
        put(command.value, SearchMatcher.normalize(
          command.label + " " + getString(R.string.tap_text_title) + " " +
            bindingIndex[command.value].orEmpty().joinToString(" ") { it.displayLabel }
        ))
      }
    }
    updateFilterSpinner()
    renderCommands()
  }

  private fun updateFilterSpinner() {
    val context = context ?: return
    val spinner = groupFilterSpinner ?: return
    val staticCount = categories.sumOf { it.commands.size }
    val totalCount = staticCount + tapTextCommands.size
    val viCollator = Collator.getInstance(Locale("vi", "VN")).apply {
      strength = Collator.PRIMARY
    }

    val groupEntries = mutableListOf<GroupFilterOption>()
    for (category in categories) {
      val title = getString(category.titleRes)
      val count = category.commands.size
      groupEntries += GroupFilterOption(
        id = category.titleRes,
        title = title,
        totalCount = count,
        displayLabel = getString(R.string.commands_filter_group_count, title, count),
      )
    }
    if (tapTextCommands.isNotEmpty()) {
      val title = getString(R.string.tap_text_title)
      val count = tapTextCommands.size
      groupEntries += GroupFilterOption(
        id = FILTER_TAP_TEXT,
        title = title,
        totalCount = count,
        displayLabel = getString(R.string.commands_filter_group_count, title, count),
      )
    }
    groupEntries.sortWith { a, b -> viCollator.compare(a.title, b.title) }

    val options = buildList {
      add(
        GroupFilterOption(
          id = FILTER_ALL,
          title = getString(R.string.commands_filter_all, totalCount),
          totalCount = totalCount,
          displayLabel = getString(R.string.commands_filter_all, totalCount),
        )
      )
      addAll(groupEntries)
    }
    filterOptions = options

    val selectedIndex = options.indexOfFirst { it.id == selectedFilterId }.let {
      if (it >= 0) it else {
        selectedFilterId = FILTER_ALL
        0
      }
    }

    updatingSpinner = true
    try {
      val adapter = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_item,
        options.map { it.displayLabel },
      ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
      }
      spinner.adapter = adapter
      spinner.setSelection(selectedIndex, false)
    } finally {
      updatingSpinner = false
    }
  }

  private fun renderCommands() {
    if (keyComboManager == null) return
    val resultsCat = resultsCategory ?: return
    val emptyRow = noResultsPref ?: return

    val scopedCommands: List<Commands.Command> =
      when {
        selectedCommandValue != null -> categories.flatMap { it.commands } + tapTextCommands
        selectedFilterId == FILTER_ALL -> categories.flatMap { it.commands } + tapTextCommands
        selectedFilterId == FILTER_TAP_TEXT -> tapTextCommands
        else -> categories.firstOrNull { it.titleRes == selectedFilterId }?.commands.orEmpty()
      }

    val q = SearchMatcher.normalize(searchQuery)
    val matchedValues = HashSet<String>(scopedCommands.size)
    for (cmd in scopedCommands) {
      val bindings = bindingIndex[cmd.value].orEmpty()
      val matchesFilter = !showAssignedOnly || bindings.isNotEmpty()
      if (!matchesFilter) continue
      val matchesQuery =
        selectedCommandValue?.let { cmd.value == it }
          ?: (q.isEmpty() ||
            SearchMatcher.matchesNormalized(
              q,
              searchableCommands[cmd.value].orEmpty(),
            ))
      if (matchesQuery) {
        matchedValues.add(cmd.value)
      }
    }

    val newTitle = getString(R.string.commands_results_header, matchedValues.size)
    if (resultsCat.title != newTitle) {
      resultsCat.title = newTitle
    }
    val showEmpty = matchedValues.isEmpty()
    if (emptyRow.isVisible != showEmpty) {
      emptyRow.isVisible = showEmpty
    }
    for ((cmdValue, row) in commandRows) {
      val visible = cmdValue in matchedValues
      if (row.isVisible != visible) {
        row.isVisible = visible
      }
    }
  }

  private fun createCommandRow(
    command: Commands.Command,
    bindings: List<BindingEntry>,
    prefs: SharedPreferences,
    keyComboManager: KeyComboManager,
  ): CommandRowPreference {
    val context = requireContext()
    return CommandRowPreference(
        context = context,
        hasBindings = bindings.isNotEmpty(),
        canEdit = bindings.any { it.kind != TargetKind.MERIDIAN_MENU && it.kind != TargetKind.CUSTOM_MENU },
        onEditAction = {
          UnifiedCommandDialogs.onCommandEditClicked(
            fragment = this@CommandsFragment,
            context = context,
            prefs = prefs,
            allGestureSlots = allGestureSlots,
            keyComboManager = keyComboManager,
            command = command,
            bindings = bindings,
            onChanged = { rebuild() },
          )
        },
        onRemoveAction = {
          UnifiedCommandDialogs.onCommandRemoveClicked(
            context = context,
            prefs = prefs,
            keyComboManager = keyComboManager,
            command = command,
            bindings = bindings,
            onChanged = { rebuild() },
          )
        },
      ).apply {
        key = "pref_cmd_row_${command.value}"
        isPersistent = false
        isIconSpaceReserved = false
        title = command.label
        summary =
          bindings.joinToString(", ") { it.displayLabel }.ifEmpty {
            getString(R.string.commands_unassigned_summary)
          }
        setOnPreferenceClickListener {
          // Tapping any command (assigned or unassigned) directly opens Add Binding.
          UnifiedCommandDialogs.onCommandAddClicked(
            fragment = this@CommandsFragment,
            context = context,
            prefs = prefs,
            allGestureSlots = allGestureSlots,
            keyComboManager = keyComboManager,
            command = command,
            onChanged = { rebuild() },
          )
          true
        }
      }
  }

  private fun dismissKeyboard() {
    val searchView = search ?: return
    searchView.clearFocus()
    val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    keyboard?.hideSoftInputFromWindow(searchView.windowToken, 0)
  }

  companion object {
    const val ARG_SEARCH_QUERY = "commands_search_query"
    const val ARG_COMMAND_VALUE = "commands_command_value"
    private const val STATE_ASSIGNED_ONLY = "commands_assigned_only"
    private const val STATE_SELECTED_FILTER = "commands_selected_filter"
    private const val SEARCH_DEBOUNCE_MS = 250L
    internal const val FILTER_ALL = 0
    internal const val FILTER_TAP_TEXT = -101
    private const val MENU_FILTER_ASSIGNED = 1
    private const val MENU_ADD_TAP_TEXT = 2
    private const val MENU_RESET_DEFAULTS = 3
  }
}

/**
 * Preference row for a single command: tapping adds a new binding; for assigned commands, Edit and
 * Remove are exposed as TalkBack custom accessibility actions (swipe up/down) and on long-press.
 */
private class CommandRowPreference(
  context: Context,
  private val hasBindings: Boolean,
  private val canEdit: Boolean,
  private val onEditAction: () -> Unit,
  private val onRemoveAction: () -> Unit,
) : AccessibilitySuitePreference(context) {

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    val itemView = holder.itemView

    @Suppress("UNCHECKED_CAST")
    val previousActionIds = itemView.getTag(TAG_CUSTOM_ACTION_IDS) as? List<Int>
    if (previousActionIds != null) {
      for (actionId in previousActionIds) {
        ViewCompat.removeAccessibilityAction(itemView, actionId)
      }
      itemView.setTag(TAG_CUSTOM_ACTION_IDS, null)
    }

    if (!hasBindings) {
      itemView.setOnLongClickListener(null)
      itemView.isLongClickable = false
      return
    }

    val registeredIds = mutableListOf<Int>()
    if (canEdit) {
      registeredIds +=
        ViewCompat.addAccessibilityAction(
          itemView,
          context.getString(R.string.commands_action_edit_binding),
        ) { _, _ ->
          onEditAction()
          true
        }
    }
    registeredIds +=
      ViewCompat.addAccessibilityAction(
        itemView,
        context.getString(R.string.commands_action_remove_binding),
      ) { _, _ ->
        onRemoveAction()
        true
      }
    itemView.setTag(TAG_CUSTOM_ACTION_IDS, registeredIds)

    itemView.isLongClickable = true
    itemView.setOnLongClickListener {
      val options = mutableListOf<Pair<String, () -> Unit>>()
      if (canEdit) {
        options += context.getString(R.string.commands_action_edit_binding) to onEditAction
      }
      options += context.getString(R.string.commands_action_remove_binding) to onRemoveAction
      AlertDialog.Builder(context)
        .setTitle(title)
        .setItems(options.map { it.first }.toTypedArray()) { _, which ->
          options[which].second()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
      true
    }
  }

  companion object {
    private const val TAG_CUSTOM_ACTION_IDS = 0x7f0b7123
  }
}

/**
 * Legacy category fragment kept for binary/deep-link compatibility; [CommandsFragment] now filters
 * categories inline via its top dropdown without launching a separate window.
 */
class CommandCategoryFragment : TalkbackBaseFragment() {

  private val categoryTitle: Int
    get() = arguments?.getInt(ARG_CATEGORY) ?: 0

  override fun getTitle(): CharSequence =
    when (categoryTitle) {
      CATEGORY_TAP_TEXT -> getString(R.string.tap_text_title)
      0 -> getString(R.string.title_pref_commands)
      else -> getString(categoryTitle)
    }

  private var allGestureSlots: List<CommandBindings.GestureSlot> = emptyList()

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    allGestureSlots = readGestures(this) + CommandBindings.arrowKeys(requireContext())
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  override fun onResume() {
    super.onResume()
    fill()
  }

  private fun fill() {
    val context = requireContext()
    val screen = preferenceScreen
    screen.removeAll()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val manager = CommandBindings.keyComboManager(context)
    val categories = Commands.categories(context)
    val staticCommands = categories.flatMap { it.commands }
    val bindingIndex =
      UnifiedCommandBindings.buildBindingIndex(context, prefs, allGestureSlots, manager, staticCommands)

    val commandsToShow: List<Commands.Command> =
      if (categoryTitle == CATEGORY_TAP_TEXT) {
        UnifiedCommandBindings.collectTapTextCommands(context, bindingIndex)
      } else {
        categories.firstOrNull { it.titleRes == categoryTitle }?.commands.orEmpty()
      }

    for (command in commandsToShow) {
      val bindings = bindingIndex[command.value].orEmpty()
      val summaryText =
        bindings.joinToString(", ") { it.displayLabel }.ifEmpty {
          getString(R.string.commands_unassigned_summary)
        }
      screen.addPreference(
        CommandRowPreference(
          context = context,
          hasBindings = bindings.isNotEmpty(),
          canEdit = bindings.any { it.kind != TargetKind.MERIDIAN_MENU && it.kind != TargetKind.CUSTOM_MENU },
          onEditAction = {
            UnifiedCommandDialogs.onCommandEditClicked(
              fragment = this@CommandCategoryFragment,
              context = context,
              prefs = prefs,
              allGestureSlots = allGestureSlots,
              keyComboManager = manager,
              command = command,
              bindings = bindings,
              onChanged = { fill() },
            )
          },
          onRemoveAction = {
            UnifiedCommandDialogs.onCommandRemoveClicked(
              context = context,
              prefs = prefs,
              keyComboManager = manager,
              command = command,
              bindings = bindings,
              onChanged = { fill() },
            )
          },
        ).apply {
          key = "pref_command_${command.value}"
          isPersistent = false
          isIconSpaceReserved = false
          title = command.label
          summary = summaryText
          setOnPreferenceClickListener {
            UnifiedCommandDialogs.onCommandAddClicked(
              fragment = this@CommandCategoryFragment,
              context = context,
              prefs = prefs,
              allGestureSlots = allGestureSlots,
              keyComboManager = manager,
              command = command,
              onChanged = { fill() },
            )
            true
          }
        }
      )
    }
  }

  companion object {
    const val ARG_CATEGORY = "category"
    const val CATEGORY_TAP_TEXT = -101
  }
}

/** Shared assignment, edit, remove, and reset dialogs for [CommandsFragment] and [CommandCategoryFragment]. */
internal object UnifiedCommandDialogs {

  fun onCommandAddClicked(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    onChanged: () -> Unit,
  ) {
    if (TapTextActions.isSentinel(command.value)) {
      TapTextEditor.show(context, null) { value ->
        val created = Commands.Command(value, TapTextActions.label(context, value) ?: value, null)
        showChooseTargetDialog(
          fragment, context, prefs, allGestureSlots, keyComboManager, created, onChanged,
        )
      }
      return
    }
    showChooseTargetDialog(
      fragment,
      context,
      prefs,
      allGestureSlots,
      keyComboManager,
      command,
      onChanged,
    )
  }

  fun onCommandEditClicked(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    bindings: List<BindingEntry>,
    onChanged: () -> Unit,
  ) {
    val editable =
      bindings.filter { it.kind != TargetKind.MERIDIAN_MENU && it.kind != TargetKind.CUSTOM_MENU }
    if (editable.isEmpty()) return
    if (editable.size == 1) {
      editSingleBinding(
        fragment, context, prefs, allGestureSlots, keyComboManager, command, editable[0], onChanged,
      )
      return
    }
    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.commands_edit_choose_title))
      .setItems(editable.map { it.displayLabel }.toTypedArray()) { _, which ->
        editSingleBinding(
          fragment, context, prefs, allGestureSlots, keyComboManager, command, editable[which], onChanged,
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun editSingleBinding(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    entry: BindingEntry,
    onChanged: () -> Unit,
  ) {
    if (entry.kind == TargetKind.KEYBOARD_SHORTCUT) {
      openKeyboardShortcutScreen(fragment, command)
    } else {
      UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
      dispatchTargetAssignment(
        fragment,
        context,
        prefs,
        allGestureSlots,
        keyComboManager,
        command,
        entry.kind,
        onChanged,
      )
    }
  }

  fun onCommandRemoveClicked(
    context: Context,
    prefs: SharedPreferences,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    bindings: List<BindingEntry>,
    onChanged: () -> Unit,
  ) {
    if (bindings.isEmpty()) return
    if (bindings.size == 1) {
      val entry = bindings[0]
      UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
      onChanged()
      PreferencesActivityUtils.announceText(
        context.getString(R.string.commands_binding_removed, entry.displayLabel),
        context,
      )
      return
    }
    val labels =
      bindings.map { it.displayLabel } + context.getString(R.string.commands_remove_all_bindings)
    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.commands_remove_choose_title))
      .setItems(labels.toTypedArray()) { _, which ->
        if (which == bindings.size) {
          for (entry in bindings) {
            UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
          }
          onChanged()
          PreferencesActivityUtils.announceText(
            context.getString(R.string.commands_all_bindings_removed, command.label),
            context,
          )
        } else {
          val entry = bindings[which]
          UnifiedCommandBindings.removeBinding(context, prefs, keyComboManager, command.value, entry)
          onChanged()
          PreferencesActivityUtils.announceText(
            context.getString(R.string.commands_binding_removed, entry.displayLabel),
            context,
          )
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  fun showChooseTargetDialog(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    onChanged: () -> Unit,
  ) {
    val options = mutableListOf<Pair<TargetKind, String>>()
    options.add(TargetKind.TOUCH_GESTURE to context.getString(R.string.commands_target_touch_gesture))
    if (UnifiedCommandBindings.supportsFingerprintGestures(context)) {
      options.add(
        TargetKind.FINGERPRINT_GESTURE to
          context.getString(R.string.commands_target_fingerprint_gesture)
      )
    }
    options.add(TargetKind.EDGE_GESTURE to context.getString(R.string.commands_target_edge_gesture))
    options.add(TargetKind.BACK_TAP to context.getString(R.string.commands_target_back_tap))
    options.add(
      TargetKind.KEYBOARD_SHORTCUT to context.getString(R.string.commands_target_keyboard_shortcut)
    )
    options.add(TargetKind.VOLUME_KEY to context.getString(R.string.commands_target_volume_key))
    options.add(TargetKind.MERIDIAN_MENU to context.getString(R.string.commands_target_meridian_menu))
    options.add(TargetKind.CUSTOM_MENU to context.getString(R.string.commands_target_custom_menu))
    options.add(
      TargetKind.GESTURE_PROFILE to context.getString(R.string.commands_target_gesture_profile)
    )
    options.add(TargetKind.APP_GESTURE to context.getString(R.string.commands_target_app_gesture))

    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.commands_choose_target_title, command.label))
      .setItems(options.map { it.second }.toTypedArray()) { _, which ->
        dispatchTargetAssignment(
          fragment,
          context,
          prefs,
          allGestureSlots,
          keyComboManager,
          command,
          options[which].first,
          onChanged,
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun dispatchTargetAssignment(
    fragment: TalkbackBaseFragment,
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    command: Commands.Command,
    kind: TargetKind,
    onChanged: () -> Unit,
  ) {
    when (kind) {
      TargetKind.TOUCH_GESTURE -> {
        val slots =
          allGestureSlots.filter {
            !UnifiedCommandBindings.isBackTapKey(context, it.key) &&
              !UnifiedCommandBindings.isFingerprintKey(context, it.key)
          }
        pickGestureSlot(context, prefs, slots, command, R.string.command_choose_gesture) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.FINGERPRINT_GESTURE -> {
        val slots =
          allGestureSlots.filter { UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        pickGestureSlot(
          context,
          prefs,
          slots,
          command,
          R.string.commands_target_fingerprint_gesture,
        ) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.BACK_TAP -> {
        val slots = allGestureSlots.filter { UnifiedCommandBindings.isBackTapKey(context, it.key) }
        pickGestureSlot(context, prefs, slots, command, R.string.commands_target_back_tap) { slot ->
          prefs.edit().putString(slot.key, command.value).apply()
          onChanged()
          announceAssigned(context, slot.name.toString(), command.label)
        }
      }
      TargetKind.EDGE_GESTURE -> {
        val unassigned = context.getString(R.string.shortcut_value_unassigned)
        val groups = UnifiedCommandBindings.groupEdgeGestures(context)
        showGroupedPickerDialog(
          context = context,
          title = context.getString(R.string.commands_target_edge_gesture),
          groups = groups,
          itemLabel = { edge ->
            val current = edge.action(prefs, unassigned)
            val name = UnifiedCommandBindings.edgeSlotLabel(context, edge)
            if (current.isNullOrEmpty() || current == unassigned) name
            else "$name: ${Commands.label(context, current) ?: current}"
          },
          onPicked = { edge ->
            UnifiedCommandBindings.assignEdgeGesture(prefs, edge, command.value)
            onChanged()
            announceAssigned(context, UnifiedCommandBindings.edgeSlotLabel(context, edge), command.label)
          },
        )
      }
      TargetKind.VOLUME_KEY -> {
        val unassigned = context.getString(R.string.shortcut_value_unassigned)
        val groups = UnifiedCommandBindings.groupVolumeSlots(context)
        showGroupedPickerDialog(
          context = context,
          title = context.getString(R.string.commands_target_volume_key),
          groups = groups,
          itemLabel = { slot ->
            val current = prefs.getString(slot.key, unassigned) ?: unassigned
            if (current.isEmpty() || current == unassigned) slot.label
            else "${slot.label}: ${Commands.label(context, current) ?: current}"
          },
          onPicked = { slot ->
            UnifiedCommandBindings.assignVolumeSlot(prefs, slot.key, command.value)
            onChanged()
            announceAssigned(context, slot.label, command.label)
          },
        )
      }
      TargetKind.KEYBOARD_SHORTCUT -> openKeyboardShortcutScreen(fragment, command)
      TargetKind.MERIDIAN_MENU -> {
        val currentlyIn = UnifiedCommandBindings.isInMeridianMenu(context, prefs, command.value)
        UnifiedCommandBindings.setInMeridianMenu(context, prefs, command.value, !currentlyIn)
        onChanged()
        PreferencesActivityUtils.announceText(
          context.getString(
            if (!currentlyIn) R.string.commands_added_to_meridian_menu
            else R.string.commands_removed_from_meridian_menu,
            command.label,
          ),
          context,
        )
      }
      TargetKind.CUSTOM_MENU -> {
        val menus = CustomMenus.menus(context)
        if (menus.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_custom_menu)
            .setMessage(R.string.commands_no_custom_menus_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_custom_menu)
          .setItems(menus.map { it.name }.toTypedArray()) { _, which ->
            val menu = menus[which]
            CustomMenus.addItem(context, menu.id, command.label, command.value)
            onChanged()
            announceAssigned(context, menu.name, command.label)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.GESTURE_PROFILE -> {
        val profiles = GestureProfiles.read(prefs)
        if (profiles.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_gesture_profile)
            .setMessage(R.string.commands_no_gesture_profiles_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        val touchSlots =
          allGestureSlots.filter { !UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_gesture_profile)
          .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
            val profile = profiles[which]
            val slotLabels =
              touchSlots.map { slot ->
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                val existing = profile.gestures[baseKey]
                if (existing.isNullOrEmpty()) slot.name.toString()
                else "${slot.name}: ${Commands.label(context, existing) ?: existing}"
              }
            AlertDialog.Builder(context)
              .setTitle(profile.name)
              .setItems(slotLabels.toTypedArray()) { _, slotIdx ->
                val slot = touchSlots[slotIdx]
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                UnifiedCommandBindings.assignToProfile(prefs, profile.id, baseKey, command.value)
                onChanged()
                announceAssigned(context, "${profile.name}: ${slot.name}", command.label)
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      TargetKind.APP_GESTURE -> {
        val appsMap = AppGestures.read(prefs)
        if (appsMap.isEmpty()) {
          AlertDialog.Builder(context)
            .setTitle(R.string.commands_target_app_gesture)
            .setMessage(R.string.commands_no_app_gestures_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
          return
        }
        val entries = appsMap.entries.toList()
        val touchSlots =
          allGestureSlots.filter { !UnifiedCommandBindings.isFingerprintKey(context, it.key) }
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_target_app_gesture)
          .setItems(entries.map { it.value.name }.toTypedArray()) { _, which ->
            val (pkg, app) = entries[which]
            val slotLabels =
              touchSlots.map { slot ->
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                val existing = app.gestures[baseKey]
                if (existing.isNullOrEmpty()) slot.name.toString()
                else "${slot.name}: ${Commands.label(context, existing) ?: existing}"
              }
            AlertDialog.Builder(context)
              .setTitle(app.name)
              .setItems(slotLabels.toTypedArray()) { _, slotIdx ->
                val slot = touchSlots[slotIdx]
                val baseKey = UnifiedCommandBindings.stripGestureSetSuffix(slot.key)
                UnifiedCommandBindings.assignToApp(prefs, pkg, app.name, baseKey, command.value)
                onChanged()
                announceAssigned(context, "${app.name}: ${slot.name}", command.label)
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
  }

  private fun <T> showGroupedPickerDialog(
    context: Context,
    title: String,
    groups: List<UnifiedCommandBindings.SlotGroup<T>>,
    itemLabel: (T) -> String,
    onPicked: (T) -> Unit,
    onBack: (() -> Unit)? = null,
  ) {
    if (groups.size <= 1 && groups.firstOrNull()?.groups.isNullOrEmpty()) {
      val items = groups.firstOrNull()?.items.orEmpty()
      AlertDialog.Builder(context)
        .setTitle(title)
        .setItems(items.map(itemLabel).toTypedArray()) { _, which -> onPicked(items[which]) }
        .apply {
          if (onBack != null) {
            setNeutralButton(R.string.commands_picker_back_to_groups) { _, _ -> onBack() }
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
      return
    }
    val groupLabels =
      groups.map { group ->
        context.getString(R.string.commands_picker_group_count_fmt, group.title, group.items.size)
      }
    AlertDialog.Builder(context)
      .setTitle(title)
      .setItems(groupLabels.toTypedArray()) { _, groupIndex ->
        val chosenGroup = groups[groupIndex]
        if (chosenGroup.groups.isNotEmpty()) {
          showGroupedPickerDialog(
            context,
            chosenGroup.title,
            chosenGroup.groups,
            itemLabel,
            onPicked,
            onBack = {
              showGroupedPickerDialog(context, title, groups, itemLabel, onPicked, onBack)
            },
          )
          return@setItems
        }
        AlertDialog.Builder(context)
          .setTitle(chosenGroup.title)
          .setItems(chosenGroup.items.map(itemLabel).toTypedArray()) { _, itemIndex ->
            onPicked(chosenGroup.items[itemIndex])
          }
          .setNeutralButton(R.string.commands_picker_back_to_groups) { _, _ ->
            showGroupedPickerDialog(context, title, groups, itemLabel, onPicked, onBack)
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      .apply {
        if (onBack != null) {
          setNeutralButton(R.string.commands_picker_back_to_groups) { _, _ -> onBack() }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun pickGestureSlot(
    context: Context,
    prefs: SharedPreferences,
    slots: List<CommandBindings.GestureSlot>,
    command: Commands.Command,
    titleRes: Int,
    onPicked: (CommandBindings.GestureSlot) -> Unit,
  ) {
    val unassigned = context.getString(R.string.shortcut_value_unassigned)
    val labelForSlot: (CommandBindings.GestureSlot) -> String = { slot ->
      val current = slot.value(context, prefs)
      if (current == unassigned) slot.name.toString()
      else "${slot.name}: ${Commands.label(context, current) ?: current}"
    }
    val confirmAndPick: (CommandBindings.GestureSlot) -> Unit = { slot ->
      val current = slot.value(context, prefs)
      if (current == unassigned || current == command.value) {
        onPicked(slot)
      } else {
        AlertDialog.Builder(context)
          .setMessage(
            context.getString(
              R.string.command_replace_gesture,
              slot.name,
              Commands.label(context, current) ?: current,
              command.label,
            )
          )
          .setPositiveButton(R.string.command_replace) { _, _ -> onPicked(slot) }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
    val groups = UnifiedCommandBindings.groupTouchGestureSlots(context, slots)
    if (groups.size > 1) {
      showGroupedPickerDialog(
        context = context,
        title = context.getString(titleRes),
        groups = groups,
        itemLabel = labelForSlot,
        onPicked = confirmAndPick,
      )
      return
    }
    AlertDialog.Builder(context)
      .setTitle(titleRes)
      .setItems(slots.map(labelForSlot).toTypedArray()) { _, which -> confirmAndPick(slots[which]) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun openKeyboardShortcutScreen(
    fragment: TalkbackBaseFragment,
    command: Commands.Command,
  ) {
    val target = CommandFragment().apply {
      arguments = Bundle().apply { putString(CommandFragment.ARG_COMMAND, command.value) }
    }
    fragment.parentFragmentManager
      .beginTransaction()
      .replace(fragment.id, target)
      .addToBackStack(null)
      .commit()
  }

  fun showResetDialog(
    context: Context,
    prefs: SharedPreferences,
    allGestureSlots: List<CommandBindings.GestureSlot>,
    keyComboManager: KeyComboManager,
    onChanged: () -> Unit,
  ) {
    val items =
      listOf(
        ResetScope.TOUCH_AND_FINGERPRINT to context.getString(R.string.commands_reset_gestures),
        ResetScope.EDGE_GESTURES to context.getString(R.string.commands_reset_edge),
        ResetScope.BACK_TAP to context.getString(R.string.commands_reset_back_tap),
        ResetScope.KEYBOARD_SHORTCUTS to context.getString(R.string.commands_reset_keyboard),
        ResetScope.VOLUME_KEYS to context.getString(R.string.commands_reset_volume),
        ResetScope.MERIDIAN_MENU to context.getString(R.string.commands_reset_meridian_menu),
        ResetScope.ALL to context.getString(R.string.commands_reset_all),
      )
    AlertDialog.Builder(context)
      .setTitle(R.string.commands_reset_defaults)
      .setItems(items.map { it.second }.toTypedArray()) { _, which ->
        val (scope, label) = items[which]
        AlertDialog.Builder(context)
          .setTitle(R.string.commands_reset_defaults)
          .setMessage(context.getString(R.string.commands_reset_confirm, label))
          .setPositiveButton(android.R.string.ok) { _, _ ->
            UnifiedCommandBindings.resetBindings(context, prefs, allGestureSlots, keyComboManager, scope)
            onChanged()
            PreferencesActivityUtils.announceText(
              context.getString(R.string.commands_reset_done, label),
              context,
            )
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun announceAssigned(context: Context, slotLabel: String, commandLabel: String) {
    PreferencesActivityUtils.announceText(
      context.getString(R.string.command_gesture_assigned, slotLabel, commandLabel),
      context,
    )
  }
}

/**
 * Returns the gestures that can be assigned, read from the Gestures screen, which [fragment] shows
 * only until it sets its own screen.
 */
internal fun readGestures(fragment: TalkbackBaseFragment): List<CommandBindings.GestureSlot> {
  fragment.addPreferencesFromResource(R.xml.gesture_preferences)
  return CommandBindings.gestures(fragment.requireContext(), fragment.preferenceScreen)
}

internal fun readGestureCategories(fragment: TalkbackBaseFragment): List<CommandBindings.GestureCategory> {
  fragment.addPreferencesFromResource(R.xml.gesture_preferences)
  return CommandBindings.gestureCategories(fragment.requireContext(), fragment.preferenceScreen)
}

