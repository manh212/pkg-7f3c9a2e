/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.texteditor

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorDocumentsTest {
  @get:Rule val folder = TemporaryFolder()
  private lateinit var directory: File
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    directory = folder.newFolder()
    EditorDocuments.init(directory)
  }

  @Test
  fun createdDocumentsSurviveReloadNewestFirst() {
    EditorDocuments.create("first", "one")
    Thread.sleep(10)
    EditorDocuments.create("second", "two")

    EditorDocuments.init(directory)

    assertEquals(listOf("second", "first"), EditorDocuments.documents().map { it.name })
    assertEquals("second", EditorDocuments.mostRecent()?.name)
    assertEquals("two", EditorDocuments.mostRecent()?.content)
  }

  @Test
  fun aNewDocumentIsNamedByItsCreationTime() {
    val created = EditorDocuments.createDefault("text")

    assertEquals("text", created?.content)
    assertTrue(created!!.name.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}")))
    assertEquals(created.name, EditorDocuments.defaultName(created.createdAt))
  }

  @Test
  fun renamePersistsAndRefusesABlankName() {
    val created = EditorDocuments.create("old name", "")!!

    assertFalse(EditorDocuments.rename(created.id, "  "))
    assertTrue(EditorDocuments.rename(created.id, "new name"))
    EditorDocuments.init(directory)

    assertEquals("new name", EditorDocuments.get(created.id)?.name)
    assertFalse(EditorDocuments.rename("missing", "name"))
  }

  @Test
  fun updatingContentAutosavesAndBecomesMostRecent() {
    val first = EditorDocuments.create("first", "draft")!!
    Thread.sleep(10)
    val second = EditorDocuments.create("second", "")!!
    assertEquals(second.id, EditorDocuments.mostRecent()?.id)

    Thread.sleep(10)
    assertTrue(EditorDocuments.updateContent(first.id, "draft grown"))
    EditorDocuments.init(directory)

    assertEquals(first.id, EditorDocuments.mostRecent()?.id)
    assertEquals("draft grown", EditorDocuments.get(first.id)?.content)
    assertTrue(EditorDocuments.updateContent(first.id, "draft grown"))
    assertFalse(EditorDocuments.updateContent("missing", "text"))
  }

  @Test
  fun deleteRemovesTheDocumentForGood() {
    val kept = EditorDocuments.create("kept", "")!!
    val deleted = EditorDocuments.create("deleted", "")!!

    assertTrue(EditorDocuments.delete(deleted.id))
    EditorDocuments.init(directory)

    assertNull(EditorDocuments.get(deleted.id))
    assertEquals(listOf(kept.id), EditorDocuments.documents().map { it.id })
    assertFalse(EditorDocuments.delete(deleted.id))
  }

  @Test
  fun backupCarriesTheDocumentsAndRestoreBringsThemBack() {
    EditorDocuments.create("first note", "alpha")
    Thread.sleep(10)
    EditorDocuments.create("second note", "beta")
    val backup = EditorDocuments.backupJson(context)!!

    assertTrue(EditorDocuments.clear())
    assertTrue(EditorDocuments.documents().isEmpty())
    assertTrue(EditorDocuments.restoreBackup(context, backup))

    assertEquals(listOf("second note", "first note"), EditorDocuments.documents().map { it.name })
    assertEquals("alpha", EditorDocuments.documents().last().content)
    EditorDocuments.init(directory)
    assertEquals(2, EditorDocuments.documents().size)
  }

  @Test
  fun aDamagedBackupChangesNothing() {
    EditorDocuments.create("kept", "text")

    assertFalse(EditorDocuments.restoreBackup(context, "not json"))

    assertEquals(listOf("kept"), EditorDocuments.documents().map { it.name })
  }
}
