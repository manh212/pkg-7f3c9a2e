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

package com.google.android.accessibility.talkback.actor

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.ActorStateWritable
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.PrimesController
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory.WindowIdentifier
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.input.WindowsDelegate
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

/**
 * Restoring the last focus when the screen changes, and what it costs: fetching a window's root
 * waits for the window's app, which is busy drawing the new screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [FocusActorForScreenStateChangeTest.RootsShadow::class])
class FocusActorForScreenStateChangeTest {

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  /** Counts how often a window's root is fetched. */
  @Implements(AccessibilityWindowInfo::class)
  class RootsShadow : ShadowAccessibilityWindowInfo() {
    @Implementation
    override fun getRoot(): AccessibilityNodeInfo? {
      rootRequests++
      return super.getRoot()
    }

    companion object {
      var rootRequests = 0
    }
  }

  /** Names each window by its id, as the window interpreter would. */
  private val windows =
    object : WindowsDelegate {
      override fun getWindowTitle(windowId: Int): CharSequence = "Window $windowId"

      override fun getAccessibilityPaneTitle(windowId: Int): CharSequence = ""

      override fun isSplitScreenMode(displayId: Int): Boolean = false

      override fun hasAccessibilityPane(windowId: Int): Boolean = false
    }

  /** Names the group pane by its title, as the check sees it when the group opens. */
  private val windowsGroup =
    object : WindowsDelegate {
      override fun getWindowTitle(windowId: Int): CharSequence = "Window $windowId"

      override fun getAccessibilityPaneTitle(windowId: Int): CharSequence = "Group"

      override fun isSplitScreenMode(displayId: Int): Boolean = false

      override fun hasAccessibilityPane(windowId: Int): Boolean = false
    }

  private lateinit var service: TestService
  private lateinit var history: AccessibilityFocusActionHistory
  private lateinit var actor: FocusActorForScreenStateChange
  private val focused = mutableListOf<AccessibilityNodeInfoCompat?>()

  @Before
  fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    FormFactorUtils.initialize(service)
    history = AccessibilityFocusActionHistory(service)
    actor =
      FocusActorForScreenStateChange(
        service,
        InputMethodMonitor(service),
        FocusFinder(service),
        PrimesController(),
      )
    actor.setActorState(
      ActorState(
        ActorStateWritable(
          null, null, null, null, history.reader, null, null, null, null, null, null, null, null,
          null,
        )
      )
    )
    actor.setPipeline(
      Pipeline.FeedbackReturner { feedback ->
        focused += feedback.failovers().first().focus()?.target()
        true
      }
    )
  }

  @Test
  fun newScreenWithoutAFocusToRestoreLeavesItsRootAlone() {
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root())
    shadowOf(service).setWindows(listOf(app))

    assertFalse(restore(app))
    assertEquals(0, RootsShadow.rootRequests)
  }

  @Test
  fun systemWindowGetsNoFocusLastPutInAnApp() {
    // The history has a focus for the shade's window id, whose node reports an application
    // window, and the node is still there. Only the rule that keeps a focus from an app out of
    // System UI stops it from being restored.
    val root = root()
    val app = window(SHADE, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    history.onAccessibilityFocusAction(button(app, root), focusInfo(), 1, screen(app))
    val shade = window(SHADE, AccessibilityWindowInfo.TYPE_SYSTEM, root)
    shadowOf(service).setWindows(listOf(shade))
    assertNotNull(
      history.reader.getLastFocusActionRecordInWindow(WindowIdentifier.create(SHADE, screen(shade)))
    )

    assertFalse(restore(shade))
    assertEquals(0, RootsShadow.rootRequests)
  }

  @Test
  fun focusLastPutInTheWindowIsRestored() {
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    history.onAccessibilityFocusAction(button(app, root), focusInfo(), 1, screen(app))
    shadowOf(service).setWindows(listOf(app))

    assertTrue(restore(app))
    assertEquals(listOf("Play"), focused.map { it?.text?.toString() })
    // One root serves to find the node and to check that the node is still in the window.
    assertEquals(1, RootsShadow.rootRequests)
  }

  @Test
  fun focusLastPutInAListIsRestoredWhenItsRowMoves() {
    // The row the user left is a collection item, so the cached node is not reused as-is, and
    // the row has moved, so the path search lands on another row with the same text at the
    // recorded position. Restoring must still land on the row the user left, because the
    // cached node still shows its recorded text.
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    shadowOf(service).setWindows(listOf(app))
    val list = collection(app, root)
    val first = row(app, list, "Play", Rect(100, 1000, 980, 1150), refresh = true)
    row(app, list, "Other", Rect(100, 1150, 980, 1300))
    row(app, list, "Play", Rect(100, 1300, 980, 1450))
    history.onAccessibilityFocusAction(first, focusInfo(), 1, screen(app))

    // The list is laid out again with another row at the recorded position and the row the
    // user left moved to the end.
    val newRoot = root()
    val newList = collection(app, newRoot)
    row(app, newList, "Play", Rect(100, 1000, 980, 1150))
    row(app, newList, "Play", Rect(100, 1150, 980, 1300))
    shadowOf(newList).addChild(first.unwrap())
    shadowOf(app).setRoot(newRoot)

    assertTrue(restore(app))
    assertEquals(listOf(first), focused)
    // One root serves to find the node and to check that the node is still in the window.
    assertEquals(1, RootsShadow.rootRequests)
  }

  @Test
  fun firstContentSkipsAToolbarUpButton() {
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    shadowOf(service).setWindows(listOf(app))
    val toolbar = toolbar(app, root)
    upButton(app, toolbar)
    button(app, root)

    assertTrue(
      actor.focusOnRequestInitialNodeOrFirstFocusableNonTitleNode(screen(app), EVENT_ID_UNTRACKED)
    )
    assertEquals(listOf("Play"), focused.map { it?.text?.toString() })
  }

  @Test
  fun firstContentSkipsAFrameworkActionBarUpButton() {
    // Meridian's own Settings uses the framework ActionBar, not a Toolbar: the Up
    // button sits in an ActionBarView inside an ActionBarContainer. Both class names
    // must count as a bar for the Q3 first-content exclusion.
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    shadowOf(service).setWindows(listOf(app))
    val actionBar = actionBar(app, root)
    upButton(app, actionBar)
    button(app, root)

    assertTrue(
      actor.focusOnRequestInitialNodeOrFirstFocusableNonTitleNode(screen(app), EVENT_ID_UNTRACKED)
    )
    assertEquals(listOf("Play"), focused.map { it?.text?.toString() })
  }

  @Test
  fun firstContentSkipsAnUpButtonInAnActionBarContainerByViewId() {
    // The framework ActionBar's container id is android:id/action_bar_container: it
    // ends in "container", not "action_bar", so the bar id pattern must list it on
    // its own.
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    shadowOf(service).setWindows(listOf(app))
    val container = plainViewGroup(app, root)
    container.viewIdResourceName = "android:id/action_bar_container"
    upButton(app, container)
    button(app, root)

    assertTrue(
      actor.focusOnRequestInitialNodeOrFirstFocusableNonTitleNode(screen(app), EVENT_ID_UNTRACKED)
    )
    assertEquals(listOf("Play"), focused.map { it?.text?.toString() })
  }

  @Test
  fun focusLostInAGroupRestoresThePanesFreshestRecordNotTheStaleRow() {
    // The group entry from the diagnostics log: the check recorded the group header
    // under the group pane's identifier, then focus was lost while the messages
    // loaded. The pane title churned, so the exact identifier now matches the older
    // list row's record, whose node the position-text guard rightly refuses. The
    // most recent record for the window — the header, titles ignored — must be
    // tried before falling through to input focus and first content.
    val root = root()
    val app = window(APP, AccessibilityWindowInfo.TYPE_APPLICATION, root)
    shadowOf(service).setWindows(listOf(app))
    val list = collection(app, root)
    val staleRow = row(app, list, "OldRow", Rect(100, 1000, 980, 1150))
    row(app, list, "OtherRow", Rect(100, 1150, 980, 1300))
    val header = header(app, root)
    history.onAccessibilityFocusAction(staleRow, focusInfo(), 1, screen(app))
    history.onAppPlacedFocus(
      header,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.APP_PLACED_FOCUS).build(),
      2,
      screen(app, windowsGroup),
    )

    // The list is laid out again with other rows at the recorded positions, and the
    // header stays: the stale row's node is gone and its position shows new text.
    val newRoot = root()
    shadowOf(newRoot).setAccessibilityWindowInfo(app)
    val newList = collection(app, newRoot)
    row(app, newList, "NewRow1", Rect(100, 1000, 980, 1150))
    row(app, newList, "NewRow2", Rect(100, 1150, 980, 1300))
    shadowOf(newRoot).addChild(header.unwrap())

    val type = AtomicInteger(FocusActionInfo.UNDEFINED)
    val found =
      FocusManagerInternal.findInitialFocusFromTargetWindowRoot(
        service,
        AccessibilityNodeInfoCompat.wrap(newRoot),
        screen(app),
        history.reader,
        FocusFinder(service),
        type,
      )

    // Only one Header node exists in the tree, so its text pins the node without
    // relying on equality through the record's copy of it.
    assertEquals("Header", found?.text?.toString())
    assertEquals(FocusActionInfo.RESTORED_LAST_FOCUS, type.get())
  }

  /** Restores the last focus into [active], counting only the roots that fetches. */
  private fun restore(active: AccessibilityWindowInfo): Boolean {
    RootsShadow.rootRequests = 0
    return actor.restoreLastFocusedNode(screen(active), EVENT_ID_UNTRACKED)
  }

  private fun screen(active: AccessibilityWindowInfo, delegate: WindowsDelegate = windows) =
    ScreenState(delegate, active, SystemClock.uptimeMillis())

  private fun root() =
    AccessibilityNodeInfo.obtain(View(service)).apply { packageName = APP_PACKAGE }

  private fun window(id: Int, type: Int, root: AccessibilityNodeInfo): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    val shadow = shadowOf(window)
    shadow.setId(id)
    shadow.setType(type)
    shadow.setActive(true)
    shadow.setFocused(true)
    shadow.setBoundsInScreen(Rect(0, 0, 1080, 2340))
    shadow.setRoot(root)
    return window
  }

  /** A button in the window, which can still be found there when its focus is restored. */
  private fun button(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfoCompat {
    val node = AccessibilityNodeInfo.obtain(View(service))
    node.packageName = APP_PACKAGE
    node.className = "android.widget.Button"
    node.text = "Play"
    node.isVisibleToUser = true
    node.isEnabled = true
    node.isClickable = true
    node.isFocusable = true
    node.setBoundsInScreen(Rect(100, 1000, 980, 1150))
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    shadowOf(root).addChild(node)
    return AccessibilityNodeInfoCompat.wrap(node)
  }

  /** A toolbar in the window, holding the navigation-Up button. */
  private fun toolbar(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfo {
    val toolbar = AccessibilityNodeInfo.obtain(View(service))
    toolbar.packageName = APP_PACKAGE
    toolbar.className = "androidx.appcompat.widget.Toolbar"
    toolbar.isVisibleToUser = true
    shadowOf(toolbar).setAccessibilityWindowInfo(window)
    shadowOf(root).addChild(toolbar)
    return toolbar
  }

  /** The navigation-Up button: clickable, no text, inside the toolbar. */
  private fun upButton(
    window: AccessibilityWindowInfo,
    toolbar: AccessibilityNodeInfo,
  ): AccessibilityNodeInfoCompat {
    val node = AccessibilityNodeInfo.obtain(View(service))
    node.packageName = APP_PACKAGE
    node.className = "android.widget.ImageButton"
    node.contentDescription = "Navigate up"
    node.isVisibleToUser = true
    node.isEnabled = true
    node.isClickable = true
    node.isFocusable = true
    node.setBoundsInScreen(Rect(0, 0, 100, 100))
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    shadowOf(toolbar).addChild(node)
    return AccessibilityNodeInfoCompat.wrap(node)
  }

  /** The framework ActionBar: a container holding the bar view, holding the Up button. */
  private fun actionBar(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfo {
    val container = AccessibilityNodeInfo.obtain(View(service))
    container.packageName = APP_PACKAGE
    container.className = "android.widget.ActionBarContainer"
    container.isVisibleToUser = true
    shadowOf(container).setAccessibilityWindowInfo(window)
    shadowOf(root).addChild(container)
    val bar = AccessibilityNodeInfo.obtain(View(service))
    bar.packageName = APP_PACKAGE
    bar.className = "android.widget.ActionBarView"
    bar.isVisibleToUser = true
    shadowOf(bar).setAccessibilityWindowInfo(window)
    shadowOf(container).addChild(bar)
    return bar
  }

  /** A plain view group: no bar class name, so only its view id can mark it a bar. */
  private fun plainViewGroup(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfo {
    val group = AccessibilityNodeInfo.obtain(View(service))
    group.packageName = APP_PACKAGE
    group.className = "android.view.ViewGroup"
    group.isVisibleToUser = true
    shadowOf(group).setAccessibilityWindowInfo(window)
    shadowOf(root).addChild(group)
    return group
  }

  /** A group header: a focusable frame the pane record points at, which stays on screen. */
  private fun header(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfoCompat {
    val node = AccessibilityNodeInfo.obtain(View(service))
    node.packageName = APP_PACKAGE
    node.className = "android.widget.FrameLayout"
    node.text = "Header"
    node.isVisibleToUser = true
    node.isEnabled = true
    node.isClickable = true
    node.isFocusable = true
    node.setBoundsInScreen(Rect(100, 200, 980, 400))
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(true)
    shadowOf(root).addChild(node)
    return AccessibilityNodeInfoCompat.wrap(node)
  }

  /** A list in the window, whose rows are collection items. */
  private fun collection(
    window: AccessibilityWindowInfo,
    root: AccessibilityNodeInfo,
  ): AccessibilityNodeInfo {
    val list = AccessibilityNodeInfo.obtain(View(service))
    list.packageName = APP_PACKAGE
    list.className = "androidx.recyclerview.widget.RecyclerView"
    list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(3, 1, false)
    list.isVisibleToUser = true
    list.setBoundsInScreen(Rect(0, 0, 1080, 2340))
    shadowOf(list).setAccessibilityWindowInfo(window)
    shadowOf(list).setRefreshReturnValue(false)
    shadowOf(root).addChild(list)
    return list
  }

  /** A focusable row in the list. Only the row the user left refreshes. */
  private fun row(
    window: AccessibilityWindowInfo,
    list: AccessibilityNodeInfo,
    text: String,
    bounds: Rect,
    refresh: Boolean = false,
  ): AccessibilityNodeInfoCompat {
    val node = AccessibilityNodeInfo.obtain(View(service))
    node.packageName = APP_PACKAGE
    node.className = "android.widget.LinearLayout"
    node.text = text
    node.isVisibleToUser = true
    node.isEnabled = true
    node.isClickable = true
    node.isFocusable = true
    node.collectionItemInfo =
      AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, 0, 1, false)
    node.setBoundsInScreen(bounds)
    shadowOf(node).setAccessibilityWindowInfo(window)
    shadowOf(node).setRefreshReturnValue(refresh)
    shadowOf(list).addChild(node)
    return AccessibilityNodeInfoCompat.wrap(node)
  }

  private fun focusInfo() =
    FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()

  private companion object {
    const val APP = 10
    const val SHADE = 20
    const val APP_PACKAGE = "com.example.app"
  }
}
