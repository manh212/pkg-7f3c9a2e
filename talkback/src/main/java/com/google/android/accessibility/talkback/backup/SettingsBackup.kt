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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.compositor.UsageHintMode
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.texteditor.EditorDocuments
import com.google.android.accessibility.talkback.timer.CountdownAlarm
import com.google.android.accessibility.talkback.timer.TimerKeepAliveService
import com.google.android.accessibility.talkback.timer.TimerSounds
import com.google.android.accessibility.talkback.utils.VerbosityPreferences
import com.google.android.accessibility.talkback.vietnameseime.KeyboardKeySounds
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
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
 * Custom labels, installed scripts and sound themes are files of their own, and are not in it.
 *
 * The Braille keyboard's settings are in it too, except its custom sound choices and its
 * touch calibration, which belong to the phone they were set on.
 *
 * The text editor's documents are in it too, as a section of their own next to the files.
 *
 * A settings file exported from Backtalk can be imported too.
 */
object SettingsBackup {
  private const val TAG = "SettingsBackup"
  private const val FORMAT = "meridian-settings"
  private const val LEGACY_FORMAT = "sandfly-settings"
  private const val VERSION = 1
  private const val DOCUMENTS_KEY = "editorDocuments"
  private const val FILE_NAME = "meridian-settings.json"
  private const val INITIAL_SETTINGS_FILE = "initial-settings.json"
  private const val BUNDLED_DEFAULTS_APPLIED = "bundled_defaults_applied"
  private const val EVENT_PROCESSING_LEVEL_RESET = "event_processing_level_reset"
  private const val USAGE_HINTS_RESET = "usage_hints_reset_2"
  private const val KEY_EVENT_PROCESSING_LEVEL = "pref_event_processing_level"
  private const val MAX_BYTES = 5 * 1024 * 1024
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
          inBackground(context, R.string.settings_export_failed) { write(context, it) }
        }
      }
    val open =
      fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
          val context = fragment.requireContext().applicationContext
          inBackground(context, R.string.settings_import_failed) { read(context, it) }
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

  private fun write(context: Context, uri: Uri) {
    val appContext = context.applicationContext
    ioExecutor.execute {
      val written =
        try {
          appContext.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(export(appContext).toByteArray())
          } != null
        } catch (e: IOException) {
          false
        } catch (e: SecurityException) {
          false
        }
      mainHandler.post {
        toast(appContext, if (written) R.string.settings_export_done else R.string.settings_export_failed)
      }
    }
  }

  private fun read(context: Context, uri: Uri) {
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
          bytes != null && import(appContext, bytes.decodeToString())
        } catch (e: IOException) {
          false
        } catch (e: SecurityException) {
          false
        }
      mainHandler.post {
        toast(appContext, if (imported) R.string.settings_import_done else R.string.settings_import_failed)
      }
    }
  }

  /** Returns every exported setting as JSON. */
  fun export(context: Context): String {
    val files = JSONObject()
    for ((storage, storageContext) in storages(context)) {
      val storageFiles = JSONObject()
      for (name in prefsNames(context, storageContext)) {
        val prefs = storageContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val values = JSONObject()
        for ((key, value) in prefs.all) {
          if (isSecretKey(key) || isSkipped(context, name, key)) continue
          if (isBraillePhoneKey(name, key)) continue
          encode(value)?.let { values.put(key, it) }
        }
        storageFiles.put(exportedName(context, name), values)
      }
      files.put(storage, storageFiles)
    }
    val root =
      JSONObject().put("format", FORMAT).put("version", VERSION).put("files", files)
    EditorDocuments.backupJson(context)?.let { root.put(DOCUMENTS_KEY, JSONArray(it)) }
    return root.toString(2)
  }

  /**
   * Replaces the settings in each preference file of [json] with the file's. Files that [json]
   * doesn't hold, API keys and the updater's state are kept. Returns false if [json] is not a
   * Meridian settings file, and then changes nothing.
   */
  fun import(context: Context, json: String): Boolean =
    importSettings(context, json, replaceMissing = true)

  private fun importSettings(context: Context, json: String, replaceMissing: Boolean): Boolean {
    var documents: JSONArray? = null
    val files =
      try {
        val root = JSONObject(json)
        if (isBacktalkFile(root)) {
          return importBacktalk(backtalkStores(context), root, context)
        }
        val format = root.optString("format")
        if ((format != FORMAT && format != LEGACY_FORMAT) || root.optInt("version") > VERSION) return false
        documents = root.optJSONArray(DOCUMENTS_KEY)
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
          if (isSecretKey(key) || isSkipped(context, name, key)) continue
          if (isBraillePhoneKey(name, key)) continue
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
    if (documents != null && !EditorDocuments.restoreBackup(context, documents.toString())) {
      return false
    }
    for ((prefs, values) in edits) {
      val editor = prefs.edit()
      for (key in prefs.all.keys) {
        if (replaceMissing && key !in values && !isSecretKey(key) && !isSkipped(context, null, key)) {
          editor.remove(key)
        }
      }
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
      if (!editor.commit()) return false
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
   * Raises every existing install to the medium event-processing level, once. Very low drops
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
    if (!prefs.edit().putString(KEY_EVENT_PROCESSING_LEVEL, "medium").commit()) {
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
        .putString(customKey, UsageHintMode.ACTIONS_ONLY)
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

  /**
   * The updater's state stays as it is on this phone, and so do the countdown alarms still to
   * fire: their deadlines belong to this phone's timeline, so a backup never carries them to
   * another phone or brings them back. The timer keep-alive consent is likewise a leave given
   * on this phone, so it stays here too. Null [name] means any file.
   */
  private fun isSkipped(context: Context, name: String?, key: String) =
    (name == null || name == defaultName(context)) &&
      (key in MigrationFiles.SKIPPED_PREF_KEYS ||
        key == "pref_gesture_profile" ||
        key == CountdownAlarm.KEY_ALARMS ||
        key == TimerKeepAliveService.PREF_ALLOWED ||
        key in TimerSounds.Slot.entries.map { it.key } ||
        key in KeyboardKeySounds.Slot.entries.map { it.key })

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

  private fun toast(context: Context, @StringRes message: Int) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }
}

