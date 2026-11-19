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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.google.android.accessibility.talkback.clipboard.ClipboardHistory
import com.google.android.accessibility.talkback.clipboard.FavoriteTexts
import com.google.android.accessibility.talkback.labeling.LabelProvider
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.soundthemes.SoundThemeManifest
import com.google.android.accessibility.talkback.soundthemes.SoundThemes
import com.google.android.accessibility.talkback.texteditor.EditorDocuments
import com.google.android.accessibility.talkback.timer.SoundFileStore
import com.google.android.accessibility.talkback.timer.TimerSounds
import com.google.android.accessibility.talkback.vietnameseime.KeyboardKeySounds
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.labeling.LabelsTable
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * One backup item at a time: each item backs up and restores on its own, in
 * the full backup's own format, and leaves the other items alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupItemsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun setUp() {
    Robolectric.setupContentProvider(LabelProvider::class.java, LabelProvider.AUTHORITY)
  }

  @Test
  fun settingsItemRoundTripLeavesOtherItemsAlone() {
    prefs.edit().clear().putString("a_string", "kept-setting").commit()
    ClipboardHistory.init(context.filesDir)
    ClipboardHistory.clear()
    ClipboardHistory.record("a clip")

    val json = SettingsBackup.exportItem(context, BackupItem.SETTINGS)
    assertTrue(json.contains("kept-setting"))
    assertFalse(json.contains("clipboardHistory"))
    assertFalse(json.contains("a clip"))

    prefs.edit().clear().putString("a_string", "changed").commit()
    ClipboardHistory.clear()
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.SETTINGS))

    assertEquals("kept-setting", prefs.getString("a_string", null))
    assertTrue(ClipboardHistory.getEntries().isEmpty())
    ClipboardHistory.clear()
  }

  @Test
  fun labelsItemRoundTripLeavesSettingsAlone() {
    prefs.edit().clear().putString("a_string", "stays").commit()
    val uri =
      Uri.Builder().scheme("content").authority(LabelProvider.AUTHORITY).path("labels").build()
    val resolver = context.contentResolver
    resolver.delete(uri, null, null)
    val label =
      ContentValues().apply {
        put(LabelsTable.KEY_PACKAGE_NAME, "com.example.app")
        put(LabelsTable.KEY_PACKAGE_SIGNATURE, "sig")
        put(LabelsTable.KEY_VIEW_NAME, "OK")
        put(LabelsTable.KEY_TEXT, "Đồng ý")
        put(LabelsTable.KEY_LOCALE, "vi")
        put(LabelsTable.KEY_PACKAGE_VERSION, 3L)
        put(LabelsTable.KEY_TIMESTAMP, 123L)
        put(LabelsTable.KEY_SOURCE_TYPE, 0)
        put(LabelsTable.KEY_SCREENSHOT_PATH, "")
      }
    assertNotNull(resolver.insert(uri, label))

    val json = SettingsBackup.exportItem(context, BackupItem.CUSTOM_LABELS)
    assertTrue(json.contains("Đồng ý"))
    assertTrue(json.contains("customLabels"))
    assertFalse(json.contains("a_string"))

    resolver.delete(uri, null, null)
    prefs.edit().putString("a_string", "changed").commit()
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.CUSTOM_LABELS))

    assertEquals("changed", prefs.getString("a_string", null))
    val cursor = resolver.query(uri, null, null, null, null)!!
    cursor.use {
      assertEquals(1, it.count)
      assertTrue(it.moveToFirst())
      assertEquals("Đồng ý", it.getString(it.getColumnIndexOrThrow(LabelsTable.KEY_TEXT)))
    }
    resolver.delete(uri, null, null)
  }

  @Test
  fun clipboardHistoryAndFavoritesAreSeparateItems() {
    ClipboardHistory.init(context.filesDir)
    FavoriteTexts.init(context.filesDir)
    ClipboardHistory.clear()
    FavoriteTexts.clear()
    ClipboardHistory.record("only a clip")
    assertTrue(FavoriteTexts.add("only a favorite"))

    val clips = SettingsBackup.exportItem(context, BackupItem.CLIPBOARD_HISTORY)
    assertTrue(clips.contains("only a clip"))
    assertFalse(clips.contains("favoriteTexts"))
    assertFalse(clips.contains("only a favorite"))
    val favorites = SettingsBackup.exportItem(context, BackupItem.FAVORITE_TEXTS)
    assertTrue(favorites.contains("only a favorite"))
    assertFalse(favorites.contains("clipboardHistory"))
    assertFalse(favorites.contains("only a clip"))

    ClipboardHistory.clear()
    FavoriteTexts.clear()
    assertTrue(SettingsBackup.importItem(context, clips, BackupItem.CLIPBOARD_HISTORY))
    assertEquals(listOf("only a clip"), ClipboardHistory.getEntries())
    assertTrue(FavoriteTexts.getEntries().isEmpty())

    assertTrue(SettingsBackup.importItem(context, favorites, BackupItem.FAVORITE_TEXTS))
    assertEquals(listOf("only a favorite"), FavoriteTexts.getEntries())
    assertEquals(listOf("only a clip"), ClipboardHistory.getEntries())
    ClipboardHistory.clear()
    FavoriteTexts.clear()
  }

  @Test
  fun timerSoundsItemDoesNotCarryKeySounds() {
    val timerDir = SoundFileStore.directory(context, "timer_sounds")
    val keyDir = SoundFileStore.directory(context, "key_sounds")
    timerDir.mkdirs()
    keyDir.mkdirs()
    File(timerDir, "clock.mp3").writeBytes(byteArrayOf(1, 2, 3))
    File(keyDir, "space.mp3").writeBytes(byteArrayOf(4, 5))
    prefs
      .edit()
      .putString(TimerSounds.Slot.CLOCK.key, "My clock")
      .putString(KeyboardKeySounds.Slot.SPACE.key, "My space")
      .commit()

    val json = SettingsBackup.exportItem(context, BackupItem.TIMER_SOUNDS)
    assertTrue(json.contains("My clock"))
    assertFalse(json.contains("My space"))

    File(timerDir, "clock.mp3").delete()
    File(keyDir, "space.mp3").delete()
    prefs.edit()
      .remove(TimerSounds.Slot.CLOCK.key)
      .remove(KeyboardKeySounds.Slot.SPACE.key)
      .commit()
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.TIMER_SOUNDS))

    assertArrayEquals(byteArrayOf(1, 2, 3), File(timerDir, "clock.mp3").readBytes())
    assertEquals("My clock", prefs.getString(TimerSounds.Slot.CLOCK.key, null))
    assertFalse(File(keyDir, "space.mp3").exists())
    assertFalse(prefs.contains(KeyboardKeySounds.Slot.SPACE.key))
    File(timerDir, "clock.mp3").delete()
    prefs.edit().remove(TimerSounds.Slot.CLOCK.key).commit()
  }

  @Test
  fun keySoundsItemDoesNotCarryTimerSounds() {
    val timerDir = SoundFileStore.directory(context, "timer_sounds")
    val keyDir = SoundFileStore.directory(context, "key_sounds")
    timerDir.mkdirs()
    keyDir.mkdirs()
    File(timerDir, "clock.mp3").writeBytes(byteArrayOf(1, 2, 3))
    File(keyDir, "space.mp3").writeBytes(byteArrayOf(4, 5))
    prefs
      .edit()
      .putString(TimerSounds.Slot.CLOCK.key, "My clock")
      .putString(KeyboardKeySounds.Slot.SPACE.key, "My space")
      .commit()

    val json = SettingsBackup.exportItem(context, BackupItem.KEY_SOUNDS)
    assertTrue(json.contains("My space"))
    assertFalse(json.contains("My clock"))

    File(timerDir, "clock.mp3").delete()
    File(keyDir, "space.mp3").delete()
    prefs.edit()
      .remove(TimerSounds.Slot.CLOCK.key)
      .remove(KeyboardKeySounds.Slot.SPACE.key)
      .commit()
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.KEY_SOUNDS))

    assertArrayEquals(byteArrayOf(4, 5), File(keyDir, "space.mp3").readBytes())
    assertEquals("My space", prefs.getString(KeyboardKeySounds.Slot.SPACE.key, null))
    assertFalse(File(timerDir, "clock.mp3").exists())
    assertFalse(prefs.contains(TimerSounds.Slot.CLOCK.key))
    File(keyDir, "space.mp3").delete()
    prefs.edit().remove(KeyboardKeySounds.Slot.SPACE.key).commit()
  }

  @Test
  fun soundThemesItemRoundTrip() {
    val draft = SoundThemes.createDraft(context, SoundThemeManifest(name = "Item theme"))
    val installed = SoundThemes.saveDraft(context, draft.id)
    assertEquals("item-theme", installed.id)

    val json = SettingsBackup.exportItem(context, BackupItem.SOUND_THEMES)
    assertTrue(json.contains("item-theme"))

    SoundThemes.delete(context, prefs, installed.id)
    assertTrue(SoundThemes.installed(context).none { it.id == "item-theme" })
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.SOUND_THEMES))

    val restored = SoundThemes.installed(context).firstOrNull { it.id == "item-theme" }
    assertNotNull(restored)
    assertEquals("Item theme", restored!!.manifest.name)
    SoundThemes.delete(context, prefs, "item-theme")
  }

  @Test
  fun scriptsItemRoundTrip() {
    val scriptsDir = File(File(context.filesDir, MigrationFiles.SCRIPTS_DIR), "item-script")
    scriptsDir.mkdirs()
    File(scriptsDir, "main.lua").writeText(
      "local manifest = { id = \"item-script\", name = \"Item script\", version = \"1\", " +
        "author = \"Test\", minApiVersion = 1, testedApiVersion = 1 }\n"
    )
    File(scriptsDir, "helper.lua").writeText("return {}")

    val json = SettingsBackup.exportItem(context, BackupItem.SCRIPTS)
    assertTrue(json.contains("item-script"))

    scriptsDir.deleteRecursively()
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.SCRIPTS))
    assertTrue(File(scriptsDir, "main.lua").isFile)
    assertTrue(File(scriptsDir, "main.lua").readText().contains("Item script"))
    assertEquals("return {}", File(scriptsDir, "helper.lua").readText())
    scriptsDir.deleteRecursively()
  }

  @Test
  fun editorDocumentsItemRoundTrip() {
    EditorDocuments.init(context.filesDir)
    EditorDocuments.clear()
    val first = EditorDocuments.create("Item note", "item text")!!

    val json = SettingsBackup.exportItem(context, BackupItem.EDITOR_DOCUMENTS)
    assertTrue(json.contains("Item note"))

    EditorDocuments.clear()
    assertTrue(EditorDocuments.documents().isEmpty())
    assertTrue(SettingsBackup.importItem(context, json, BackupItem.EDITOR_DOCUMENTS))
    assertEquals(listOf("Item note"), EditorDocuments.documents().map { it.name })
    assertEquals("item text", EditorDocuments.get(first.id)?.content)
    EditorDocuments.clear()
  }

  @Test
  fun importingAFullBackupThroughImportItemRestoresOnlyThatItem() {
    prefs.edit().clear().putString("a_string", "before").commit()
    ClipboardHistory.init(context.filesDir)
    ClipboardHistory.clear()
    ClipboardHistory.record("clip one")

    val full = SettingsBackup.export(context)

    prefs.edit().putString("a_string", "after").commit()
    ClipboardHistory.clear()
    assertTrue(SettingsBackup.importItem(context, full, BackupItem.CLIPBOARD_HISTORY))

    assertEquals("after", prefs.getString("a_string", null))
    assertEquals(listOf("clip one"), ClipboardHistory.getEntries())
    ClipboardHistory.clear()
  }

  @Test
  fun importItemRefusesAFileWithoutThatItem() {
    prefs.edit().clear().putString("a_string", "mine").commit()
    val settingsOnly = SettingsBackup.exportItem(context, BackupItem.SETTINGS)
    assertFalse(SettingsBackup.importItem(context, settingsOnly, BackupItem.CUSTOM_LABELS))
    assertEquals("mine", prefs.getString("a_string", null))

    val labelsOnly =
      """{"format":"meridian-settings","version":1,"files":{},"customLabels":[]}"""
    assertFalse(SettingsBackup.importItem(context, labelsOnly, BackupItem.SETTINGS))
    assertEquals("mine", prefs.getString("a_string", null))
    assertFalse(SettingsBackup.importItem(context, "not json", BackupItem.CUSTOM_LABELS))
  }

  @Test
  fun perItemFileRestoresThroughTheFullImportToo() {
    val uri =
      Uri.Builder().scheme("content").authority(LabelProvider.AUTHORITY).path("labels").build()
    val resolver = context.contentResolver
    resolver.delete(uri, null, null)
    val label =
      ContentValues().apply {
        put(LabelsTable.KEY_PACKAGE_NAME, "com.example.app")
        put(LabelsTable.KEY_PACKAGE_SIGNATURE, "sig")
        put(LabelsTable.KEY_VIEW_NAME, "OK")
        put(LabelsTable.KEY_TEXT, "Đồng ý")
        put(LabelsTable.KEY_LOCALE, "vi")
        put(LabelsTable.KEY_PACKAGE_VERSION, 3L)
        put(LabelsTable.KEY_TIMESTAMP, 123L)
        put(LabelsTable.KEY_SOURCE_TYPE, 0)
        put(LabelsTable.KEY_SCREENSHOT_PATH, "")
      }
    assertNotNull(resolver.insert(uri, label))

    val json = SettingsBackup.exportItem(context, BackupItem.CUSTOM_LABELS)

    resolver.delete(uri, null, null)
    assertTrue(SettingsBackup.import(context, json))
    val cursor = resolver.query(uri, null, null, null, null)!!
    cursor.use {
      assertEquals(1, it.count)
      assertTrue(it.moveToFirst())
      assertEquals("Đồng ý", it.getString(it.getColumnIndexOrThrow(LabelsTable.KEY_TEXT)))
    }
    resolver.delete(uri, null, null)
  }
}
