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

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapTextActionsTest {

  private val context: Application = RuntimeEnvironment.getApplication()

  class TargetService : TalkBackService() {
    var root: AccessibilityNodeInfo? = null
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = root
    override fun onCreate() {}
  }

  // Created, so the service is attached to a context: perform() reads a string when nothing
  // is found, and that needs an attached service.
  private val service: TargetService by lazy { Robolectric.buildService(TargetService::class.java).create().get() }

  @Test
  fun tapValueEncodesLabelAndPressType() {
    assertEquals("TAP_TEXT:TAP:Cài đặt", TapTextActions.valueOf("Cài đặt", false))
    assertEquals("TAP_TEXT:LONG:Cài đặt", TapTextActions.valueOf("Cài đặt", true))
  }

  @Test
  fun valueOfTrimsTheLabel() {
    assertEquals("TAP_TEXT:TAP:Cài đặt", TapTextActions.valueOf("  Cài đặt  ", false))
  }

  @Test
  fun valueOfJoinsLabelsWithNewlines() {
    assertEquals(
      "TAP_TEXT:TAP:Chi tiết chuỗi bài\nMenu\nXóa cuộc trò chuyện",
      TapTextActions.valueOf(listOf("Chi tiết chuỗi bài", "Menu", "Xóa cuộc trò chuyện"), false),
    )
    assertEquals(
      "TAP_TEXT:LONG:A\nB",
      TapTextActions.valueOf(listOf("A", "B"), true),
    )
  }

  @Test
  fun valueOfDropsBlankAndRepeatedLabels() {
    assertEquals("TAP_TEXT:TAP:A\nB", TapTextActions.valueOf(listOf("  A  ", "", "A", "B"), false))
  }

  @Test
  fun onlyTapTextValuesAreActions() {
    assertTrue(TapTextActions.isAction(TapTextActions.valueOf("Cài đặt", false)))
    assertTrue(TapTextActions.isAction(TapTextActions.valueOf("Cài đặt", true)))
    assertTrue(TapTextActions.isAction(TapTextActions.valueOf(listOf("A", "B"), false)))
    assertTrue(TapTextActions.isAction("TAP_TEXT:TAP:A\nB"))
    assertFalse(TapTextActions.isAction("NEXT"))
    assertFalse(TapTextActions.isAction(TapTextActions.SENTINEL))
    assertFalse(TapTextActions.isAction("TAP_TEXT:"))
    assertFalse(TapTextActions.isAction("TAP_TEXT:TAP:"))
    assertFalse(TapTextActions.isAction("TAP_TEXT:TAP:   "))
    assertFalse(TapTextActions.isAction("TAP_TEXT:TAP:\n   \n"))
    assertFalse(TapTextActions.isAction(null))
  }

  @Test
  fun parseReadsLabelAndPressType() {
    assertEquals(TapTextActions.Parsed(listOf("Cài đặt"), false), TapTextActions.parse("TAP_TEXT:TAP:Cài đặt"))
    assertEquals(TapTextActions.Parsed(listOf("Cài đặt"), true), TapTextActions.parse("TAP_TEXT:LONG:Cài đặt"))
    assertEquals(TapTextActions.Parsed(listOf("A:B"), false), TapTextActions.parse("TAP_TEXT:TAP:A:B"))
    assertNull(TapTextActions.parse("NEXT"))
    assertNull(TapTextActions.parse(TapTextActions.SENTINEL))
    assertNull(TapTextActions.parse(null))
  }

  @Test
  fun parseReadsTheLabelListInOrder() {
    assertEquals(
      TapTextActions.Parsed(listOf("Chi tiết chuỗi bài", "Menu", "Xóa cuộc trò chuyện"), false),
      TapTextActions.parse("TAP_TEXT:TAP:Chi tiết chuỗi bài\nMenu\nXóa cuộc trò chuyện"),
    )
    assertEquals(
      TapTextActions.Parsed(listOf("A", "B"), true),
      TapTextActions.parse("TAP_TEXT:LONG:A\nB"),
    )
    // A colon inside a label is still part of that label, not a separator.
    assertEquals(
      TapTextActions.Parsed(listOf("A:B", "C"), false),
      TapTextActions.parse("TAP_TEXT:TAP:A:B\nC"),
    )
  }

  @Test
  fun parseIgnoresEmptyLines() {
    assertEquals(
      TapTextActions.Parsed(listOf("A", "B"), false),
      TapTextActions.parse("TAP_TEXT:TAP:A\n\n   \nB\n"),
    )
    assertNull(TapTextActions.parse("TAP_TEXT:TAP:\n   \n"))
  }

  @Test
  fun labelNamesTheAction() {
    assertEquals(
      "Tự nhấn trên màn hình: 'Cài đặt'",
      TapTextActions.label(context, TapTextActions.valueOf("Cài đặt", false)),
    )
    assertEquals(
      "Tự nhấn trên màn hình (nhấn giữ): 'Cài đặt'",
      TapTextActions.label(context, TapTextActions.valueOf("Cài đặt", true)),
    )
    assertEquals(
      "Tự nhấn trên màn hình: 'A', 'B'",
      TapTextActions.label(context, TapTextActions.valueOf(listOf("A", "B"), false)),
    )
    assertEquals(
      "Tự nhấn trên màn hình (nhấn giữ): 'A', 'B'",
      TapTextActions.label(context, TapTextActions.valueOf(listOf("A", "B"), true)),
    )
    assertNull(TapTextActions.label(context, "NEXT"))
    assertNull(TapTextActions.label(context, TapTextActions.SENTINEL))
    assertNull(TapTextActions.label(context, null))
  }

  @Test
  fun exactMatchBeatsContains() {
    val tree = groupRows()
    assertEquals(tree.label1, TapTextActions.firstBest(tree.root, "Nhóm ABC"))
  }

  @Test
  fun tiesTakeTheFirstInReadingOrder() {
    val tree = groupRows()
    assertEquals(tree.label1, TapTextActions.firstBest(tree.root, "ABC"))
    assertEquals(tree.count1, TapTextActions.firstBest(tree.root, "tin nhắn"))
  }

  @Test
  fun notFoundReturnsNull() {
    val tree = groupRows()
    assertNull(TapTextActions.firstBest(tree.root, "không có"))
    assertNull(TapTextActions.firstBest(tree.root, ""))
    assertNull(TapTextActions.firstBest(tree.root, "   "))
  }

  @Test
  fun childOfAContainerRowIsFound() {
    val tree = groupRows()
    assertEquals(tree.settings, TapTextActions.firstBest(tree.root, "Cài đặt"))
  }

  @Test
  fun tapWalksUpToTheClickableRow() {
    val row = node(clickable = true)
    val label = node(text = "Nhóm ABC")
    add(row, label)
    sealAll(row, label)
    var rowClicks = 0
    var labelClicks = 0
    Shadows.shadowOf(row).setOnPerformActionListener { action, _ ->
      if (action == AccessibilityNodeInfo.ACTION_CLICK) rowClicks++
      true
    }
    Shadows.shadowOf(label).setOnPerformActionListener { action, _ ->
      if (action == AccessibilityNodeInfo.ACTION_CLICK) labelClicks++
      true
    }
    assertTrue(TapTextActions.activate(service, label, false))
    assertEquals(1, rowClicks)
    assertEquals(0, labelClicks)
  }

  @Test
  fun tapPrefersClickOverLongClick() {
    val button = node(text = "Gửi", clickable = true, longClickable = true)
    sealAll(button)
    var clicks = 0
    var longClicks = 0
    Shadows.shadowOf(button).setOnPerformActionListener { action, _ ->
      when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> clicks++
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> longClicks++
      }
      true
    }
    assertTrue(TapTextActions.activate(service, button, false))
    assertEquals(1, clicks)
    assertEquals(0, longClicks)
  }

  @Test
  fun longPressUsesLongClick() {
    val button = node(text = "Gửi", clickable = true, longClickable = true)
    sealAll(button)
    var clicks = 0
    var longClicks = 0
    Shadows.shadowOf(button).setOnPerformActionListener { action, _ ->
      when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> clicks++
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> longClicks++
      }
      true
    }
    assertTrue(TapTextActions.activate(service, button, true))
    assertEquals(0, clicks)
    assertEquals(1, longClicks)
  }

  @Test
  fun noClickableAncestorAndNoBoundsReturnsFalse() {
    val label = node(text = "Nhóm ABC", bounds = false)
    sealAll(label)
    assertFalse(TapTextActions.activate(service, label, false))
    assertFalse(TapTextActions.activate(service, label, true))
  }

  @Test
  fun performTapsTheFirstLabelInListOrderAndStops() {
    val tree = tappableRows()
    service.root = tree.root
    val row1Clicks = clickCounter(tree.row1)
    val row3Clicks = clickCounter(tree.row3)
    // Both labels are on screen, but "Cài đặt" comes first in the list, so only it is tapped.
    assertTrue(
      TapTextActions.perform(service, TapTextActions.valueOf(listOf("Cài đặt", "Nhóm ABC"), false))
    )
    assertEquals(1, row3Clicks())
    assertEquals(0, row1Clicks())
  }

  @Test
  fun performSkipsAMissingLabelForTheNextOne() {
    val tree = tappableRows()
    service.root = tree.root
    val row1Clicks = clickCounter(tree.row1)
    val row3Clicks = clickCounter(tree.row3)
    assertTrue(
      TapTextActions.perform(service, TapTextActions.valueOf(listOf("không có", "Nhóm ABC"), false))
    )
    assertEquals(1, row1Clicks())
    assertEquals(0, row3Clicks())
  }

  @Test
  fun performReturnsFalseWhenNoLabelIsFound() {
    val tree = tappableRows()
    service.root = tree.root
    val row1Clicks = clickCounter(tree.row1)
    val row3Clicks = clickCounter(tree.row3)
    assertFalse(
      TapTextActions.perform(service, TapTextActions.valueOf(listOf("không có", "cũng không"), false))
    )
    assertEquals(0, row1Clicks())
    assertEquals(0, row3Clicks())
  }

  @Test
  fun notFoundNamesTheFirstLabelInTheList() {
    val parsed = TapTextActions.parse(TapTextActions.valueOf(listOf("không có", "cũng không"), false))!!
    assertEquals(
      "Không tìm thấy 'không có'",
      context.getString(R.string.tap_text_not_found, parsed.labels.first()),
    )
  }

  private class Rows(
    val root: AccessibilityNodeInfo,
    val label1: AccessibilityNodeInfo,
    val count1: AccessibilityNodeInfo,
    val label2: AccessibilityNodeInfo,
    val count2: AccessibilityNodeInfo,
    val settings: AccessibilityNodeInfo,
  )

  /** Two group rows and a settings row, in reading order, as a chat list shows them. */
  private fun groupRows(): Rows {
    val root = node()
    val row1 = node()
    val label1 = node(text = "Nhóm ABC")
    val count1 = node(text = "3 tin nhắn")
    val row2 = node()
    val label2 = node(text = "Nhóm ABC 2")
    val count2 = node(text = "1 tin nhắn")
    val row3 = node()
    val settings = node(text = "Cài đặt")
    add(root, row1)
    add(row1, label1)
    add(row1, count1)
    add(root, row2)
    add(row2, label2)
    add(row2, count2)
    add(root, row3)
    add(row3, settings)
    sealAll(root, row1, label1, count1, row2, label2, count2, row3, settings)
    return Rows(root, label1, count1, label2, count2, settings)
  }

  /** Two tappable rows, in reading order: a group row and a settings row. */
  private class TappableRows(
    val root: AccessibilityNodeInfo,
    val row1: AccessibilityNodeInfo,
    val label1: AccessibilityNodeInfo,
    val row3: AccessibilityNodeInfo,
    val settings: AccessibilityNodeInfo,
  )

  private fun tappableRows(): TappableRows {
    val root = node()
    val row1 = node(clickable = true)
    val label1 = node(text = "Nhóm ABC")
    val row3 = node(clickable = true)
    val settings = node(text = "Cài đặt")
    add(root, row1)
    add(row1, label1)
    add(root, row3)
    add(row3, settings)
    sealAll(root, row1, label1, row3, settings)
    return TappableRows(root, row1, label1, row3, settings)
  }

  /** Counts the clicks [node] takes, and returns a reader for that count. */
  private fun clickCounter(node: AccessibilityNodeInfo): () -> Int {
    var clicks = 0
    Shadows.shadowOf(node).setOnPerformActionListener { action, _ ->
      if (action == AccessibilityNodeInfo.ACTION_CLICK) clicks++
      true
    }
    return { clicks }
  }

  private fun node(
    text: String? = null,
    clickable: Boolean = false,
    longClickable: Boolean = false,
    bounds: Boolean = true,
  ): AccessibilityNodeInfo {
    return AccessibilityNodeInfo.obtain().apply {
      setVisibleToUser(true)
      setEnabled(true)
      text?.let { setText(it) }
      setClickable(clickable)
      setLongClickable(longClickable)
      if (bounds) setBoundsInScreen(Rect(0, 0, 100, 50))
    }
  }

  private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
    Shadows.shadowOf(parent).addChild(child)
  }

  private fun sealAll(vararg nodes: AccessibilityNodeInfo) {
    for (node in nodes) {
      ReflectionHelpers.callInstanceMethod<Any>(
        node, "setSealed", ClassParameter.from(java.lang.Boolean.TYPE, true)
      )
    }
  }
}
