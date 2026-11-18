package com.google.android.accessibility.talkback.taskassistant

import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import java.util.concurrent.Executor

internal class OfflineTaskRunner(
  private val service: TalkBackService,
  private val main: Handler,
  private val worker: Executor,
  private val reader: TaskScreenReader,
  private val canOperate: () -> Boolean,
  private val announce: (String) -> Unit,
  private val apps: () -> List<TaskApp> = { emptyList() },
) {
  private class Run(val steps: ArrayDeque<OfflineScreenStep>, val apps: List<TaskApp>) {
    var deadline = 0L
    var reading = false
    var awaitingScreen = false
  }

  @Volatile private var run: Run? = null
  private val next = Runnable { run?.let(::advance) }

  val active: Boolean get() = run != null

  fun start(goal: String): Boolean {
    val command = OfflineTaskCommand.parse(goal) ?: return false
    val current = Run(ArrayDeque(screenSteps(command)), apps())
    run = current
    advance(current)
    return true
  }

  private fun screenSteps(command: OfflineTaskCommand): List<OfflineScreenStep> = when (command) {
    is OfflineTaskCommand.Click -> listOf(OfflineScreenStep.ClickLabel(command.label))
    is OfflineTaskCommand.LongClick -> listOf(OfflineScreenStep.LongClickLabel(command.label))
    is OfflineTaskCommand.Input -> listOf(OfflineScreenStep.Input(command.text, command.label, command.append))
    is OfflineTaskCommand.Toggle -> listOf(OfflineScreenStep.Toggle(command.label, command.checked),
      OfflineScreenStep.VerifyToggle(command.label, command.checked))
    is OfflineTaskCommand.Item -> listOf(OfflineScreenStep.Item(command.index, command.video))
    is OfflineTaskCommand.Search -> listOf(OfflineScreenStep.SearchCurrent,
      OfflineScreenStep.EnterCurrentQuery(command.query), OfflineScreenStep.SubmitSearch(command.query))
    is OfflineTaskCommand.Scroll -> List(command.count) { OfflineScreenStep.Scroll(command.forward) }
    is OfflineTaskCommand.Drag -> listOf(OfflineScreenStep.Drag(command.label, command.direction))
    is OfflineTaskCommand.Sequence -> command.commands.flatMap(::screenSteps)
  }

  fun event(event: AccessibilityEvent) {
    val current = run ?: return
    if (!current.awaitingScreen || current.reading) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> schedule(0)
    }
  }

  fun cancel() {
    main.removeCallbacks(next)
    run = null
  }

  private fun schedule(delay: Long) {
    main.removeCallbacks(next)
    main.postDelayed(next, delay)
  }

  private fun advance(current: Run) {
    if (current.reading || run !== current) return
    if (!canOperate()) {
      finish(R.string.task_offline_interrupted)
      return
    }
    val step = current.steps.firstOrNull()
    if (step == null) {
      finish(null)
      return
    }
    if (current.deadline == 0L) current.deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
    if (SystemClock.uptimeMillis() >= current.deadline) {
      finish(R.string.task_offline_target_unavailable)
      return
    }
    current.reading = true
    current.awaitingScreen = false
    worker.execute {
      val decision = if (run !== current || !canOperate()) OfflineTaskScreen.Decision.Wait
      else runCatching { execute(current, step) }.getOrDefault(OfflineTaskScreen.Decision.Unavailable)
      main.post {
        if (run !== current) return@post
        current.reading = false
        when (decision) {
          OfflineTaskScreen.Decision.Ready -> {
            current.steps.removeFirst()
            current.deadline = 0L
            schedule(0)
          }
          OfflineTaskScreen.Decision.Wait -> {
            current.awaitingScreen = true
            schedule(SCREEN_RECHECK_MS)
          }
          OfflineTaskScreen.Decision.Ambiguous -> finish(R.string.task_offline_target_ambiguous)
          OfflineTaskScreen.Decision.Unavailable -> finish(R.string.task_offline_target_unavailable)
          is OfflineTaskScreen.Decision.Act -> error("Unresolved offline action")
        }
      }
    }
  }

  private fun execute(current: Run, step: OfflineScreenStep): OfflineTaskScreen.Decision {
    val capture = runCatching { reader.read(includeStructure = true) }.getOrNull()
      ?: return OfflineTaskScreen.Decision.Wait
    val decision = OfflineTaskScreen(capture.screen, current.apps).decide(step)
    if (decision !is OfflineTaskScreen.Decision.Act) {
      return decision
    }
    if (run !== current || SystemClock.uptimeMillis() >= current.deadline || !canOperate()) {
      return OfflineTaskScreen.Decision.Wait
    }
    if (decision.guard != null && !reader.matches(capture, decision.guard)) return OfflineTaskScreen.Decision.Wait
    val performed = reader.perform(capture, decision.step) ||
      (step is OfflineScreenStep.SubmitSearch &&
        decision.step.operation == TaskOperation.PRESS_ENTER &&
        run === current && canOperate() && reader.clickSearchKey(capture, decision.step.node))
    if (!performed) return OfflineTaskScreen.Decision.Wait
    if (step is OfflineScreenStep.Input) {
      current.steps.add(1, OfflineScreenStep.VerifyInput(decision.step.text, step.label))
    }
    return OfflineTaskScreen.Decision.Ready
  }

  private fun finish(message: Int?) {
    cancel()
    if (message != null) announce(service.getString(message))
  }

  companion object {
    private const val STEP_TIMEOUT_MS = 8_000L
    private const val SCREEN_RECHECK_MS = 250L
  }
}
