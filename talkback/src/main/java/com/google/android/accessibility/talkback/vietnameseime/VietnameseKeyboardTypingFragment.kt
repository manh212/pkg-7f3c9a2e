package com.google.android.accessibility.talkback.vietnameseime

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment

class VietnameseKeyboardTypingFragment :
  TalkBackPreferenceFragment(R.xml.vietnamese_keyboard_typing_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.vietnamese_ime_typing)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val modelPref =
      findPreference<Preference>(getString(R.string.pref_vietnamese_keyboard_ai_spell_model_key))
        ?: return

    VietnameseSpellModelManager.refreshState(requireContext())
    VietnameseSpellModelManager.state.observe(viewLifecycleOwner) { st ->
      updateModelSummary(modelPref, st)
    }

    modelPref.setOnPreferenceClickListener {
      val ctx = requireContext()
      when {
        VietnameseSpellModelManager.isDownloading() -> {
          VietnameseSpellModelManager.cancelDownload()
        }
        VietnameseSpellModelManager.isReady(ctx) -> {
          val sizeMb = ((VietnameseSpellModelManager.totalDiskBytes(ctx) + 524_288L) / 1_048_576L).toInt().coerceAtLeast(1)
          AlertDialog.Builder(ctx)
            .setTitle(R.string.vietnamese_ime_ai_spell_model_delete_title)
            .setMessage(getString(R.string.vietnamese_ime_ai_spell_model_delete_message, sizeMb))
            .setPositiveButton(R.string.vietnamese_ime_ai_spell_model_delete_confirm) { _, _ ->
              VietnameseSpellModelManager.deleteModel(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
        else -> {
          AlertDialog.Builder(ctx)
            .setTitle(R.string.vietnamese_ime_ai_spell_model_download_title)
            .setMessage(R.string.vietnamese_ime_ai_spell_model_download_message)
            .setPositiveButton(R.string.vietnamese_ime_ai_spell_model_download_confirm) { _, _ ->
              VietnameseSpellModelManager.startDownload(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      }
      true
    }
  }

  private fun updateModelSummary(pref: Preference, st: VietnameseSpellModelManager.State) {
    pref.summary = when {
      st.downloading -> getString(R.string.vietnamese_ime_ai_spell_model_summary_downloading, st.progress)
      st.ready -> {
        val sizeMb = ((st.downloadedBytes + 524_288L) / 1_048_576L).toInt().coerceAtLeast(1)
        getString(R.string.vietnamese_ime_ai_spell_model_summary_ready, sizeMb)
      }
      st.failed -> {
        val reason = when (st.failure) {
          ModelFiles.Failure.NETWORK, ModelFiles.Failure.TIME_LIMIT -> R.string.vietnamese_ime_ai_spell_error_network
          ModelFiles.Failure.HTTP -> R.string.vietnamese_ime_ai_spell_error_source
          ModelFiles.Failure.SIZE_MISMATCH, ModelFiles.Failure.HASH_MISMATCH -> R.string.vietnamese_ime_ai_spell_error_integrity
          ModelFiles.Failure.STORAGE, ModelFiles.Failure.LOW_SPACE -> R.string.vietnamese_ime_ai_spell_error_storage
          null -> null
        }
        getString(R.string.vietnamese_ime_ai_spell_model_summary_failed) +
          (reason?.let { " " + getString(it) } ?: "")
      }
      else -> getString(R.string.vietnamese_ime_ai_spell_model_summary_not_downloaded)
    }
  }
}
