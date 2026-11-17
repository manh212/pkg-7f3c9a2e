package com.google.android.accessibility.talkback.taskassistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandCallDialog
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandPhoneNumbers
import com.google.android.accessibility.talkback.calls.CallPermissions
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.android.accessibility.utils.widget.DialogUtils
import java.util.concurrent.Executor

internal class OfflineTaskRunner(
  private val service: TalkBackService,
  private val main: Handler,
  private val worker: Executor,
  private val reader: TaskScreenReader,
  private val canOperate: () -> Boolean,
  private val apps: () -> List<TaskApp>,
  private val announce: (String) -> Unit,
) {
  private class Run(val steps: ArrayDeque<OfflineScreenStep>, val draft: Boolean) {
    var deadline = 0L
    var reading = false
    var awaitingScreen = false
    var recipientCandidate: String? = null
    var recipientCandidateSince = 0L
  }

  private data class Contact(val name: String, val number: String)

  @Volatile private var run: Run? = null
  private var contactDialog: AlertDialog? = null
  private val next = Runnable { run?.let(::advance) }

  val active: Boolean get() = run != null

  fun start(goal: String): Boolean {
    val command = OfflineTaskCommand.parse(goal) ?: return false
    val steps = when (command) {
      is OfflineTaskCommand.Draft -> {
        if (command.app == OfflineTaskCommand.MessageApp.SMS) {
          withNumber(command.recipient, VoiceCommandPhoneNumbers.parseNumber(command.recipient)) {
            openSms(it, command.text)
          }
          return true
        }
        listOf(
          OfflineScreenStep.Open(OfflineTaskScreen.ZALO),
          OfflineScreenStep.Search(OfflineTaskScreen.ZALO),
          OfflineScreenStep.EnterQuery(OfflineTaskScreen.ZALO, command.recipient),
          OfflineScreenStep.ZaloRecipient(command.recipient),
          OfflineScreenStep.ZaloDraft(command.recipient, command.text),
          OfflineScreenStep.VerifyZaloDraft(command.recipient, command.text),
        )
      }
      is OfflineTaskCommand.Call -> {
        withNumber(command.recipient, command.number) { contact ->
          VoiceCommandCallDialog.show(service, contact.number, {
            announce(service.getString(R.string.voice_call_no_permission))
          }, contact.name)
        }
        return true
      }
      OfflineTaskCommand.AskMessageApp -> {
        announce(service.getString(R.string.task_offline_message_app))
        return true
      }
      OfflineTaskCommand.AskCallTarget -> {
        announce(service.getString(R.string.task_offline_call_target))
        return true
      }
      else -> screenSteps(command) ?: return false
    }
    val current = Run(ArrayDeque(steps), command is OfflineTaskCommand.Draft && command.text.isNotBlank())
    run = current
    advance(current)
    return true
  }

  private fun screenSteps(command: OfflineTaskCommand): List<OfflineScreenStep>? {
    return when (command) {
      is OfflineTaskCommand.Open -> listOf(OfflineScreenStep.Open(command.app))
      is OfflineTaskCommand.Click -> listOf(OfflineScreenStep.ClickLabel(command.label))
      is OfflineTaskCommand.Scroll -> List(command.count) { OfflineScreenStep.Scroll(command.forward) }
      is OfflineTaskCommand.Navigate -> listOf(OfflineScreenStep.Navigate(command.action))
      is OfflineTaskCommand.YouTube -> buildList {
        if (command.openApp) add(OfflineScreenStep.Open(OfflineTaskScreen.YOUTUBE))
        add(OfflineScreenStep.Search(OfflineTaskScreen.YOUTUBE))
        command.query?.let {
          add(OfflineScreenStep.EnterQuery(OfflineTaskScreen.YOUTUBE, it))
          add(OfflineScreenStep.SubmitYouTube(it))
        }
        if (command.openFirst) add(OfflineScreenStep.FirstVideo(command.query))
      }
      OfflineTaskCommand.FirstVideo -> listOf(OfflineScreenStep.FirstVideo(null))
      is OfflineTaskCommand.LongClick -> listOf(OfflineScreenStep.LongClickLabel(command.label))
      is OfflineTaskCommand.Input -> listOf(OfflineScreenStep.Input(command.text, command.label, command.append))
      is OfflineTaskCommand.Edit -> listOf(OfflineScreenStep.Edit(command.action))
      is OfflineTaskCommand.Toggle -> listOf(OfflineScreenStep.Toggle(command.label, command.checked),
        OfflineScreenStep.VerifyToggle(command.label, command.checked))
      is OfflineTaskCommand.Item -> listOf(OfflineScreenStep.Item(command.index))
      is OfflineTaskCommand.Video -> listOf(OfflineScreenStep.FirstVideo(null, command.index))
      is OfflineTaskCommand.Search -> listOf(OfflineScreenStep.SearchCurrent,
        OfflineScreenStep.EnterCurrentQuery(command.query), OfflineScreenStep.SubmitSearch(command.query))
      is OfflineTaskCommand.Panel -> listOf(OfflineScreenStep.Panel(command.panel))
      is OfflineTaskCommand.Sequence -> command.commands.flatMap { screenSteps(it) ?: return null }
      else -> null
    }
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
    contactDialog?.dismiss()
    contactDialog = null
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
      finish(if (current.draft) R.string.task_offline_draft_ready else null)
      return
    }
    if (current.deadline == 0L) current.deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
    if (SystemClock.uptimeMillis() >= current.deadline) {
      finish(if (step is OfflineScreenStep.ZaloRecipient) R.string.task_offline_contact_missing
        else R.string.task_offline_target_unavailable)
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
          OfflineTaskScreen.Decision.ExistingDraft -> finish(R.string.task_offline_existing_draft)
          is OfflineTaskScreen.Decision.Act, is OfflineTaskScreen.Decision.Edit -> error("Unresolved offline action")
        }
      }
    }
  }

  private fun execute(current: Run, step: OfflineScreenStep): OfflineTaskScreen.Decision {
    if (step is OfflineScreenStep.Open) return openApp(current, step.app)
    if (step is OfflineScreenStep.Navigate) {
      val action = if (step.action == TaskOperation.HOME) AccessibilityService.GLOBAL_ACTION_HOME
      else AccessibilityService.GLOBAL_ACTION_BACK
      return performed(run === current && canOperate() && service.performGlobalAction(action))
    }
    if (step is OfflineScreenStep.Panel) {
      val action = when (step.panel) {
        OfflinePanel.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        OfflinePanel.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        OfflinePanel.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
      }
      return performed(run === current && canOperate() && service.performGlobalAction(action))
    }
    val capture = runCatching { reader.read(includeStructure = true) }.getOrNull()
      ?: return OfflineTaskScreen.Decision.Wait
    val decision = OfflineTaskScreen(capture.screen).decide(step)
    if (decision is OfflineTaskScreen.Decision.Edit) {
      if (run !== current || !canOperate()) return OfflineTaskScreen.Decision.Wait
      return performed(reader.edit(capture, decision.node, decision.action))
    }
    if (decision !is OfflineTaskScreen.Decision.Act) {
      current.recipientCandidate = null
      return decision
    }
    if (step is OfflineScreenStep.ZaloRecipient) {
      val target = capture.screen.nodes.firstOrNull { it.id == decision.step.node }
        ?: return OfflineTaskScreen.Decision.Wait
      val key = "${capture.windowId}:${target.viewId}:${target.label}:${target.top}:${target.left}"
      val now = SystemClock.uptimeMillis()
      if (current.recipientCandidate != key) {
        current.recipientCandidate = key
        current.recipientCandidateSince = now
      }
      if (now - current.recipientCandidateSince < RECIPIENT_STABILITY_MS) return OfflineTaskScreen.Decision.Wait
    }
    if (run !== current || SystemClock.uptimeMillis() >= current.deadline || !canOperate()) {
      return OfflineTaskScreen.Decision.Wait
    }
    if (decision.guard != null && !reader.matches(capture, decision.guard)) return OfflineTaskScreen.Decision.Wait
    val performed = reader.perform(capture, decision.step) ||
      ((step is OfflineScreenStep.SubmitYouTube || step is OfflineScreenStep.SubmitSearch) &&
        decision.step.operation == TaskOperation.PRESS_ENTER &&
        run === current && canOperate() && reader.clickSearchKey(capture, decision.step.node))
    if (!performed) return OfflineTaskScreen.Decision.Wait
    if (step is OfflineScreenStep.Input) {
      current.steps.add(1, OfflineScreenStep.VerifyInput(decision.step.text, step.label))
    } else if (step is OfflineScreenStep.Edit && step.action == OfflineTextAction.CLEAR) {
      current.steps.add(1, OfflineScreenStep.VerifyInput("", ""))
    }
    return OfflineTaskScreen.Decision.Ready
  }

  private fun openApp(current: Run, name: String): OfflineTaskScreen.Decision {
    val installed = apps()
    val alias = if (TaskProtocol.normalize(name).replace(" ", "") == "youtube") OfflineTaskScreen.YOUTUBE else name
    val matching = installed.filter { it.packageName == alias || TaskProtocol.normalize(it.name) == TaskProtocol.normalize(name) }
    if (matching.size > 1) return OfflineTaskScreen.Decision.Ambiguous
    val packageName = matching.singleOrNull()?.packageName ?: return OfflineTaskScreen.Decision.Unavailable
    val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return OfflineTaskScreen.Decision.Unavailable
    if (run !== current || !canOperate()) return OfflineTaskScreen.Decision.Wait
    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return OfflineTaskScreen.Decision.Ready
  }

  private fun withNumber(name: String, number: String?, action: (Contact) -> Unit) {
    if (number != null) {
      action(Contact("", number))
      return
    }
    if (ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      announce(service.getString(R.string.task_offline_contacts_permission))
      CallPermissions.requestContacts(service)
      return
    }
    val current = Run(ArrayDeque(), false)
    run = current
    worker.execute {
      val contacts = runCatching { findContacts(name) }.getOrDefault(emptyList())
      main.post {
        if (run !== current) return@post
        cancel()
        if (!canOperate()) return@post
        when (contacts.size) {
          0 -> announce(service.getString(R.string.task_offline_contact_missing))
          1 -> action(contacts.single())
          else -> chooseContact(contacts, action)
        }
      }
    }
  }

  private fun findContacts(name: String): List<Contact> {
    val matches = mutableListOf<Contact>()
    service.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
      null, null, null)?.use { cursor ->
      while (cursor.moveToNext()) {
        val displayName = cursor.getString(0) ?: continue
        if (!OfflineTaskScreen.equalNames(displayName, name)) continue
        val number = cursor.getString(1)?.filter { it.isDigit() || it == '+' }.orEmpty()
        VoiceCommandPhoneNumbers.parseNumber(number)?.let { matches += Contact(displayName, it) }
      }
    }
    return matches.distinctBy { it.number }
  }

  private fun chooseContact(contacts: List<Contact>, action: (Contact) -> Unit) {
    val labels = contacts.map { "${it.name}, ${VoiceCommandPhoneNumbers.grouped(it.number)}" }.toTypedArray()
    val dialog = MaterialComponentUtils.alertDialogBuilder(service).setTitle(R.string.task_offline_choose_contact)
      .setItems(labels) { _, index -> if (canOperate()) action(contacts[index]) }
      .setNegativeButton(android.R.string.cancel, null).create()
    dialog.window?.let(DialogUtils::setWindowTypeToDialog)
    contactDialog = dialog
    dialog.setOnDismissListener { if (contactDialog === dialog) contactDialog = null }
    dialog.show()
  }

  private fun openSms(contact: Contact, message: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null))
      .putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { service.startActivity(intent) }
      .onFailure { announce(service.getString(R.string.task_offline_target_unavailable)) }
  }

  private fun finish(message: Int?) {
    cancel()
    if (message != null) announce(service.getString(message))
  }

  private fun performed(success: Boolean): OfflineTaskScreen.Decision =
    if (success) OfflineTaskScreen.Decision.Ready else OfflineTaskScreen.Decision.Unavailable

  companion object {
    private const val STEP_TIMEOUT_MS = 8_000L
    private const val SCREEN_RECHECK_MS = 250L
    private const val RECIPIENT_STABILITY_MS = 200L
  }
}
