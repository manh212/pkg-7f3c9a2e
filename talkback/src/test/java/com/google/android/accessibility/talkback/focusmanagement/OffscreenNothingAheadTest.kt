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

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * A swipe out of a scroll view doesn't scroll it when nothing after the focused item is off
 * screen, as with a dialog message of one long text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OffscreenNothingAheadTest {
  private var nextId = 1L

  private inner class Node(className: String) : AccessibilityNodeInfoCompat(info(className)) {
    var parentNode: Node? = null
    val children = mutableListOf<Node>()

    override fun getParent(): AccessibilityNodeInfoCompat? = parentNode

    override fun getChildCount(): Int = children.size

    override fun getChild(index: Int): AccessibilityNodeInfoCompat? = children[index]

    fun add(vararg nodes: Node): Node {
      nodes.forEach {
        it.parentNode = this
        children.add(it)
      }
      return this
    }
  }

  @Suppress("DEPRECATION")
  private fun info(className: String) =
    AccessibilityNodeInfo.obtain().apply {
      ReflectionHelpers.setField(this, "mSourceNodeId", nextId++)
      this.className = className
      isVisibleToUser = true
      setBoundsInParent(Rect(0, 0, 900, 100))
      setBoundsInScreen(Rect(0, 0, 900, 100))
    }

  /** A scroll view 1000 px tall on screen. */
  @Suppress("DEPRECATION")
  private fun scrollView() =
    Node("android.widget.ScrollView").apply {
      isScrollable = true
      setBoundsInParent(Rect(0, 0, 900, 1000))
      setBoundsInScreen(Rect(0, 0, 900, 1000))
    }

  /** A notification row of the shade, fully on screen from [top] to [bottom]. */
  @Suppress("DEPRECATION")
  private fun row(title: String, top: Int, bottom: Int) =
    Node("android.widget.FrameLayout").apply {
      text = title
      isFocusable = true
      setBoundsInParent(Rect(0, 0, 900, bottom - top))
      setBoundsInScreen(Rect(0, top, 900, bottom))
    }

  @Test
  @Suppress("DEPRECATION")
  fun shadeRowCutOffAtTheEdge_scrollsOnToTheRowsPastIt() {
    // The last row shown is cut off by the shade's bottom edge; the rows past it aren't in the tree.
    val first = row("Message 1", 0, 600)
    val last =
      row("Message 2", 600, 1000).apply { setBoundsInParent(Rect(0, 0, 900, 450)) }
    val scroll = scrollView().add(first, last)
    assertFalse(OffscreenContent.hasNothingAhead(scroll, last, /* forward= */ true, /* rtl= */ false))
  }
}
