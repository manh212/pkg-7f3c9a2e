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

package com.google.android.accessibility.talkback.notifications

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.calls.CallLimitController
import com.google.android.accessibility.talkback.calls.CallPermissions
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status
import com.google.android.accessibility.talkback.shizuku.ShizukuGrants
import com.google.android.accessibility.talkback.timer.TimerKeepAliveService
import java.util.Locale

/**
 * Initial setup: the language first, then every permission SandFly stands on, one at a time.
 * Choosing a language applies it at once and moves the walk on by itself. A step the phone
 * already grants is passed over; dealing with a step — in the system's own dialog or settings
 * page — moves the walk on by itself when this screen comes back, so there is no button to press
 * between one permission and the next, and a step whose grant cannot be checked moves on when
 * the walk returns from its page all the same. Each step says where the walk stands and the
 * state it finds — for the battery, the state the system itself reports, never a guess. A step
 * can be passed by, the setup can be left at any time, and the end lists every state as it
 * stands, with the language as the language chosen rather than a grant.
 */
class InitialSetupFragment : NotificationCodeFragment(R.string.timer_setup_title) {
  private val flow = SetupFlow()

  private var languageChosen = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    languageChosen = savedInstanceState?.getBoolean(KEY_LANGUAGE_CHOSEN) ?: false
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean(KEY_LANGUAGE_CHOSEN, languageChosen)
    super.onSaveInstanceState(outState)
  }

  /** One step of the walk: a permission, what it is called, what its states say, and where it
   * is given — or the language choice, which has no grant state and is shown as the language
   * chosen, or a step whose grant cannot be checked. */
  private class Step(
    @StringRes val title: Int,
    @StringRes val grantedText: Int = 0,
    @StringRes val missingText: Int = 0,
    @StringRes val openLabel: Int = 0,
    val status: (Context) -> Status,
    val open: (Context) -> Unit = {},
    val applicable: (Context) -> Boolean = { true },
    val automatic: Boolean = true,
    val unverifiable: Boolean = false,
    val choice: Boolean = false,
    val summaryText: ((Context) -> String)? = null,
  )

  override fun onPause() {
    super.onPause()
    flow.leaveScreen()
  }

  override fun onResume() {
    // Before the screen is built again, so that it is built where the walk has moved to.
    val context = requireContext()
    val availableSteps = steps().filter { it.applicable(context) }
    val current = availableSteps.getOrNull(flow.step)
    val status = current?.status?.invoke(context)
    val unverifiable = current?.unverifiable == true || status == Status.UNKNOWN
    flow.returnToScreen(availableSteps.size, status == Status.GRANTED, unverifiable)
    super.onResume()
  }

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val steps = steps().filter { it.applicable(context) }
    flow.settle(steps.map { if (it.automatic) it.status(context) else Status.GRANTED })
    if (flow.atSummary(steps.size)) {
      buildSummary(screen, steps)
      return
    }
    buildStep(screen, steps, steps[flow.step])
  }

  private fun buildStep(screen: PreferenceGroup, steps: List<Step>, current: Step) {
    if (current.choice) {
      buildLanguageStep(screen, steps)
      return
    }
    val context = requireContext()
    val status = current.status(context)
    val category = screen.category(current.title)
    category.action(
      getString(
        R.string.timer_setup_progress,
        flow.step + 1,
        steps.size,
        getString(statusText(status)),
      ),
      if (status == Status.UNKNOWN) null else getString(detailText(current, status)),
    ) {
      rebuild()
    }
    if (current.title == R.string.timer_setup_battery_title && isXiaomiFamily()) {
      category.information(
        getString(R.string.timer_setup_xiaomi_title),
        getString(R.string.timer_setup_xiaomi_unverified),
      )
    }
    if (current.title == R.string.timer_setup_autostart_title) {
      category.information(
        getString(R.string.timer_setup_autostart_title),
        getString(R.string.timer_setup_autostart_missing),
      )
    }
    if (status != Status.GRANTED) {
      category.action(getString(current.openLabel)) {
        flow.tapOpen()
        if (current.title == R.string.call_permission_manage_title) {
          CallPermissions.requestManagement(context) {
            if (isAdded) {
              flow.completeOpen(steps.size)
              rebuild()
            }
          }
        } else if (
          current.title == R.string.timer_setup_dump_title ||
            current.title == R.string.timer_setup_secure_title
        ) {
          TimerPermissionChecks.requestShizukuGrant(
            context,
            current.title,
            if (current.title == R.string.timer_setup_dump_title) {
              ShizukuGrants.Grant.DUMP
            } else {
              ShizukuGrants.Grant.WRITE_SECURE_SETTINGS
            },
          ) {
            if (isAdded) {
              flow.completeOpen(steps.size)
              rebuild()
            }
          }
        } else if (current.title == R.string.timer_setup_keep_alive_title) {
          current.open(context)
          flow.completeOpen(steps.size)
          rebuild()
        } else current.open(context)
      }
    }
    if (current.title == R.string.timer_setup_keep_alive_title && status == Status.GRANTED) {
      category.action(getString(R.string.timer_setup_keep_alive_off)) {
        TimerKeepAliveService.setAllowed(context, false)
        rebuild()
      }
    }
    if (flow.reviewing) {
      category.action(getString(R.string.timer_setup_back)) {
        flow.backToSummary(steps.size)
        rebuild()
      }
    } else {
      category.action(getString(R.string.timer_setup_skip)) {
        flow.skip()
        rebuild()
      }
      category.action(getString(R.string.timer_setup_exit)) { leave() }
    }
  }

  private fun buildLanguageStep(screen: PreferenceGroup, steps: List<Step>) {
    val context = requireContext()
    val category = screen.category(R.string.timer_setup_language_title)
    category.action(
      getString(
        R.string.timer_setup_progress,
        flow.step + 1,
        steps.size,
        currentLanguageName(),
      ),
      getString(R.string.timer_setup_language_detail),
    ) {
      rebuild()
    }
    category.addPreference(
      LanguagePreference(context, currentLanguageIndex()) { index ->
        onLanguageSelected(steps.size, index)
      }
    )
    if (flow.reviewing) {
      category.action(getString(R.string.timer_setup_back)) {
        flow.backToSummary(steps.size)
        rebuild()
      }
    } else {
      category.action(getString(R.string.timer_setup_language_keep)) {
        keepLanguage(steps.size)
      }
      category.action(getString(R.string.timer_setup_exit)) { leave() }
    }
  }

  private fun onLanguageSelected(stepCount: Int, index: Int) {
    val language = AppLanguage.entries[index]
    if (storedLanguageTag() == language.tag) return
    languageChosen = true
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    flow.chooseLanguage(stepCount)
    rebuild()
  }

  private fun keepLanguage(stepCount: Int) {
    val language = AppLanguage.entries[currentLanguageIndex()]
    languageChosen = true
    if (storedLanguageTag() != language.tag) {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
    flow.skip()
    rebuild()
  }

  private fun storedLanguageTag(): String? {
    val stored = AppCompatDelegate.getApplicationLocales()
    if (stored.isEmpty) return null
    return stored[0]?.toLanguageTag()?.substringBefore('-')
  }

  private fun currentLanguageIndex(): Int {
    val tag = storedLanguageTag()
    val index = AppLanguage.entries.indexOfFirst { it.tag == tag }
    return if (index >= 0) index else 0
  }

  private fun currentLanguageName(): String =
    AppLanguage.entries.getOrNull(currentLanguageIndex())?.name ?: AppLanguage.VIETNAMESE.name

  /** Every permission with the state it stands in; tapping one goes back to its step. */
  private fun buildSummary(screen: PreferenceGroup, steps: List<Step>) {
    val context = requireContext()
    val category = screen.category(R.string.timer_setup_summary_title)
    steps.forEachIndexed { index, item ->
      val summary =
        item.summaryText?.invoke(context) ?: getString(statusText(item.status(context)))
      category.action(getString(item.title), summary) {
        flow.review(index)
        rebuild()
      }
    }
    if (isXiaomiFamily()) {
      category.information(
        getString(R.string.timer_setup_xiaomi_title),
        getString(R.string.timer_setup_xiaomi_unverified),
      )
    }
    category.action(getString(R.string.timer_setup_done)) { leave() }
  }

  private fun leave() {
    flow.finish()
    languageChosen = false
    runCatching { parentFragmentManager.popBackStack() }
  }

  private fun statusText(status: Status): Int =
    when (status) {
      Status.GRANTED -> R.string.timer_setup_status_granted
      Status.MISSING -> R.string.timer_setup_status_missing
      Status.UNKNOWN -> R.string.timer_setup_status_unknown
    }

  private fun detailText(step: Step, status: Status): Int =
    if (status == Status.GRANTED) step.grantedText else step.missingText

  private fun isXiaomiFamily(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).trim() in setOf("xiaomi", "redmi", "poco")

  /** The phone makers that gate background starts behind their own autostart page. */
  private fun isAutoStartFamily(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).trim() in
      setOf(
        "xiaomi",
        "redmi",
        "poco",
        "oppo",
        "realme",
        "oneplus",
        "vivo",
        "iqoo",
        "huawei",
        "honor",
      )

  private fun steps(): List<Step> =
    listOf(
      Step(
        R.string.timer_setup_language_title,
        status = { if (languageChosen) Status.GRANTED else Status.MISSING },
        choice = true,
        summaryText = { currentLanguageName() },
      ),
      Step(
        R.string.timer_setup_service_title,
        R.string.timer_setup_service_granted,
        R.string.timer_setup_service_missing,
        R.string.timer_setup_service_open,
        { TimerPermissionChecks.serviceStatus(it) },
        { TimerPermissionChecks.openAccessibilitySettings(it) },
      ),
      Step(
        R.string.timer_setup_battery_title,
        R.string.timer_setup_battery_granted,
        R.string.timer_setup_battery_missing,
        R.string.timer_setup_battery_open,
        { TimerPermissionChecks.batteryStatus(it) },
        { TimerPermissionChecks.promptForBatteryExemption(it) },
      ),
      Step(
        R.string.timer_setup_autostart_title,
        R.string.timer_setup_autostart_granted,
        R.string.timer_setup_autostart_missing,
        R.string.timer_setup_autostart_open,
        { TimerPermissionChecks.autostartStatus(it) },
        { TimerPermissionChecks.openAutoStartSettings(it) },
        { isAutoStartFamily() },
        unverifiable = true,
      ),
      Step(
        R.string.timer_setup_exact_alarm_title,
        R.string.timer_setup_exact_alarm_granted,
        R.string.timer_setup_exact_alarm_missing,
        R.string.timer_setup_exact_alarm_open,
        { TimerPermissionChecks.exactAlarmStatus(it) },
        { TimerPermissionChecks.openExactAlarmSettings(it) },
        { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
      ),
      Step(
        R.string.timer_setup_keep_alive_title,
        R.string.timer_setup_keep_alive_granted,
        R.string.timer_setup_keep_alive_missing,
        R.string.timer_setup_keep_alive_open,
        { if (TimerKeepAliveService.allowed(it)) Status.GRANTED else Status.MISSING },
        { TimerKeepAliveService.setAllowed(it, true) },
      ),
      Step(
        R.string.timer_setup_microphone_title,
        R.string.timer_setup_microphone_granted,
        R.string.timer_setup_microphone_missing,
        R.string.timer_setup_microphone_open,
        { TimerPermissionChecks.microphoneStatus(it) },
        { TimerPermissionChecks.requestMicrophone(it) },
        { TimerPermissionChecks.hasMicrophone(it) },
      ),
      Step(
        R.string.timer_setup_phone_title,
        R.string.timer_setup_phone_granted,
        R.string.timer_setup_phone_missing,
        R.string.timer_setup_phone_open,
        { TimerPermissionChecks.phoneStateStatus(it) },
        { TimerPermissionChecks.requestPhoneState(it) },
        { TimerPermissionChecks.hasTelephony(it) },
      ),
      Step(
        R.string.call_permission_contacts_title,
        R.string.call_permission_contacts_granted,
        R.string.call_permission_contacts_missing,
        R.string.call_permission_contacts_open,
        { CallPermissions.contactsStatus(it) },
        { CallPermissions.requestContacts(it) },
        { TimerPermissionChecks.hasTelephony(it) },
      ),
      Step(
        R.string.call_permission_log_title,
        R.string.call_permission_log_granted,
        R.string.call_permission_log_missing,
        R.string.call_permission_log_open,
        { CallPermissions.logStatus(it) },
        { CallPermissions.requestLog(it) },
        { TimerPermissionChecks.hasTelephony(it) },
      ),
      Step(
        R.string.call_permission_legacy_title,
        R.string.call_permission_legacy_granted,
        R.string.call_permission_legacy_missing,
        R.string.call_permission_legacy_open,
        { CallPermissions.legacyStatus(it) },
        { CallPermissions.requestLegacy(it) },
        { TimerPermissionChecks.hasTelephony(it) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
          !CallLimitController.hasManagedAccess(it) },
      ),
      Step(
        R.string.call_permission_manage_title,
        R.string.call_permission_manage_granted,
        R.string.call_permission_manage_missing,
        R.string.call_permission_manage_open,
        { CallPermissions.managementStatus(it) },
        {},
        { TimerPermissionChecks.hasTelephony(it) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        automatic = false,
      ),
      Step(
        R.string.timer_setup_access_title,
        R.string.timer_setup_access_granted,
        R.string.timer_setup_access_missing,
        R.string.timer_setup_access_open,
        { TimerPermissionChecks.notificationAccessStatus(it) },
        { NotificationXmlFragment.openAccessSettings(it) },
      ),
      Step(
        R.string.timer_setup_notifications_title,
        R.string.timer_setup_notifications_granted,
        R.string.timer_setup_notifications_missing,
        R.string.timer_setup_notifications_open,
        { TimerPermissionChecks.appNotificationsStatus(it) },
        { TimerPermissionChecks.openAppNotificationSettings(it) },
      ),
      Step(
        R.string.timer_setup_dnd_title,
        R.string.timer_setup_dnd_granted,
        R.string.timer_setup_dnd_missing,
        R.string.timer_setup_dnd_open,
        { TimerPermissionChecks.notificationPolicyStatus(it) },
        { TimerPermissionChecks.openNotificationPolicySettings(it) },
        { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M },
      ),
      Step(
        R.string.timer_setup_install_title,
        R.string.timer_setup_install_granted,
        R.string.timer_setup_install_missing,
        R.string.timer_setup_install_open,
        { TimerPermissionChecks.installStatus(it) },
        { TimerPermissionChecks.openInstallSettings(it) },
      ),
      Step(
        R.string.timer_setup_brightness_title,
        R.string.timer_setup_brightness_granted,
        R.string.timer_setup_brightness_missing,
        R.string.timer_setup_brightness_open,
        { TimerPermissionChecks.brightnessStatus(it) },
        { TimerPermissionChecks.openBrightnessSettings(it) },
      ),
      Step(
        R.string.timer_setup_camera_title,
        R.string.timer_setup_camera_granted,
        R.string.timer_setup_camera_missing,
        R.string.timer_setup_camera_open,
        { TimerPermissionChecks.cameraStatus(it) },
        { TimerPermissionChecks.requestCamera(it) },
        { TimerPermissionChecks.hasCamera(it) },
      ),
      Step(
        R.string.timer_setup_call_title,
        R.string.timer_setup_call_granted,
        R.string.timer_setup_call_missing,
        R.string.timer_setup_call_open,
        { TimerPermissionChecks.callStatus(it) },
        { TimerPermissionChecks.requestCall(it) },
      ),
      Step(
        R.string.timer_setup_location_title,
        R.string.timer_setup_location_granted,
        R.string.timer_setup_location_missing,
        R.string.timer_setup_location_open,
        { TimerPermissionChecks.locationStatus(it) },
        { TimerPermissionChecks.requestLocation(it) },
      ),
      Step(
        R.string.timer_setup_bluetooth_title,
        R.string.timer_setup_bluetooth_granted,
        R.string.timer_setup_bluetooth_missing,
        R.string.timer_setup_bluetooth_open,
        { TimerPermissionChecks.bluetoothStatus(it) },
        { TimerPermissionChecks.requestBluetooth(it) },
        { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
      ),
      Step(
        R.string.timer_setup_overlay_title,
        R.string.timer_setup_overlay_granted,
        R.string.timer_setup_overlay_missing,
        R.string.timer_setup_overlay_open,
        { TimerPermissionChecks.overlayStatus(it) },
        { TimerPermissionChecks.openOverlaySettings(it) },
        { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M },
      ),
      Step(
        R.string.timer_setup_storage_title,
        R.string.timer_setup_storage_granted,
        R.string.timer_setup_storage_missing,
        R.string.timer_setup_storage_open,
        { TimerPermissionChecks.storageStatus(it) },
        { TimerPermissionChecks.requestStorage(it) },
        { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q },
      ),
      Step(
        R.string.timer_setup_usage_title,
        R.string.timer_setup_usage_granted,
        R.string.timer_setup_usage_missing,
        R.string.timer_setup_usage_open,
        { TimerPermissionChecks.usageStatus(it) },
        { TimerPermissionChecks.openUsageSettings(it) },
      ),
      Step(
        R.string.timer_setup_dump_title,
        R.string.timer_setup_dump_granted,
        R.string.timer_setup_dump_missing,
        R.string.timer_setup_dump_open,
        { TimerPermissionChecks.dumpStatus(it) },
        {},
        { true },
        automatic = false,
      ),
      Step(
        R.string.timer_setup_secure_title,
        R.string.timer_setup_secure_granted,
        R.string.timer_setup_secure_missing,
        R.string.timer_setup_secure_open,
        { TimerPermissionChecks.secureSettingsStatus(it) },
        {},
        { true },
        automatic = false,
      ),
    )

  companion object {
    private const val KEY_LANGUAGE_CHOSEN = "initial_setup_language_chosen"
  }
}

private enum class AppLanguage(val tag: String, val name: String) {
  VIETNAMESE("vi", "Tiếng Việt"),
  ENGLISH("en", "English"),
}

private class LanguagePreference(
  context: Context,
  private val selected: Int,
  private val onSelect: (Int) -> Unit,
) : Preference(context) {
  init {
    layoutResource = R.layout.timer_setup_language
    isPersistent = false
    isSelectable = false
    isIconSpaceReserved = false
  }

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    val spinner = holder.findViewById(R.id.timer_setup_language_spinner) as Spinner
    spinner.contentDescription = context.getString(R.string.timer_setup_language_title)
    spinner.adapter =
      ArrayAdapter(
          context,
          android.R.layout.simple_spinner_item,
          AppLanguage.entries.map { it.name },
        )
        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    spinner.setSelection(selected)
    spinner.onItemSelectedListener =
      object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: AdapterView<*>?,
          view: View?,
          position: Int,
          id: Long,
        ) {
          onSelect(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
      }
  }
}
