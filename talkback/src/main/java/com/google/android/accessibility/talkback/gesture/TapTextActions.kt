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

package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.taskassistant.TaskProtocol
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.output.SpeechController
import java.util.ArrayDeque

/**
 * Gesture actions that tap on screen ("Tự nhấn trên màn hình"): a gesture runs a tap (or a
 * long-press) on the first node whose visible label contains one of the saved texts, tried in
 * the order they were added. Exactly one tap runs per gesture: once a text is found and
 * tapped, the rest are not tried. The gesture preference saves [PREFIX] plus the press type
 * and the texts, joined with a newline, so the list rides inside the action value and needs
 * no extra storage: a value saved in an app's gestures only runs in that app, and a value
 * saved in a gesture profile runs everywhere, because the gesture resolution already prefers
 * the app's gestures, then the profile, then the default.
 *
 * A text is matched with the same two tiers as the task assistant: a node's own text, hint
 * and description first, then its joined label (its own fields, or its descendants' text for
 * a container). A match only needs the wanted text to be contained in the label. When several
 * nodes match, the first one in reading order (topmost) is tapped; there is no guessing.
 */
object TapTextActions {

  /** Starts every saved value of these actions. It must never change once released. */
  private const val PREFIX = "TAP_TEXT:"

  /** The value of the entry in the action list. It is not a runnable action. */
  const val SENTINEL = "TAP_TEXT"

  private const val TAP = "TAP:"
  private const val LONG = "LONG:"

  /** How long a tap stroke lasts, and how long a long-press stroke lasts, on screen. */
  private const val TAP_MS = 75L

  /** The most nodes the search walks, and the most parents activation walks up. */
  private const val NODE_LIMIT = 2500
  private const val MAX_PARENT_DEPTH = 12

  /**
   * A saved tap-text action: the labels to look for, in the order they were added, and
   * whether it is a long-press. The press type is one setting for the whole list: whichever
   * label is tapped is tapped that way.
   */
  data class Parsed(val labels: List<String>, val longPress: Boolean)

  /** Returns the action that taps [label], or long-presses it when [longPress]. */
  @JvmStatic
  fun valueOf(label: String, longPress: Boolean): String = valueOf(listOf(label), longPress)

  /**
   * Returns the action that taps the first of [labels] found on screen, or long-presses it
   * when [longPress]. Blank labels are dropped, a label is kept only the first time it
   * appears, and a label spanning lines is split into its lines, so the saved list holds
   * each text once, in order. The labels are joined with a newline, the same separator
   * Sao chép nối thêm uses between copied texts.
   */
  @JvmStatic
  fun valueOf(labels: List<String>, longPress: Boolean): String =
    PREFIX + (if (longPress) LONG else TAP) + labels
      .flatMap { it.split('\n') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .joinToString("\n")

  /** Returns the action saved as [value], or null when it isn't one of these actions. */
  @JvmStatic
  fun parse(value: String?): Parsed? {
    if (value == null || !value.startsWith(PREFIX)) return null
    val rest = value.substring(PREFIX.length)
    val longPress = when {
      rest.startsWith(TAP) -> false
      rest.startsWith(LONG) -> true
      else -> return null
    }
    val text = rest.substring(if (longPress) LONG.length else TAP.length)
    val labels = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    return if (labels.isEmpty()) null else Parsed(labels, longPress)
  }

  /** Returns whether [value] is an action that taps on screen. */
  @JvmStatic fun isAction(value: String?): Boolean = parse(value) != null

  /** Returns whether [value] is the entry in the action list, not a saved action. */
  @JvmStatic fun isSentinel(value: String?): Boolean = value == SENTINEL

  /**
   * Returns "Tự nhấn trên màn hình: '<label1>', '<label2>'", or null when [value] isn't one
   * of these actions.
   */
  @JvmStatic
  fun label(context: Context, value: String?): String? {
    val parsed = parse(value) ?: return null
    return context.getString(
      if (parsed.longPress) R.string.tap_text_action_long else R.string.tap_text_action,
      parsed.labels.joinToString("', '"),
    )
  }

  /**
   * Tries the saved labels in order, and taps (or long-presses) the first node whose label
   * contains the first label found on the current screen. Exactly one tap runs: once a label
   * is found and tapped, the rest are not tried. Speaks the label that was tapped, or
   * "không tìm thấy '<first label>'" when none of the labels is on the current screen.
   * Never scrolls: only the screen in front is searched.
   */
  @JvmStatic
  fun perform(service: TalkBackService, value: String): Boolean {
    val parsed = parse(value) ?: return false
    val root = service.getRootInActiveWindow()
    if (root != null) {
      for (label in parsed.labels) {
        val node = firstBest(root, label) ?: continue
        val spoken = displayLabel(node)
        if (!activate(service, node, parsed.longPress)) return false
        say(service, spoken)
        return true
      }
    }
    say(service, service.getString(R.string.tap_text_not_found, parsed.labels.first()))
    return false
  }

  /**
   * Returns the first node in reading order whose label best matches [wanted], or null when
   * nothing matches. Own text, hint and description are tried first; when none of them
   * matches, the joined label is tried. A match only needs the wanted text to be contained in
   * the label. Ties are kept in reading order, so the first one is the topmost.
   */
  internal fun firstBest(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
    if (wanted.isBlank()) return null
    val nodes = visibleNodes(root)
    val own = TaskProtocol.topScored(nodes) { nodeScore(it, wanted) }
    if (own.isNotEmpty()) return own.first()
    val joined = TaskProtocol.topScored(nodes) { joinedLabelScore(it, wanted) }
    return joined.firstOrNull()
  }

  private fun nodeScore(node: AccessibilityNodeInfo, wanted: String): Int {
    val fields = AccessibilityNodeInfoCompat.wrap(node)
    return listOf(fields.text, fields.hintText, fields.contentDescription)
      .maxOfOrNull { TaskProtocol.labelScore(wanted, it?.toString().orEmpty()) } ?: 0
  }

  private fun joinedLabelScore(node: AccessibilityNodeInfo, wanted: String): Int =
    labelOf(node).splitToSequence("; ")
      .maxOfOrNull { TaskProtocol.labelScore(wanted, it) } ?: 0

  /**
   * The label a node is matched by: its own text, description, state and hint, joined; or
   * its descendants' text when it has none of its own, as a container row does.
   */
  private fun labelOf(node: AccessibilityNodeInfo): String {
    val fields = AccessibilityNodeInfoCompat.wrap(node)
    val own = listOfNotNull(
      fields.text?.toString(),
      fields.contentDescription?.toString(),
      fields.stateDescription?.toString(),
      fields.hintText?.toString(),
    ).filter { it.isNotBlank() }.distinct().joinToString("; ").take(400)
    return own.ifEmpty { descendantLabel(node) }
  }

  private fun descendantLabel(root: AccessibilityNodeInfo): String {
    val parts = mutableListOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    for (i in 0 until root.childCount.coerceAtMost(12)) {
      root.getChild(i)?.let(queue::addLast)
    }
    var count = 0
    while (queue.isNotEmpty() && count++ < 24) {
      val item = queue.removeFirst()
      if (!item.isVisibleToUser) continue
      (item.text ?: item.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
      for (i in 0 until item.childCount.coerceAtMost(8)) {
        item.getChild(i)?.let(queue::addLast)
      }
    }
    return parts.distinct().joinToString("; ").take(400)
  }

  /** The label spoken after a tap: the node's own text, or its description, or its joined label. */
  private fun displayLabel(node: AccessibilityNodeInfo): String {
    val fields = AccessibilityNodeInfoCompat.wrap(node)
    return fields.text?.toString()?.takeIf { it.isNotBlank() }
      ?: fields.contentDescription?.toString()?.takeIf { it.isNotBlank() }
      ?: labelOf(node)
  }

  /** The visible nodes of the current screen, in reading order (topmost first). */
  private fun visibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
    val nodes = ArrayList<AccessibilityNodeInfo>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addFirst(root)
    var count = 0
    while (stack.isNotEmpty() && count++ < NODE_LIMIT) {
      val node = stack.removeFirst()
      if (!node.isVisibleToUser) continue
      nodes.add(node)
      for (i in node.childCount - 1 downTo 0) {
        node.getChild(i)?.let { stack.addFirst(it) }
      }
    }
    return nodes
  }

  /**
   * Taps (or long-presses) [target]: up to [MAX_PARENT_DEPTH] parents are tried for a node
   * that can take the press; when none can, the press is sent to the node's centre on screen.
   */
  internal fun activate(service: TalkBackService, target: AccessibilityNodeInfo, longPress: Boolean): Boolean {
    var current: AccessibilityNodeInfo? = target
    val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
    for (depth in 0 until MAX_PARENT_DEPTH) {
      val node = current ?: break
      val canPress =
        if (longPress) node.isEnabled && node.isLongClickable
        else node.isEnabled && node.isClickable
      if (canPress && node.performAction(action)) return true
      current = node.parent
    }
    val rect = Rect()
    target.getBoundsInScreen(rect)
    if (rect.isEmpty()) return false
    val path = Path()
    path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
    val duration = if (longPress) ViewConfiguration.getLongPressTimeout().toLong() else TAP_MS
    val description = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
    return service.dispatchGesture(description, null, null)
  }

  private fun say(service: TalkBackService, message: String) {
    service.speechController?.speak(
      message,
      Performance.EVENT_ID_UNTRACKED,
      SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE),
    )
  }
}
