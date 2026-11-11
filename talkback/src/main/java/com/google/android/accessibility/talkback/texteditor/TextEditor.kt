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

package com.google.android.accessibility.talkback.texteditor

import android.content.Context
import com.google.android.accessibility.talkback.granularediting.GranularEditing

/**
 * The standalone text editor: the granular editing screen opened on one of the user's own
 * documents instead of on another app's text field. Opening it goes back to the document the
 * user was writing last, or starts a new one when there is none.
 */
object TextEditor {

  @JvmStatic
  fun open(context: Context): Boolean {
    EditorDocuments.init(context.applicationContext.filesDir)
    val document = EditorDocuments.mostRecent() ?: EditorDocuments.createDefault() ?: return false
    return GranularEditing.openDocument(context, document.id)
  }
}
