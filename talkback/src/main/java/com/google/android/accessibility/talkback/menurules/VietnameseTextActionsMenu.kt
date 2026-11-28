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

package com.google.android.accessibility.talkback.menurules

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.talkback.vietnameseime.VietnameseOnnxSpellCorrector
import com.google.android.accessibility.talkback.vietnameseime.VietnameseSpellModelManager
import java.util.concurrent.Executors
import android.view.Menu
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.contextmenu.ContextMenu
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem.DeferredType
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.vietnameseime.VietnameseLexicon
import com.google.android.accessibility.talkback.vietnameseime.VietnameseSpellChecker
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.Role
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.TextCursorTracker.NO_POSITION

class VietnameseTextActionsMenu(
  private val pipeline: Pipeline.FeedbackReturner,
  private val actorState: ActorState,
  private val accessibilityFocusMonitor: AccessibilityFocusMonitor,
) : NodeMenu {

  override fun accept(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
    if (node == null) return false
    if (!isWordSuggestionsEnabled(context) && !isSpellCheckEnabled(context)) return false
    val editingNode = resolveEditingNode(node) ?: return false
    if (!isEligibleEditNode(editingNode)) return false
    return getMenuItemsForNode(context, node, includeAncestors = false).isNotEmpty()
  }

  override fun getMenuItemsForNode(
    context: Context,
    node: AccessibilityNodeInfoCompat?,
    includeAncestors: Boolean,
  ): MutableList<ContextMenuItem> {
    val items = mutableListOf<ContextMenuItem>()
    if (node == null) return items
    val suggestionsOn = isWordSuggestionsEnabled(context)
    val spellCheckOn = isSpellCheckEnabled(context)
    if (!suggestionsOn && !spellCheckOn) return items

    val editingNode = resolveEditingNode(node) ?: return items
    if (!isEligibleEditNode(editingNode)) return items

    val text = extractEditableText(editingNode)
    val cursor = resolveCursorPosition(editingNode, text)

    if (spellCheckOn && text.isNotEmpty()) {
      val errorSpan = VietnameseSpellChecker.findErrorAtCursor(context, text, cursor)
      if (errorSpan != null) {
        val fixes = VietnameseSpellChecker.suggestCorrections(
          context = context,
          misspelledWord = errorSpan.word,
          previousWord = errorSpan.previousWord,
          maxResults = MAX_SUGGESTIONS,
          nextWord = errorSpan.nextWord,
          preferredFix = errorSpan.preferredFix,
        )
        val topFix = fixes.firstOrNull()
        for (fix in fixes) {
          val title = context.getString(R.string.vietnamese_ime_action_fix_spelling, fix)
          val item = ContextMenu.createMenuItem(
            context,
            Menu.NONE,
            R.id.edittext_breakout_vietnamese_suggestion,
            Menu.NONE,
            title,
          )
          item.setOnMenuItemClickListener {
            val applied = replaceSpanInNode(editingNode, text, errorSpan.start, errorSpan.end, fix, appendSpace = false)
            if (applied) {
              VietnameseLexicon.recordChosenSuggestion(context, errorSpan.previousWord, fix, topFix)
              speakFeedback(context.getString(R.string.vietnamese_ime_action_replaced_with, fix))
            } else {
              pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.sound(R.raw.complete))
            }
            true
          }
          item.setSkipRefocusEvents(true)
          item.setSkipWindowEvents(true)
          item.setDeferredType(DeferredType.ACCESSIBILITY_FOCUS_RECEIVED)
          items += item
        }
      }
    }

    if (suggestionsOn) {
      val ctx = extractCursorContext(text, cursor)
      val suggestions = VietnameseLexicon.suggestWords(
        context = context,
        previousWord = ctx.previousWord,
        currentPrefix = ctx.currentWord,
        maxResults = MAX_SUGGESTIONS,
      )
      val topCandidate = suggestions.firstOrNull()
      for (suggestion in suggestions) {
        val displaySuggestion = if (ctx.capitalize) {
          suggestion.replaceFirstChar { it.uppercaseChar() }
        } else {
          suggestion
        }
        val title = context.getString(R.string.vietnamese_ime_action_word_suggestion, displaySuggestion)
        val item = ContextMenu.createMenuItem(
          context,
          Menu.NONE,
          R.id.edittext_breakout_vietnamese_suggestion,
          Menu.NONE,
          title,
        )
        item.setOnMenuItemClickListener {
          val appendSpace = ctx.replaceEnd == text.length || !text[ctx.replaceEnd].isWhitespace()
          val applied = replaceSpanInNode(
            node = editingNode,
            originalText = text,
            start = ctx.replaceStart,
            end = ctx.replaceEnd,
            replacement = displaySuggestion,
            appendSpace = appendSpace,
          )
          if (applied) {
            VietnameseLexicon.recordChosenSuggestion(
              context = context,
              previousWord = ctx.previousWord,
              chosenWord = suggestion,
              rejectedTopWord = topCandidate,
            )
            speakFeedback(displaySuggestion)
          } else {
            pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.sound(R.raw.complete))
          }
          true
        }
        item.setSkipRefocusEvents(true)
        item.setSkipWindowEvents(true)
        item.setDeferredType(DeferredType.ACCESSIBILITY_FOCUS_RECEIVED)
        items += item
      }
    }

    return items
  }

  private fun resolveEditingNode(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
    val fromKeyboard = accessibilityFocusMonitor.getEditingNodeFromFocusedKeyboard(node)
    if (fromKeyboard != null) return fromKeyboard
    return if (Role.getRole(node) == Role.ROLE_EDIT_TEXT) node else null
  }

  private fun isEligibleEditNode(node: AccessibilityNodeInfoCompat): Boolean =
    Role.getRole(node) == Role.ROLE_EDIT_TEXT &&
      node.isEditable &&
      node.isEnabled &&
      !node.isPassword

  private fun resolveCursorPosition(node: AccessibilityNodeInfoCompat, text: String): Int {
    val nodeEnd = node.textSelectionEnd
    if (nodeEnd in 0..text.length) return nodeEnd
    val tracked = actorState.editState.currentCursorPosition
    if (tracked != NO_POSITION && tracked in 0..text.length) return tracked
    return text.length
  }

  private fun speakFeedback(text: String) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text))
  }

  internal data class CursorContext(
    val previousWord: String?,
    val currentWord: String,
    val replaceStart: Int,
    val replaceEnd: Int,
    val capitalize: Boolean,
  )

  companion object {
    private const val MAX_SUGGESTIONS = 4
    private val aiSpellExecutor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "ViSpellFullCheck").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isWordSuggestionsEnabled(context: Context): Boolean =
      SharedPreferencesUtils.getSharedPreferences(context).getBoolean(
        context.getString(R.string.pref_vietnamese_keyboard_word_suggestions_key),
        false,
      )

    @JvmStatic
    fun isSpellCheckEnabled(context: Context): Boolean =
      SharedPreferencesUtils.getSharedPreferences(context).getBoolean(
        context.getString(R.string.pref_vietnamese_keyboard_spell_check_key),
        false,
      )

    @JvmStatic
    fun navigateToSpellingError(
      context: Context,
      node: AccessibilityNodeInfoCompat,
      pipeline: Pipeline.FeedbackReturner,
      forward: Boolean,
    ): Boolean {
      if (!isSpellCheckEnabled(context)) return false
      val text = extractEditableText(node)
      if (text.isEmpty()) {
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(context.getString(R.string.vietnamese_ime_no_spelling_errors)),
        )
        return true
      }
      val cursor = node.textSelectionEnd.takeIf { it in 0..text.length } ?: if (forward) 0 else text.length
      val error = if (forward) {
        VietnameseSpellChecker.findNextError(context, text, cursor)
      } else {
        VietnameseSpellChecker.findPreviousError(context, text, cursor)
      }
      if (error == null) {
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(context.getString(R.string.vietnamese_ime_no_spelling_errors)),
        )
        return true
      }
      if (!node.isFocused) {
        node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
      }
      val args = Bundle().apply {
        putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, error.start)
        putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, error.end)
      }
      node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, args)

      val topFix = error.preferredFix ?: VietnameseSpellChecker.suggestCorrections(
        context = context,
        misspelledWord = error.word,
        previousWord = error.previousWord,
        maxResults = 1,
        nextWord = error.nextWord,
      ).firstOrNull()
      val announcement = if (topFix != null) {
        context.getString(R.string.vietnamese_ime_spelling_error_with_fix, error.word, topFix)
      } else {
        context.getString(R.string.vietnamese_ime_spelling_error_found, error.word)
      }
      pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(announcement))
      return true
    }

    /**
     * Checks and corrects spelling across the entire editable field. Uses the downloaded
     * offline AI ONNX model when available, and falls back to Tier-1 offline dictionary +
     * phonetic + bigram correction otherwise.
     */
    @JvmStatic
    fun checkSpellingFullField(
      context: Context,
      node: AccessibilityNodeInfoCompat?,
      pipeline: Pipeline.FeedbackReturner,
    ): Boolean {
      if (node == null || !node.isEditable || !node.isEnabled || node.isPassword) return false
      val text = extractEditableText(node)
      if (text.isBlank()) {
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(context.getString(R.string.vietnamese_ime_no_spelling_errors)),
        )
        return true
      }
      val app = context.applicationContext
      if (VietnameseSpellModelManager.isReady(app)) {
        val nodeCopy = AccessibilityNodeInfoCompat.obtain(node)
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(app.getString(R.string.vietnamese_ime_checking_spelling_ai)),
        )
        aiSpellExecutor.execute {
          val aiResult = VietnameseOnnxSpellCorrector.correctFullText(app, text)
          mainHandler.post {
            try {
              if (!nodeCopy.refresh() || !nodeCopy.isEditable || !nodeCopy.isEnabled ||
                nodeCopy.isPassword || extractEditableText(nodeCopy) != text) {
                pipeline.returnFeedback(EVENT_ID_UNTRACKED,
                  Feedback.speech(app.getString(R.string.vietnamese_ime_ai_spell_input_changed)))
                return@post
              }
              if (aiResult != null) {
                if (aiResult.changedWords > 0 && aiResult.correctedText != text) {
                  val ok = applyFullTextReplacement(nodeCopy, aiResult.correctedText)
                  if (ok) {
                    pipeline.returnFeedback(
                      EVENT_ID_UNTRACKED,
                      Feedback.speech(
                        app.getString(
                          R.string.vietnamese_ime_full_check_fixed_ai,
                          aiResult.changedWords,
                          aiResult.correctedText,
                        )
                      ),
                    )
                  } else {
                    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.sound(R.raw.complete))
                  }
                } else {
                  pipeline.returnFeedback(
                    EVENT_ID_UNTRACKED,
                    Feedback.speech(app.getString(R.string.vietnamese_ime_no_spelling_errors)),
                  )
                }
              } else {
                runOfflineFullFieldPass(app, nodeCopy, text, pipeline)
              }
            } finally {
              nodeCopy.recycle()
            }
          }
        }
        return true
      }
      runOfflineFullFieldPass(app, node, text, pipeline)
      return true
    }

    private fun runOfflineFullFieldPass(
      context: Context,
      node: AccessibilityNodeInfoCompat,
      text: String,
      pipeline: Pipeline.FeedbackReturner,
    ) {
      val (corrected, fixedCount) = VietnameseSpellChecker.correctFullTextOffline(context, text)
      if (fixedCount > 0 && corrected != text) {
        val ok = applyFullTextReplacement(node, corrected)
        if (ok) {
          pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.speech(context.getString(R.string.vietnamese_ime_full_check_fixed_offline, fixedCount)),
          )
        } else {
          pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.sound(R.raw.complete))
        }
      } else {
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(context.getString(R.string.vietnamese_ime_full_check_clean_offline)),
        )
      }
    }

    private fun applyFullTextReplacement(
      node: AccessibilityNodeInfoCompat,
      updatedText: String,
    ): Boolean {
      node.refresh()
      if (!node.isFocused) {
        node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
      }
      val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updatedText)
      }
      val ok = node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, args)
      if (ok) {
        val selArgs = Bundle().apply {
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, updatedText.length)
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, updatedText.length)
        }
        node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, selArgs)
      }
      return ok
    }

    internal fun extractEditableText(node: AccessibilityNodeInfoCompat): String {
      if (node.isShowingHintText || AccessibilityNodeInfoUtils.isEmptyEditTextRegardlessOfHint(node)) {
        return ""
      }
      return AccessibilityNodeInfoUtils.getText(node)?.toString().orEmpty()
    }

    internal fun extractCursorContext(text: String, cursorPos: Int): CursorContext {
      val pos = cursorPos.coerceIn(0, text.length)
      var wordStart = pos
      while (wordStart > 0 && text[wordStart - 1].isLetter()) {
        wordStart--
      }
      var wordEnd = pos
      while (wordEnd < text.length && text[wordEnd].isLetter()) {
        wordEnd++
      }
      val currentWord = text.substring(wordStart, wordEnd)
      var prevEnd = wordStart
      while (prevEnd > 0 && !text[prevEnd - 1].isLetter()) {
        prevEnd--
      }
      var prevStart = prevEnd
      while (prevStart > 0 && text[prevStart - 1].isLetter()) {
        prevStart--
      }
      val previousWord = text.substring(prevStart, prevEnd).takeIf { it.isNotEmpty() }
      val capitalize = currentWord.firstOrNull()?.isUpperCase() == true
      return CursorContext(
        previousWord = previousWord,
        currentWord = currentWord,
        replaceStart = wordStart,
        replaceEnd = wordEnd,
        capitalize = capitalize,
      )
    }

    private fun replaceSpanInNode(
      node: AccessibilityNodeInfoCompat,
      originalText: String,
      start: Int,
      end: Int,
      replacement: String,
      appendSpace: Boolean,
    ): Boolean {
      if (!node.isFocused) {
        node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
      }
      val safeStart = start.coerceIn(0, originalText.length)
      val safeEnd = end.coerceIn(safeStart, originalText.length)
      val inserted = if (appendSpace) "$replacement " else replacement
      val updated = buildString(originalText.length + inserted.length) {
        append(originalText, 0, safeStart)
        append(inserted)
        append(originalText, safeEnd, originalText.length)
      }
      val setTextArgs = Bundle().apply {
        putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
      }
      val ok = node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, setTextArgs)
      if (ok) {
        val newCursor = safeStart + inserted.length
        val selArgs = Bundle().apply {
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
        node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, selArgs)
      }
      return ok
    }
  }
}
