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

package com.google.android.accessibility.talkback.granularediting

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.icu.text.ListFormatter
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LocaleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.AiTextRequests
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandEditor
import com.google.android.accessibility.talkback.textcorrection.CorrectionPrompt
import com.google.android.accessibility.talkback.texteditor.EditorDocuments
import com.google.android.accessibility.talkback.texteditor.TextFileFormat
import com.google.android.accessibility.talkback.translation.AutomaticTranslation
import com.google.android.accessibility.talkback.translation.Translation
import com.google.android.accessibility.talkback.translation.TranslationFailure
import com.google.android.accessibility.talkback.translation.TranslationFailure.Kind
import com.google.android.accessibility.talkback.translation.TranslationLanguages
import com.google.android.accessibility.talkback.translation.TranslationService
import com.google.android.accessibility.talkback.translation.TranslationSettings
import com.google.android.accessibility.talkback.translation.Translator
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import java.io.IOException
import java.util.Locale
import java.util.function.Consumer
import org.json.JSONObject

/**
 * The granular editing screen: the text as a list of pieces, split by characters, words,
 * sentences, lines or paragraphs. Double tapping a piece edits it, and its actions insert text
 * before or after it, delete it or select it. Selected pieces can be copied, cut or deleted
 * together. Save writes the text back to the text field it came from. Opened on one of the
 * user's own documents instead, the screen shows the document's name and saves the document
 * as its text changes, with no Save button.
 */
class GranularEditingActivity : AppCompatActivity() {
  private var sessionId = -1
  private var documentId: String? = null
  private var promptFields: List<EditText> = emptyList()
  private var commitNestedInput: (() -> Unit)? = null
  private var text = ""
  private var granularity = Granularity.WORDS
  private var pieces: List<Piece> = emptyList()
  private val selected = HashSet<Int>()
  private val undo = ArrayList<String>()
  private val redo = ArrayList<String>()
  private var lastFind = ""
  private var translator: Translator? = null
  private var aiRequests: AiTextRequests? = null

  /** One system file picker per file type, each remembering the type it saves. */
  private val saveFileLaunchers: Map<TextFileFormat, ActivityResultLauncher<String>> =
    TextFileFormat.values().associateWith { format ->
      registerForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
        val fileName = pendingSaveFileName
        pendingSaveFileName = null
        if (uri != null && fileName != null) {
          writeExportFile(uri, format, fileName)
        }
      }
    }

  /** The file name the open file picker will save, set just before launching it. */
  private var pendingSaveFileName: String? = null

  /** The styles Rewrite offers, the same wordings as the text correction screen's. */
  private val rewriteStyles =
    listOf(
      R.string.correction_professional to "Make the wording professional.",
      R.string.correction_friendly to "Make the wording friendly and warm.",
      R.string.correction_formal to "Make the wording formal and respectful.",
      R.string.correction_simple to "Use simple, easy-to-understand wording.",
      R.string.correction_shorter to "Shorten the wording while keeping its essential information.",
      R.string.correction_longer to "Expand the wording without adding facts or new ideas.",
      R.string.correction_spelling to
        "Correct spelling, punctuation and recognition errors only; keep the writing style.",
      R.string.correction_coherent to "Improve coherence and sentence order, and remove repetition.",
      R.string.correction_custom to "",
    )

  private lateinit var list: RecyclerView
  private lateinit var splitBy: Button
  private lateinit var empty: TextView
  private lateinit var heading: TextView
  private val adapter = PieceAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTitle(R.string.granular_editing_title)
    sessionId = intent.getIntExtra(GranularEditing.EXTRA_SESSION_ID, -1)
    val initial = GranularEditing.initialText(sessionId)
    if (initial == null) {
      finish()
      return
    }
    GranularEditing.registerActivity(sessionId, this)
    documentId = GranularEditing.documentId(sessionId)
    text = savedInstanceState?.getString(STATE_TEXT) ?: initial
    savedInstanceState?.getString(STATE_GRANULARITY)?.let { granularity = Granularity.valueOf(it) }

    heading =
      TextView(this).apply {
        setText(R.string.granular_editing_title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(dp(16), dp(12), dp(16), dp(4))
        ViewCompat.setAccessibilityHeading(this, true)
      }
    if (documentId != null) {
      val name = documentName()
      heading.text = name
      setTitle(name)
      heading.isClickable = true
      heading.setOnClickListener { renameDocument() }
      ViewCompat.replaceAccessibilityAction(
        heading,
        AccessibilityActionCompat.ACTION_CLICK,
        getString(R.string.text_editor_rename),
        null,
      )
    }
    val title = heading
    splitBy = button("") { chooseGranularity() }
    empty =
      TextView(this).apply {
        setText(R.string.granular_editing_empty)
        setPadding(dp(16), dp(16), dp(16), dp(16))
      }
    list =
      RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@GranularEditingActivity)
        adapter = this@GranularEditingActivity.adapter
      }
    val buttons =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        if (GranularEditing.isEditable(sessionId)) addView(button(R.string.granular_editing_save) { save() }, weight())
        addView(button(android.R.string.copy) { copy() }, weight())
        addView(button(R.string.granular_editing_more) { showMore(it) }, weight())
      }
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(splitBy)
        addView(empty)
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(buttons)
      }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars =
        insets.getInsets(
          WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()
        )
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }
    setContentView(root)
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
      },
    )
    refresh()
  }

  override fun onResume() {
    super.onResume()
    GranularEditing.editorResumed(this)
    for (result in GranularEditing.takeChildResults(sessionId)) {
      val field = promptFields.getOrNull(result.fieldIndex)
      if (field != null) {
        field.setText(result.text)
        field.setSelection(result.text.length)
        // An edit made inside a single-input prompt returns to the parent editor's
        // text list immediately. The parent Save button still controls the final write.
        commitNestedInput?.invoke()
      } else {
        // If Android recreated the parent's dialog, preserve the user's changes.
        GranularEditing.copy(this, result.text)
        Toast.makeText(this, R.string.granular_editing_not_saved, Toast.LENGTH_LONG).show()
      }
    }
  }

  override fun onPause() {
    GranularEditing.editorPaused(this)
    super.onPause()
  }

  override fun onDestroy() {
    translator?.shutdown()
    translator = null
    aiRequests?.cancel()
    aiRequests = null
    GranularEditing.unregisterActivity(sessionId, this)
    if (isFinishing) GranularEditing.close(sessionId)
    super.onDestroy()
  }

  /** Finds the dialog input that the user invoked nested editing on. */
  internal fun nestedFieldIndex(node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat): Int? {
    if (node.packageName?.toString() != packageName || !node.isEditable) return null
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    if (bounds.isEmpty) return null
    for ((index, field) in promptFields.withIndex()) {
      if (!field.isAttachedToWindow || !field.isShown) continue
      val xy = IntArray(2)
      field.getLocationOnScreen(xy)
      val fieldBounds = Rect(xy[0], xy[1], xy[0] + field.width, xy[1] + field.height)
      if (Rect.intersects(bounds, fieldBounds) &&
          bounds.centerX() in fieldBounds.left..fieldBounds.right &&
          bounds.centerY() in fieldBounds.top..fieldBounds.bottom) {
        return index
      }
    }
    return null
  }

  private fun trackDialogInputs(
    dialog: AlertDialog,
    fields: List<EditText>,
    onNestedSave: (() -> Unit)? = null,
  ) {
    promptFields = fields
    commitNestedInput = onNestedSave
    dialog.setOnDismissListener {
      if (promptFields === fields) {
        promptFields = emptyList()
        commitNestedInput = null
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_TEXT, text)
    outState.putString(STATE_GRANULARITY, granularity.name)
  }

  /** Splits the text again and shows it, with no piece selected. */
  private fun refresh() {
    pieces = TextPieces.split(text, granularity)
    selected.clear()
    splitBy.text = getString(R.string.granular_editing_split_by, getString(nameOf(granularity)))
    empty.visibility = if (pieces.isEmpty()) View.VISIBLE else View.GONE
    adapter.notifyDataSetChanged()
  }

  /** Makes [newText] the text, keeping the old one for Undo, and focuses the piece at [focusAt]. */
  private fun change(newText: String, focusAt: Int, @StringRes announcement: Int? = null) {
    if (newText == text) return
    undo += text
    if (undo.size > MAX_UNDO) undo.removeAt(0)
    redo.clear()
    text = newText
    refresh()
    focus(focusAt)
    persistDocument()
    announcement?.let { list.announceForAccessibility(getString(it)) }
  }

  /** A document is saved as its text changes; a text field is only written back by Save. */
  private fun persistDocument() {
    documentId?.let { EditorDocuments.updateContent(it, text) }
  }

  private fun focus(index: Int) {
    if (pieces.isEmpty()) return
    val position = index.coerceIn(0, pieces.size - 1)
    list.scrollToPosition(position)
    list.post {
      list
        .findViewHolderForAdapterPosition(position)
        ?.itemView
        ?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }
  }

  private fun chooseGranularity() {
    val values = Granularity.values()
    AlertDialog.Builder(this)
      .setTitle(R.string.granular_editing_split_by_title)
      .setSingleChoiceItems(
        values.map { getString(nameOf(it)) }.toTypedArray(),
        values.indexOf(granularity),
      ) { dialog, which ->
        granularity = values[which]
        refresh()
        dialog.dismiss()
        focus(0)
      }
      .show()
  }

  private fun edit(index: Int) {
    val piece = pieces.getOrNull(index) ?: return
    ask(R.string.granular_editing_edit, text.substring(piece.coreStart, piece.coreEnd)) { typed ->
      if (typed.isEmpty()) {
        change(TextPieces.delete(text, listOf(piece)), index, R.string.granular_editing_deleted)
      } else {
        change(TextPieces.replace(text, piece, typed), index)
      }
    }
  }

  private fun insert(index: Int, after: Boolean) {
    val piece = pieces.getOrNull(index) ?: return
    ask(if (after) R.string.granular_editing_insert_after else R.string.granular_editing_insert_before, "") { typed ->
      if (typed.isEmpty()) return@ask
      if (after) {
        change(TextPieces.insertAfter(text, piece, typed, granularity), index + 1)
      } else {
        change(TextPieces.insertBefore(text, piece, typed, granularity), index)
      }
    }
  }

  private fun addAtEnd() {
    ask(R.string.granular_editing_add_at_end, "") { typed ->
      if (typed.isNotEmpty()) change(TextPieces.append(text, typed, granularity), Int.MAX_VALUE)
    }
  }

  private fun delete(indexes: Collection<Int>) {
    val chosen = indexes.mapNotNull { pieces.getOrNull(it) }
    if (chosen.isEmpty()) return
    change(TextPieces.delete(text, chosen), indexes.min(), R.string.granular_editing_deleted)
  }

  private fun toggleSelected(index: Int) {
    if (!selected.remove(index)) selected += index
    adapter.notifyItemChanged(index)
  }

  /** Copies the selected pieces, or all of the text when none is selected. */
  private fun copy() {
    val copied = if (selected.isEmpty()) text else TextPieces.join(text, selected.map { pieces[it] })
    GranularEditing.copy(this, copied)
    list.announceForAccessibility(getString(R.string.granular_editing_copied))
  }

  private fun cut() {
    if (selected.isEmpty()) return
    GranularEditing.copy(this, TextPieces.join(text, selected.map { pieces[it] }))
    delete(selected.toList())
  }

  /** Focuses the next piece holding [query], or announces that there is none. */
  private fun find(query: String, from: Int, forward: Boolean = true) {
    lastFind = query
    val index = findMatch(query, from, forward, includeFrom = false)
    if (index != null) {
      focus(index)
    } else {
      list.announceForAccessibility(getString(R.string.granular_editing_not_found))
    }
  }

  /**
   * The index of a piece holding [query], looking from [from] in one direction and wrapping
   * around the ends, or null when no piece holds it. [includeFrom] starts the search at [from]
   * itself instead of at the piece after it.
   */
  private fun findMatch(query: String, from: Int, forward: Boolean, includeFrom: Boolean): Int? {
    if (pieces.isEmpty()) return null
    val steps = if (includeFrom) 0 until pieces.size else 1..pieces.size
    for (step in steps) {
      val index = Math.floorMod(if (forward) from + step else from - step, pieces.size)
      val piece = pieces[index]
      if (text.substring(piece.coreStart, piece.coreEnd).contains(query, ignoreCase = true)) {
        return index
      }
    }
    return null
  }

  /** Replaces the first match at or after the focused piece, and says how many were replaced. */
  private fun replaceOne(query: String, replacement: String) {
    if (query.isEmpty()) return
    lastFind = query
    val target = findMatch(query, focusedIndex().coerceAtLeast(0), forward = true, includeFrom = true)
    val newText = target?.let { TextPieces.replaceFirst(text, pieces[it], query, replacement) }
    if (target == null || newText == null) {
      list.announceForAccessibility(getString(R.string.granular_editing_not_found))
      return
    }
    change(newText, target)
    list.announceForAccessibility(
      resources.getQuantityString(R.plurals.granular_editing_replaced, 1, 1)
    )
  }

  private fun focusedIndex(): Int {
    val focused = list.focusedChild ?: list.findFocus()
    val accessibilityFocused =
      (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isAccessibilityFocused }
    val view = accessibilityFocused ?: focused ?: return -1
    return list.getChildAdapterPosition(view)
  }

  private fun showReplace() {
    val find = editText(R.string.granular_editing_find, lastFind)
    val replacement = editText(R.string.granular_editing_replace_with, "")
    val fields =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(find)
        addView(replacement)
      }
    val dialog = AlertDialog.Builder(this)
      .setTitle(R.string.granular_editing_replace)
      .setView(fields)
      .setPositiveButton(R.string.granular_editing_replace_all) { _, _ ->
        val (newText, count) =
          TextPieces.replaceAll(text, find.text.toString(), replacement.text.toString())
        lastFind = find.text.toString()
        if (count > 0) change(newText, 0)
        list.announceForAccessibility(
          resources.getQuantityString(R.plurals.granular_editing_replaced, count, count)
        )
      }
      .setNeutralButton(R.string.granular_editing_replace_one) { _, _ ->
        replaceOne(find.text.toString(), replacement.text.toString())
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
    trackDialogInputs(dialog, listOf(find, replacement))
  }

  private fun undoLast() {
    val previous = undo.removeLastOrNull() ?: return
    redo += text
    text = previous
    refresh()
    focus(0)
    persistDocument()
  }

  private fun redoLast() {
    val next = redo.removeLastOrNull() ?: return
    undo += text
    text = next
    refresh()
    focus(0)
    persistDocument()
  }

  private fun showMore(anchor: View) {
    val menu = PopupMenu(this, anchor)
    val actions = ArrayList<() -> Unit>()
    fun add(@StringRes title: Int, action: () -> Unit) {
      menu.menu.add(0, actions.size, actions.size, title)
      actions += action
    }
    if (undo.isNotEmpty()) add(R.string.granular_editing_undo) { undoLast() }
    if (redo.isNotEmpty()) add(R.string.granular_editing_redo) { redoLast() }
    add(R.string.granular_editing_find) {
      ask(R.string.granular_editing_find, lastFind) { typed -> if (typed.isNotEmpty()) find(typed, focusedIndex()) }
    }
    if (lastFind.isNotEmpty()) {
      add(R.string.granular_editing_find_next) { find(lastFind, focusedIndex()) }
      add(R.string.granular_editing_find_previous) { find(lastFind, focusedIndex(), forward = false) }
    }
    add(R.string.granular_editing_replace) { showReplace() }
    add(R.string.granular_editing_translate) { translateTarget() }
    add(R.string.granular_editing_summarize) { summarizeTarget() }
    add(R.string.granular_editing_rewrite) { chooseRewriteStyle() }
    add(R.string.granular_editing_add_at_end) { addAtEnd() }
    if (pieces.isNotEmpty()) {
      add(android.R.string.selectAll) {
        selected.addAll(pieces.indices)
        adapter.notifyDataSetChanged()
      }
    }
    if (selected.isNotEmpty()) {
      add(R.string.granular_editing_clear_selection) {
        selected.clear()
        adapter.notifyDataSetChanged()
      }
      add(R.string.granular_editing_cut_selected) { cut() }
      add(R.string.granular_editing_delete_selected) { delete(selected.toList()) }
    }
    if (documentId != null) {
      add(R.string.text_editor_new_document) { newDocument() }
      add(R.string.text_editor_open_document) { showDocumentList() }
      add(R.string.text_editor_rename) { renameDocument() }
      add(R.string.text_editor_paste_from_clipboard) { pasteFromClipboard() }
      add(R.string.text_editor_share) { shareDocument() }
      add(R.string.text_editor_save_as_file) { saveDocumentAsFile() }
      add(R.string.text_editor_word_count) { showWordCount() }
      add(R.string.text_editor_delete_document) { confirmDeleteDocument() }
    }
    menu.setOnMenuItemClickListener { item ->
      actions[item.itemId]()
      true
    }
    menu.show()
  }

  /** Runs one spoken editor command, heard while this screen is in front of the user. */
  internal fun runVoiceCommand(command: VoiceCommandEditor.Command) {
    when (command) {
      VoiceCommandEditor.Command.SelectCurrent -> selectFocusedPiece()
      VoiceCommandEditor.Command.DeleteSelection -> deleteSelectionOrFocused()
      VoiceCommandEditor.Command.ReadCurrent -> readFocusedPiece()
      VoiceCommandEditor.Command.ReadAll -> readWholeText()
      VoiceCommandEditor.Command.Undo -> undoLast()
      VoiceCommandEditor.Command.Redo -> redoLast()
      is VoiceCommandEditor.Command.Find -> findByVoice(command.query)
    }
  }

  private fun selectFocusedPiece() {
    val index = focusedIndex()
    if (index !in pieces.indices) return
    if (selected.add(index)) adapter.notifyItemChanged(index)
    focus(index)
  }

  /** Deletes the selected pieces, or the piece in focus when none is selected. Undo brings them back. */
  private fun deleteSelectionOrFocused() {
    if (selected.isNotEmpty()) {
      delete(selected.toList())
      return
    }
    val index = focusedIndex()
    if (index in pieces.indices) delete(listOf(index))
  }

  private fun readFocusedPiece() {
    val piece = pieces.getOrNull(focusedIndex().coerceAtLeast(0)) ?: return
    list.announceForAccessibility(label(piece))
  }

  private fun readWholeText() {
    list.announceForAccessibility(
      if (text.isEmpty()) getString(R.string.granular_editing_empty) else text
    )
  }

  private fun findByVoice(query: String?) {
    when {
      query != null -> find(query, focusedIndex())
      lastFind.isNotEmpty() -> find(lastFind, focusedIndex())
      else -> list.announceForAccessibility(getString(R.string.granular_editing_voice_no_find))
    }
  }

  /** The range of text the AI actions work on: the selected pieces, or the piece in focus. */
  private fun targetRange(): Pair<Int, Int>? {
    if (selected.isNotEmpty()) {
      return TextPieces.span(selected.mapNotNull { pieces.getOrNull(it) })
    }
    val piece = pieces.getOrNull(focusedIndex().coerceAtLeast(0)) ?: return null
    return piece.coreStart to piece.coreEnd
  }

  private fun targetText(): String? = targetRange()?.let { (start, end) -> text.substring(start, end) }

  private fun aiRequests(): AiTextRequests =
    aiRequests
      ?: AiTextRequests(
          this,
          Consumer { message ->
            runOnUiThread {
              if (!isFinishing && !isDestroyed) list.announceForAccessibility(message)
            }
          },
        )
        .also { aiRequests = it }

  /** Translates the target text with the translation service, and reads it the way translations are read. */
  private fun translateTarget() {
    val target = targetText()
    if (target.isNullOrBlank()) {
      list.announceForAccessibility(getString(R.string.granular_editing_ai_nothing))
      return
    }
    val active = translator ?: Translator(this).also { translator = it }
    active.translate(target) { result ->
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        when (result) {
          is Translator.Result.Done -> speakTranslation(target, result.translation)
          is Translator.Result.Downloading ->
            list.announceForAccessibility(
              getString(R.string.translation_downloading, languageNames(result.languages))
            )
          is Translator.Result.Failed ->
            list.announceForAccessibility(translationFailureText(result.failure, result.service))
        }
      }
    }
  }

  /** Reads a translation aloud, honouring the bilingual, voice and copy translation settings. */
  private fun speakTranslation(source: String, translation: Translation) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(this)
    if (TranslationLanguages.same(translation.from, translation.to) && translation.text == source) {
      list.announceForAccessibility(
        getString(R.string.translation_already_in, TranslationLanguages.name(translation.to))
      )
      return
    }
    if (TranslationSettings.copy(prefs)) GranularEditing.copy(this, translation.text)
    val controller =
      if (TalkBackService.isServiceActive()) TalkBackService.getInstance()?.speechController else null
    if (controller == null) {
      list.announceForAccessibility(translation.text)
      return
    }
    val options =
      SpeechController.SpeakOptions.create()
        .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
        .setSpeechParams(AutomaticTranslation.untranslated())
    if (TranslationSettings.bilingual(prefs)) {
      controller.speak(
        TranslationSettings.bilingualSpeech(prefs, source, translation.text),
        EVENT_ID_UNTRACKED,
        options,
      )
      return
    }
    val translated = SpannableString(translation.text)
    if (TranslationSettings.targetVoice(prefs)) {
      translated.setSpan(
        LocaleSpan(Locale.forLanguageTag(translation.to)),
        0,
        translated.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }
    val speech = SpannableStringBuilder()
    val from = translation.from
    if (TranslationSettings.saySource(prefs) && from != null) {
      speech.append(getString(R.string.translation_from, TranslationLanguages.name(from))).append(' ')
    }
    speech.append(translated)
    controller.speak(speech, EVENT_ID_UNTRACKED, options)
  }

  private fun languageNames(languages: List<String>): String =
    ListFormatter.getInstance().format(languages.map { TranslationLanguages.name(it) })

  /** What a failed translation is announced as, the same wording as the translate commands'. */
  private fun translationFailureText(failure: TranslationFailure, service: TranslationService): String {
    val name = getString(service.label)
    return when (failure.kind) {
      Kind.NOT_SET_UP ->
        when {
          service == TranslationService.AI -> getString(R.string.ai_service_not_set_up)
          service.needsKey -> getString(R.string.translation_error_no_key, name)
          else -> getString(R.string.translation_error_no_address, name)
        }
      Kind.CONNECTION ->
        if (service == TranslationService.ON_DEVICE) getString(R.string.translation_error_download)
        else getString(R.string.translation_error_connection, name)
      Kind.API_KEY -> getString(R.string.translation_error_api_key, name)
      Kind.QUOTA -> getString(R.string.translation_error_quota, name)
      Kind.LANGUAGE ->
        getString(R.string.translation_error_language, name, TranslationLanguages.name(failure.detail))
      Kind.NOT_DETECTED -> getString(R.string.translation_error_not_detected)
      Kind.DOWNLOADING -> getString(R.string.translation_error_downloading)
      Kind.DETECTION_DOWNLOADING -> getString(R.string.translation_error_detection_downloading)
      Kind.OTHER ->
        when {
          service == TranslationService.AI && failure.detail.isNotEmpty() -> failure.detail
          failure.detail.isEmpty() -> getString(R.string.translation_error, name)
          else -> getString(R.string.translation_error_details, name, failure.detail)
        }
    }
  }

  /** Summarizes the selected pieces, or the whole text when none is selected, and shows the summary. */
  private fun summarizeTarget() {
    val target = if (selected.isNotEmpty()) targetText() else text
    if (target.isNullOrBlank()) {
      list.announceForAccessibility(getString(R.string.granular_editing_ai_nothing))
      return
    }
    if (!AiTextRequests.isSetUp(this)) {
      list.announceForAccessibility(getString(R.string.ai_service_not_set_up))
      return
    }
    list.announceForAccessibility(getString(R.string.granular_editing_ai_working))
    aiRequests().ask(summarizePrompt(target), json = false) { answer, failure ->
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (answer != null) {
          showSummary(answer)
        } else {
          list.announceForAccessibility(
            failure?.userMessage(this) ?: getString(R.string.granular_editing_ai_failed)
          )
        }
      }
    }
  }

  private fun showSummary(summary: String) {
    AlertDialog.Builder(this)
      .setTitle(R.string.granular_editing_summarize)
      .setMessage(summary)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun summarizePrompt(target: String): String =
    """Summarize the text in the JSON data below in its own language, keeping its key points, names, facts and numbers. Return only the summary, without commentary.
${JSONObject().put("source", target)}"""

  /** Asks which style to rewrite the target text in, the text correction screen's styles. */
  private fun chooseRewriteStyle() {
    val target = targetText()
    if (target.isNullOrBlank()) {
      list.announceForAccessibility(getString(R.string.granular_editing_ai_nothing))
      return
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.granular_editing_rewrite)
      .setItems(rewriteStyles.map { getString(it.first) }.toTypedArray()) { _, which ->
        val (label, instruction) = rewriteStyles[which]
        if (label == R.string.correction_custom) {
          ask(R.string.correction_instruction, "") { typed ->
            val custom = typed.trim()
            if (custom.isNotEmpty()) rewriteTarget(custom)
          }
        } else {
          rewriteTarget(instruction)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun rewriteTarget(instruction: String) {
    val target = targetText()
    if (target.isNullOrBlank()) {
      list.announceForAccessibility(getString(R.string.granular_editing_ai_nothing))
      return
    }
    if (!AiTextRequests.isSetUp(this)) {
      list.announceForAccessibility(getString(R.string.ai_service_not_set_up))
      return
    }
    list.announceForAccessibility(getString(R.string.correction_working))
    aiRequests().ask(CorrectionPrompt.build(target, instruction), json = true) { answer, failure ->
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        val result = answer?.let(CorrectionPrompt::result)
        if (result != null) {
          previewRewrite(target, instruction, result)
        } else {
          list.announceForAccessibility(
            failure?.userMessage(this) ?: getString(R.string.correction_failed)
          )
        }
      }
    }
  }

  /** Shows the rewritten text first; only Apply puts it into the text, where Undo can undo it. */
  private fun previewRewrite(expected: String, instruction: String, result: String) {
    AlertDialog.Builder(this)
      .setTitle(R.string.correction_preview)
      .setMessage(result)
      .setPositiveButton(R.string.correction_apply) { _, _ -> applyRewrite(expected, result) }
      .setNeutralButton(R.string.correction_retry) { _, _ -> rewriteTarget(instruction) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun applyRewrite(expected: String, result: String) {
    val range = targetRange() ?: return
    if (text.substring(range.first, range.second) != expected) {
      list.announceForAccessibility(getString(R.string.correction_changed))
      return
    }
    val focusAt = pieces.indexOfFirst { it.coreStart >= range.first }.coerceAtLeast(0)
    change(text.replaceRange(range.first, range.second, result), focusAt)
  }

  private fun save() {
    GranularEditing.save(this, sessionId, text)
    finish()
  }

  private fun documentName(): String =
    documentId?.let { EditorDocuments.get(it)?.name } ?: getString(R.string.granular_editing_title)

  private fun renameDocument() {
    val id = documentId ?: return
    val current = EditorDocuments.get(id)?.name ?: return
    ask(R.string.text_editor_rename, current) { typed ->
      val name = typed.trim()
      if (name.isNotEmpty() && name != current && EditorDocuments.rename(id, name)) {
        heading.text = name
        setTitle(name)
      }
    }
  }

  private fun newDocument() {
    val created = EditorDocuments.createDefault() ?: return
    switchToDocument(created.id)
  }

  private fun showDocumentList() {
    val current = documentId ?: return
    val others = EditorDocuments.documents().filter { it.id != current }
    if (others.isEmpty()) {
      list.announceForAccessibility(getString(R.string.text_editor_no_other_documents))
      return
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.text_editor_open_document)
      .setItems(others.map { it.name }.toTypedArray()) { _, which ->
        switchToDocument(others[which].id)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** Shows another document in this same screen, with a fresh Undo history of its own. */
  private fun switchToDocument(id: String) {
    if (id == documentId) return
    val newSession = GranularEditing.createDocumentSession(id) ?: return
    GranularEditing.unregisterActivity(sessionId, this)
    GranularEditing.close(sessionId)
    sessionId = newSession
    GranularEditing.registerActivity(sessionId, this)
    documentId = id
    text = GranularEditing.initialText(sessionId) ?: ""
    undo.clear()
    redo.clear()
    lastFind = ""
    val name = documentName()
    heading.text = name
    setTitle(name)
    refresh()
    focus(0)
  }

  private fun shareDocument() {
    val intent =
      Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(intent, getString(R.string.text_editor_share)))
  }

  /** Asks for a file name and a file type, then saves this document as a file of that type. */
  private fun saveDocumentAsFile() {
    val id = documentId ?: return
    val current = EditorDocuments.get(id)?.name ?: return
    val savedName = TextFileFormat.sanitizeFileName(current)
    val nameField = editText(R.string.text_editor_save_as_file_name, savedName)
    val typeSpinner =
      Spinner(this).apply {
        this.id = View.generateViewId()
        adapter =
          ArrayAdapter(
              this@GranularEditingActivity,
              android.R.layout.simple_spinner_item,
              TextFileFormat.values().map { getString(it.labelRes) },
            )
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        prompt = getString(R.string.text_editor_save_as_file_type)
      }
    val box =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(
          nameField,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
          ),
        )
        val typeLabel =
          TextView(this@GranularEditingActivity).apply {
            text = getString(R.string.text_editor_save_as_file_type)
            labelFor = typeSpinner.id
            setPadding(0, dp(12), 0, dp(4))
          }
        addView(
          typeLabel,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
          ),
        )
        addView(
          typeSpinner,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
          ),
        )
      }
    val confirm = {
      val name = nameField.text.toString().trim().ifEmpty { savedName }
      val format =
        TextFileFormat.values().getOrElse(typeSpinner.selectedItemPosition) { TextFileFormat.TXT }
      val fileName = TextFileFormat.fileName(name, format)
      pendingSaveFileName = fileName
      saveFileLaunchers[format]?.launch(fileName)
    }
    val dialog =
      AlertDialog.Builder(this)
        .setTitle(R.string.text_editor_save_as_file)
        .setView(box)
        .setPositiveButton(R.string.granular_editing_save) { _, _ -> confirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    trackDialogInputs(dialog, listOf(nameField)) {
      confirm()
      dialog.dismiss()
    }
    nameField.requestFocus()
    dialog.window?.setSoftInputMode(
      WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    nameField.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(nameField, InputMethodManager.SHOW_IMPLICIT) }
  }

  /** Writes the document's text as a file of [format] at [uri], on a background thread. */
  private fun writeExportFile(uri: Uri, format: TextFileFormat, fileName: String) {
    val currentText = text
    val title = fileName.substringBeforeLast('.')
    Thread {
      val written =
        try {
          contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(TextFileFormat.toBytes(currentText, format, title))
          } != null
        } catch (e: IOException) {
          false
        } catch (e: SecurityException) {
          false
        }
      runOnUiThread {
        list.announceForAccessibility(
          getString(
            if (written) R.string.text_editor_save_done else R.string.text_editor_save_failed,
            fileName,
          )
        )
      }
    }
      .start()
  }

  private fun pasteFromClipboard() {
    val clipboard = getSystemService(ClipboardManager::class.java)
    val clip = clipboard?.primaryClip
    val pasted =
      if (clip != null && clip.itemCount > 0) {
        clip.getItemAt(0)?.coerceToText(this)?.toString()
      } else {
        null
      }
    if (pasted.isNullOrEmpty()) {
      list.announceForAccessibility(getString(R.string.text_editor_empty_clipboard))
      return
    }
    if (text.isEmpty()) {
      change(pasted, 0)
      return
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.text_editor_paste_from_clipboard)
      .setItems(
        arrayOf(
          getString(R.string.text_editor_paste_replace),
          getString(R.string.granular_editing_add_at_end),
        )
      ) { _, which ->
        if (which == 0) {
          change(pasted, 0)
        } else {
          change(TextPieces.append(text, pasted, granularity), Int.MAX_VALUE)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showWordCount() {
    val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
    AlertDialog.Builder(this)
      .setTitle(R.string.text_editor_word_count)
      .setMessage(getString(R.string.text_editor_word_count_message, words, text.length))
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun confirmDeleteDocument() {
    val id = documentId ?: return
    AlertDialog.Builder(this)
      .setTitle(R.string.text_editor_delete_document)
      .setMessage(R.string.text_editor_delete_confirm)
      .setPositiveButton(R.string.granular_editing_delete) { _, _ ->
        if (!EditorDocuments.delete(id)) return@setPositiveButton
        val next = EditorDocuments.mostRecent() ?: EditorDocuments.createDefault()
        if (next == null) {
          finish()
        } else {
          switchToDocument(next.id)
          list.announceForAccessibility(getString(R.string.granular_editing_deleted))
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** Closes the screen, asking first when the text was changed. A document needs no asking. */
  private fun close() {
    if (documentId != null) {
      GranularEditing.close(sessionId)
      finish()
      return
    }
    if (text == GranularEditing.initialText(sessionId)) {
      GranularEditing.close(sessionId)
      finish()
      return
    }
    val dialog =
      AlertDialog.Builder(this)
        .setTitle(
          if (GranularEditing.isEditable(sessionId)) R.string.granular_editing_save_changes
          else R.string.granular_editing_copy_changes
        )
        .setNegativeButton(R.string.granular_editing_discard) { _, _ ->
          GranularEditing.close(sessionId)
          finish()
        }
        .setNeutralButton(R.string.granular_editing_keep_editing, null)
    if (GranularEditing.isEditable(sessionId)) {
      dialog.setPositiveButton(R.string.granular_editing_save) { _, _ -> save() }
    } else {
      dialog.setPositiveButton(android.R.string.copy) { _, _ ->
        GranularEditing.copy(this, text)
        finish()
      }
    }
    dialog.show()
  }

  /** Asks for text in a dialog titled [title], starting with [initial], and calls [onDone] with it. */
  private fun ask(@StringRes title: Int, initial: String, onDone: (String) -> Unit) {
    val field = editText(title, initial)
    val box =
      LinearLayout(this).apply {
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      }
    val dialog = AlertDialog.Builder(this)
      .setTitle(title)
      .setView(box)
      .setPositiveButton(android.R.string.ok) { _, _ -> onDone(field.text.toString()) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
    trackDialogInputs(dialog, listOf(field)) {
      onDone(field.text.toString())
      dialog.dismiss()
    }
    field.requestFocus()
    dialog.window?.setSoftInputMode(
      WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    field.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
  }

  private fun editText(@StringRes hint: Int, initial: String): EditText =
    KeyboardSafeEditText(this).apply {
      setHint(hint)
      // No automatic capitals: the text often goes in the middle of a sentence.
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      setText(initial)
      setSelection(initial.length)
    }

  /** What a piece is shown as: its text, or a name for white space, which has no text to read. */
  private fun label(piece: Piece): String {
    val shown = text.substring(piece.coreStart, piece.coreEnd)
    return when {
      shown == "\n" -> getString(R.string.granular_editing_new_line)
      shown == "\t" -> getString(R.string.granular_editing_tab)
      shown.isBlank() -> getString(R.string.granular_editing_space)
      else -> shown
    }
  }

  private inner class PieceAdapter : RecyclerView.Adapter<PieceHolder>() {
    override fun getItemCount(): Int = pieces.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PieceHolder =
      PieceHolder(
        TextView(parent.context).apply {
          layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          minHeight = dp(48)
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(16), dp(8), dp(16), dp(8))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
          isClickable = true
          isLongClickable = true
          isFocusable = true
          val outValue = TypedValue()
          context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
          setBackgroundResource(outValue.resourceId)
        }
      )

    override fun onBindViewHolder(holder: PieceHolder, position: Int) {
      val view = holder.itemView as TextView
      val isSelected = position in selected
      view.text = label(pieces[position])
      view.isSelected = isSelected
      ViewCompat.setStateDescription(
        view,
        if (isSelected) getString(R.string.granular_editing_selected) else null,
      )
      view.setOnClickListener { edit(holder.bindingAdapterPosition) }
      view.setOnLongClickListener {
        showPieceActions(holder.bindingAdapterPosition)
        true
      }
      ViewCompat.replaceAccessibilityAction(
        view,
        AccessibilityActionCompat.ACTION_CLICK,
        getString(R.string.granular_editing_edit),
        null,
      )
      ViewCompat.replaceAccessibilityAction(
        view,
        AccessibilityActionCompat.ACTION_LONG_CLICK,
        getString(R.string.granular_editing_more),
        null,
      )
      holder.actionIds.forEach { ViewCompat.removeAccessibilityAction(view, it) }
      holder.actionIds.clear()
      pieceActions(isSelected).forEach { (title, action) ->
        holder.actionIds +=
          ViewCompat.addAccessibilityAction(view, getString(title)) { _, _ ->
            action(holder.bindingAdapterPosition)
            true
          }
      }
    }
  }

  private class PieceHolder(view: View) : RecyclerView.ViewHolder(view) {
    val actionIds = ArrayList<Int>()
  }

  /** What can be done with a piece, besides editing it, which double tapping does. */
  private fun pieceActions(isSelected: Boolean): List<Pair<Int, (Int) -> Unit>> =
    listOf(
      (if (isSelected) R.string.granular_editing_unselect else R.string.granular_editing_select) to
        { index: Int -> toggleSelected(index) },
      R.string.granular_editing_insert_before to { index: Int -> insert(index, after = false) },
      R.string.granular_editing_insert_after to { index: Int -> insert(index, after = true) },
      R.string.granular_editing_delete to { index: Int -> delete(listOf(index)) },
    )

  private fun showPieceActions(index: Int) {
    if (index !in pieces.indices) return
    val actions = listOf(R.string.granular_editing_edit to { i: Int -> edit(i) }) + pieceActions(index in selected)
    AlertDialog.Builder(this)
      .setTitle(label(pieces[index]))
      .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second(index) }
      .show()
  }

  private fun button(@StringRes title: Int, onClick: (View) -> Unit): Button =
    button(getString(title), onClick)

  private fun button(title: String, onClick: (View) -> Unit): Button =
    Button(this).apply {
      text = title
      isAllCaps = false
      setOnClickListener { onClick(it) }
    }

  private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

  companion object {
    private const val STATE_TEXT = "text"
    private const val STATE_GRANULARITY = "granularity"
    private const val MAX_UNDO = 100

    @StringRes
    fun nameOf(granularity: Granularity): Int =
      when (granularity) {
        Granularity.CHARACTERS -> R.string.granular_editing_characters
        Granularity.WORDS -> R.string.granular_editing_words
        Granularity.SENTENCES -> R.string.granular_editing_sentences
        Granularity.LINES -> R.string.granular_editing_lines
        Granularity.PARAGRAPHS -> R.string.granular_editing_paragraphs
      }
  }
}
