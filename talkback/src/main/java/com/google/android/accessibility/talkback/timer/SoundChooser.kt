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
import android.content.SharedPreferences
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
import com.google.android.accessibility.talkback.soundthemes.LibrarySound
import com.google.android.accessibility.talkback.soundthemes.SOUND_LIBRARY
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import java.io.File
import java.io.IOException
import java.io.InputStream

interface SoundSlot {
  val key: String
  val fileName: String
  val defaultResId: Int
}

interface SoundChoiceStore {
  fun soundFile(context: Context, slot: SoundSlot): File?

  fun label(context: Context, prefs: SharedPreferences, slot: SoundSlot): String?

  fun setSound(
    context: Context,
    prefs: SharedPreferences,
    slot: SoundSlot,
    input: InputStream,
    extension: String,
    label: String,
  ): SoundFileStore.Result

  fun useDefault(context: Context, prefs: SharedPreferences, slot: SoundSlot)
}

open class SoundChooser(
  private val fragment: Fragment,
  private val slot: SoundSlot,
  private val store: SoundChoiceStore,
  private val library: List<LibrarySound> = SOUND_LIBRARY,
  private val defaultSummary: String? = null,
  private val libraryDefaultLabel: String? = null,
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

  fun stopPreview() {
    player?.release()
    player = null
  }

  private fun refreshSummary() {
    val context = fragment.context ?: return
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    row?.summary =
      store.label(context, prefs, slot)
        ?: defaultSummary
        ?: fragment.getString(R.string.theme_sound_summary_default)
  }

  private fun showChoices() {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val choices = ArrayList<Pair<Int, () -> Unit>>()
    choices += R.string.theme_sound_choose to { launchPicker() }
    choices += R.string.theme_sound_choose_library to { showLibrary() }
    choices += R.string.theme_sound_preview to { previewCurrent() }
    if (store.label(context, prefs, slot) != null) {
      choices +=
        R.string.theme_sound_use_default to
          {
            store.useDefault(context, prefs, slot)
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
    names += libraryDefaultLabel ?: fragment.getString(R.string.theme_sound_library_default)
    names += library.map { it.name }
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
            else if (position > 0) previewResource(library[position - 1].resId)
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
        store.useDefault(context, prefs, slot)
        refreshSummary()
        previewResource(slot.defaultResId)
      } else {
        setLibrarySound(library[position - 1].resId, library[position - 1].name)
      }
    }
    dialog.setOnDismissListener { stopPreview() }
    dialog.show()
  }

  private fun setLibrarySound(resId: Int, name: String) {
    val context = fragment.requireContext().applicationContext
    val activity = fragment.requireActivity()
    SoundFileStore.copyExecutor.execute {
      val result =
        try {
          context.resources.openRawResource(resId).use {
            store.setSound(context, prefs(context), slot, it, "ogg", name)
          }
        } catch (e: IOException) {
          SoundFileStore.Result.FAILED
        } catch (e: RuntimeException) {
          SoundFileStore.Result.FAILED
        }
      activity.runOnUiThread {
        if (!fragment.isAdded) return@runOnUiThread
        refreshSummary()
        if (result == SoundFileStore.Result.OK) previewCurrent() else showResult(result)
      }
    }
  }

  private fun launchPicker() {
    try {
      chooseFile.launch(SOUND_TYPES)
    } catch (e: ActivityNotFoundException) {
      showMessage(R.string.sound_theme_no_picker)
    }
  }

  private fun onFilePicked(uri: Uri) {
    val context = fragment.requireContext().applicationContext
    val activity = fragment.requireActivity()
    val name = displayName(context, uri) ?: "sound"
    val extension = extensionOf(context, uri)
    SoundFileStore.copyExecutor.execute {
      val result =
        try {
          context.contentResolver.openInputStream(uri)?.use {
            store.setSound(context, prefs(context), slot, it, extension, name)
          } ?: SoundFileStore.Result.FAILED
        } catch (e: IOException) {
          SoundFileStore.Result.FAILED
        } catch (e: RuntimeException) {
          SoundFileStore.Result.FAILED
        }
      activity.runOnUiThread {
        if (!fragment.isAdded) return@runOnUiThread
        refreshSummary()
        if (result == SoundFileStore.Result.OK) previewCurrent() else showResult(result)
      }
    }
  }

  private fun showResult(result: SoundFileStore.Result) {
    when (result) {
      SoundFileStore.Result.TOO_LARGE -> showMessage(R.string.theme_sound_too_large)
      SoundFileStore.Result.NOT_AUDIO -> showMessage(R.string.theme_sound_not_audio)
      SoundFileStore.Result.FAILED -> showMessage(R.string.theme_sound_failed)
      SoundFileStore.Result.OK -> {}
    }
  }

  private fun showMessage(message: Int) {
    if (!fragment.isAdded) return
    AlertDialog.Builder(fragment.requireContext())
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun previewCurrent() {
    val context = fragment.requireContext()
    val file = store.soundFile(context, slot)
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

  private fun displayName(context: Context, uri: Uri): String? =
    try {
      context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: RuntimeException) {
      null
    }

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
