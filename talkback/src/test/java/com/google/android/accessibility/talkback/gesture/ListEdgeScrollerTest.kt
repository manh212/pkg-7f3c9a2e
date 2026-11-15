package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.utils.FocusFinder
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ListEdgeScrollerTest {
  class ScrollService : AccessibilityService() {
    val root: AccessibilityNodeInfo = AccessibilityNodeInfo.obtain()
    override fun getRootInActiveWindow(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(root)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
  }

  private val service = Robolectric.buildService(ScrollService::class.java).create().get()
  private val node = AccessibilityNodeInfoCompat.wrap(service.root)
  private val focusMonitor = AccessibilityFocusMonitor(
    service, FocusFinder(service), AccessibilityFocusActionHistory(service).reader,
  )
  private lateinit var scroller: ListEdgeScroller

  @After
  fun tearDown() {
    if (::scroller.isInitialized) scroller.cancel()
  }

  @Test
  fun scrollsPastAHundredPagesAndStopsWhenTheTargetStopsMoving() {
    val action = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN
    setUpNode(action)
    var pages = 0
    scroller = ListEdgeScroller(service, Pipeline.FeedbackReturner { feedback ->
      assertEquals(action.id, feedback.failovers().first().scroll()!!.nodeAction())
      if (pages < 150) {
        pages++
        sendEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
      }
      true
    }, focusMonitor)

    scroller.startAutomaticScroll(node, toStart = false, eventId = null)
    advance(45_000)

    assertEquals(150, pages)
    assertFalse(ListEdgeScroller.isActive)
  }

  @Test
  fun touchStopsAutomaticScrollingUp() {
    val action = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PAGE_UP
    setUpNode(action)
    var requests = 0
    scroller = ListEdgeScroller(service, Pipeline.FeedbackReturner { feedback ->
      assertEquals(action.id, feedback.failovers().first().scroll()!!.nodeAction())
      requests++
      sendEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
      true
    }, focusMonitor)
    scroller.startAutomaticScroll(node, toStart = true, eventId = null)
    advance(250)

    sendEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    advance(5_000)

    assertEquals(1, requests)
    assertFalse(ListEdgeScroller.isActive)
  }

  private fun setUpNode(action: AccessibilityNodeInfoCompat.AccessibilityActionCompat) {
    node.isVisibleToUser = true
    node.addAction(action)
    shadowOf(service.root).setRefreshReturnValue(true)
    shadowOf(checkNotNull(service.getSystemService(PowerManager::class.java))).setIsInteractive(true)
  }

  private fun sendEvent(type: Int) {
    val event = AccessibilityEvent.obtain(type)
    shadowOf(event).setWindowId(node.windowId)
    scroller.onAccessibilityEvent(event)
    event.recycle()
  }

  private fun advance(ms: Long) =
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
