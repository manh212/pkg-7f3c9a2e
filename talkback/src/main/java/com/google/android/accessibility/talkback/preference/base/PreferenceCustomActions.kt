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

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.material.preference.AccessibilitySuiteSwitchPreference

/**
 * Shared helper that binds secondary actions to both TalkBack swipe up/down
 * (Accessibility Custom Actions) and long-press menu on any view or preference row.
 */
object PreferenceCustomActions {
  private const val TAG_CUSTOM_ACTION_IDS = 0x7f0b7241

  data class Entry(
    val label: String,
    val onInvoke: () -> Unit,
  )

  @JvmStatic
  fun bind(view: View, dialogTitle: CharSequence?, actions: List<Entry>) {
    @Suppress("UNCHECKED_CAST")
    val previousIds = view.getTag(TAG_CUSTOM_ACTION_IDS) as? List<Int>
    if (previousIds != null) {
      for (id in previousIds) {
        ViewCompat.removeAccessibilityAction(view, id)
      }
      view.setTag(TAG_CUSTOM_ACTION_IDS, null)
    }

    if (actions.isEmpty()) {
      view.setOnLongClickListener(null)
      view.isLongClickable = false
      return
    }

    val registeredIds = ArrayList<Int>(actions.size)
    for (entry in actions) {
      registeredIds +=
        ViewCompat.addAccessibilityAction(view, entry.label) { _, _ ->
          entry.onInvoke()
          true
        }
    }
    view.setTag(TAG_CUSTOM_ACTION_IDS, registeredIds)

    view.isLongClickable = true
    view.setOnLongClickListener {
      if (actions.size == 1) {
        actions[0].onInvoke()
      } else {
        AlertDialog.Builder(view.context)
          .setTitle(dialogTitle)
          .setItems(actions.map { it.label }.toTypedArray()) { _, which ->
            actions[which].onInvoke()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      true
    }
  }
}

/** Preference row with TalkBack Custom Actions (swipe up/down) and matching long-press menu. */
open class ActionablePreference(
  context: Context,
  private val actionsProvider: () -> List<PreferenceCustomActions.Entry>,
) : AccessibilitySuitePreference(context) {
  init {
    isPersistent = false
    isIconSpaceReserved = false
  }

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    PreferenceCustomActions.bind(holder.itemView, title, actionsProvider())
  }
}

/** SwitchPreference with TalkBack Custom Actions (swipe up/down) and matching long-press menu. */
open class ActionableSwitchPreference(
  context: Context,
  private val actionsProvider: () -> List<PreferenceCustomActions.Entry>,
) : AccessibilitySuiteSwitchPreference(context) {
  init {
    isPersistent = false
    isIconSpaceReserved = false
  }

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    PreferenceCustomActions.bind(holder.itemView, title, actionsProvider())
  }
}
