package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.Process
import com.google.android.accessibility.utils.PerformanceHintHelper
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.NodeOverrides
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.Performance.PreparedSpeechWork
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
    // Q3: true for jobs created by warm(). A pending warm is dropped by request() and
    // replaced by a newer warm, but a warm never replaces a pending request job.
    val isWarm: Boolean = false,
  ) {
    @Volatile var cancelled = false
  }

  private val handler = Handler(Looper.getMainLooper())
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(
      {
        PerformanceHintHelper.registerWorkerThread(Process.myTid())
        task.run()
      },
      "NavigationOrder",
    ).apply { isDaemon = true }
  }
  private var active: Job? = null
  private var pending: Job? = null
  private var warmTask: Runnable? = null
  // Q3: the window of the warm whose start is still pending (deferTask or startTask not
  // yet run). Set when the warm is posted, cleared when it starts or is cancelled.
  private var warmWindowId: Int? = null

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
        // Q11f: the replaced pending build ends CANCELLED — once per job, not per
        // callback (a job's callbacks fire once each, so counting there would count
        // one build several times).
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.ORDER_BUILD_CANCELLED)
        old.callbacks.forEach { it.accept(Result.CANCELLED) }
      }
      pending = job
    }
  }

  @JvmStatic
  fun warm(windowId: Int, root: Supplier<AccessibilityNodeInfoCompat?>) {
    if (!LatencyExperiments.Feature.ASYNC_ORDER.enabled() || !TraversalTreeCache.enabled ||
      !LatencyExperiments.preparationAllowed() || NodeOverrides.hasSource() || windowId < 0 ||
      TraversalTreeCache.shouldUseLiveTraversal(windowId)) {
      // The build may not run: drop any pending warm, as today.
      warmTask?.let { handler.removeCallbacks(it) }
      warmTask = null
      warmWindowId = null
      return
    }
    // Q3: coalesce — if a warm for the same window is already pending, leave it alone
    // instead of cancelling and re-posting it. Every focus event used to re-arm the pending
    // start, so during continuous swiping the build start could be deferred indefinitely.
    // A warm for a different window still cancels the pending warm and posts a new one.
    if (warmTask != null && warmWindowId == windowId) return
    warmTask?.let { handler.removeCallbacks(it) }
    // 2C: build the reading order at once on focus change, instead of waiting 150 ms for
    // input to go quiet. The order is usually ready by the time the 30 ms prepare timer
    // fires, so the next swipe's targets can be prepared instead of reported MISSING_ORDER.
    // The build still runs on the single NavigationOrder thread with its 200 ms budget.
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
      warmWindowId = null
      if (!LatencyExperiments.Feature.ASYNC_ORDER.enabled() ||
        NodeOverrides.hasSource() || !LatencyExperiments.preparationAllowed()) return@Runnable
      val job = Job(windowId, TraversalTreeCache.revision(), root, null, isWarm = true)
      if (active == null) {
        start(job)
      } else {
        // Q3: queue the warm behind the active build instead of dropping it, so the rebuild
        // follows the active build instead of waiting for the next focus/clear event. A
        // pending warm is replaced by the newer warm; a pending request job is never
        // clobbered by a warm.
        val waiting = pending
        if (waiting == null || waiting.isWarm) {
          // Q11f: a pending warm replaced here by a newer warm ends CANCELLED — it never
          // ran and no callback is delivered for it, so count the job here, once. (This
          // fifth end is outside worker C's four-site table: without it the replaced warm
          // would reach none of the counted ends.)
          if (waiting != null) {
            Performance.getInstance()
              .onPreparedSpeechWork(PreparedSpeechWork.ORDER_BUILD_CANCELLED)
          }
          pending = job
        }
      }
    }
    val deferTask = Runnable {
      warmTask = startTask
      handler.post(startTask)
    }
    warmTask = deferTask
    warmWindowId = windowId
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
    warmWindowId = null
    val waiting = pending
    val running = active
    pending = null
    active = null
    TraversalTreeCache.setBuildingWindow(-1)
    waiting?.discard?.run()
    for (job in listOfNotNull(running, waiting)) {
      job.cancelled = true
      // Q11f: cancelled — once per job. A build cancelled here never reaches the
      // completion handler (its handler.post block returns early on active !== job),
      // so this count and the completion-handler count cannot double-count one job.
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.ORDER_BUILD_CANCELLED)
      val callbacks = job.callbacks.toList()
      job.callbacks.clear()
      callbacks.forEach { it.accept(Result.CANCELLED) }
    }
  }

  private fun start(job: Job) {
    if (!LatencyExperiments.Feature.ASYNC_ORDER.enabled() || NodeOverrides.hasSource()) {
      job.discard?.run()
      // Q11f: the build never started — count once per job, next to the accept.
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.ORDER_BUILD_CANCELLED)
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
      var budgetExceeded = false
      val strategy = try {
        if (job.cancelled) {
          job.discard?.run()
        } else {
          root = job.root.get()
        }
        root?.let { value ->
          TraversalBudget.run(started + 200, Supplier { OrderedTraversalStrategy(value) })
        }
      } catch (exhausted: java.util.concurrent.CancellationException) {
        budgetExceeded = true
        null
      } catch (failure: RuntimeException) {
        null
      }
      val resolvedRoot = root
      val duration = SystemClock.uptimeMillis() - started
      if (budgetExceeded || duration >= 150L) {
        TraversalTreeCache.noteSlowBuild(job.windowId, duration, budgetExceeded)
      }
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
        // Q11f: one outcome count per completed build, 1:1 with Result — once, right
        // here, before the callbacks fire (counting at the accept sites would count
        // one build once per registered callback).
        Performance.getInstance().onPreparedSpeechWork(
          when (result) {
            Result.READY -> PreparedSpeechWork.ORDER_BUILD_READY
            Result.STALE -> PreparedSpeechWork.ORDER_BUILD_STALE
            Result.CANCELLED -> PreparedSpeechWork.ORDER_BUILD_CANCELLED
            Result.FAILED -> PreparedSpeechWork.ORDER_BUILD_FAILED
          }
        )
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
