package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.taskassistant.TaskOperation.CLICK
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.PRESS_ENTER
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SCROLL_BACKWARD
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SCROLL_FORWARD
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SET_TEXT
import java.text.Normalizer
import java.util.Locale

internal sealed interface OfflineScreenStep {
  data class ClickLabel(val label: String) : OfflineScreenStep
  data class LongClickLabel(val label: String) : OfflineScreenStep
  data class Input(val text: String, val label: String, val append: Boolean) : OfflineScreenStep
  data class VerifyInput(val text: String, val label: String) : OfflineScreenStep
  data class Toggle(val label: String, val checked: Boolean) : OfflineScreenStep
  data class VerifyToggle(val label: String, val checked: Boolean) : OfflineScreenStep
  data class Item(val index: Int, val video: Boolean = false) : OfflineScreenStep
  data class Drag(val label: String, val direction: String) : OfflineScreenStep
  data object SearchCurrent : OfflineScreenStep
  data class EnterCurrentQuery(val query: String) : OfflineScreenStep
  data class SubmitSearch(val query: String) : OfflineScreenStep
  data class Scroll(val forward: Boolean) : OfflineScreenStep
}

internal class OfflineTaskScreen(private val screen: TaskScreen, private val apps: List<TaskApp> = emptyList()) {
  sealed interface Decision {
    data class Act(val step: TaskStep, val guard: String? = null) : Decision
    data object Ready : Decision
    data object Wait : Decision
    data object Ambiguous : Decision
    data object Unavailable : Decision
  }

  private val nodes = screen.nodes
  private val byId = nodes.associateBy { it.id }
  private val searchLabels = setOf("tim kiem", "search")

  fun decide(step: OfflineScreenStep): Decision = when (step) {
    is OfflineScreenStep.ClickLabel -> clickLabel(step.label)
    is OfflineScreenStep.LongClickLabel -> clickLabel(step.label, TaskOperation.LONG_CLICK)
    is OfflineScreenStep.Input -> input(step.text, step.label, step.append)
    is OfflineScreenStep.VerifyInput -> verifyInput(step.text, step.label)
    is OfflineScreenStep.Toggle -> toggle(step.label, step.checked, verify = false)
    is OfflineScreenStep.VerifyToggle -> toggle(step.label, step.checked, verify = true)
    is OfflineScreenStep.Item -> if (step.video) firstMedia(null, step.index) else item(step.index)
    is OfflineScreenStep.Drag -> drag(step.label, step.direction)
    OfflineScreenStep.SearchCurrent -> search()
    is OfflineScreenStep.EnterCurrentQuery -> enterQuery(step.query)
    is OfflineScreenStep.SubmitSearch -> submitSearch(step.query)
    is OfflineScreenStep.Scroll -> scroll(step.forward)
  }

  private fun clickLabel(label: String, action: TaskOperation = CLICK): Decision {
    val wanted = normalize(label)
    if (action == CLICK && wanted in searchLabels) return search()
    val targets = bestNodes(wanted).mapNotNull { target(it, action.wire) }.distinctBy { it.id }
    return when (targets.size) {
      0 -> if (action == CLICK) openApp(wanted) else Decision.Wait
      1 -> act(action, targets.single())
      else -> Decision.Ambiguous
    }
  }

  private fun openApp(wanted: String): Decision {
    if (wanted.isBlank()) return Decision.Wait
    val matches = TaskProtocol.topScored(apps) { TaskProtocol.labelScore(wanted, it.name) }
    return when (matches.size) {
      0 -> Decision.Wait
      1 -> Decision.Act(TaskStep(screen.id, TaskOperation.OPEN_APP, "", matches.single().packageName))
      else -> Decision.Ambiguous
    }
  }

  private fun drag(label: String, direction: String): Decision {
    if (direction !in TaskProtocol.dragDirections) return Decision.Unavailable
    val wanted = normalize(label)
    val matches = bestNodes(wanted)
    return when (matches.size) {
      0 -> Decision.Wait
      1 -> act(TaskOperation.DRAG, matches.single(), direction)
      else -> Decision.Ambiguous
    }
  }

  private fun bestNodes(wanted: String, candidates: List<TaskNode> = nodes): List<TaskNode> {
    val own = TaskProtocol.topScored(candidates) { nodeScore(it, wanted) }
    if (own.isNotEmpty()) return own
    return TaskProtocol.topScored(candidates) { joinedLabelScore(it, wanted) }
  }

  private fun nodeScore(node: TaskNode, wanted: String): Int =
    listOf(node.text, node.hint, node.description)
      .maxOfOrNull { TaskProtocol.labelScore(wanted, it) } ?: 0

  private fun joinedLabelScore(node: TaskNode, wanted: String): Int =
    node.label.splitToSequence("; ")
      .maxOfOrNull { TaskProtocol.labelScore(wanted, it) } ?: 0

  private fun editor(label: String): List<TaskNode> {
    val editable = nodes.filter { SET_TEXT.wire in it.actions }
    if (label.isNotBlank()) return bestNodes(label, editable)
    val focused = editable.filter { it.focused }
    return focused.ifEmpty { editable }
  }

  private fun input(text: String, label: String, append: Boolean): Decision {
    val editors = editor(label)
    if (editors.size > 1) return Decision.Ambiguous
    val field = editors.singleOrNull() ?: return Decision.Wait
    val result = if (append && field.text.isNotEmpty()) {
      field.text + (if (field.text.last().isWhitespace() || text.firstOrNull()?.isWhitespace() == true) "" else " ") + text
    } else text
    if (result == field.text) return Decision.Ready
    return act(SET_TEXT, field, result)
  }

  private fun verifyInput(text: String, label: String): Decision {
    val editors = editor(label)
    if (editors.size > 1) return Decision.Ambiguous
    return if (editors.singleOrNull()?.text == text) Decision.Ready else Decision.Wait
  }

  private fun toggle(label: String, checked: Boolean, verify: Boolean): Decision {
    val matches = bestNodes(label)
      .flatMap { node ->
        (sequenceOf(node) + ancestors(node).take(3)).filter { it.checked != null }.toList()
      }.distinctBy { it.id }
    if (matches.size > 1) return Decision.Ambiguous
    val node = matches.singleOrNull() ?: return Decision.Wait
    if (node.checked == checked) return Decision.Ready
    if (verify) return Decision.Wait
    val target = clickable(node) ?: return Decision.Unavailable
    return act(CLICK, target, guard = node.id)
  }

  private fun item(index: Int): Decision {
    if (screen.truncated) return Decision.Wait
    val lists = nodes.filter { it.collection && ancestors(it).none { parent -> parent.collection } }
    val candidates = lists.mapNotNull { list ->
      val rows = nodes.filter { node ->
        CLICK.wire in node.actions && ancestors(node).any { it.id == list.id }
      }
      rows.filter { node -> ancestors(node).none { parent -> rows.any { it.id == parent.id } } }
        .sortedWith(compareBy<TaskNode> { rowIndex(it, list) ?: Int.MAX_VALUE }
          .thenBy { it.top }.thenBy { it.left }).getOrNull(index - 1)
    }.distinctBy { it.id }
    return clickUnique(candidates)
  }

  private fun scroll(forward: Boolean): Decision {
    val action = if (forward) SCROLL_FORWARD else SCROLL_BACKWARD
    val options = nodes.filter { action.wire in it.actions }
    val primary = options.filter { node -> ancestors(node).none { action.wire in it.actions } }
    return when (primary.size) {
      0 -> Decision.Wait
      1 -> act(action, primary.single())
      else -> Decision.Ambiguous
    }
  }

  private fun search(): Decision {
    val fields = searchFields()
    if (fields.size > 1) return Decision.Ambiguous
    val field = fields.singleOrNull()
    if (field != null) {
      return if (field.focused) Decision.Ready
      else clickable(field)?.let { act(CLICK, it) } ?: Decision.Ready
    }
    return clickUnique(nodes.filter { isSearch(it) }.mapNotNull(::clickable).distinctBy { it.id })
  }

  private fun enterQuery(query: String): Decision {
    val fields = searchFields()
    if (fields.size > 1) return Decision.Ambiguous
    val field = fields.singleOrNull() ?: return Decision.Wait
    return if (equalNames(field.text, query)) Decision.Ready else act(SET_TEXT, field, query)
  }

  private fun submitSearch(query: String): Decision {
    val fields = searchFields().filter { equalNames(it.text, query) }
    if (fields.size > 1) return Decision.Ambiguous
    val field = fields.singleOrNull() ?: return Decision.Wait
    if (PRESS_ENTER.wire in field.actions) return act(PRESS_ENTER, field)
    return when (val button = clickUnique(nodes.filter { node ->
      CLICK.wire in node.actions && labeled(node) { normalize(it) in searchLabels } &&
        SET_TEXT.wire !in node.actions
    })) {
      Decision.Wait -> if (field.focused) act(PRESS_ENTER, field) else Decision.Wait
      else -> button
    }
  }

  private fun firstMedia(query: String?, index: Int): Decision {
    if (screen.truncated) return Decision.Wait
    val explicitResults = nodes.filter { it.collection && it.viewId.contains("search_result", ignoreCase = true) }
    val filters = nodes.any { labeled(it) { name ->
      normalize(name) in setOf("bo loc", "bo loc tim kiem", "loc", "filter", "filters", "search filters")
    } }
    val hasQuery = query != null && searchFields().isEmpty() &&
      nodes.any { labeled(it) { name -> equalNames(name, query) } }
    val resultLists = when {
      explicitResults.isNotEmpty() -> explicitResults
      filters || hasQuery -> nodes.filter { it.collection }
      else -> return Decision.Wait
    }
    val candidates = resultLists.mapNotNull { list ->
      val videos = nodes.filter { node ->
        CLICK.wire in node.actions && ancestors(node).any { it.id == list.id } && isVideo(node)
      }
      if (videos.isEmpty()) return@mapNotNull null
      val rows = videos.filter { video -> ancestors(video).none { parent -> videos.any { it.id == parent.id } } }
      rows.sortedWith(compareBy<TaskNode> { rowIndex(it, list) ?: Int.MAX_VALUE }
        .thenBy { it.top }.thenBy { it.left }).getOrNull(index - 1)
    }.distinctBy { it.id }
    return clickUnique(candidates)
  }

  private fun rowIndex(node: TaskNode, list: TaskNode): Int? =
    (sequenceOf(node) + ancestors(node).takeWhile { it.id != list.id }).firstNotNullOfOrNull { it.row }

  private fun isVideo(node: TaskNode): Boolean {
    val label = normalize(node.label)
    if (listOf("quang cao", "tai tro", "sponsored", "action menu", "trinh don thao tac", "chuyen den kenh",
        "go to channel", "shorts").any { label.startsWith(it) }) return false
    if (label.isBlank()) return false
    val duration = Regex("\\b(?:\\d{1,2}:\\d{2}|\\d+\\s*(?:gio|phut|giay|hours?|minutes?|seconds?))\\b")
    return duration.containsMatchIn(label) ||
      listOf("luot xem", " views", " thoi luong", "duration").any(label::contains)
  }

  private fun searchFields() = nodes.filter { SET_TEXT.wire in it.actions && isSearch(it) }

  private fun isSearch(node: TaskNode): Boolean =
    labeled(node) { normalize(it) in searchLabels } ||
      node.viewId.substringAfterLast('/').lowercase(Locale.ROOT) in
        setOf("search_button", "search_edit_text", "search_src_text", "search_query", "search_box")

  private fun clickable(node: TaskNode): TaskNode? =
    target(node, CLICK.wire)

  private fun target(node: TaskNode, action: String): TaskNode? =
    (sequenceOf(node) + ancestors(node).take(4)).takeWhile { !it.collection }
      .firstOrNull { action in it.actions }

  private fun clickUnique(options: List<TaskNode>): Decision = when (options.size) {
    0 -> Decision.Wait
    1 -> act(CLICK, options.single())
    else -> Decision.Ambiguous
  }

  private fun labeled(node: TaskNode, matches: (String) -> Boolean): Boolean =
    sequenceOf(node.text, node.hint, node.description, node.label)
      .flatMap { it.splitToSequence("; ") }.any(matches)

  private fun ancestors(node: TaskNode): Sequence<TaskNode> =
    generateSequence(node.parent?.let(byId::get)) { it.parent?.let(byId::get) }

  private fun act(action: TaskOperation, node: TaskNode, text: String = "", guard: String? = null) =
    Decision.Act(TaskStep(screen.id, action, node.id, screen.packageName, text), guard)

  companion object {
    fun equalNames(first: String, second: String): Boolean =
      nameKey(first) == nameKey(second)

    private fun nameKey(name: String): String =
      Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trim()

    private fun normalize(text: String) = TaskProtocol.normalize(text)
  }
}
