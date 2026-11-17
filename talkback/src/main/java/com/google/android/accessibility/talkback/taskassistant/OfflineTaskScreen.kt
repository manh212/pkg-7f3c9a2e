package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.taskassistant.TaskOperation.CLICK
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.PRESS_ENTER
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SCROLL_BACKWARD
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SCROLL_FORWARD
import com.google.android.accessibility.talkback.taskassistant.TaskOperation.SET_TEXT
import java.text.Normalizer
import java.util.Locale

internal sealed interface OfflineScreenStep {
  data class Open(val app: String) : OfflineScreenStep
  data class Navigate(val action: TaskOperation) : OfflineScreenStep
  data class ClickLabel(val label: String) : OfflineScreenStep
  data class Scroll(val forward: Boolean) : OfflineScreenStep
  data class Search(val packageName: String) : OfflineScreenStep
  data class EnterQuery(val packageName: String, val query: String) : OfflineScreenStep
  data class SubmitYouTube(val query: String) : OfflineScreenStep
  data class FirstVideo(val query: String?) : OfflineScreenStep
  data class ZaloRecipient(val name: String) : OfflineScreenStep
  data class ZaloDraft(val name: String, val text: String) : OfflineScreenStep
  data class VerifyZaloDraft(val name: String, val text: String) : OfflineScreenStep
}

internal class OfflineTaskScreen(private val screen: TaskScreen) {
  sealed interface Decision {
    data class Act(val step: TaskStep, val guard: String? = null) : Decision
    data object Ready : Decision
    data object Wait : Decision
    data object Ambiguous : Decision
    data object Unavailable : Decision
    data object ExistingDraft : Decision
  }

  private val nodes = screen.nodes
  private val byId = nodes.associateBy { it.id }
  private val searchLabels = setOf("tim kiem", "search", "search youtube", "tim kiem tren youtube")
  private val composerLabels = setOf("tin nhan", "nhap tin nhan", "type a message", "message")

  fun decide(step: OfflineScreenStep): Decision = when (step) {
    is OfflineScreenStep.ClickLabel -> clickLabel(step.label)
    is OfflineScreenStep.Scroll -> scroll(step.forward)
    is OfflineScreenStep.Search -> inApp(step.packageName) { search() }
    is OfflineScreenStep.EnterQuery -> inApp(step.packageName) { enterQuery(step.query) }
    is OfflineScreenStep.SubmitYouTube -> inApp(YOUTUBE) { submitYouTube(step.query) }
    is OfflineScreenStep.FirstVideo -> inApp(YOUTUBE) { firstVideo(step.query) }
    is OfflineScreenStep.ZaloRecipient -> inApp(ZALO) { zaloRecipient(step.name) }
    is OfflineScreenStep.ZaloDraft -> inApp(ZALO) { zaloDraft(step.name, step.text, verify = false) }
    is OfflineScreenStep.VerifyZaloDraft -> inApp(ZALO) { zaloDraft(step.name, step.text, verify = true) }
    is OfflineScreenStep.Open, is OfflineScreenStep.Navigate -> Decision.Unavailable
  }

  private fun inApp(packageName: String, decide: () -> Decision): Decision =
    if (screen.packageName == packageName) decide() else Decision.Wait

  private fun clickLabel(label: String): Decision {
    val wanted = normalize(label)
    if (Regex("^(gui|send|goi|call|xoa|delete|pay|thanh toan)( |$)").containsMatchIn(wanted)) {
      return Decision.Unavailable
    }
    val matches = nodes.filter { labeled(it) { name -> normalize(name) == wanted } }
    if (wanted in searchLabels && screen.packageName in setOf(YOUTUBE, ZALO)) return search()
    return clickUnique(matches.mapNotNull(::clickable).distinctBy { it.id })
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

  private fun submitYouTube(query: String): Decision {
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

  private fun firstVideo(query: String?): Decision {
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
        .thenBy { it.top }.thenBy { it.left }).firstOrNull()
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

  private fun zaloRecipient(name: String): Decision {
    val field = searchFields().singleOrNull() ?: return Decision.Wait
    if (screen.truncated || !equalNames(field.text, name) || nodes.any { it.role == "ProgressBar" }) {
      return Decision.Wait
    }
    val options = nodes.filter { node ->
      node.top >= field.top && SET_TEXT.wire !in node.actions && labeled(node) { equalNames(it, name) }
    }.mapNotNull(::clickable).distinctBy { it.id }
    return clickUnique(options)
  }

  private fun zaloDraft(name: String, message: String, verify: Boolean): Decision {
    val editors = nodes.filter { node ->
      SET_TEXT.wire in node.actions && (listOf(node.hint, node.description).any { normalize(it) in composerLabels } ||
        (verify && message.isNotBlank() && node.text == message))
    }
    if (editors.size > 1) return Decision.Ambiguous
    val editor = editors.singleOrNull() ?: return Decision.Wait
    if (searchFields().isNotEmpty()) return Decision.Wait
    val top = nodes.minOf { it.top }
    val headerBottom = top + (editor.top - top) / 3
    val header = nodes.filter { node ->
      node.top <= headerBottom && node.top < editor.top && ancestors(node).none { it.collection } &&
        labeled(node) { equalNames(it, name) }
    }.minByOrNull { it.top } ?: return Decision.Wait
    if (message.isBlank() || editor.text == message) return Decision.Ready
    if (verify) return Decision.Wait
    if (editor.text.isNotBlank()) return Decision.ExistingDraft
    return act(SET_TEXT, editor, message, header.id)
  }

  private fun searchFields() = nodes.filter { SET_TEXT.wire in it.actions && isSearch(it) }

  private fun isSearch(node: TaskNode): Boolean =
    labeled(node) { normalize(it) in searchLabels } ||
      node.viewId.substringAfterLast('/').lowercase(Locale.ROOT) in
        setOf("search_button", "search_edit_text", "search_src_text", "search_query", "search_box")

  private fun clickable(node: TaskNode): TaskNode? =
    (sequenceOf(node) + ancestors(node).take(4)).takeWhile { !it.collection }
      .firstOrNull { CLICK.wire in it.actions }

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
    const val YOUTUBE = "com.google.android.youtube"
    const val ZALO = "com.zing.zalo"

    fun equalNames(first: String, second: String): Boolean =
      nameKey(first) == nameKey(second)

    private fun nameKey(name: String): String =
      Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trim()

    private fun normalize(text: String) = TaskProtocol.normalize(text)
  }
}
