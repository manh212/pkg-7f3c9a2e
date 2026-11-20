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

package com.google.android.accessibility.talkback.focusmanagement.record

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.AccessibilityNode
import com.google.android.accessibility.utils.input.WindowsDelegate
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/**
 * Finding the node to refocus after the screen changed asks the app for nodes on the main thread,
 * so it is done once per change, each ancestor is asked for once, and the search after a content
 * change has a time limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefocusSearchTest {

  private class Node(label: String, web: Boolean = false) :
    AccessibilityNodeInfoCompat(
      AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication()))
    ) {
    var owner: Node? = null
    val children = mutableListOf<Node>()
    var alive = true
    var refreshes = 0
    var childReads = 0
    var answerMs = 0L

    init {
      text = label
      className = "android.view.View"
      packageName = "example.app"
      isVisibleToUser = true
      if (web) {
        addAction(ACTION_NEXT_HTML_ELEMENT)
        addAction(ACTION_PREVIOUS_HTML_ELEMENT)
      }
    }

    fun add(vararg nodes: Node) = nodes.forEach {
      it.owner = this
      children.add(it)
    }

    private fun answer() {
      if (answerMs > 0) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(answerMs))
      }
    }

    override fun refresh(): Boolean {
      refreshes++
      answer()
      return alive
    }

    override fun getParent(): AccessibilityNodeInfoCompat? = owner

    override fun getParent(prefetchingStrategy: Int): AccessibilityNodeInfoCompat? = owner

    override fun getChildCount() = children.size

    override fun getChild(index: Int): AccessibilityNodeInfoCompat? {
      childReads++
      answer()
      return children.getOrNull(index)
    }

    override fun getChild(index: Int, prefetchingStrategy: Int) = getChild(index)
  }

  private val focusFinder = FocusFinder(null)

  private fun newHistory() = AccessibilityFocusActionHistory(RuntimeEnvironment.getApplication())

  /** Seeks [record] in [root] between startRefocus and endRefocus, as after a content change. */
  private fun seekDuringRefocus(
    history: AccessibilityFocusActionHistory,
    root: AccessibilityNodeInfoCompat,
    record: FocusActionRecord,
  ): AccessibilityNodeInfoCompat? {
    history.startRefocus()
    try {
      return history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record)
    } finally {
      history.endRefocus()
    }
  }

  /** Returns [path] with the ancestors' positions read until [deadline]. */
  private fun withSiblingContext(
    path: NodePathDescription,
    deadline: NodePathDescription.Deadline,
  ): List<NodeDescription> =
    descriptions(NodePathDescription(path).apply { addSiblingContext(1, deadline) })

  private fun record(node: AccessibilityNodeInfoCompat) =
    FocusActionRecord(node, FocusActionInfo.builder().build(), /* actionTime= */ 1000)

  private fun descriptions(path: NodePathDescription): List<NodeDescription> =
    ReflectionHelpers.getField(path, "nodeDescriptions")

  private fun newRoot() =
    Node("").apply {
      text = null
      isVisibleToUser = false
    }

  private fun chain(depth: Int, web: Boolean = false): List<Node> {
    val nodes = List(depth + 1) { Node("node-$it", web) }
    for (i in 0 until depth) {
      nodes[i].add(nodes[i + 1])
    }
    return nodes
  }

  @Test
  fun aRecordNotFoundIsNotSoughtAgainInTheSameWindowDuringOneRefocus() {
    val nodes = chain(depth = 6, web = true)
    val record = record(nodes.last())
    nodes.forEach { it.alive = false }
    val root = newRoot()
    val history = AccessibilityFocusActionHistory(RuntimeEnvironment.getApplication())

    history.startRefocus()
    assertNull(history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record))
    val requests = nodes.sumOf { it.refreshes + it.childReads }
    assertTrue(requests > 0)
    assertNull(history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record))
    assertEquals(requests, nodes.sumOf { it.refreshes + it.childReads })
    history.endRefocus()

    assertNull(history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record))
    assertTrue(nodes.sumOf { it.refreshes + it.childReads } > requests)
  }

  @Test
  fun outsideARefocusEverySearchAsksTheApp() {
    val nodes = chain(depth = 3)
    val record = record(nodes.last())
    nodes.forEach { it.alive = false }
    val root = newRoot()
    val history = AccessibilityFocusActionHistory(RuntimeEnvironment.getApplication())

    history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record)
    val requests = nodes.sumOf { it.refreshes }
    history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record)
    assertEquals(2 * requests, nodes.sumOf { it.refreshes })
  }

  @Test
  fun eachAncestorWithoutAUniqueIdIsRefreshedOnce() {
    val nodes = chain(depth = 5)
    val record = record(nodes.last())
    nodes.forEach { it.alive = false }

    newHistory().reader.getFocusableNodeFromFocusRecord(newRoot(), focusFinder, record)

    for (ancestor in nodes.dropLast(1)) {
      assertEquals(ancestor.text.toString(), 1, ancestor.refreshes)
    }
  }

  /** Removes [nodes] from the app, makes each of them answer in 100 ms, and resets the counts. */
  private fun removeAndSlowDown(nodes: List<Node>) =
    nodes.forEach {
      it.alive = false
      it.answerMs = 100
      it.refreshes = 0
      it.childReads = 0
    }

  @Test
  fun theSearchStopsAtItsTimeLimitDuringARefocus() {
    val nodes = chain(depth = 30)
    val record = record(nodes.last())
    removeAndSlowDown(nodes)
    val start = SystemClock.uptimeMillis()

    assertNull(seekDuringRefocus(newHistory(), newRoot(), record))

    // One refresh of the focused node before the search, then the requests that fit in the limit.
    val requests = nodes.sumOf { it.refreshes + it.childReads }
    assertTrue(
      "$requests requests",
      requests <= 1 + NodePathDescription.SEARCH_TIME_LIMIT_MS / 100 + 1,
    )
    assertTrue(SystemClock.uptimeMillis() - start <= NodePathDescription.SEARCH_TIME_LIMIT_MS + 200)
  }

  @Test
  fun aSearchOutsideARefocusHasNoTimeLimit() {
    val nodes = chain(depth = 30)
    val record = record(nodes.last())
    removeAndSlowDown(nodes)

    newHistory().reader.getFocusableNodeFromFocusRecord(newRoot(), focusFinder, record)

    // The upward search reaches the root.
    for (ancestor in nodes.dropLast(1)) {
      assertEquals(ancestor.text.toString(), 1, ancestor.refreshes)
    }
  }

  @Test
  fun aSearchStoppedAtItsTimeLimitIsSoughtAgainDuringTheSameRefocus() {
    val nodes = chain(depth = 30)
    val record = record(nodes.last())
    removeAndSlowDown(nodes)
    val root = newRoot()
    val history = newHistory()

    history.startRefocus()
    assertNull(history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record))
    val requests = nodes.sumOf { it.refreshes + it.childReads }
    assertNull(history.reader.getFocusableNodeFromFocusRecord(root, focusFinder, record))
    history.endRefocus()

    assertTrue(nodes.sumOf { it.refreshes + it.childReads } > requests)
  }

  @Test
  fun theAncestorsNearestTheRootAreLeftWithoutPositionsAtTheTimeLimit() {
    val root = Node("root", web = true)
    val outer = Node("outer", web = true)
    val group = Node("group", web = true)
    val first = Node("first", web = true)
    val leaf = Node("leaf", web = true)
    val last = Node("last", web = true)
    root.add(outer)
    outer.add(group)
    group.add(first, leaf, last)
    val record = record(leaf)
    outer.answerMs = NodePathDescription.SEARCH_TIME_LIMIT_MS

    val path =
      withSiblingContext(
        record.ancestorIdentityPath,
        NodePathDescription.Deadline.afterSearchTimeLimit(),
      )

    assertEquals(1, path[0].rawIndexInParent)
    assertEquals(0, path[1].rawIndexInParent)
    assertEquals(NodeDescription.UNDEFINED_INDEX, path[2].rawIndexInParent)
    assertEquals(0, root.childReads)
  }

  @Test
  fun aPassedDeadlineReadsNoSiblings() {
    val nodes = chain(depth = 3, web = true)
    val record = record(nodes.last())
    nodes.forEach { it.childReads = 0 }

    val path =
      withSiblingContext(
        record.ancestorIdentityPath,
        NodePathDescription.Deadline(SystemClock.uptimeMillis()),
      )

    assertEquals(0, nodes.sumOf { it.childReads })
    assertTrue(path.drop(1).all { it.rawIndexInParent == NodeDescription.UNDEFINED_INDEX })
  }

  @Test
  fun siblingsNotReadByTheTimeLimitAreReadByTheNextSearch() {
    // root > outer > group > (first, leaf, last), in web content.
    val root = Node("root", web = true)
    val outer = Node("outer", web = true)
    val group = Node("group", web = true)
    val first = Node("first", web = true)
    val leaf = Node("leaf", web = true)
    val last = Node("last", web = true)
    root.add(outer)
    outer.add(group)
    group.add(first, leaf, last)
    val record = record(leaf)
    leaf.alive = false
    // Finding the group among the children of outer takes the whole time limit.
    outer.answerMs = NodePathDescription.SEARCH_TIME_LIMIT_MS
    val history = newHistory()

    seekDuringRefocus(history, newRoot(), record)

    // Sibling reads exhausted the restoration budget; the downward search starts no new IPC.
    assertEquals(0, group.refreshes)
    // The position of outer was not read, and is read the next time, without reading the group's
    // position again.
    assertEquals(0, root.childReads)
    outer.answerMs = 0
    val outerChildReads = outer.childReads
    val path = descriptions(record.nodePathDescription)
    assertTrue(root.childReads > 0)
    assertEquals(0, path[2].rawIndexInParent)
    assertEquals(0, path[1].rawIndexInParent)
    assertEquals(outerChildReads, outer.childReads)
  }

  @Test
  fun aReplacedListItemIsFoundAtItsPlace() {
    val root = Node("root")
    val list = Node("list")
    val a = Node("a")
    val b = Node("b")
    val c = Node("c")
    root.add(list)
    list.add(a, b, c)
    val record = record(b)

    b.alive = false
    val newB = Node("b")
    newB.owner = list
    list.children[1] = newB

    val found =
      record.nodePathDescription.findNodeToRefocus(
        root,
        focusFinder,
        NodePathDescription.Deadline.afterSearchTimeLimit(),
      )

    assertEquals(newB, found)
  }
  private fun document(label: String, id: String) = Node(label, web = true).apply {
    className = "android.webkit.WebView"
    uniqueId = id
    extras.putString("AccessibilityNodeInfo.chromeRole", "rootWebArea")
  }

  @Test fun aMissingDocumentDoesNotSearchTheContentsOfOtherPages() {
    val old = document("old", "document-old").apply { alive = false }
    val other = document("other", "document-other").apply {
      add(*Array(200) { Node("unrelated-$it", web = true) })
    }
    val root = Node("window").apply { add(other) }
    assertNull(FocusActionRecord.findRecordedDocument(root, old))
    assertEquals(0, other.childReads)
  }

  @Test fun theSameDocumentCanBeFoundUnderANewHost() {
    val old = document("old host", "same-document").apply { alive = false }
    val other = document("other", "another-document")
    val moved = document("new host", "same-document")
    val root = Node("window").apply { add(other, Node("new native host").apply { add(moved) }) }
    assertEquals(moved, FocusActionRecord.findRecordedDocument(root, old))
    assertEquals(0, other.childReads)
  }

  @Test fun aLivingDocumentDoesNotNeedAWindowSearch() {
    val old = document("existing", "same-document")
    val root = Node("window").apply { add(old) }
    assertEquals(old, FocusActionRecord.findRecordedDocument(root, old))
    assertEquals(0, root.childReads)
  }
  @Test fun aRememberedWebChildPositionIsValidatedAfterAnInsertion() {
    val parent = Node("parent", web = true)
    val children = Array(12) { Node("child-$it", web = true) }
    parent.add(*children)
    val child = AccessibilityNode.takeOwnership(children[10])!!
    val owner = AccessibilityNode.takeOwnership(parent)!!
    assertEquals(10, NodeDescription.getRawIndexInParent(child, owner, NodePathDescription.Deadline.none()))
    parent.childReads = 0
    assertEquals(10, NodeDescription.getRawIndexInParent(child, owner, NodePathDescription.Deadline.none()))
    assertEquals(1, parent.childReads)
    parent.children.add(0, Node("inserted", web = true).apply { this.owner = parent })
    parent.childReads = 0
    assertEquals(11, NodeDescription.getRawIndexInParent(child, owner, NodePathDescription.Deadline.none()))
    assertTrue(parent.childReads > 1)
  }

  @Test fun readingTheNextSiblingsTextAlsoRemembersItsPosition() {
    val parent = Node("parent", web = true)
    val children = Array(12) { Node("child-$it", web = true) }
    parent.add(*children)
    val owner = AccessibilityNode.takeOwnership(parent)!!
    NodeDescription.withoutSiblingContext(children[9], true).withSiblingContext(owner, true, 50)
    parent.childReads = 0
    assertEquals(10, NodeDescription.getRawIndexInParent(
      AccessibilityNode.takeOwnership(children[10])!!, owner, NodePathDescription.Deadline.none()))
    assertEquals(1, parent.childReads)
  }

  @Test fun uniqueIdSearchChecksTheBudgetWithinOneWideParentsChildren() {
    val old = Node("old", web = true).apply { uniqueId = "old-item" }
    val record = record(old)
    old.alive = false
    val root = Node("large page").apply {
      add(*Array(100) { Node("item-$it", web = true) })
      answerMs = 100
    }
    val stopped = java.util.concurrent.atomic.AtomicBoolean()
    assertNull(FocusActionRecord.getFocusableNodeFromFocusRecord(root, focusFinder, record, true, stopped))
    assertTrue(stopped.get())
    assertEquals(3, root.childReads)
  }

  @Test fun textCollectionStopsBeforeTheNextChildAfterItsDeadline() {
    val root = Node("root").apply {
      add(*Array(10) { Node("text-$it") })
      answerMs = 150
    }
    val text = NodeDescription.getText(AccessibilityNode.takeOwnership(root), true,
      NodePathDescription.Deadline.afterSearchTimeLimit())
    assertEquals(2, root.childReads)
    assertTrue(text.toString().contains("text-0"))
  }

  @Test fun aPositionFoundAtTheDeadlineIsKeptWhenItsNeighborsTextIsNotReady() {
    val root = Node("root", web = true)
    val outer = Node("outer", web = true)
    val group = Node("group", web = true)
    val leaf = Node("leaf", web = true)
    root.add(outer)
    outer.add(group, Node("neighbor", web = true))
    group.add(leaf)
    val path = NodePathDescription(record(leaf).ancestorIdentityPath)
    outer.answerMs = NodePathDescription.SEARCH_TIME_LIMIT_MS
    val next = path.addSiblingContext(1, NodePathDescription.Deadline.afterSearchTimeLimit())
    assertEquals(2, next)
    assertEquals(0, descriptions(path)[1].rawIndexInParent)
    val reads = outer.childReads
    outer.answerMs = 0
    path.addSiblingContext(next, NodePathDescription.Deadline.afterSearchTimeLimit())
    assertEquals(reads, outer.childReads)
  }

  private fun chatList(): Node {
    val list = Node("list").apply {
      text = null
      unwrap().collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(1, 1, false)
    }
    return list
  }

  private fun chatRow(label: String, id: String) =
    Node(label).apply {
      uniqueId = id
      isClickable = true
    }

  @Test
  fun aRecycledCollectionRowIsNotReturnedByUniqueId() {
    // The chat list, with the group row focused and recorded.
    val root = Node("root")
    val list = chatList()
    val group = chatRow("Meridian\nlast message", "row-group")
    root.add(list)
    list.add(group)
    val record = record(group)

    // Control: before any rebind, the record restores the row by its unique id.
    assertEquals(group, seekDuringRefocus(newHistory(), root, record))

    // The list re-sorts while the user is inside the group: the ViewHolder that showed
    // the group now shows a DM. Same view, same unique id, different text.
    group.text = "Duy Nguyen\nlast message"

    assertNull(seekDuringRefocus(newHistory(), root, record))
  }

  @Test
  fun aPositionMatchWithDifferentTextIsNotReturnedWhenTheRowIsGone() {
    // The chat list, with the group row focused and recorded.
    val root = Node("root")
    val list = chatList()
    val a = chatRow("A\nlast message", "row-a")
    val group = chatRow("Meridian\nlast message", "row-group")
    val dm = chatRow("Duy Nguyen\nlast message", "row-dm")
    root.add(list)
    list.add(a, group, dm)
    val record = record(group)

    // Control: before the re-sort, the record restores the group row.
    assertEquals(group, seekDuringRefocus(newHistory(), root, record))

    // The list re-sorts while the user is inside the group, and the group row scrolls
    // off-screen: the DM now sits at the recorded position.
    group.alive = false
    list.children.remove(group)
    group.owner = null

    assertNull(seekDuringRefocus(newHistory(), root, record))
  }

  @Test
  fun anAppPlacedFocusIsRememberedForItsPaneButNotForEventMatching() {
    val history = newHistory()
    val screen = screen()
    val talkbackNode = windowed(Node("TalkBack"))
    history.onAccessibilityFocusAction(
        talkbackNode,
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
        /* actionTime= */ SystemClock.uptimeMillis(),
        screen)
    val placedNode = windowed(Node("Placed"))
    history.onAppPlacedFocus(
        placedNode,
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.APP_PLACED_FOCUS).build(),
        /* actionTime= */ SystemClock.uptimeMillis(),
        screen)

    // The pane record is the placed node ...
    val paneRecord =
        history.reader.getLastFocusActionRecordInWindow(WindowIdentifier.create(WINDOW, screen))
    assertNotNull(paneRecord)
    assertEquals("Placed", paneRecord?.getFocusedNode()?.text?.toString())
    // ... but the action queue still ends at the TalkBack action ...
    assertEquals("TalkBack", history.getLastFocusActionRecord()?.getFocusedNode()?.text?.toString())
    // ... so a later focus event on the placed node does not look TalkBack-assigned ...
    assertNull(history.matchFocusActionRecordFromEvent(focusEvent(placedNode)))
    // ... while the TalkBack node's own event still matches.
    assertNotNull(history.matchFocusActionRecordFromEvent(focusEvent(talkbackNode)))
  }

  @Test
  fun anAppPlacedFocusOnAnEditFieldBecomesTheLastEditableRecord() {
    val history = newHistory()
    val screen = screen()
    val editNode = windowed(Node("Message")).apply { isEditable = true }
    history.onAppPlacedFocus(
        editNode,
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.APP_PLACED_FOCUS).build(),
        /* actionTime= */ SystemClock.uptimeMillis(),
        screen)
    assertEquals(
        "Message",
        history.reader.getLastEditableFocusActionRecord()?.getFocusedNode()?.text?.toString())

    // A later placement on a non-editable node leaves the edit field as the last editable.
    val messageNode = windowed(Node("Hello"))
    history.onAppPlacedFocus(
        messageNode,
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.APP_PLACED_FOCUS).build(),
        /* actionTime= */ SystemClock.uptimeMillis(),
        screen)
    assertEquals(
        "Message",
        history.reader.getLastEditableFocusActionRecord()?.getFocusedNode()?.text?.toString())
  }

  /** A screen state for a window whose title is its id, with no pane title. */
  private fun screen(): ScreenState {
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(WINDOW)
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
    shadowOf(window).setActive(true)
    shadowOf(window).setFocused(true)
    return ScreenState(windows, window, SystemClock.uptimeMillis())
  }

  private val windows =
    object : WindowsDelegate {
      override fun getWindowTitle(windowId: Int): CharSequence = "Window $windowId"

      override fun getAccessibilityPaneTitle(windowId: Int): CharSequence = ""

      override fun isSplitScreenMode(displayId: Int): Boolean = false

      override fun hasAccessibilityPane(windowId: Int): Boolean = false
    }

  /** Reports [node] as living in the test window. */
  private fun windowed(node: Node): Node {
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(WINDOW)
    shadowOf(node.unwrap()).setAccessibilityWindowInfo(window)
    return node
  }

  /** A focus event whose source is [node]. */
  private fun focusEvent(node: AccessibilityNodeInfoCompat): AccessibilityEvent {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(event).setSourceNode(node.unwrap())
    return event
  }

  private companion object {
    const val WINDOW = 7
  }
}
