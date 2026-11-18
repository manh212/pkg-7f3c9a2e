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

package com.google.android.accessibility.talkback.gesture

import android.text.InputType
import android.widget.EditText
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.accessibility.utils.widget.DialogUtils

/**
 * The dialog that lets the user type the exact value a slider should go to. It opens
 * instead of toggling playback when the play/pause gesture is performed while the
 * accessibility focus is on a slider. The dialog is deliberately simple: a title, one
 * number field, and Cancel and OK. OK sets the slider to the typed percent (clamped to
 * 0-100); an empty or non-numeric entry changes nothing.
 */
object SliderValueDialog {

  @JvmStatic
  fun show(service: TalkBackService) {
    val input =
      EditText(service).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = service.getString(R.string.slider_value_dialog_title)
      }
    val dialog =
      A11yAlertDialogWrapper.materialDialogBuilder(service)
        .setTitle(R.string.slider_value_dialog_title)
        .setView(input)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          input.text.toString().trim().toIntOrNull()?.let { service.setSliderToPercent(it) }
        }
        .setCancelable(true)
        .create()
    service.registerDialog(dialog, /* hasEditText= */ true)
    dialog.setOnDismissListener { service.unregisterDialog(dialog) }
    DialogUtils.setWindowTypeToDialog(dialog.window)
    dialog.show()
    input.requestFocus()
  }
}
