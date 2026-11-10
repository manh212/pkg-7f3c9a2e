/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import android.content.ActivityNotFoundException
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.soundthemes.SOUND_LIBRARY
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import java.io.IOException

/**
 * The "Chọn âm thanh" row of one timer's settings page: it shows the sound in effect and
 * opens the same choices as a sound theme's sound — a file of the user's own, one of the
 * bundled library sounds with a preview as each is browsed, a preview of the current
 * choice, and back to the timer's own sound. Files are copied in on a background thread.
 */
class TimerSoundChooser(
  private val fragment: Fragment,
  private val slot: TimerSounds.Slot,
) {
  private var row: Preference? = null
  private var player: MediaPlayer? = null

  private val chooseFile: ActivityResultLauncher<Array<String>> =
    fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) onFilePicked(uri)
    }

  fun bind(row: Preference) {
    this.row = row
    refreshSummary()
    row.setOnPreferenceClickListener {
      showChoices()
      true
    }
  }

  /** Stops any preview; the owning page calls this when it pauses. */
  fun stopPreview() {
    player?.release()
    player = null
  }

  private fun refreshSummary() {
    val context = fragment.context ?: return
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    row?.summary =
      TimerSounds.label(context, prefs, slot)
        ?: fragment.getString(R.string.theme_sound_summary_default)
  }

  private fun showChoices() {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val choices = ArrayList<Pair<Int, () -> Unit>>()
    choices += R.string.theme_sound_choose to { launchPicker() }
    choices += R.string.theme_sound_choose_library to { showLibrary() }
    choices += R.string.theme_sound_preview to { previewCurrent() }
    if (TimerSounds.label(context, prefs, slot) != null) {
      choices +=
        R.string.theme_sound_use_default to
          {
            TimerSounds.useDefault(context, prefs, slot)
            refreshSummary()
          }
    }
    AlertDialog.Builder(context)
      .setTitle(R.string.timer_sound_choice_title)
      .setItems(choices.map { fragment.getString(it.first) }.toTypedArray()) { _, which ->
        choices[which].second()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showLibrary() {
    val context = fragment.requireContext()
    val names = ArrayList<String>()
    names += fragment.getString(R.string.theme_sound_library_default)
    names += SOUND_LIBRARY.map { it.name }
    val listView = ListView(context)
    listView.accessibilityDelegate =
      object : View.AccessibilityDelegate() {
        override fun onRequestSendAccessibilityEvent(
          host: ViewGroup,
          child: View,
          event: AccessibilityEvent,
        ): Boolean {
          if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            val position = listView.getPositionForView(child)
            if (position == 0) previewResource(slot.defaultResId)
            else if (position > 0) previewResource(SOUND_LIBRARY[position - 1].resId)
          }
          return super.onRequestSendAccessibilityEvent(host, child, event)
        }
      }
    listView.adapter =
      object : BaseAdapter() {
        override fun getCount(): Int = names.size

        override fun getItem(position: Int): Any = names[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
          val density = context.resources.displayMetrics.density
          val view =
            (convertView as? TextView)
              ?: TextView(context).apply {
                setPadding(
                  (16 * density).toInt(),
                  (12 * density).toInt(),
                  (16 * density).toInt(),
                  (12 * density).toInt(),
                )
                textSize = 18f
              }
          view.text = names[position]
          return view
        }
      }
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(R.string.timer_sound_choice_title)
        .setView(listView)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    listView.setOnItemClickListener { _, _, position, _ ->
      dialog.dismiss()
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      if (position == 0) {
        TimerSounds.useDefault(context, prefs, slot)
        refreshSummary()
        previewResource(slot.defaultResId)
      } else {
        setLibrarySound(SOUND_LIBRARY[position - 1].resId, SOUND_LIBRARY[position - 1].name)
      }
    }
    dialog.setOnDismissListener { stopPreview() }
    dialog.show()
  }

  private fun setLibrarySound(resId: Int, name: String) {
    val context = fragment.requireContext().applicationContext
    val activity = fragment.requireActivity()
    TimerSounds.copyExecutor.execute {
      val result =
        try {
          context.resources.openRawResource(resId).use {
            TimerSounds.setSound(context, prefs(context), slot, it, "ogg", name)
          }
        } catch (e: IOException) {
          TimerSounds.Result.FAILED
        } catch (e: RuntimeException) {
          TimerSounds.Result.FAILED
        }
      activity.runOnUiThread {
        if (!fragment.isAdded) return@runOnUiThread
        refreshSummary()
        if (result == TimerSounds.Result.OK) previewCurrent() else showResult(result)
      }
    }
  }

  private fun launchPicker() {
    try {
      chooseFile.launch(SOUND_TYPES)
    } catch (e: ActivityNotFoundException) {
      // Watches have no document picker.
      showMessage(R.string.sound_theme_no_picker)
    }
  }

  private fun onFilePicked(uri: Uri) {
    val context = fragment.requireContext().applicationContext
    val activity = fragment.requireActivity()
    val name = displayName(context, uri) ?: "sound"
    val extension = extensionOf(context, uri)
    TimerSounds.copyExecutor.execute {
      val result =
        try {
          context.contentResolver.openInputStream(uri)?.use {
            TimerSounds.setSound(context, prefs(context), slot, it, extension, name)
          } ?: TimerSounds.Result.FAILED
        } catch (e: IOException) {
          TimerSounds.Result.FAILED
        } catch (e: RuntimeException) {
          // Another app's provider can throw anything, such as SecurityException.
          TimerSounds.Result.FAILED
        }
      activity.runOnUiThread {
        if (!fragment.isAdded) return@runOnUiThread
        refreshSummary()
        if (result == TimerSounds.Result.OK) previewCurrent() else showResult(result)
      }
    }
  }

  private fun showResult(result: TimerSounds.Result) {
    when (result) {
      TimerSounds.Result.TOO_LARGE -> showMessage(R.string.theme_sound_too_large)
      TimerSounds.Result.NOT_AUDIO -> showMessage(R.string.theme_sound_not_audio)
      TimerSounds.Result.FAILED -> showMessage(R.string.theme_sound_failed)
      TimerSounds.Result.OK -> {}
    }
  }

  private fun showMessage(message: Int) {
    if (!fragment.isAdded) return
    AlertDialog.Builder(fragment.requireContext())
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  /** Plays the sound now in effect: the chosen file, or the timer's own sound. */
  private fun previewCurrent() {
    val context = fragment.requireContext()
    val file = TimerSounds.soundFile(context, slot)
    if (file == null) {
      previewResource(slot.defaultResId)
      return
    }
    stopPreview()
    val created = MediaPlayer()
    try {
      created.apply {
        setAudioAttributes(previewAttributes(context))
        setDataSource(file.path)
        prepare()
        setOnCompletionListener {
          if (player === it) player = null
          it.release()
        }
        start()
      }
      player = created
    } catch (e: Exception) {
      created.release()
    }
  }

  private fun previewResource(resId: Int) {
    stopPreview()
    val context = fragment.requireContext()
    player =
      MediaPlayer.create(context, resId, previewAttributes(context), 0)?.apply {
        setOnCompletionListener {
          if (player === it) player = null
          it.release()
        }
        start()
      }
  }

  private fun previewAttributes(context: Context): AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(FailoverTextToSpeech.speechUsage(context))
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

  private fun prefs(context: Context) = SharedPreferencesUtils.getSharedPreferences(context)

  /** The picked file's display name, for the row's summary. */
  private fun displayName(context: Context, uri: Uri): String? =
    try {
      context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: RuntimeException) {
      null
    }

  /** Returns the extension of a chosen file, from its name, or else from its type. */
  private fun extensionOf(context: Context, uri: Uri): String {
    SoundThemes.extensionOf(displayName(context, uri))?.let {
      return it
    }
    val type = context.contentResolver.getType(uri)
    val fromType = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return SoundThemes.extensionOf("sound.$fromType") ?: DEFAULT_EXTENSION
  }

  private companion object {
    const val DEFAULT_EXTENSION = "wav"
    val SOUND_TYPES = arrayOf("audio/*", "application/ogg")
  }
}
