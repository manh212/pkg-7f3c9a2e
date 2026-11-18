package com.google.android.accessibility.talkback.taskassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class CapturedTaskScreen(val screen: TaskScreen, val windowId: Int, val handles: Map<String, AccessibilityNodeInfoCompat>)

class TaskScreenReader(private val service: AccessibilityService) {
  private val sequence = AtomicLong()

  fun read(includeStructure: Boolean = false): CapturedTaskScreen {
    val windows = service.windows.orEmpty().filter {
      it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }
    val window = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused }
      ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      ?: error("Không có cửa sổ ứng dụng")
    val root = window.root?.let(AccessibilityNodeInfoCompat::wrap) ?: error("Ứng dụng chưa cung cấp nội dung màn hình")
    val nodes = mutableListOf<TaskNode>()
    val handles = LinkedHashMap<String, AccessibilityNodeInfoCompat>()
    val pending = ArrayDeque<Pair<AccessibilityNodeInfoCompat, String?>>()
    pending.addLast(root to null)
    var visited = 0
    val deadline = SystemClock.uptimeMillis() + 2500
    val nodeLimit = 600
    while (pending.isNotEmpty() && visited++ < 1800 && nodes.size < nodeLimit && SystemClock.uptimeMillis() < deadline) {
      val (node, parent) = pending.removeFirst()
      if (!node.isVisibleToUser || isPrivateInput(node)) continue
      var childParent = parent
      val actions = linkedSetOf<String>()
      val supported = node.actionList.map { it.id }.toSet()
      if (node.isEnabled) {
        if (AccessibilityNodeInfo.ACTION_CLICK in supported || node.isClickable) actions += "click"
        if (AccessibilityNodeInfo.ACTION_LONG_CLICK in supported || node.isLongClickable) actions += "long_click"
        if (AccessibilityNodeInfo.ACTION_SET_TEXT in supported && node.isEditable) actions += "set_text"
        if (AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_IME_ENTER.id in supported) actions += "press_enter"
        if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in supported) actions += "scroll_forward"
        if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in supported) actions += "scroll_backward"
      }
      val label = label(node, actions.isNotEmpty())
      if (includeStructure || label.isNotBlank() || actions.isNotEmpty()) {
        val id = "n${nodes.size}"
        val item = TaskNode(id, label, node.viewIdResourceName.orEmpty(),
          node.className?.toString()?.substringAfterLast('.').orEmpty(),
          actions, if (node.isCheckable) node.isChecked else null)
        nodes += if (includeStructure) withStructure(item, node, parent) else item
        handles[id] = node
        childParent = id
      }
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let { pending.addLast(it to childParent) }
      }
    }
    val packageName = root.packageName?.toString() ?: error("Không xác định được ứng dụng")
    val title = window.title?.toString().orEmpty()
    val fingerprint = if (includeStructure) "" else {
      val signature = "$packageName:${window.id}:$title:${nodes.joinToString { it.json().toString() }}"
      MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    return CapturedTaskScreen(TaskScreen(sequence.incrementAndGet(), packageName, title, fingerprint, nodes, pending.isNotEmpty()), window.id, handles)
  }

  private fun withStructure(
    item: TaskNode,
    node: AccessibilityNodeInfoCompat,
    parent: String?,
  ): TaskNode {
    val bounds = Rect().also(node::getBoundsInScreen)
    return item.copy(
      parent = parent,
      text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty(),
      hint = node.hintText?.toString().orEmpty().ifBlank {
        if (node.isShowingHintText) node.text?.toString().orEmpty() else ""
      },
      description = node.contentDescription?.toString().orEmpty(),
      top = bounds.top,
      left = bounds.left,
      right = bounds.right,
      bottom = bounds.bottom,
      row = node.collectionItemInfo?.rowIndex?.takeIf { it >= 0 },
      collection = node.collectionInfo != null || node.isScrollable,
      focused = node.isFocused,
    )
  }

  private fun isPrivateInput(node: AccessibilityNodeInfoCompat): Boolean = node.isPassword ||
    (node.isEditable && TaskProtocol.isCredentialRequest(listOfNotNull(node.hintText, node.contentDescription,
      node.viewIdResourceName).joinToString(" ")))

  private fun label(node: AccessibilityNodeInfoCompat, includeDescendants: Boolean): String =
    listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.stateDescription?.toString(), node.hintText?.toString())
      .filter { it.isNotBlank() }.distinct().joinToString("; ").take(400)
      .ifEmpty { if (includeDescendants) descendantLabel(node) else "" }

  private fun descendantLabel(root: AccessibilityNodeInfoCompat): String {
    val parts = mutableListOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
    for (i in 0 until root.childCount.coerceAtMost(12)) root.getChild(i)?.let(queue::addLast)
    var count = 0
    while (queue.isNotEmpty() && count++ < 24) {
      val item = queue.removeFirst()
      if (!item.isVisibleToUser || isPrivateInput(item)) continue
      (item.text ?: item.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
      for (i in 0 until item.childCount.coerceAtMost(8)) item.getChild(i)?.let(queue::addLast)
    }
    return parts.distinct().joinToString("; ").take(400)
  }

  fun matches(capture: CapturedTaskScreen, id: String): Boolean {
    val node = capture.handles[id] ?: return false
    if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || isPrivateInput(node) || node.windowId != capture.windowId ||
      node.packageName?.toString() != capture.screen.packageName) return false
    val expected = capture.screen.nodes.firstOrNull { it.id == id } ?: return false
    return label(node, expected.actions.isNotEmpty()) == expected.label &&
      node.viewIdResourceName.orEmpty() == expected.viewId &&
      (if (node.isCheckable) node.isChecked else null) == expected.checked
  }

  fun clickSearchKey(capture: CapturedTaskScreen, fieldId: String): Boolean {
    if (!matches(capture, fieldId) || capture.handles[fieldId]?.isFocused != true) return false
    val keyboard = service.windows.orEmpty().singleOrNull {
      it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    } ?: return false
    val root = keyboard.root?.let(AccessibilityNodeInfoCompat::wrap) ?: return false
    val pending = ArrayDeque(listOf(root))
    val buttons = mutableListOf<AccessibilityNodeInfoCompat>()
    var visited = 0
    while (pending.isNotEmpty() && visited++ < 200) {
      val node = pending.removeFirst()
      if (!node.isVisibleToUser) continue
      if (node.isEnabled && node.isClickable && listOfNotNull(node.text, node.contentDescription)
          .any { TaskProtocol.normalize(it.toString()) in setOf("search", "tim kiem") }) buttons += node
      for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
    }
    if (pending.isNotEmpty()) return false
    return buttons.singleOrNull()?.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK) == true
  }

  fun launcherApps(): List<TaskApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return service.packageManager.queryIntentActivities(intent, 0)
      .map { TaskApp(it.activityInfo.packageName, it.loadLabel(service.packageManager).toString()) }
      .distinctBy { it.packageName }.sortedBy { it.name }
  }

  fun perform(capture: CapturedTaskScreen, step: TaskStep): Boolean {
    when (step.operation) {
      TaskOperation.OPEN_APP -> {
        val intent = service.packageManager.getLaunchIntentForPackage(step.packageName)
          ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        service.startActivity(intent)
        return true
      }
      TaskOperation.BACK -> return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      TaskOperation.HOME -> return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      TaskOperation.DRAG -> return drag(capture, step)
      else -> Unit
    }
    val node = capture.handles[step.node] ?: return false
    if (!matches(capture, step.node)) return false
    val id = when (step.operation) {
      TaskOperation.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
      TaskOperation.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
      TaskOperation.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
      TaskOperation.PRESS_ENTER -> AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_IME_ENTER.id
      TaskOperation.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
      TaskOperation.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
      else -> return false
    }
    val args = if (step.operation == TaskOperation.SET_TEXT) android.os.Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text)
    } else null
    return node.performAction(id, args)
  }

  private fun drag(capture: CapturedTaskScreen, step: TaskStep): Boolean {
    if (step.text !in TaskProtocol.dragDirections) return false
    val node = capture.handles[step.node] ?: return false
    if (!matches(capture, step.node)) return false
    val bounds = Rect().also(node::getBoundsInScreen)
    if (bounds.width() <= 0 || bounds.height() <= 0) return false
    val metrics = service.resources.displayMetrics
    val centerX = (bounds.left + bounds.right) / 2f
    val centerY = (bounds.top + bounds.bottom) / 2f
    val distanceX = (metrics.widthPixels / 3f).coerceAtLeast(1f)
    val distanceY = (metrics.heightPixels / 3f).coerceAtLeast(1f)
    val endX = (centerX + when (step.text) {
      "trai" -> -distanceX
      "phai" -> distanceX
      else -> 0f
    }).coerceIn(0f, (metrics.widthPixels - 1).toFloat())
    val endY = (centerY + when (step.text) {
      "len" -> -distanceY
      "xuong" -> distanceY
      else -> 0f
    }).coerceIn(0f, (metrics.heightPixels - 1).toFloat())
    if (endX == centerX && endY == centerY) return false
    val path = Path().apply { moveTo(centerX, centerY); lineTo(endX, endY) }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, DRAG_MS)).build()
    if (!service.dispatchGesture(gesture, null)) return false
    SystemClock.sleep(DRAG_MS + 100)
    return true
  }

  companion object {
    private const val DRAG_MS = 400L
  }
}
