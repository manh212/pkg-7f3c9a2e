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

package com.google.android.accessibility.talkback.scripting

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

object ItemInspector {
  /** A part of an item's details: a heading and its lines, each a name and a value. */
  data class Section(@StringRes val title: Int, val lines: List<Pair<String, String>>)

  /**
   * Opens a window with the details of [node], under a heading for each part. Returns what to say
   * instead when there is no item.
   */
  @JvmStatic
  fun show(context: Context, node: AccessibilityNodeInfoCompat?): String? {
    node ?: return context.getString(R.string.script_inspect_no_item)
    context.startActivity(ItemDetailsActivity.intent(context, report(context, node)))
    return null
  }

  /** Copies the details of [node] as text, and returns what to say. */
  @JvmStatic
  fun copy(context: Context, node: AccessibilityNodeInfoCompat?): String {
    node ?: return context.getString(R.string.script_inspect_no_item)
    context.copyToClipboard(
      context.getString(R.string.script_inspect_clip_label),
      plainText(context, report(context, node)),
    )
    return context.getString(R.string.item_details_copied_all)
  }

  /**
   * The details of [node]: the texts and item types it has, the items in it, the id and role
   * scripts match, and the app it is in. Parts with nothing in them are left out.
   */
  fun report(context: Context, node: AccessibilityNodeInfoCompat): List<Section> {
    val role = ScriptRoles.role(node)
    val script =
      lines(
        "id" to node.viewIdResourceName,
        "role" to role,
        "appRole" to ScriptRoles.appRole(node).takeIf { it != role },
        "className" to node.className,
        "rules" to Scripts.rule(node)?.sources?.joinToString("; "),
      ) +
        listOfNotNull(
          ("" to context.getString(R.string.item_details_no_id)).takeIf {
            node.viewIdResourceName == null
          }
        )
    return listOf(
        Section(R.string.item_details_texts, lines(*texts(node).toTypedArray())),
        Section(R.string.item_details_types, itemTypes(context, node).map { "" to it }),
        Section(R.string.item_details_inside, innerTree(node)),
        Section(R.string.item_details_script, script),
        Section(
          R.string.item_details_app,
          lines(
            "app" to node.packageName,
            "activity" to Scripts.currentActivity(),
            "window" to Scripts.currentWindowTitle(),
          ),
        ),
      )
      .filter { it.lines.isNotEmpty() }
  }

  /** [sections] as text: each heading, then its lines, with a blank line between parts. */
  fun plainText(context: Context, sections: List<Section>): String =
    sections.joinToString("\n\n") { section ->
      (listOf(context.getString(section.title)) + section.lines.map(::lineText)).joinToString("\n")
    }

  /** A line of a part: "name: value", or the value alone when it has no name. */
  fun lineText(line: Pair<String, String>): String =
    if (line.first.isEmpty()) line.second else "${line.first}: ${line.second}"

  private fun lines(vararg values: Pair<String, CharSequence?>): List<Pair<String, String>> =
    values.mapNotNull { (name, value) -> value?.toString()?.takeIf { it.isNotEmpty() }?.let { name to it } }

  /** The texts of [node]: its text, description, hint and state. */
  private fun texts(node: AccessibilityNodeInfoCompat): List<Pair<String, CharSequence?>> =
    listOf(
      "text" to node.text,
      "description" to node.contentDescription,
      "hint" to node.hintText,
      "state" to node.stateDescription,
    )

  /**
   * The item types [node] is, by their titles. Meridian has no macros package, so this is empty
   * and the Item types part is left out; the part is kept so a future macros port can fill it in.
   */
  @Suppress("UNUSED_PARAMETER")
  private fun itemTypes(context: Context, node: AccessibilityNodeInfoCompat): List<String> =
    emptyList()

  /**
   * The items in [node] in reading order, each under the item it is in: its level (1 for the items
   * directly in [node]), class, whether it can be clicked, and its texts. Items with no text that
   * can't be clicked are left out, but the items in them are listed.
   */
  private fun innerTree(node: AccessibilityNodeInfoCompat): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    var count = 0
    fun visit(item: AccessibilityNodeInfoCompat, level: Int) {
      for (child in item.childrenInReadingOrder()) {
        if (count >= MAX_TREE_ITEMS || lines.size >= MAX_TREE_LINES) return
        count++
        if (!child.isVisibleToUser) continue
        val texts = texts(child).mapNotNull { (name, value) -> value?.toString()?.takeIf { it.isNotEmpty() }?.let { "$name: $it" } }
        val clickable = listOfNotNull("clickable".takeIf { child.isClickable }, "longClickable".takeIf { child.isLongClickable })
        if (texts.isNotEmpty() || clickable.isNotEmpty()) {
          val parts = listOfNotNull(child.className?.toString()?.substringAfterLast('.')) + clickable + texts
          lines += "level $level" to parts.joinToString("; ")
        }
        if (level < MAX_TREE_LEVEL) visit(child, level + 1)
      }
    }
    visit(node, 1)
    return lines
  }

  /**
   * Copies every item on screen as JSON, then gives [done] what to say. Reading the screen asks the
   * app about each item in turn, so it happens off the main thread.
   */
  @JvmStatic
  fun copyScreenTree(context: Context, node: AccessibilityNodeInfoCompat?, done: Consumer<String>) {
    val main = Handler(Looper.getMainLooper())
    treeReader.execute {
      val message =
        try {
          readScreenTree(context, node)
        } catch (e: RuntimeException) {
          context.getString(R.string.script_tree_none)
        }
      main.post { done.accept(message) }
    }
  }

  private fun readScreenTree(context: Context, node: AccessibilityNodeInfoCompat?): String {
    val activeRoot = (context as? AccessibilityService)?.rootInActiveWindow
    val root =
      node?.let(AccessibilityNodeInfoUtils::getRoot)
        ?: activeRoot?.let(AccessibilityNodeInfoCompat::wrap)
        ?: return context.getString(R.string.script_tree_none)
    var count = 0
    fun describe(item: AccessibilityNodeInfoCompat, depth: Int): JSONObject =
      JSONObject().apply {
        count++
        putOpt("id", item.viewIdResourceName)
        putOpt("class", item.className?.toString()?.substringAfterLast('.'))
        putOpt("role", ScriptRoles.role(item).takeIf { it != "none" })
        putOpt("text", AccessibilityNodeInfoUtils.getText(item)?.toString())
        putOpt("description", item.contentDescription?.toString())
        putOpt("hint", item.hintText?.toString())
        putOpt("state", item.stateDescription?.toString())
        putList("flags", FLAGS.filter { (_, test) -> test(item) }.map { it.first })
        putList("actions", item.actionList.mapNotNull { it.label?.toString() })
        putList("rules", Scripts.rule(item)?.sources.orEmpty())
        if (depth < MAX_DEPTH) {
          val children = item.children().asSequence().takeWhile { count < MAX_ITEMS }
          putList("children", children.map { describe(it, depth + 1) }.toList())
        }
      }
    val tree =
      JSONObject()
        .putOpt("app", root.packageName?.toString())
        .putOpt("activity", Scripts.currentActivity())
        .putOpt("window", Scripts.currentWindowTitle())
        .put("root", describe(root, 0))
    context.copyToClipboard(context.getString(R.string.script_tree_clip_label), tree.toString(1))
    return context.getString(R.string.script_tree_copied, count)
  }

  private fun JSONObject.putList(key: String, values: List<Any>) {
    if (values.isNotEmpty()) put(key, JSONArray(values))
  }

  private val treeReader by lazy {
    ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
  }
  private const val MAX_DEPTH = 60
  private const val MAX_ITEMS = 2000
  /** How many items in the inspected item Items inside looks at and lists, and how deep. */
  private const val MAX_TREE_ITEMS = 120
  private const val MAX_TREE_LINES = 40
  private const val MAX_TREE_LEVEL = 8

  private val FLAGS: List<Pair<String, (AccessibilityNodeInfoCompat) -> Boolean>> =
    listOf(
      "clickable" to { it.isClickable },
      "longClickable" to { it.isLongClickable },
      "focusable" to { it.isFocusable },
      "checkable" to { it.isCheckable },
      "checked" to { it.isChecked },
      "selected" to { it.isSelected },
      "editable" to { it.isEditable },
      "scrollable" to { it.isScrollable },
      "heading" to AccessibilityNodeInfoUtils::isHeading,
      "password" to { it.isPassword },
      "focused" to { it.isAccessibilityFocused },
      "offscreen" to { !it.isVisibleToUser },
      "disabled" to { !it.isEnabled },
    )
}

fun Context.copyToClipboard(label: CharSequence, text: CharSequence): Boolean =
  getSystemService(ClipboardManager::class.java)
    ?.setPrimaryClip(ClipData.newPlainText(label, text)) != null
