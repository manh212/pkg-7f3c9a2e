package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.NodeOverrides
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalBudget
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Supplier

object AsyncTraversalOrder {
  enum class Result { READY, STALE, FAILED, CANCELLED }

  private class Job(
    val windowId: Int,
    val revision: Long,
    val root: Supplier<AccessibilityNodeInfoCompat?>,
    val eventId: Performance.EventId?,
    val callbacks: MutableList<Consumer<Result>> = ArrayList(),
    val discard: Runnable? = null,
  ) {
    @Volatile var cancelled = false
  }

  private val handler = Handler(Looper.getMainLooper())
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "NavigationOrder").apply { isDaemon = true }
  }
  private var active: Job? = null
  private var pending: Job? = null
  private var warmTask: Runnable? = null

  @JvmStatic
  fun request(
    root: AccessibilityNodeInfoCompat,
    eventId: Performance.EventId?,
    callback: Consumer<Result>,
  ) {
    val revision = TraversalTreeCache.revision()
    val running = active
    if (running != null && !running.cancelled && running.windowId == root.windowId &&
      running.revision == revision) {
      running.callbacks += callback
      return
    }
    val copy = AccessibilityNodeInfoCompat.obtain(root)
    val job = Job(root.windowId, revision, Supplier { copy }, eventId, discard = Runnable { copy.recycle() })
    job.callbacks += callback
    if (active == null) start(job) else {
      pending?.let { old ->
        old.discard?.run()
        old.callbacks.forEach { it.accept(Result.CANCELLED) }
      }
      pending = job
    }
  }

  @JvmStatic
  fun warm(windowId: Int, root: Supplier<AccessibilityNodeInfoCompat?>) {
    warmTask?.let { handler.removeCallbacks(it) }
    if (!LatencyExperiments.Feature.ASYNC_ORDER.enabled() || !TraversalTreeCache.enabled ||
      !LatencyExperiments.preparationAllowed() || NodeOverrides.hasSource() || windowId < 0) return
    // 2C: build the reading order at once on focus change, instead of waiting 150 ms for
    // input to go quiet. The order is usually ready by the time the 30 ms prepare timer
    // fires, so the next swipe's targets can be prepared instead of reported MISSING_ORDER.
    // The build still runs on the single NavigationOrder thread with its 200 ms budget, and
    // warm() is still a no-op while a build is active.
    // Q6: the build must not start until the focus event's feedback is composed and queued.
    // warm() is called before the event reaches the processor, which only queues the event
    // for the compositor — ProcessorEventQueue posts its SPEAK turn after warm's post — so
    // a single post would start the whole-tree walk while the main thread is still
    // composing that feedback. The first post therefore only re-posts: the build starts on
    // the next turn, after the queued composition turn has run. Still prompt — one extra
    // queue turn, no timer — and cancel() still drops the pending build at either step.
    // Paired with Q5 (EventFilter retries the prepare when the order is ready): the order
    // arrives one turn later, and the prepare waits for it instead of a fixed timer.
    val startTask = Runnable {
      warmTask = null
      if (active == null && LatencyExperiments.Feature.ASYNC_ORDER.enabled() &&
        !NodeOverrides.hasSource() && LatencyExperiments.preparationAllowed()) {
        start(Job(windowId, TraversalTreeCache.revision(), root, null))
      }
    }
    val deferTask = Runnable {
      warmTask = startTask
      handler.post(startTask)
    }
    warmTask = deferTask
    handler.post(deferTask)
  }

  @JvmStatic
  fun onEvent(event: AccessibilityEvent) {
    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
      cancel()
    } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
        AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0 &&
      active?.windowId != event.windowId) {
      cancel()
    }
  }

  @JvmStatic
  fun cancel() {
    warmTask?.let { handler.removeCallbacks(it) }
    warmTask = null
    val waiting = pending
    val running = active
    pending = null
    active = null
    TraversalTreeCache.setBuildingWindow(-1)
    waiting?.discard?.run()
    for (job in listOfNotNull(running, waiting)) {
      job.cancelled = true
      val callbacks = job.callbacks.toList()
      job.callbacks.clear()
      callbacks.forEach { it.accept(Result.CANCELLED) }
    }
  }

  private fun start(job: Job) {
    if (!LatencyExperiments.Feature.ASYNC_ORDER.enabled() || NodeOverrides.hasSource()) {
      job.discard?.run()
      job.callbacks.toList().forEach { it.accept(Result.CANCELLED) }
      return
    }
    active = job
    TraversalTreeCache.setBuildingWindow(job.windowId)
    executor.execute {
      val perf = Performance.getInstance()
      val token = perf.beginNavigationWork()
      val started = SystemClock.uptimeMillis()
      var root: AccessibilityNodeInfoCompat? = null
      val strategy = try {
        if (job.cancelled) {
          job.discard?.run()
        } else {
          root = job.root.get()
        }
        root?.let { value ->
          TraversalBudget.run(started + 200, Supplier { OrderedTraversalStrategy(value) })
        }
      } catch (failure: RuntimeException) {
        null
      }
      val resolvedRoot = root
      val duration = SystemClock.uptimeMillis() - started
      if (job.eventId != null) perf.endNavigationWork(job.eventId, "order_async", token)
      handler.post {
        if (active !== job) {
          resolvedRoot?.recycle()
          return@post
        }
        TraversalTreeCache.noteBuild(duration)
        val result = when {
          job.cancelled || !LatencyExperiments.Feature.ASYNC_ORDER.enabled() -> Result.CANCELLED
          NodeOverrides.hasSource() -> Result.STALE
          job.revision != TraversalTreeCache.revision() -> Result.STALE
          strategy == null || resolvedRoot == null -> Result.FAILED
          else -> {
            TraversalTreeCache.put(resolvedRoot, strategy)
            Result.READY
          }
        }
        if (result != Result.READY) resolvedRoot?.recycle()
        if (active === job) active = null
        TraversalTreeCache.setBuildingWindow(-1)
        val next = pending
        pending = null
        job.callbacks.toList().forEach { it.accept(result) }
        if (next != null && active == null) start(next)
      }
    }
  }
}
