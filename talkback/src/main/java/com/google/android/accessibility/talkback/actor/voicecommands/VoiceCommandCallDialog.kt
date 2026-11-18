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

package com.google.android.accessibility.talkback.actor.voicecommands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R

/**
 * The confirmation a spoken phone number is shown in before anything is dialled: the
 * number, grouped for reading, with a call button and a cancel button. The call is
 * placed only from the button, never from the voice command itself.
 */
object VoiceCommandCallDialog {

  /**
   * Shows the confirmation for [number]. [onDialFallback] runs when the call permission
   * is missing and the dialler was opened with the number filled in instead, so the
   * caller can say why no call started.
   */
  @JvmStatic
  fun show(context: Context, number: String, onDialFallback: () -> Unit) {
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(R.string.voice_call_confirm_title)
        .setMessage(VoiceCommandPhoneNumbers.grouped(number))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.voice_call_action) { _, _ ->
          placeCall(context, number, onDialFallback)
        }
        .create()
    showOverlay(dialog)
  }

  /**
   * Places the call to [number] when the call permission is held; without it, opens the
   * dialler with the number filled in, for the user to press the call button there.
   */
  private fun placeCall(context: Context, number: String, onDialFallback: () -> Unit) {
    val uri = Uri.parse("tel:" + Uri.encode(number))
    val granted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED
    val intent =
      Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    if (!granted) {
      onDialFallback()
    }
  }

  /**
   * Shows a dialog as an accessibility overlay. The dialog is opened from the
   * accessibility service, whose context has no window token: a plain show() throws
   * BadTokenException and kills the process, so the overlay type is set first, exactly
   * as the countdown alarm dialogs do.
   */
  private fun showOverlay(dialog: AlertDialog) {
    dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    dialog.show()
  }
}
