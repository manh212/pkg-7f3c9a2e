package com.google.android.accessibility.talkback.compositor

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedSpeechExperimentsTest {
  @Before
  fun setUp() { reset() }

  @After
  fun reset() {
    PreparedFocusSpeech.clear()
    TraversalTreeCache.clear("test")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
  }

  @Test
  fun freshModeUsesCurrentContentWithoutTheAgeFloorButNeverTheWrongTargetType() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, true)
    val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
    PreparedFocusSpeech.put(node, EventFeedback.builder().build(), "", NavigationTarget.TARGET_HEADING)
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_DEFAULT))
    assertNotNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_HEADING))
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_HEADING))
  }

  @Test
  fun currentContentIsRejectedImmediatelyAfterInvalidation() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, true)
    val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
    PreparedFocusSpeech.put(node, EventFeedback.builder().build(), "")
    assertNotNull(PreparedFocusSpeech.peek(node, NavigationTarget.TARGET_DEFAULT))
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    PreparedFocusSpeech.onAccessibilityEvent(event)
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_DEFAULT))
    event.recycle()
  }

  @Test
  fun scopedInvalidationPreservesForeignWindowsAndRejectsUnknownWindows() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
    assertFalse(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 8, 7))
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, -1, 7))
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 7, 7))
  }

  @Test
  fun scopedInvalidationKeepsOnlyTargetsOutsideAKnownContentChange() {
    val windowId = 71
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(windowId)
    val info = AccessibilityNodeInfo()
    info.isVisibleToUser = true
    info.isFocusable = true
    shadowOf(info).setAccessibilityWindowInfo(window)
    info.setBoundsInScreen(Rect(0, 0, 100, 100))
    val target = AccessibilityNodeInfoCompat.wrap(info)
    TraversalTreeCache.put(target, OrderedTraversalStrategy(target))
    PreparedFocusSpeech.put(target, EventFeedback.builder().build(), "")
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    shadowOf(event).setWindowId(windowId)
    event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT

    TraversalTreeCache.noteGracedChange(windowId, Rect(0, 200, 100, 300))
    PreparedFocusSpeech.onAccessibilityEvent(event)
    assertNotNull(PreparedFocusSpeech.peek(target, NavigationTarget.TARGET_DEFAULT))

    TraversalTreeCache.noteGracedChange(windowId, Rect(0, 70, 100, 120))
    PreparedFocusSpeech.onAccessibilityEvent(event)
    assertNull(PreparedFocusSpeech.peek(target, NavigationTarget.TARGET_DEFAULT))
    event.recycle()
  }
}
