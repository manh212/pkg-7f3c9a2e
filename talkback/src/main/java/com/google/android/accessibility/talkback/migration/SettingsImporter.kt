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

package com.google.android.accessibility.talkback.migration

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.os.UserManagerCompat
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Copies the settings and custom labels of SandFly on Meridian's first start.
 *
 * It runs from the Application, before anything loads the preferences or the labels database, so
 * no stale copy of them is cached. The files come from [SettingsExportProvider] in SandFly,
 * which must be signed with SandFly's key.
 */
object SettingsImporter {
  private const val TAG = "SettingsImporter"
  private const val KEY_DONE = "migration_done"
  private const val KEY_ATTEMPTS = "migration_attempts"
  // A failed copy is tried again on the next starts, but not forever.
  private const val MAX_ATTEMPTS = 3
  private const val TEMP_SUFFIX = ".import"
  private data class StagedFile(val target: File, val temp: File, val path: String)

  private const val IMPORT_WAIT_TIMEOUT_MS = 5_000L

  /**
   * Imports the old app's settings, once. Call it first thing in Application.onCreate. The
   * provider query and staging run on a background thread; only startup publishes the copies.
   */
  @JvmStatic
  fun importIfNeeded(application: Application) {
    val context: Context = application
    if (context.packageName == AppIdMove.OLD_PACKAGE || !isMainProcess(context)) {
      return
    }
    val state = SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    if (state.getBoolean(KEY_DONE, false)) {
      return
    }
    // Custom labels are in credential-protected storage, so wait until the user unlocks.
    if (!UserManagerCompat.isUserUnlocked(context)) {
      LogUtils.i(TAG, "The user is locked, so settings are imported later")
      return
    }
    val attempts = state.getInt(KEY_ATTEMPTS, 0) + 1
    val pending = CompletableFuture.supplyAsync { prepareImport(context) }
    val imported =
      try {
        val files = pending.get(IMPORT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (files.isNotEmpty()) publishImport(context, files)
        true
      } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        LogUtils.e(TAG, "Cannot import settings from %s: %s", AppIdMove.OLD_PACKAGE, e)
        false
      } finally {
        pending.thenAccept { files -> files.forEach { it.temp.delete() } }
      }
    state.edit().putInt(KEY_ATTEMPTS, attempts)
      .putBoolean(KEY_DONE, imported || attempts >= MAX_ATTEMPTS).commit()
  }

  /** Returns true if SandFly is installed, signed with its own key. */
  @JvmStatic
  fun isOldAppInstalled(context: Context): Boolean =
    AppIdMove.isSignedBy(context.packageManager, AppIdMove.OLD_PACKAGE, AppIdMove.OLD_CERT_SHA256)

  private fun prepareImport(context: Context): List<StagedFile> {
    if (!isOldAppInstalled(context)) {
      LogUtils.i(TAG, "SandFly is not installed, so there are no settings to import")
      return emptyList()
    }
    val authority = AppIdMove.authority(AppIdMove.OLD_PACKAGE)
    val provider = context.packageManager.resolveContentProvider(authority, 0)
    if (provider == null || provider.packageName != AppIdMove.OLD_PACKAGE) {
      LogUtils.w(TAG, "The old app cannot share its settings, so none are imported")
      return emptyList()
    }
    val base = Uri.Builder().scheme("content").authority(authority).build()
    val paths = listFiles(context, base)

    val prefsDir =
      File(context.createDeviceProtectedStorageContext().dataDir, MigrationFiles.PREFS_DIR)
    val database = context.getDatabasePath(MigrationFiles.LABELS_DATABASE)
    val files = mutableListOf<StagedFile>()
    try {
      for (path in paths) {
        val (directory, name) = MigrationFiles.parse(path) ?: continue
        val target =
          when (directory) {
            MigrationFiles.PREFS_DIR -> {
              val targetName =
                MigrationFiles.importedPrefsName(name, AppIdMove.OLD_PACKAGE, context.packageName)
                  ?: continue
              File(prefsDir, targetName)
            }
            MigrationFiles.SCRIPTS_DIR -> scriptTarget(context, name)
            MigrationFiles.FILES_DIR -> targetIn(context.filesDir, name)
            MigrationFiles.DEVICE_FILES_DIR ->
              targetIn(context.createDeviceProtectedStorageContext().filesDir, name)
            else -> File(database.parentFile, name)
          }
        val uri =
          base
            .buildUpon()
            .appendPath(AppIdMove.PATH_FILE)
            .appendPath(directory)
            .apply { name.split('/').forEach { appendPath(it) } }
            .build()
        val parent = requireNotNull(target.parentFile)
        parent.mkdirs()
        val temp = File.createTempFile("meridian-", TEMP_SUFFIX, parent)
        files += StagedFile(target, temp, path)
        val input =
          context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
      }
      return files
    } catch (error: Exception) {
      files.forEach { it.temp.delete() }
      throw error
    }
  }

  private fun publishImport(context: Context, files: List<StagedFile>) {
    val database = context.getDatabasePath(MigrationFiles.LABELS_DATABASE)
    if (files.any { it.path.startsWith(MigrationFiles.DATABASES_DIR + "/") }) {
      for (suffix in listOf("-wal", "-shm", "-journal")) {
        File(database.path + suffix).delete()
      }
    }
    for ((target, temp, path) in files) {
      if (!temp.renameTo(target)) {
        throw IOException("Cannot move $temp to $target")
      }
      if (path.startsWith(MigrationFiles.PREFS_DIR + "/")) {
        File(target.path + ".bak").delete()
      }
    }

    // Loads the default preferences from the copy, which is the first load in this process.
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val editor = prefs.edit()
    MigrationFiles.SKIPPED_PREF_KEYS.forEach { editor.remove(it) }
    editor.commit()

    LogUtils.i(
      TAG,
      "Imported %d files from %s: %s",
      files.size,
      AppIdMove.OLD_PACKAGE,
      files.joinToString(", ") { "${it.path} (${it.target.length()} bytes)" },
    )
  }

  private fun listFiles(context: Context, base: Uri): List<String> {
    val uri = base.buildUpon().appendPath(AppIdMove.PATH_FILES).build()
    val cursor =
      context.contentResolver.query(uri, null, null, null, null)
        ?: throw IOException("The old app did not list its files")
    return cursor.use {
      val column = it.getColumnIndexOrThrow(AppIdMove.COLUMN_PATH)
      buildList {
        while (it.moveToNext()) {
          add(it.getString(column))
        }
      }
    }
  }

  private fun scriptTarget(context: Context, name: String): File {
    val files = context.filesDir.canonicalFile
    val root = File(files, MigrationFiles.SCRIPTS_DIR).canonicalFile
    if (!root.path.startsWith(files.path + File.separator)) {
      throw IOException("The scripts directory leaves app storage")
    }
    return targetIn(root, name)
  }

  private fun targetIn(root: File, name: String): File {
    val base = root.canonicalFile
    val target = File(base, name)
    if (!target.canonicalPath.startsWith(base.path + File.separator)) {
      throw IOException("The file leaves its storage directory")
    }
    return target
  }

  private fun isMainProcess(context: Context): Boolean {
    val processName =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
      } else {
        try {
          File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        } catch (e: IOException) {
          return false
        }
      }
    return processName == context.packageName
  }
}
