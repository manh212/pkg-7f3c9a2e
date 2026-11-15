package com.google.android.accessibility.talkback.vietnameseime

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.SearchMatcher
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

class VietnameseKeyboardShortcutsFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_shortcuts_preferences) {

  private lateinit var prefs: SharedPreferences

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_shortcuts)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    findPreference<Preference>("pref_vietnamese_keyboard_shortcut_add")?.setOnPreferenceClickListener {
      editShortcut(null)
      true
    }
    findPreference<EditTextPreference>("pref_vietnamese_keyboard_shortcut_search")?.apply {
      setSummaryProvider { text?.takeUnless(String::isBlank) }
      setOnBindEditTextListener {
        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        it.privateImeOptions = VietnameseKeyboardShortcuts.LITERAL_CODE_FIELD
        it.setHint(R.string.vietnamese_ime_shortcut_search)
        it.setSelection(it.length())
      }
      setOnPreferenceChangeListener { _, value ->
        refreshEntries(value.toString())
        true
      }
    }
    refreshEntries()
  }

  override fun onResume() {
    super.onResume()
    refreshEntries()
  }

  private fun refreshEntries(
    query: String = findPreference<EditTextPreference>("pref_vietnamese_keyboard_shortcut_search")
      ?.text.orEmpty(),
  ) {
    val list = findPreference<PreferenceGroup>("pref_vietnamese_keyboard_shortcut_list") ?: return
    val normalizedQuery = SearchMatcher.normalize(query)
    val entries = VietnameseKeyboardShortcuts.read(prefs).toSortedMap()
    list.removeAll()
    for ((code, replacement) in entries) {
      if (!SearchMatcher.matchesNormalized(normalizedQuery, SearchMatcher.normalize("$code $replacement"))) {
        continue
      }
      list.addPreference(AccessibilitySuitePreference(requireContext()).apply {
        title = code
        summary = replacement
        isPersistent = false
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
          editShortcut(code)
          true
        }
      })
    }
    if (list.preferenceCount == 0) {
      list.addPreference(AccessibilitySuitePreference(requireContext()).apply {
        setTitle(if (entries.isEmpty()) R.string.vietnamese_ime_shortcut_empty
          else R.string.vietnamese_ime_shortcut_no_results)
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
      })
    }
  }

  private fun editShortcut(original: String?) {
    val context = requireContext()
    val padding = (24 * resources.displayMetrics.density).toInt()
    val form = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding / 2, padding, 0)
    }
    val code = field(form, R.string.vietnamese_ime_shortcut_code, original.orEmpty(), multiline = false)
    code.privateImeOptions = VietnameseKeyboardShortcuts.LITERAL_CODE_FIELD
    val saved = original?.let { VietnameseKeyboardShortcuts.read(prefs)[it] }.orEmpty()
    val replacement = field(form, R.string.vietnamese_ime_shortcut_replacement, saved, multiline = true)
    val dialog = AlertDialog.Builder(context)
      .setTitle(if (original == null) R.string.vietnamese_ime_shortcut_add else R.string.vietnamese_ime_shortcut_edit)
      .setView(ScrollView(context).apply { addView(form) })
      .setPositiveButton(R.string.vietnamese_ime_shortcut_save, null)
      .setNegativeButton(android.R.string.cancel, null)
      .apply { if (original != null) setNeutralButton(R.string.vietnamese_ime_shortcut_delete, null) }
      .create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val key = code.text.toString().trim()
        val value = replacement.text.toString()
        val entries = VietnameseKeyboardShortcuts.read(prefs).toMutableMap()
        when {
          !VietnameseKeyboardShortcuts.isValidCode(key) ->
            code.error = getString(R.string.vietnamese_ime_shortcut_invalid)
          key != original && key in entries ->
            code.error = getString(R.string.vietnamese_ime_shortcut_duplicate)
          value.isBlank() ->
            replacement.error = getString(R.string.vietnamese_ime_shortcut_required_text)
          else -> {
            if (original != null) entries.remove(original)
            entries[key] = value
            VietnameseKeyboardShortcuts.write(prefs, entries)
            refreshEntries()
            dialog.dismiss()
          }
        }
      }
      if (original != null) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
          AlertDialog.Builder(context)
            .setTitle(getString(R.string.vietnamese_ime_shortcut_confirm_delete, original))
            .setPositiveButton(R.string.vietnamese_ime_shortcut_delete) { _, _ ->
              val entries = VietnameseKeyboardShortcuts.read(prefs).toMutableMap()
              entries.remove(original)
              VietnameseKeyboardShortcuts.write(prefs, entries)
              refreshEntries()
              dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      }
    }
    dialog.show()
  }

  private fun field(
    form: LinearLayout, @StringRes label: Int, value: String, multiline: Boolean,
  ): KeyboardSafeEditText {
    val edit = KeyboardSafeEditText(form.context).apply {
      id = View.generateViewId()
      inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      isSingleLine = !multiline
      if (multiline) maxLines = 6
      setText(value)
      setSelection(length())
    }
    form.addView(TextView(form.context).apply {
      setText(label)
      labelFor = edit.id
    })
    form.addView(edit, MATCH_PARENT, WRAP_CONTENT)
    return edit
  }
}
