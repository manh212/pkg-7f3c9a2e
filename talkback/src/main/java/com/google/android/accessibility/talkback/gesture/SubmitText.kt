package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.ArrayDeque

object SubmitText {
  @JvmStatic
  fun submit(service: AccessibilityService, field: AccessibilityNodeInfoCompat?): Boolean {
    if (field == null || !field.refresh() || !field.isEditable || !field.isEnabled
        || !field.isVisibleToUser || field.isShowingHintText || field.text.isNullOrBlank()) return false
    val words = sendWords()
    val send = SendButtonFinder.find(field, words)
    if (send != null) return send.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && field.isFocused) {
      val action = field.actionList.firstOrNull { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
      if (action != null && isSendLabel(action.label, words)) {
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      }
    }
    return findKeyboardSendButton(service, words)
  }

  private fun findKeyboardSendButton(
      service: AccessibilityService, words: Collection<String>): Boolean {
    val buttons = ArrayList<AccessibilityNodeInfoCompat>()
    for (window in service.windows) {
      try {
        if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
        val root = window.root ?: continue
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(AccessibilityNodeInfoCompat.wrap(root))
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 200) {
          val node = queue.removeFirst()
          for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
          if (node.isEnabled && node.isVisibleToUser && node.isClickable
              && (isSendLabel(node.text, words) || isSendLabel(node.contentDescription, words))) {
            buttons.add(node)
          } else {
            node.recycle()
          }
        }
        for (leftover in queue) leftover.recycle()
      } finally {
        window.recycle()
      }
    }
    val target = buttons.distinct().singleOrNull()
    val clicked = target?.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK) == true
    for (button in buttons) button.recycle()
    return clicked
  }

  @JvmStatic
  fun isSendLabel(label: CharSequence?, words: Collection<String> = sendWords()): Boolean =
    SendButtonFinder.labelStrength(label, words) == SendButtonFinder.Strength.STRONG

  private fun sendWords(): List<String> {
    val system = Resources.getSystem()
    val id = system.getIdentifier("ime_action_send", "string", "android")
    return (listOfNotNull(if (id != 0) system.getString(id) else null) +
        listOf("Gửi", "Gửi tin nhắn", "Gửi SMS", "Send", "Send message", "Send SMS")).distinct()
  }
}
