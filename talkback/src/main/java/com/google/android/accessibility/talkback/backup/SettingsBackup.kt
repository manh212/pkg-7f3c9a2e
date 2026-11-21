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

package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.clipboard.ClipboardHistory
import com.google.android.accessibility.talkback.clipboard.FavoriteTexts
import com.google.android.accessibility.talkback.compositor.UsageHintMode
import com.google.android.accessibility.talkback.labeling.CustomLabelsBackup
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.talkback.texteditor.EditorDocuments
import com.google.android.accessibility.talkback.timer.CountdownAlarm
import com.google.android.accessibility.talkback.timer.TimerKeepAliveService
import com.google.android.accessibility.talkback.timer.TimerSounds
import com.google.android.accessibility.talkback.utils.VerbosityPreferences
import com.google.android.accessibility.talkback.vietnameseime.KeyboardKeySounds
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.labeling.LabelsTable
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Exports Meridian's settings to a file and imports them from one, so that a phone can be set up
 * like another. The file holds every preference file, in device-protected and in
 * credential-protected storage, except the updater's state, the state of the move from Backtalk
 * Aaron, and API keys.
 *
 * Custom labels, the clipboard history and favorites, the timer and key sounds with their
 * files, the installed sound themes and the installed scripts are in it too, each as a
 * section of their own.
 *
 * The Braille keyboard's settings are in it too, except its custom sound choices and its
 * touch calibration, which belong to the phone they were set on.
 *
 * The text editor's documents are in it too, as a section of their own next to the files.
 *
 * Each of those pieces can also be backed up and restored on its own, from the Backup
 * screen: [exportItem] writes one item in this same format, and [importItem] restores
 * only that item from a file, even when the file holds more. The full backup and
 * restore above stay exactly as they are.
 *
 * A settings file exported from Backtalk can be imported too.
 *
 * A settings profile holds settings of the main, braille keyboard and braille display preference
 * files, without the state that only this phone has, such as whether a tutorial was shown: see
 * [profileValues]. While profiles are applied, an exported file holds the settings as they are
 * without them.
 */
object SettingsBackup {
  private const val TAG = "SettingsBackup"
  private const val FORMAT = "meridian-settings"
  private const val LEGACY_FORMAT = "sandfly-settings"
  private const val VERSION = 1
  private const val DOCUMENTS_KEY = "editorDocuments"
  private const val LABELS_KEY = "customLabels"
  private const val CLIPBOARD_HISTORY_KEY = "clipboardHistory"
  private const val FAVORITES_KEY = "favoriteTexts"
  private const val TIMER_SOUNDS_KEY = "timerSounds"
  private const val KEY_SOUNDS_KEY = "keySounds"
  private const val SOUND_THEMES_KEY = "soundThemes"
  private const val SCRIPTS_KEY = "scripts"
  private const val FILE_NAME = "meridian-settings.json"
  private const val INITIAL_SETTINGS_FILE = "initial-settings.json"
  private const val BUNDLED_DEFAULTS_APPLIED = "bundled_defaults_applied"
  private const val EVENT_PROCESSING_LEVEL_RESET = "event_processing_level_reset"
  private const val USAGE_HINTS_RESET = "usage_hints_reset_2"
  private const val KEY_EVENT_PROCESSING_LEVEL = "pref_event_processing_level"
  /**
   * The most a settings file can be, for export and for import. Sounds and themes ride
   * the file as base64, so a backup with every timer sound chosen at its largest is
   * about 20 MB on its own; 32 MB covers the sounds, themes and scripts a phone
   * realistically holds, with room. Larger would need the file streamed instead of held
   * whole in memory: this design holds the JSON tree, its text and its bytes at once,
   * about five times the file, and the app has no large heap. A backup past the limit
   * fails loudly on export and on import, instead of dropping files to fit it.
   */
  private const val MAX_BYTES = 32 * 1024 * 1024
  /** A script's files, as a backup may hold them: the same limits as a script package. */
  private const val SCRIPTS_MAX_BYTES = 5L * 1024 * 1024
  private const val SCRIPTS_MAX_FILES = 200
  private const val PREFS_SUFFIX = ".xml"

  /** The default preference file, whose name holds the app ID, so a file from another ID fits. */
  private const val DEFAULT_PREFS = "default"
  private const val DEVICE = "device"
  private const val CREDENTIAL = "credential"
  private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "settings-backup").apply { isDaemon = true }
  }
  private val mainHandler = Handler(Looper.getMainLooper())

  /** API keys stay on the phone, since the file can be shared or synced anywhere. */
  @VisibleForTesting
  val SECRET_KEYS = setOf("pref_gemini_api_key", "pref_ai_service_api_key")

  private val SKIPPED_FILES = setOf(MigrationFiles.STATE_PREFS, "meridian_enrollment", "meridian_support_private")

  private const val BRAILLE_PREFS = "BraillePhoneBreakthroughV2Prefs"

  private val BRAILLE_PHONE_KEYS =
    setOf(
      "use_custom_sound",
      "custom_sound_uri",
      "custom_sound_capital_uri",
      "custom_sound_space_uri",
      "use_calibrated_points",
    ) + (1..6).flatMap { listOf("cal_x_$it", "cal_y_$it") }

  /**
   * The preference files, besides the default one, that a settings profile holds. Other files keep
   * installed scripts, script sources, sound theme drafts and other content, not settings.
   */
  private val PROFILE_FILES = setOf("braille_keyboard", "braille_display")

  /**
   * Settings that stay as they are when a settings profile is applied: the state of first runs,
   * tutorials, migrations, downloads and counters, which describe this phone and not a profile.
   * SandFly's speech-control answers are not listed here: Meridian has no such feature.
   */
  private val PROFILE_STATE_KEYS =
    setOf(
      "has_training_exit",
      "has_onboarding_exit",
      "pref_direct_touch_seen",
      "pref_audio_outputs_seen",
      "talkback_enabled_count",
      "talkback_off_timestamp",
      "pref_previous_global_window_animation_scale_key",
      "migration_done",
      "migration_attempts",
      "cache_cleanup_version",
      "battery_optimization_check_done",
    )

  /** Parts of the keys of [PROFILE_STATE_KEYS]-like state. */
  private val PROFILE_STATE_FRAGMENTS =
    listOf(
      "_shown_times",
      "first_time",
      "first_update",
      "opt_in_count",
      "_installed",
      "_uninstalled",
      "install_lib",
      "_tutorial_done",
    )

  /** Whether [key] is left out of settings profiles and kept when one is applied. */
  @VisibleForTesting
  fun isKeptOutOfProfiles(key: String): Boolean =
    key in PROFILE_STATE_KEYS ||
      key in BACKTALK_SKIPPED_KEYS ||
      key.endsWith("_shown") ||
      key.endsWith("_shown_key") ||
      PROFILE_STATE_FRAGMENTS.any { key.contains(it) }

  /** Files of libraries, such as Google Play services and WebView, which hold their own state. */
  private val LIBRARY_PREFIXES = listOf("com.google.", "androidx.", "WebView", "Firebase")

  /** Sets up Export settings and Import settings in the main settings screen. */
  @JvmStatic
  fun setUpPreferences(fragment: PreferenceFragmentCompat) {
    val export =
      fragment.findPreference<Preference>(fragment.getString(R.string.pref_export_settings_key))
    val import =
      fragment.findPreference<Preference>(fragment.getString(R.string.pref_import_settings_key))
    if (export == null || import == null) {
      return
    }
    // Watches have no file picker.
    if (FormFactorUtils.isAndroidWear()) {
      export.parent?.removePreference(export)
      import.parent?.removePreference(import)
      return
    }
    val create =
      fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
      ) { uri ->
        uri?.let {
          val context = fragment.requireContext().applicationContext
          inBackground(context, R.string.settings_export_failed) {
            write(
              context,
              it,
              { export(context) },
              R.string.settings_export_done,
              R.string.settings_export_failed,
            )
          }
        }
      }
    val open =
      fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
          val context = fragment.requireContext().applicationContext
          inBackground(context, R.string.settings_import_failed) {
            read(
              context,
              it,
              { readContext, json -> import(readContext, json) },
              R.string.settings_import_done,
              R.string.settings_import_failed,
            )
          }
        }
      }
    export.setOnPreferenceClickListener {
      create.launch(FILE_NAME)
      true
    }
    import.setOnPreferenceClickListener {
      open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
      true
    }
  }

  /**
   * Files can be on a cloud provider, which downloads or uploads before the stream returns, and the
   * settings screen shares its main thread with the screen reader.
   */
  private val executor =
    ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())

  private fun inBackground(context: Context, @StringRes failed: Int, work: () -> Unit) {
    executor.execute {
      try {
        work()
      } catch (e: Throwable) {
        toast(context, failed)
      }
    }
  }

  private fun write(
    context: Context,
    uri: Uri,
    json: () -> String,
    @StringRes done: Int,
    @StringRes failed: Int,
    itemName: String? = null,
  ) {
    val appContext = context.applicationContext
    ioExecutor.execute {
      val written =
        try {
          val bytes = json().toByteArray()
          // A file past the import limit would import as "too large", so refuse to
          // write one rather than hand over a backup that cannot be used.
          bytes.size <= MAX_BYTES &&
            appContext.contentResolver.openOutputStream(uri, "wt")?.use {
              it.write(bytes)
            } != null
        } catch (e: IOException) {
          false
        } catch (e: SecurityException) {
          false
        }
      mainHandler.post {
        toast(appContext, if (written) done else failed, itemName)
      }
    }
  }

  private fun read(
    context: Context,
    uri: Uri,
    restore: (Context, String) -> Boolean,
    @StringRes done: Int,
    @StringRes failed: Int,
    itemName: String? = null,
  ) {
    val appContext = context.applicationContext
    ioExecutor.execute {
      val imported =
        try {
          val bytes =
            appContext.contentResolver.openInputStream(uri)?.use { input ->
              val buffer = ByteArray(MAX_BYTES + 1)
              var length = 0
              while (length < buffer.size) {
                val count = input.read(buffer, length, buffer.size - length)
                if (count < 0) break
                length += count
              }
              if (length > MAX_BYTES) throw IOException("The file is too large")
              buffer.copyOf(length)
            }
          bytes != null && restore(appContext, bytes.decodeToString())
        } catch (e: IOException) {
          false
        } catch (e: SecurityException) {
          false
        }
      mainHandler.post {
        toast(appContext, if (imported) done else failed, itemName)
      }
    }
  }

  /** Writes one backup item to the file the user picked, for [BackupItems]. */
  internal fun writeItem(context: Context, uri: Uri, item: BackupItem) {
    val appContext = context.applicationContext
    write(
      appContext,
      uri,
      { exportItem(appContext, item) },
      R.string.backup_item_export_done,
      R.string.backup_item_export_failed,
      appContext.getString(item.titleRes),
    )
  }

  /** Reads one backup item from the file the user picked, for [BackupItems]. */
  internal fun readItem(context: Context, uri: Uri, item: BackupItem) {
    val appContext = context.applicationContext
    read(
      appContext,
      uri,
      { readContext, json -> importItem(readContext, json, item) },
      R.string.backup_item_import_done,
      R.string.backup_item_import_failed,
      appContext.getString(item.titleRes),
    )
  }

  /** Returns every exported setting as JSON, with the settings that profiles replace as without them. */
  fun export(context: Context): String {
    val root =
      JSONObject().put("format", FORMAT).put("version", VERSION).put("files", exportFiles(context))
    for (item in BackupItem.entries) {
      if (item != BackupItem.SETTINGS) putSection(root, context, item)
    }
    return root.toString(2)
  }

  /**
   * Returns only [item] as JSON, in the same format as [export]: the settings file's
   * format and version, the preference files for [BackupItem.SETTINGS] and an empty
   * "files" object otherwise, and only that item's own section. A file written by
   * this is read by [import] and by [importItem] alike: [import] restores whatever
   * the file holds and leaves the rest alone, and [importItem] restores only [item].
   */
  fun exportItem(context: Context, item: BackupItem): String {
    val root = JSONObject().put("format", FORMAT).put("version", VERSION)
    if (item == BackupItem.SETTINGS) {
      root.put("files", exportFiles(context))
    } else {
      root.put("files", JSONObject())
      putSection(root, context, item)
    }
    return root.toString(2)
  }

  /** Every preference file as the backup carries it, in device and credential storage. */
  private fun exportFiles(context: Context): JSONObject {
    val base = SettingsProfiles.baseValues(context)
    val files = JSONObject()
    for ((storage, storageContext) in storages(context)) {
      val storageFiles = JSONObject()
      for (name in prefsNames(context, storageContext)) {
        val prefs = storageContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val exported = exportedName(context, name)
        val replaced = if (storage == DEVICE) base else emptyMap()
        val values = JSONObject()
        val all = prefs.all
        for ((key, value) in all) {
          if (isLeftOut(context, name, key, forProfile = false)) continue
          val profileKey = profileKey(exported, key)
          val kept = if (profileKey in replaced) replaced[profileKey] else value
          encode(kept)?.let { values.put(key, it) }
        }
        for ((profileKey, value) in replaced) {
          val (file, key) = splitProfileKey(profileKey) ?: continue
          if (file != exported || key in all) continue
          encode(value)?.let { values.put(key, it) }
        }
        storageFiles.put(exported, values)
      }
      files.put(storage, storageFiles)
    }
    return files
  }

  /** Adds [item]'s own section to [root], or nothing when the item cannot be read. */
  private fun putSection(root: JSONObject, context: Context, item: BackupItem) {
    when (item) {
      BackupItem.SETTINGS -> return
      BackupItem.EDITOR_DOCUMENTS ->
        EditorDocuments.backupJson(context)?.let { root.put(DOCUMENTS_KEY, JSONArray(it)) }
      BackupItem.CUSTOM_LABELS ->
        CustomLabelsBackup.backupJson(context)?.let { root.put(LABELS_KEY, JSONArray(it)) }
      BackupItem.CLIPBOARD_HISTORY ->
        ClipboardHistory.backupJson(context)?.let { root.put(CLIPBOARD_HISTORY_KEY, JSONArray(it)) }
      BackupItem.FAVORITE_TEXTS ->
        FavoriteTexts.backupJson(context)?.let { root.put(FAVORITES_KEY, JSONArray(it)) }
      BackupItem.TIMER_SOUNDS ->
        root.put(TIMER_SOUNDS_KEY, JSONArray(TimerSounds.backupJson(context)))
      BackupItem.KEY_SOUNDS ->
        root.put(KEY_SOUNDS_KEY, JSONArray(KeyboardKeySounds.backupJson(context)))
      BackupItem.SOUND_THEMES ->
        root.put(SOUND_THEMES_KEY, JSONArray(SoundThemes.backupJson(context)))
      BackupItem.SCRIPTS -> root.put(SCRIPTS_KEY, JSONArray(backupScriptsJson(context)))
    }
  }

  /** The key of a setting in a settings profile: the exported name of its file, "/" and its key. */
  @JvmStatic fun profileKey(file: String, key: String): String = "$file/$key"

  /** The file and key of [profileKey], or null when it isn't one. */
  @JvmStatic
  fun splitProfileKey(profileKey: String): Pair<String, String>? {
    val slash = profileKey.indexOf('/')
    if (slash <= 0 || slash == profileKey.length - 1) return null
    return profileKey.substring(0, slash) to profileKey.substring(slash + 1)
  }

  /** The preference key of [profileKey]. */
  @JvmStatic fun keyOf(profileKey: String): String = splitProfileKey(profileKey)?.second ?: profileKey

  /**
   * Every setting that a settings profile can hold, by [profileKey]: those of the main, braille
   * keyboard and braille display preference files in device-protected storage, without API keys,
   * the updater's state and the keys of [isKeptOutOfProfiles].
   */
  fun profileValues(context: Context): Map<String, Any> {
    val storage = context.createDeviceProtectedStorageContext()
    val values = mutableMapOf<String, Any>()
    for (name in prefsNames(context, storage)) {
      if (!isProfileFile(context, name)) continue
      val exported = exportedName(context, name)
      for ((key, value) in storage.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
        if (value == null || isLeftOut(context, name, key, forProfile = true)) continue
        values[profileKey(exported, key)] = value
      }
    }
    return values
  }

  /** The preference file of [profileKey], or null when a profile can't hold the setting. */
  private fun profilePrefs(context: Context, profileKey: String): Pair<SharedPreferences, String>? {
    val (file, key) = splitProfileKey(profileKey) ?: return null
    val name = importedName(context, file) ?: return null
    if (!isProfileFile(context, name) || isLeftOut(context, name, key, forProfile = true)) return null
    val storage = context.createDeviceProtectedStorageContext()
    return storage.getSharedPreferences(name, Context.MODE_PRIVATE) to key
  }

  /** Whether a settings profile can hold [profileKey]. */
  fun isProfileKey(context: Context, profileKey: String): Boolean = profilePrefs(context, profileKey) != null

  /** The value of [profileKey], or null when it isn't set or a profile can't hold it. */
  fun readProfileValue(context: Context, profileKey: String): Any? {
    val (prefs, key) = profilePrefs(context, profileKey) ?: return null
    return prefs.all[key]
  }

  /**
   * Whether [value] can be set as [profileKey]: a profile can hold the setting, and the value is of
   * the type Meridian reads it as. Null, for a setting that isn't set, always can.
   */
  fun canSetProfileValue(context: Context, profileKey: String, value: Any?): Boolean {
    val (prefs, key) = profilePrefs(context, profileKey) ?: return false
    return value == null || fits(context, prefs, key, value)
  }

  /** Sets each of [values] by [profileKey], and removes those that are null. */
  fun writeProfileValues(context: Context, values: Map<String, Any?>) {
    val edits = mutableMapOf<SharedPreferences, SharedPreferences.Editor>()
    for ((profileKey, value) in values) {
      val (prefs, key) = profilePrefs(context, profileKey) ?: continue
      val editor = edits.getOrPut(prefs) { prefs.edit() }
      @Suppress("UNCHECKED_CAST")
      when (value) {
        null -> editor.remove(key)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is String -> editor.putString(key, value)
        is Set<*> -> editor.putStringSet(key, value as Set<String>)
      }
    }
    for (editor in edits.values) editor.commit()
  }

  /** The installed scripts as the settings backup carries them: each script's files. */
  private fun backupScriptsJson(context: Context): String {
    val scriptsDir = File(context.filesDir, MigrationFiles.SCRIPTS_DIR)
    val scripts = JSONArray()
    for (dir in scriptsDir
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && MigrationFiles.isSafeScriptId(it.name) }
      .sortedBy { it.name }) {
      FileTreeBackup.toJson(dir, SCRIPTS_MAX_BYTES, SCRIPTS_MAX_FILES)?.let { files ->
        scripts.put(JSONObject().put("id", dir.name).put("files", files))
      }
    }
    return scripts.toString()
  }

  /**
   * Replaces the settings in each preference file of [json] with the file's. Files that [json]
   * doesn't hold, API keys and the updater's state are kept. Returns false if [json] is not a
   * Meridian settings file, and then changes nothing.
   */
  fun import(context: Context, json: String): Boolean =
    // The imported settings are the settings without profiles, which are then applied to them.
    SettingsProfiles.replacingSettings(context) { importSettings(context, json, replaceMissing = true) }

  /**
   * Restores only [item] from [json], and leaves every other item alone — even when
   * the file holds more than [item], as a full backup does. Returns false when [json]
   * is not a Meridian settings file, or holds nothing of [item], and then changes
   * nothing. A file exported from Backtalk holds settings only, without the sections
   * a single item is picked from, so it goes through the full [import] instead.
   */
  fun importItem(context: Context, json: String, item: BackupItem): Boolean {
    val root =
      try {
        JSONObject(json)
      } catch (e: JSONException) {
        return false
      }
    if (isBacktalkFile(root)) return false
    val format = root.optString("format")
    if ((format != FORMAT && format != LEGACY_FORMAT) || root.optInt("version") > VERSION) {
      return false
    }
    val single = JSONObject().put("format", FORMAT).put("version", VERSION)
    try {
      if (item == BackupItem.SETTINGS) {
        // A per-item file for another item carries an empty "files" object; it
        // holds no settings to restore, so it is not a settings backup.
        val files = root.optJSONObject("files") ?: return false
        if (files.length() == 0) return false
        single.put("files", files)
      } else {
        single.put("files", JSONObject())
        if (!putItemSection(single, root, item)) return false
      }
    } catch (e: JSONException) {
      return false
    }
    return importSettings(context, single.toString(), replaceMissing = true)
  }

  /** Copies [item]'s section from [root] into [single]; false when [root] holds none. */
  private fun putItemSection(single: JSONObject, root: JSONObject, item: BackupItem): Boolean {
    val key =
      when (item) {
        BackupItem.SETTINGS -> return true
        BackupItem.EDITOR_DOCUMENTS -> DOCUMENTS_KEY
        BackupItem.CUSTOM_LABELS -> LABELS_KEY
        BackupItem.CLIPBOARD_HISTORY -> CLIPBOARD_HISTORY_KEY
        BackupItem.FAVORITE_TEXTS -> FAVORITES_KEY
        BackupItem.TIMER_SOUNDS -> TIMER_SOUNDS_KEY
        BackupItem.KEY_SOUNDS -> KEY_SOUNDS_KEY
        BackupItem.SOUND_THEMES -> SOUND_THEMES_KEY
        BackupItem.SCRIPTS -> SCRIPTS_KEY
      }
    val section = root.optJSONArray(key) ?: return false
    single.put(key, section)
    return true
  }

  private fun importSettings(context: Context, json: String, replaceMissing: Boolean): Boolean {
    var documents: JSONArray? = null
    var labels: JSONArray? = null
    var clipboardHistory: JSONArray? = null
    var favorites: JSONArray? = null
    var timerSounds: JSONArray? = null
    var keySounds: JSONArray? = null
    var soundThemes: JSONArray? = null
    var scripts: JSONArray? = null
    val files =
      try {
        val root = JSONObject(json)
        if (isBacktalkFile(root)) {
          return importBacktalk(backtalkStores(context), root, context)
        }
        val format = root.optString("format")
        if ((format != FORMAT && format != LEGACY_FORMAT) || root.optInt("version") > VERSION) return false
        if (root.has(DOCUMENTS_KEY) && !root.isNull(DOCUMENTS_KEY)) {
          documents = root.optJSONArray(DOCUMENTS_KEY) ?: return false
        }
        if (root.has(LABELS_KEY) && !root.isNull(LABELS_KEY)) {
          labels = root.optJSONArray(LABELS_KEY) ?: return false
        }
        if (root.has(CLIPBOARD_HISTORY_KEY) && !root.isNull(CLIPBOARD_HISTORY_KEY)) {
          clipboardHistory = root.optJSONArray(CLIPBOARD_HISTORY_KEY) ?: return false
        }
        if (root.has(FAVORITES_KEY) && !root.isNull(FAVORITES_KEY)) {
          favorites = root.optJSONArray(FAVORITES_KEY) ?: return false
        }
        if (root.has(TIMER_SOUNDS_KEY) && !root.isNull(TIMER_SOUNDS_KEY)) {
          timerSounds = root.optJSONArray(TIMER_SOUNDS_KEY) ?: return false
        }
        if (root.has(KEY_SOUNDS_KEY) && !root.isNull(KEY_SOUNDS_KEY)) {
          keySounds = root.optJSONArray(KEY_SOUNDS_KEY) ?: return false
        }
        if (root.has(SOUND_THEMES_KEY) && !root.isNull(SOUND_THEMES_KEY)) {
          soundThemes = root.optJSONArray(SOUND_THEMES_KEY) ?: return false
        }
        if (root.has(SCRIPTS_KEY) && !root.isNull(SCRIPTS_KEY)) {
          scripts = root.optJSONArray(SCRIPTS_KEY) ?: return false
        }
        root.getJSONObject("files")
      } catch (e: JSONException) {
        return false
      }
    val edits = mutableListOf<Pair<SharedPreferences, Map<String, Any>>>()
    for ((storage, storageContext) in storages(context)) {
      val storageFiles = files.optJSONObject(storage) ?: continue
      for (file in storageFiles.keys()) {
        val name = importedName(context, file) ?: continue
        val values = storageFiles.optJSONObject(file) ?: return false
        val prefs = storageContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val decoded = mutableMapOf<String, Any>()
        for (key in values.keys()) {
          if (isLeftOut(context, name, key, forProfile = false)) continue
          val value = decode(values.optJSONObject(key)) ?: return false
          // A value that doesn't fit keeps the phone's own.
          decoded[key] = if (fits(context, prefs, key, value)) value else prefs.all[key] ?: continue
        }
        if (name == BRAILLE_PREFS) {
          for (key in BRAILLE_PHONE_KEYS) {
            prefs.all[key]?.let { decoded[key] = it }
          }
        }
        edits += prefs to decoded
      }
    }
    if (documents != null && !validateDocumentsBackup(documents)) {
      return false
    }
    if (labels != null && !validateLabelsBackup(labels)) {
      return false
    }
    if (clipboardHistory != null && !validateStringArrayBackup(clipboardHistory)) {
      return false
    }
    if (favorites != null && !validateStringArrayBackup(favorites)) {
      return false
    }
    if (timerSounds != null && !validateSoundsBackup(timerSounds, TimerSounds.Slot.entries.map { it.fileName }.toSet())) {
      return false
    }
    if (keySounds != null && !validateSoundsBackup(keySounds, KeyboardKeySounds.Slot.entries.map { it.fileName }.toSet())) {
      return false
    }
    if (soundThemes != null && !validateSoundThemesBackup(soundThemes)) {
      return false
    }
    val parsedScripts =
      if (scripts != null) {
        parseScriptsBackup(scripts) ?: return false
      } else {
        null
      }
    if (documents != null && !EditorDocuments.restoreBackup(context, documents.toString())) {
      return false
    }
    if (labels != null && !CustomLabelsBackup.restoreBackup(context, labels.toString())) {
      return false
    }
    if (clipboardHistory != null && !ClipboardHistory.restoreBackup(context, clipboardHistory.toString())) {
      return false
    }
    if (favorites != null && !FavoriteTexts.restoreBackup(context, favorites.toString())) {
      return false
    }
    if (timerSounds != null && !TimerSounds.restoreBackup(context, timerSounds.toString())) {
      return false
    }
    if (keySounds != null && !KeyboardKeySounds.restoreBackup(context, keySounds.toString())) {
      return false
    }
    if (soundThemes != null && !SoundThemes.restoreBackup(context, soundThemes.toString())) {
      return false
    }
    if (parsedScripts != null && !writeScriptsBackup(context, parsedScripts)) {
      return false
    }
    for ((prefs, values) in edits) {
      val editor = prefs.edit()
      for (key in prefs.all.keys) {
        if (replaceMissing && key !in values && !isLeftOut(context, null, key, forProfile = false)) {
          editor.remove(key)
        }
      }
      for ((key, value) in values) {
        if (!replaceMissing && prefs.contains(key)) continue
        @Suppress("UNCHECKED_CAST")
        when (value) {
          is Boolean -> editor.putBoolean(key, value)
          is Int -> editor.putInt(key, value)
          is Long -> editor.putLong(key, value)
          is Float -> editor.putFloat(key, value)
          is String -> editor.putString(key, value)
          is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
      }
      if (!editor.commit()) return false
    }
    return true
  }

  /**
   * Replaces the installed scripts with the ones in a settings backup. Every script is
   * read first, so a bad backup changes nothing. A script's manifest is not checked
   * here: reading it lives in the phone flavor, and a script whose manifest cannot be
   * read is skipped when the scripts load. The scripts list in memory stays as it was
   * until the app restarts, like the script index the preferences already restore.
   */
  private fun validateDocumentsBackup(documents: JSONArray): Boolean {
    for (i in 0 until documents.length()) {
      if (documents.optJSONObject(i) == null) return false
    }
    return true
  }

  private fun validateLabelsBackup(labels: JSONArray): Boolean {
    val requiredColumns =
      arrayOf(
        LabelsTable.KEY_PACKAGE_NAME,
        LabelsTable.KEY_PACKAGE_SIGNATURE,
        LabelsTable.KEY_VIEW_NAME,
        LabelsTable.KEY_TEXT,
        LabelsTable.KEY_LOCALE,
        LabelsTable.KEY_PACKAGE_VERSION,
        LabelsTable.KEY_TIMESTAMP,
        LabelsTable.KEY_SOURCE_TYPE,
      )
    for (i in 0 until labels.length()) {
      val entry = labels.optJSONObject(i) ?: return false
      for (column in requiredColumns) {
        if (!entry.has(column)) return false
      }
    }
    return true
  }

  private fun validateStringArrayBackup(array: JSONArray): Boolean {
    for (i in 0 until array.length()) {
      if (array.isNull(i) || array.opt(i) !is String) return false
    }
    return true
  }

  private fun validateSoundsBackup(sounds: JSONArray, allowedSlots: Set<String>): Boolean {
    for (i in 0 until sounds.length()) {
      val entry = sounds.optJSONObject(i) ?: return false
      val slot = entry.optString("slot", "")
      val name = entry.optString("name", "")
      val data = entry.optString("data", "")
      if (
        slot !in allowedSlots ||
          !name.startsWith("$slot.") ||
          name.contains('/') ||
          name.contains('\\') ||
          name.contains('\u0000')
      ) {
        return false
      }
      val bytes =
        try {
          Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
          return false
        }
      if (bytes.isEmpty() || bytes.size > SoundThemes.MAX_SOUND_BYTES) return false
    }
    return true
  }

  private fun validateSoundThemesBackup(themes: JSONArray): Boolean {
    val seen = HashSet<String>()
    for (i in 0 until themes.length()) {
      val entry = themes.optJSONObject(i) ?: return false
      val id = entry.optString("id", "")
      if (id.isEmpty() || id.startsWith(".") || !seen.add(id)) return false
      if (FileTreeBackup.parse(entry.optJSONArray("files"), SoundThemes.MAX_THEME_BYTES, 1000) == null) {
        return false
      }
    }
    return true
  }

  private fun parseScriptsBackup(scripts: JSONArray): LinkedHashMap<String, List<Pair<String, ByteArray>>>? {
    val wanted = LinkedHashMap<String, List<Pair<String, ByteArray>>>()
    for (i in 0 until scripts.length()) {
      val entry = scripts.optJSONObject(i) ?: return null
      val id = entry.optString("id", "")
      if (!MigrationFiles.isSafeScriptId(id) || wanted.containsKey(id)) return null
      val files =
        FileTreeBackup.parse(entry.optJSONArray("files"), SCRIPTS_MAX_BYTES, SCRIPTS_MAX_FILES)
          ?: return null
      if (files.none { it.first == "main.lua" }) return null
      wanted[id] = files
    }
    return wanted
  }

  private fun writeScriptsBackup(
    context: Context,
    wanted: LinkedHashMap<String, List<Pair<String, ByteArray>>>,
  ): Boolean {
    val scriptsDir = File(context.filesDir, MigrationFiles.SCRIPTS_DIR)
    for (dir in scriptsDir
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && MigrationFiles.isSafeScriptId(it.name) }) {
      if (dir.name !in wanted) dir.deleteRecursively()
    }
    for ((id, files) in wanted) {
      if (!FileTreeBackup.write(File(scriptsDir, id), files)) return false
    }
    return true
  }

  @JvmStatic
  fun applyInitialDefaultsIfNeeded(context: Context) {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val devicePreferences =
      File(deviceContext.dataDir, "${MigrationFiles.PREFS_DIR}/${defaultName(context)}$PREFS_SUFFIX")
    if (!devicePreferences.exists() && UserManagerCompat.isUserUnlocked(context)) {
      SharedPreferencesUtils.migrateSharedPreferences(context)
    }

    val statePrefs =
      SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    if (statePrefs.getBoolean(BUNDLED_DEFAULTS_APPLIED, false)) return

    val currentSettings = readInitialSettings(context, INITIAL_SETTINGS_FILE) ?: return
    if (!importSettings(context, currentSettings, replaceMissing = false)) {
      LogUtils.e(TAG, "Cannot apply the bundled initial settings")
      return
    }
    if (
      !statePrefs
        .edit()
        .putBoolean(BUNDLED_DEFAULTS_APPLIED, true)
        .remove("initial_settings_applied")
        .remove("initial_settings_snapshot")
        .commit()
    ) {
      LogUtils.e(TAG, "Cannot record that the bundled initial settings were applied")
    }
  }

  /**
   * Raises every existing install to the low event-processing level, once. Very low drops
   * progress updates and announcements that are not tied to the user's own touch, which left
   * progress bars silent. The reset runs a single time; a level the user picks afterwards in
   * the developer settings is left alone.
   */
  @JvmStatic
  fun applyEventProcessingLevelResetIfNeeded(context: Context) {
    val statePrefs =
      SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    if (statePrefs.getBoolean(EVENT_PROCESSING_LEVEL_RESET, false)) return
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (!prefs.edit().putString(KEY_EVENT_PROCESSING_LEVEL, "low").commit()) {
      LogUtils.e(TAG, "Cannot raise the event processing level")
      return
    }
    if (!statePrefs.edit().putBoolean(EVENT_PROCESSING_LEVEL_RESET, true).commit()) {
      LogUtils.e(TAG, "Cannot record the event processing level reset")
    }
  }

  /**
   * Sets every existing install to speak usage hints only when the focused item has actions,
   * once. Values stored by the old on/off setting are turned into the matching mode first, so
   * no stored value is left unreadable. The reset runs a single time; a mode the user picks
   * afterwards in the reading settings is left alone.
   */
  @JvmStatic
  fun applyUsageHintsResetIfNeeded(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    UsageHintMode.migrate(prefs, context.resources)
    val statePrefs =
      SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    if (statePrefs.getBoolean(USAGE_HINTS_RESET, false)) return
    val key = context.getString(R.string.pref_a11y_hints_key)
    val customKey =
      VerbosityPreferences.toVerbosityPrefKey(
        context.getString(R.string.pref_verbosity_preset_value_custom), key)
    if (!prefs.edit()
        .putString(key, UsageHintMode.ACTIONS_ONLY)
        .putString(customKey, UsageHintMode.OFF)
        .commit()) {
      LogUtils.e(TAG, "Cannot set usage hints to actions only")
      return
    }
    if (!statePrefs.edit().putBoolean(USAGE_HINTS_RESET, true).commit()) {
      LogUtils.e(TAG, "Cannot record the usage hints reset")
    }
  }

  private fun readInitialSettings(context: Context, fileName: String): String? =
    try {
      context.assets.open(fileName).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
      LogUtils.e(TAG, "Cannot read bundled settings %s: %s", fileName, e)
      null
    }

  /** Backtalk's settings file: format 1, with a preference file for each store. */
  private const val BACKTALK_FORMAT = 1

  /** Settings that Backtalk leaves out of its file, by part of their key: state and secrets. */
  private val BACKTALK_SKIPPED_FRAGMENTS =
    listOf(
      "api_key",
      "_shown",
      "_installed",
      "_uninstalled",
      "install_lib",
      "first_update",
      "first_time",
      "opt_in_count",
    )

  /** Settings that are state of Backtalk's on its phone, not settings to bring over. */
  private val BACKTALK_SKIPPED_KEYS =
    setOf(
      "pref_developer_version_code",
      "brailleime_shared_prefs_version",
      "remembered_devices_with_addresses",
    )

  private fun isBacktalkFile(root: JSONObject) =
    root.opt("format") is Int && root.optJSONObject("stores") != null

  /** Meridian's preference files for the stores in Backtalk's file, by their names there. */
  private fun backtalkStores(context: Context): Map<String, SharedPreferences> =
    mapOf(
      "main" to SharedPreferencesUtils.getSharedPreferences(context),
      "braille_keyboard" to SharedPreferencesUtils.getSharedPreferences(context, "braille_keyboard"),
      "braille_display" to SharedPreferencesUtils.getSharedPreferences(context, "braille_display"),
    )

  /**
   * Sets the settings in a file exported from Backtalk, [root], in [stores]. Settings that the file
   * doesn't hold, such as Meridian's own, are kept. Returns false if the file can't be read, and then
   * changes nothing.
   */
  @VisibleForTesting
  fun importBacktalk(
    stores: Map<String, SharedPreferences>,
    root: JSONObject,
    context: Context? = null,
  ): Boolean {
    if (root.optInt("format") != BACKTALK_FORMAT) return false
    val storesJson = root.optJSONObject("stores") ?: return false
    val edits = mutableListOf<Pair<SharedPreferences, Map<String, Any>>>()
    for ((name, prefs) in stores) {
      val entries = storesJson.optJSONObject(name) ?: continue
      val decoded = mutableMapOf<String, Any>()
      for (key in entries.keys()) {
        if (!isBacktalkSettingKept(key)) continue
        val value = decodeBacktalk(entries.optJSONObject(key)) ?: return false
        if (fits(context, prefs, key, value)) decoded[key] = value
      }
      edits += prefs to decoded
    }
    for ((prefs, values) in edits) {
      val editor = prefs.edit()
      for ((key, value) in values) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
          is Boolean -> editor.putBoolean(key, value)
          is Int -> editor.putInt(key, value)
          is Long -> editor.putLong(key, value)
          is Float -> editor.putFloat(key, value)
          is String -> editor.putString(key, value)
          is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
      }
      editor.commit()
    }
    return true
  }

  private fun isBacktalkSettingKept(key: String) =
    !isSecretKey(key) &&
      key !in BACKTALK_SKIPPED_KEYS &&
      key !in MigrationFiles.SKIPPED_PREF_KEYS &&
      BACKTALK_SKIPPED_FRAGMENTS.none { key.contains(it) }

  /** Reads one setting of Backtalk's file: its type in "t" and its value in "v". */
  private fun decodeBacktalk(json: JSONObject?): Any? {
    json ?: return null
    return try {
      when (json.getString("t")) {
        "bool" -> json.getBoolean("v")
        "int" -> json.getInt("v")
        "long" -> json.getLong("v")
        "float" -> json.getDouble("v").toFloat()
        "string" -> json.getString("v")
        "set" -> {
          val array = json.getJSONArray("v")
          (0 until array.length()).map { array.getString(it) }.toSet()
        }
        else -> null
      }
    } catch (e: JSONException) {
      null
    }
  }

  /**
   * Whether [value] can be stored under [key] in [prefs]. Meridian reads each setting as one type, so
   * a value of another type, or text where a number is stored as text, would throw when read. The
   * type comes from the setting's current value, or else from its default resource.
   */
  private fun fits(context: Context?, prefs: SharedPreferences, key: String, value: Any): Boolean {
    val expected = prefs.all[key] ?: context?.let { defaultValue(it, key) } ?: return true
    if (expected is Set<*>) return value is Set<*>
    if (expected.javaClass != value.javaClass) return false
    return !(expected is String && expected.toFloatOrNull() != null && (value as String).toFloatOrNull() == null)
  }

  /** The default resource of the setting [key], named after the key with "_default" added. */
  private fun defaultValue(context: Context, key: String): Any? {
    val res = context.resources
    for (type in listOf("bool", "integer", "string")) {
      @Suppress("DiscouragedApi")
      val id = res.getIdentifier(key + "_default", type, context.packageName)
      if (id == 0) continue
      return when (type) {
        "bool" -> res.getBoolean(id)
        "integer" -> res.getInteger(id)
        else -> res.getString(id)
      }
    }
    return null
  }

  private fun storages(context: Context): List<Pair<String, Context>> =
    listOf(DEVICE to context.createDeviceProtectedStorageContext(), CREDENTIAL to context)

  private fun prefsNames(context: Context, storageContext: Context): List<String> {
    val directory = File(storageContext.dataDir, MigrationFiles.PREFS_DIR)
    return directory
      .listFiles()
      .orEmpty()
      .map { it.name }
      .filter { it.endsWith(PREFS_SUFFIX) && it.length > PREFS_SUFFIX.length }
      .map { it.removeSuffix(PREFS_SUFFIX) }
      .filter { name -> name == defaultName(context) || !isOtherFile(name) }
      .sorted()
  }

  /** Whether [name] is a file that isn't exported: the move's state, or a library's. */
  private fun isOtherFile(name: String) =
    name in SKIPPED_FILES || LIBRARY_PREFIXES.any { name.startsWith(it) }

  private fun defaultName(context: Context) = context.packageName + "_preferences"

  private fun exportedName(context: Context, name: String) =
    if (name == defaultName(context)) DEFAULT_PREFS else name

  private fun importedName(context: Context, name: String): String? =
    when {
      name == DEFAULT_PREFS -> defaultName(context)
      !MigrationFiles.isSafeName(name) || isOtherFile(name) -> null
      // Another app ID's default file, which this app doesn't read.
      name.endsWith("_preferences") -> null
      else -> name
    }

  private fun isProfileFile(context: Context, name: String) =
    name == defaultName(context) || name in PROFILE_FILES

  /**
   * Whether [key] of the preference file [name], or of any file for null, is left out of the file
   * or profile, and kept as it is when one is imported or applied.
   */
  private fun isLeftOut(context: Context, name: String?, key: String, forProfile: Boolean) =
    isSecretKey(key) || isSkipped(context, name, key) || isBraillePhoneKey(name, key) ||
      (forProfile && isKeptOutOfProfiles(key))

  /**
   * The updater's state stays as it is on this phone, and so do the countdown alarms still to
   * fire: their deadlines belong to this phone's timeline, so a backup never carries them to
   * another phone or brings them back. The timer keep-alive consent is likewise a leave given
   * on this phone, so it stays here too. Null [name] means any file.
   */
  private fun isSkipped(context: Context, name: String?, key: String) =
    (name == null || name == defaultName(context)) &&
      (key in MigrationFiles.SKIPPED_PREF_KEYS ||
        key == CountdownAlarm.KEY_ALARMS ||
        key == TimerKeepAliveService.PREF_ALLOWED)

  private fun isBraillePhoneKey(name: String?, key: String) =
    name == BRAILLE_PREFS && key in BRAILLE_PHONE_KEYS

  private fun isSecretKey(key: String) = key.contains("api_key", ignoreCase = true)

  @VisibleForTesting
  fun encode(value: Any?): JSONObject? =
    when (value) {
      is Boolean -> JSONObject().put("type", "boolean").put("value", value)
      is Int -> JSONObject().put("type", "int").put("value", value)
      is Long -> JSONObject().put("type", "long").put("value", value)
      is Float -> JSONObject().put("type", "float").put("value", value.toString())
      is String -> JSONObject().put("type", "string").put("value", value)
      is Set<*> ->
        JSONObject().put("type", "set").put("value", JSONArray(value.map { it.toString() }))
      else -> null
    }

  @VisibleForTesting
  fun decode(json: JSONObject?): Any? {
    json ?: return null
    return try {
      when (json.getString("type")) {
        "boolean" -> json.getBoolean("value")
        "int" -> json.getInt("value")
        "long" -> json.getLong("value")
        "float" -> json.getString("value").toFloat()
        "string" -> json.getString("value")
        "set" -> {
          val array = json.getJSONArray("value")
          (0 until array.length()).map { array.getString(it) }.toSet()
        }
        else -> null
      }
    } catch (e: JSONException) {
      null
    } catch (e: NumberFormatException) {
      null
    }
  }

  private fun toast(context: Context, @StringRes message: Int, arg: String? = null) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(
          context,
          if (arg == null) context.getString(message) else context.getString(message, arg),
          Toast.LENGTH_LONG,
        )
        .show()
    }
  }
}

