package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** The symbols-row picker: every available symbol, with the position it sits in today. */
class VietnameseKeyboardSymbolsFragment : TalkbackBaseFragment() {

  private val rows = LinkedHashMap<Char, Preference>()

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_symbols_row_customize)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    val screen = preferenceManager.createPreferenceScreen(context)
    preferenceScreen = screen
    for (symbol in VietnameseKeyboardSymbols.AVAILABLE_SYMBOLS) {
      val row = AccessibilitySuitePreference(context)
      row.title = symbol.toString()
      row.isPersistent = false
      row.isIconSpaceReserved = false
      row.setOnPreferenceClickListener {
        choosePosition(symbol)
        true
      }
      screen.addPreference(row)
      rows[symbol] = row
    }
    refreshSummaries()
  }

  override fun onResume() {
    super.onResume()
    refreshSummaries()
  }

  private fun choosePosition(symbol: Char) {
    val context = requireContext()
    val current = currentRow()
    val labels = (1..VietnameseKeyboardSymbols.ROW_LENGTH).map { position ->
      val atPosition = current[position - 1]
      if (atPosition == symbol) {
        getString(R.string.vietnamese_ime_symbols_row_position_current, position, atPosition.toString())
      } else {
        getString(R.string.vietnamese_ime_symbols_row_position, position)
      }
    }
    AlertDialog.Builder(context)
      .setTitle(getString(R.string.vietnamese_ime_symbols_row_choose_position, symbol.toString()))
      .setSingleChoiceItems(labels.toTypedArray(), current.indexOf(symbol)) { dialog, which ->
        saveRow(VietnameseKeyboardSymbols.placeSymbolAt(current, symbol, which + 1))
        refreshSummaries()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun refreshSummaries() {
    val current = currentRow()
    for ((symbol, row) in rows) {
      val position = current.indexOf(symbol)
      row.summary = if (position >= 0) {
        getString(R.string.vietnamese_ime_symbols_row_in_position, position + 1)
      } else {
        null
      }
    }
  }

  private fun currentRow(): String {
    val context = requireContext()
    return VietnameseKeyboardSymbols.read(context, SharedPreferencesUtils.getSharedPreferences(context))
  }

  private fun saveRow(row: String) {
    val context = requireContext()
    SharedPreferencesUtils.getSharedPreferences(context).edit()
      .putString(context.getString(R.string.pref_vietnamese_keyboard_symbols_row_key), row)
      .apply()
  }
}
