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

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notificationlist.NotificationListService

import com.google.android.accessibility.talkback.permission.PermissionUtils
import com.google.android.accessibility.talkback.selector.ScreenBrightness
import com.google.android.accessibility.talkback.shizuku.PlayInstaller
import com.google.android.accessibility.talkback.shizuku.ShizukuGrants
import com.google.android.accessibility.talkback.shizuku.ShizukuShell
import com.google.android.accessibility.talkback.utils.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The permissions SandFly stands on, checked and asked for in the open: enabling a feature
 * checks what it needs right then, and the initial setup walks through all of them one by one.
 * On Android 12+, exact spoken alarms need special access unless the system exempts the app.
 * Initial setup checks that access separately from Android battery optimization exemptions;
 * phone-maker background restrictions, such as Xiaomi's, are another independent setting.
 * The rest are what the features stand on: reading notifications, posting SandFly's own, the
 * microphone dictation listens with, the phone state that following calls reads, installing
 * the updates the updater downloads, and writing the system settings the brightness control
 * changes.
 */
object TimerPermissionChecks {
  /** A permission's state as it could be checked: held, not held, or not checkable here. */
  enum class Status {
    GRANTED,
    MISSING,
    UNKNOWN,
  }

  /** Whether the phone leaves SandFly alone while it rests, as the system itself reports it. */
  fun batteryStatus(context: Context): Status {
    val power = context.getSystemService(PowerManager::class.java) ?: return Status.UNKNOWN
    return if (power.isIgnoringBatteryOptimizations(context.packageName)) Status.GRANTED
    else Status.MISSING
  }

  /** Whether Android currently allows exact alarms. This also accounts for system exemptions. */
  fun exactAlarmStatus(context: Context): Status {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.GRANTED
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return Status.UNKNOWN
    return if (alarms.canScheduleExactAlarms()) Status.GRANTED else Status.MISSING
  }

  /** Whether SandFly may read notifications, which the daily summary is read from. */
  fun notificationAccessStatus(context: Context): Status =
    if (NotificationListService.isAccessGranted(context)) Status.GRANTED else Status.MISSING

  /** Whether SandFly's own notifications are allowed. */
  fun appNotificationsStatus(context: Context): Status =
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) Status.GRANTED
    else Status.MISSING

  fun notificationPolicyStatus(context: Context): Status {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Status.GRANTED
    val notificationManager =
      context.getSystemService(NotificationManager::class.java) ?: return Status.UNKNOWN
    return if (notificationManager.isNotificationPolicyAccessGranted) Status.GRANTED
    else Status.MISSING
  }

  /** Whether SandFly's screen reader service is switched on. */
  fun serviceStatus(context: Context): Status {
    val enabled =
      Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return Status.MISSING
    // The setting lists the switched-on services as package/class pairs; SandFly has the one.
    return if (enabled.split(':').any { it.startsWith(context.packageName + "/") }) {
      Status.GRANTED
    } else {
      Status.MISSING
    }
  }

  /**
   * Asks the system to exempt SandFly from battery optimization, at once when it can, and by
   * the list of apps when the direct ask is not there. Does nothing while SandFly is exempt.
   */
  fun promptForBatteryExemption(context: Context) {
    if (batteryStatus(context) != Status.MISSING) return
    val direct =
      Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:" + context.packageName),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(direct)
      return
    } catch (e: ActivityNotFoundException) {
      // Fall through to the list.
    }
    try {
      context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // Nowhere to ask; the initial setup will say the state it found.
    }
  }

  /**
   * Whether the phone maker's autostart leave is held. No system call reports it, so the
   * state is always [Status.UNKNOWN] and the initial setup can only offer the maker's page.
   */
  fun autostartStatus(context: Context): Status = Status.UNKNOWN

  /** Opens the phone maker's autostart page, falling back to Meridian's app page. */
  fun openAutoStartSettings(context: Context) {
    val pages =
      listOf(
        "com.miui.securitycenter" to
          "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to
          "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to
          "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to
          "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
      )
    for ((makerPackage, page) in pages) {
      try {
        context.startActivity(
          Intent()
            .setComponent(ComponentName(makerPackage, page))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
      } catch (_: ActivityNotFoundException) {
        // This maker's page is not on the phone; try the next maker.
      } catch (_: SecurityException) {
        // This maker's page refuses the open; try the next maker.
      }
    }
    try {
      context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (_: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  /** Opens the system's exact alarm special-access screen. */
  fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      exactAlarmStatus(context) != Status.MISSING) return

    try {
      context.startActivity(
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      return
    } catch (_: ActivityNotFoundException) {
      // Fall back to app details if the device has no dedicated page.
    } catch (_: SecurityException) {
      // A device-specific settings app may refuse the direct request.
    }

    try {
      context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (_: ActivityNotFoundException) {
      // The settings screens report the actual system state.
    } catch (_: SecurityException) {
      // The settings screens report the actual system state.
    }
  }

  /** Whether the phone has a microphone at all; without one there is nothing to ask for. */
  fun hasMicrophone(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

  /** Whether SandFly may listen, which dictation stands on. */
  fun microphoneStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Asks for the microphone in place, with the system's own dialog. */
  fun requestMicrophone(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.RECORD_AUDIO)
  }

  /** Whether the phone has telephony at all; without it there is no call state to read. */
  fun hasTelephony(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

  /** Whether SandFly may read the phone state, which following calls stands on. */
  fun phoneStateStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Asks for the phone state in place, with the system's own dialog. */
  fun requestPhoneState(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.READ_PHONE_STATE)
  }

  /**
   * Whether what the updater downloads can be installed, by the updater's own test: SandFly
   * may install packages itself, or the install goes through Shizuku and needs no such leave.
   */
  fun installStatus(context: Context): Status =
    if (
      context.packageManager.canRequestPackageInstalls() ||
        (PlayInstaller.isEnabled(context) && ShizukuShell.isAllowed())
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Opens the system's page for letting SandFly install the apps it downloads. */
  fun openInstallSettings(context: Context) {
    try {
      context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  /** Whether SandFly may change system settings, which the brightness control writes with. */
  fun brightnessStatus(context: Context): Status =
    if (ScreenBrightness.canWrite(context)) Status.GRANTED else Status.MISSING

  /** Opens the system's page for letting SandFly change system settings. */
  fun openBrightnessSettings(context: Context) {
    try {
      ScreenBrightness.requestWritePermission(context)
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  fun openNotificationPolicySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
      context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (_: ActivityNotFoundException) {
      return
    }
  }

  /** Opens the system's notification settings for SandFly itself. */
  fun openAppNotificationSettings(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  /** Opens the system's accessibility settings, where the service is switched on. */
  fun openAccessibilitySettings(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

  fun cameraStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun requestCamera(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.CAMERA)
  }

  fun callStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun requestCall(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.CALL_PHONE)
  }

  fun locationStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun requestLocation(context: Context) {
    PermissionUtils.requestPermissions(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )
  }

  fun bluetoothStatus(context: Context): Status {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.GRANTED
    return if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }
  }

  fun requestBluetooth(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.BLUETOOTH_CONNECT)
  }

  fun overlayStatus(context: Context): Status {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Status.GRANTED
    return if (Settings.canDrawOverlays(context)) Status.GRANTED else Status.MISSING
  }

  fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
      context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (_: ActivityNotFoundException) {
    }
  }

  fun storageStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun requestStorage(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
  }

  fun usageStatus(context: Context): Status =
    if (ShizukuGrants.Grant.USAGE_STATS.isGranted(context)) Status.GRANTED else Status.MISSING

  fun openUsageSettings(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (_: ActivityNotFoundException) {
    }
  }

  fun dumpStatus(context: Context): Status =
    if (
      context.checkSelfPermission(Manifest.permission.DUMP) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun secureSettingsStatus(context: Context): Status =
    if (
      context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  fun requestShizukuGrant(
    context: Context,
    @StringRes title: Int,
    grant: ShizukuGrants.Grant,
    onDone: () -> Unit,
  ) {
    if (ShizukuGrants.isReady()) {
      ShizukuGrants.request(context, grant) { onDone() }
      return
    }
    val command = "adb shell " + grant.shellCommand(context.packageName).joinToString(" ")
    MaterialAlertDialogBuilder(context)
      .setTitle(title)
      .setMessage(context.getString(R.string.timer_setup_shizuku_command, command))
      .setPositiveButton(R.string.action_copy_command) { _, _ ->
        ClipboardUtils.copyToClipboard(context, command)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .setOnDismissListener { onDone() }
      .show()
  }
}
