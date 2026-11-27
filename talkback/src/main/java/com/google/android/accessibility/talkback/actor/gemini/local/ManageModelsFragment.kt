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

package com.google.android.accessibility.talkback.actor.gemini.local

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager.State
import com.google.android.accessibility.talkback.preference.base.PreferenceCustomActions
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/**
 * Lists the models on the phone, including unfinished downloads. Tapping an unfinished model
 * resumes downloading; deleting any model lives safely in Custom Actions (swipe up/down) and
 * long-press so screen-reader users never delete multi-gigabyte models by accidental double-tap.
 */
class ManageModelsFragment : TalkbackBaseFragment() {
  private lateinit var manager: LocalModelManager
  private var shown: List<String> = emptyList()

  private val listener: () -> Unit = { if (isAdded) refresh() }

  public override fun getTitle(): CharSequence = getText(R.string.title_pref_manage_models)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    manager = LocalModelManager.get(requireContext())
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    refresh(force = true)
  }

  override fun onStart() {
    super.onStart()
    manager.addListener(listener)
    refresh(force = true)
  }

  override fun onStop() {
    manager.removeListener(listener)
    super.onStop()
  }

  private fun refresh(force: Boolean = false) {
    val context = requireContext()
    val store = manager.store
    val downloading = (manager.state as? State.Downloading)?.model
    val installed = store.installedModels()
    val unfinished = store.unfinishedDownloads().filter { it.first != downloading }
    val snapshot = installed.map { "i:${it.id}" } + unfinished.map { "u:${it.first.id}" }
    if (!force && snapshot == shown) return
    shown = snapshot

    val screen = preferenceScreen
    screen.removeAll()
    if (installed.isEmpty() && unfinished.isEmpty()) {
      screen.addPreference(info(context, getString(R.string.manage_models_empty)))
      return
    }
    screen.addPreference(
      info(
        context,
        getString(
          R.string.manage_models_storage_used,
          Formatter.formatShortFileSize(context, store.usedBytes()),
        ),
      )
    )
    for (model in installed) {
      screen.addPreference(entry(context, model.name, model, model.sizeBytes, isUnfinished = false))
    }
    for ((model, bytes) in unfinished) {
      screen.addPreference(
        entry(context, getString(R.string.manage_models_unfinished, model.name), model, bytes, isUnfinished = true)
      )
    }
  }

  private fun info(context: Context, text: String) =
    Preference(context).apply {
      isPersistent = false
      isSelectable = false
      layoutResource = R.layout.listitem_2texts
      title = text
    }

  private fun entry(
    context: Context,
    title: String,
    model: LocalModel,
    bytes: Long,
    isUnfinished: Boolean,
  ) =
    ModelPreference(
      context = context,
      onPrimaryClick = {
        if (isUnfinished) {
          manager.startDownload(model)
        }
      },
      actionsProvider = {
        buildList {
          if (isUnfinished) {
            add(PreferenceCustomActions.Entry(context.getString(R.string.manage_models_download)) {
              manager.startDownload(model)
            })
          }
          add(PreferenceCustomActions.Entry(context.getString(R.string.manage_models_delete)) {
            confirmDelete(model, bytes)
          })
        }
      },
    ).apply {
      this.title = title
      summary = Formatter.formatShortFileSize(context, bytes)
    }

  private class ModelPreference(
    context: Context,
    private val onPrimaryClick: () -> Unit,
    private val actionsProvider: () -> List<PreferenceCustomActions.Entry>,
  ) : Preference(context) {
    init {
      isPersistent = false
      layoutResource = R.layout.listitem_2texts
      setOnPreferenceClickListener {
        onPrimaryClick()
        true
      }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      PreferenceCustomActions.bind(holder.itemView, title, actionsProvider())
    }
  }

  private fun confirmDelete(model: LocalModel, freedBytes: Long) {
    val context = requireContext()
    AlertDialog.Builder(context)
      .setTitle(getString(R.string.manage_models_delete_title, model.name))
      .setMessage(
        getString(
          R.string.manage_models_delete_message,
          Formatter.formatShortFileSize(context, freedBytes),
        )
      )
      .setPositiveButton(R.string.manage_models_delete) { _, _ ->
        manager.delete(model)
        Toast.makeText(
            context,
            getString(R.string.manage_models_deleted, model.name),
            Toast.LENGTH_SHORT,
          )
          .show()
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }
}
