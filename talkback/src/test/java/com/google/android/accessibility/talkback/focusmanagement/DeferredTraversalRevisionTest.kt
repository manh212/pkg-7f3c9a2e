package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.LatencyExperiments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeferredTraversalRevisionTest {
  @Before
  fun setUp() {
    TraversalTreeCache.clear("test")
    TraversalTreeCache.setBuildingWindow(7)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
  }

  @After
  fun reset() {
    TraversalTreeCache.setBuildingWindow(-1)
    TraversalTreeCache.clear("test")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
  }

  private fun textChange(window: Int): AccessibilityEvent =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).also {
      shadowOf(it).setWindowId(window)
      it.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    }

  // Q4 (c1): a same-window text change leaves the saved order in place, so it must not move
  // the revision either — an in-flight build of that same order is no longer discarded as
  // STALE for a change that changed nothing about the order.
  @Test
  fun deferredTextChangePreservesTheInFlightBuildRevision() {
    val before = TraversalTreeCache.revision()
    val event = textChange(7)
    TraversalTreeCache.noteDeferredEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  @Test
  fun deferredForeignUpdatesPreserveTheInFlightWindowRevision() {
    val before = TraversalTreeCache.revision()
    val event = textChange(8)
    TraversalTreeCache.noteDeferredEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  @Test
  fun knownForeignContentPreservesRevisionWithScopedExperimentOff() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
    val before = TraversalTreeCache.revision()
    val event = textChange(8)
    TraversalTreeCache.onAccessibilityEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  // Q4 (c1): same as above, for an admitted (not deferred) text change.
  @Test
  fun admittedTextChangePreservesTheInFlightBuildRevision() {
    val before = TraversalTreeCache.revision()
    val event = textChange(7)
    TraversalTreeCache.onAccessibilityEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  // Q4 (c1): a text change from an unknown window is still a text change — it cannot change
  // the order in any window, so it does not move the revision either.
  @Test
  fun deferredTextChangeFromUnknownWindowPreservesRevision() {
    val before = TraversalTreeCache.revision()
    val event = textChange(-1)
    TraversalTreeCache.noteDeferredEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  // Q4 (c1): the narrowed rule still invalidates an in-flight build for a genuinely
  // structural change, even before any order is saved — a content change with no change
  // type is treated as structural.
  @Test
  fun deferredStructuralContentStillInvalidatesAnInFlightBuild() {
    val before = TraversalTreeCache.revision()
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).also {
      shadowOf(it).setWindowId(7)
    }
    TraversalTreeCache.noteDeferredEvent(event)
    assertTrue(TraversalTreeCache.revision() > before)
    event.recycle()
  }

  @Test
  fun admittedStructuralContentStillInvalidatesAnInFlightBuildWithoutASavedTree() {
    val before = TraversalTreeCache.revision()
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).also {
      shadowOf(it).setWindowId(7)
    }
    TraversalTreeCache.onAccessibilityEvent(event)
    assertTrue(TraversalTreeCache.revision() > before)
    event.recycle()
  }
}
