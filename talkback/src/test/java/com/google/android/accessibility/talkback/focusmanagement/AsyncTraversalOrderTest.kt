package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.LatencyExperiments
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncTraversalOrderTest {
  @Before
  fun setUp() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, true)
    LatencyExperiments.setPreparationAllowed(true)
    TraversalTreeCache.enabled = true
    TraversalTreeCache.clear("test")
  }

  @After
  fun reset() {
    AsyncTraversalOrder.cancel()
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, false)
    LatencyExperiments.setPreparationAllowed(false)
    TraversalTreeCache.clear("test")
  }

  private fun root(): AccessibilityNodeInfoCompat =
    AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain().apply { packageName = "example" })

  private fun await(result: AtomicReference<AsyncTraversalOrder.Result?>): AsyncTraversalOrder.Result {
    val end = System.nanoTime() + 3_000_000_000L
    while (System.nanoTime() < end) {
      shadowOf(Looper.getMainLooper()).idle()
      result.get()?.let { return it }
      Thread.sleep(5)
    }
    fail("Background order did not publish a result at uptime ${SystemClock.uptimeMillis()}")
    throw AssertionError()
  }

  @Test
  fun completeOrderPublishesBeforeTheNavigationCallback() {
    val root = root()
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root, null) {
      if (it == AsyncTraversalOrder.Result.READY) assertNotNull(TraversalTreeCache.get(root, root))
      result.set(it)
    }
    assertEquals(AsyncTraversalOrder.Result.READY, await(result))
  }

  @Test
  fun changedContentRejectsTheOrderBeforePublishingIt() {
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { result.set(it) }
    val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    TraversalTreeCache.onAccessibilityEvent(changed)
    changed.recycle()
    assertEquals(AsyncTraversalOrder.Result.STALE, await(result))
  }

  @Test
  fun cancellationImmediatelyReleasesTheWaitingNavigation() {
    var callbacks = 0
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { callbacks++; result.set(it) }
    AsyncTraversalOrder.cancel()
    assertEquals(AsyncTraversalOrder.Result.CANCELLED, await(result))
    assertEquals(1, callbacks)
  }
  @Test
  fun newRequestCanCompleteImmediatelyAfterCancellation() {
    val first = AtomicReference<AsyncTraversalOrder.Result?>()
    val next = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { first.set(it) }
    AsyncTraversalOrder.cancel()
    AsyncTraversalOrder.request(root(), null) { next.set(it) }
    assertEquals(AsyncTraversalOrder.Result.CANCELLED, first.get())
    assertEquals(AsyncTraversalOrder.Result.READY, await(next))
  }

  // Q6: warm() is called during the focus event's dispatch, before the processor queues
  // the event for the compositor (ProcessorEventQueue posts its SPEAK turn after warm's
  // post). The build must start only after that queued composition turn has run — one
  // extra queue turn, no timer — so the whole-tree walk never overlaps the main thread's
  // composition of the focus event's feedback.
  @Test
  fun warmBuildStartsOnlyAfterTheQueuedCompositionTurn() {
    val order = Collections.synchronizedList(mutableListOf<String>())
    val root = root()
    AsyncTraversalOrder.warm(7, Supplier {
      order.add("build-started")
      root
    })
    // The compositor's turn, queued after warm's post.
    Handler(Looper.getMainLooper()).post { order.add("composed") }
    val shadowLooper = shadowOf(Looper.getMainLooper())
    // First turn: warm's defer task runs and re-posts the real start. No build yet.
    shadowLooper.runOneTask()
    assertEquals(emptyList<String>(), order)
    // Second turn: the compositor's turn runs. Still no build.
    shadowLooper.runOneTask()
    assertEquals(listOf("composed"), order)
    // Third turn: the deferred start runs, and the build starts on the NavigationOrder thread.
    shadowLooper.runOneTask()
    awaitEntry(order, "build-started")
    assertEquals(listOf("composed", "build-started"), order)
  }

  // Q6: calling warm() again before the looper runs replaces the pending defer, so only
  // one build starts.
  @Test
  fun warmCalledTwiceBeforeTheLooperRunsStartsOnlyOneBuild() {
    val starts = AtomicInteger(0)
    val root = root()
    AsyncTraversalOrder.warm(7, Supplier {
      starts.incrementAndGet()
      root
    })
    AsyncTraversalOrder.warm(7, Supplier {
      starts.incrementAndGet()
      root
    })
    shadowOf(Looper.getMainLooper()).idle()
    awaitStarts(starts, 1)
    assertEquals(1, starts.get())
  }

  // Q6: cancel() before any turn drops the pending defer, so no build starts.
  @Test
  fun warmCancelledBeforeTheDeferTurnNeverBuilds() {
    val starts = AtomicInteger(0)
    val root = root()
    AsyncTraversalOrder.warm(7, Supplier {
      starts.incrementAndGet()
      root
    })
    AsyncTraversalOrder.cancel()
    shadowOf(Looper.getMainLooper()).idle()
    Thread.sleep(100)
    assertEquals(0, starts.get())
  }

  // Q6: cancel() after the defer turn ran but before the deferred start drops the start
  // too — the pending task is tracked across the handoff from defer to start.
  @Test
  fun warmCancelledAfterTheDeferTurnNeverBuilds() {
    val starts = AtomicInteger(0)
    val root = root()
    AsyncTraversalOrder.warm(7, Supplier {
      starts.incrementAndGet()
      root
    })
    shadowOf(Looper.getMainLooper()).runOneTask()
    AsyncTraversalOrder.cancel()
    shadowOf(Looper.getMainLooper()).idle()
    Thread.sleep(100)
    assertEquals(0, starts.get())
  }

  private fun awaitEntry(order: List<String>, entry: String) {
    val end = System.nanoTime() + 3_000_000_000L
    while (System.nanoTime() < end) {
      if (order.contains(entry)) return
      Thread.sleep(5)
    }
    fail("Warm build did not start: '$entry' was never recorded")
  }

  private fun awaitStarts(starts: AtomicInteger, expected: Int) {
    val end = System.nanoTime() + 3_000_000_000L
    while (System.nanoTime() < end) {
      if (starts.get() == expected) return
      Thread.sleep(5)
    }
    fail("Expected $expected build starts, got ${starts.get()}")
  }
}
