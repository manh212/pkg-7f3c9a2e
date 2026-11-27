/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for {@link ClipboardHistory}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClipboardHistoryTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() {
    ClipboardHistory.init(null);
    ClipboardHistory.clear();
  }

  @Test
  public void record_newestFirst() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");

    assertEquals(Arrays.asList("two", "one"), ClipboardHistory.getEntries());
  }

  @Test
  public void record_duplicateMovesToFrontWithoutDuplicating() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("one");

    assertEquals(Arrays.asList("one", "two"), ClipboardHistory.getEntries());
  }

  @Test
  public void record_ignoresEmptyText() {
    ClipboardHistory.record("");
    ClipboardHistory.record(null);

    assertTrue(ClipboardHistory.getEntries().isEmpty());
  }

  @Test
  public void record_hasNoCap() {
    for (int i = 0; i < 60; i++) {
      ClipboardHistory.record("text " + i);
    }

    assertEquals(60, ClipboardHistory.getEntries().size());
    assertEquals("text 59", ClipboardHistory.getEntries().get(0));
    assertEquals("text 0", ClipboardHistory.getEntries().get(59));
  }

  @Test
  public void remove_deletesOnlyThatEntry() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("three");

    ClipboardHistory.remove("two");

    assertEquals(Arrays.asList("three", "one"), ClipboardHistory.getEntries());
  }

  @Test
  public void removeAllDeletesSelectedEntriesAndPersists() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    for (int i = 1; i <= 5; i++) {
      ClipboardHistory.record("entry " + i);
    }
    assertEquals(3, ClipboardHistory.removeAll(Arrays.asList("entry 1", "entry 3", "entry 5")));
    assertEquals(Arrays.asList("entry 4", "entry 2"), ClipboardHistory.getEntries());
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("entry 4", "entry 2"), ClipboardHistory.getEntries());
  }

  @Test
  public void replace_editsEntryInPlaceAndSurvivesReload() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("three");

    assertTrue(ClipboardHistory.replace("two", "two edited"));
    assertEquals(Arrays.asList("three", "two edited", "one"), ClipboardHistory.getEntries());
    assertTrue(!ClipboardHistory.replace("one", "three"));
    assertTrue(!ClipboardHistory.replace("one", "   "));
    assertEquals(Arrays.asList("three", "two edited", "one"), ClipboardHistory.getEntries());

    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("three", "two edited", "one"), ClipboardHistory.getEntries());
  }

  @Test
  public void init_reloadsStoredEntriesAndRemovals() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.remove("one");

    ClipboardHistory.init(dir);

    assertEquals(Arrays.asList("two"), ClipboardHistory.getEntries());
  }

  @Test
  public void allEntriesAndFullTextSurviveColdReload() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    String fullText = "  Xin chào\n😀  ".repeat(1_000);
    ClipboardHistory.record(fullText);
    for (int i = 0; i < 75; i++) {
      ClipboardHistory.record("entry " + i);
    }
    java.util.List<String> expected = ClipboardHistory.getEntries();

    ClipboardHistory.init(folder.newFolder());
    assertTrue(ClipboardHistory.getEntries().isEmpty());
    ClipboardHistory.init(dir);

    assertEquals(expected, ClipboardHistory.getEntries());
    assertEquals(76, ClipboardHistory.getEntries().size());
    assertEquals(fullText, ClipboardHistory.getEntries().get(75));
  }

  @Test
  public void uninitializedCopiesAreSavedAlongsideExistingHistory() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("saved");
    ClipboardHistory.init(folder.newFolder());
    ClipboardHistory.init(null);
    ClipboardHistory.record("copied during startup");

    ClipboardHistory.init(dir);
    assertEquals(
        Arrays.asList("copied during startup", "saved"), ClipboardHistory.getEntries());
    ClipboardHistory.init(folder.newFolder());
    ClipboardHistory.init(dir);
    assertEquals(
        Arrays.asList("copied during startup", "saved"), ClipboardHistory.getEntries());
  }

  @Test
  public void interruptedAtomicWriteKeepsLastCompleteHistory() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("first");
    ClipboardHistory.record("second");
    AtomicFile file = new AtomicFile(new File(dir, "clipboard-history.json"));
    try (FileOutputStream output = file.startWrite()) {
      output.write("[\"unfinished".getBytes(StandardCharsets.UTF_8));
    }

    ClipboardHistory.init(folder.newFolder());
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("second", "first"), ClipboardHistory.getEntries());
    ClipboardHistory.record("third");
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("third", "second", "first"), ClipboardHistory.getEntries());
  }

  @Test
  public void backupOnlyHistoryIsRecovered() throws Exception {
    File dir = folder.newFolder();
    Files.write(
        new File(dir, "clipboard-history.json.bak").toPath(),
        "[\"recovered\"]".getBytes(StandardCharsets.UTF_8));

    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("recovered"), ClipboardHistory.getEntries());
    ClipboardHistory.record("new");
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("new", "recovered"), ClipboardHistory.getEntries());
  }

  @Test
  public void damagedHistoryIsPreservedAndNewCopiesRemainPersistent() throws Exception {
    File dir = folder.newFolder();
    File file = new File(dir, "clipboard-history.json");
    String damaged = "[\"incomplete";
    Files.write(file.toPath(), damaged.getBytes(StandardCharsets.UTF_8));

    ClipboardHistory.init(dir);
    ClipboardHistory.record("new persistent entry");
    ClipboardHistory.init(folder.newFolder());
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("new persistent entry"), ClipboardHistory.getEntries());
    File[] preserved = dir.listFiles((parent, name) -> name.startsWith("clipboard-history.json.corrupt-"));
    assertEquals(1, preserved.length);
    assertEquals(damaged, new String(Files.readAllBytes(preserved[0].toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void failedWritesDoNotPublishUnsavedChanges() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("first");
    ClipboardHistory.record("second");
    String saved = new String(
        Files.readAllBytes(new File(dir, "clipboard-history.json").toPath()), StandardCharsets.UTF_8);
    File moved = new File(folder.getRoot(), "temporarily-unavailable");
    Files.move(dir.toPath(), moved.toPath());
    Files.write(dir.toPath(), new byte[] {1});

    ClipboardHistory.record("unsaved");
    assertEquals(Arrays.asList("second", "first"), ClipboardHistory.getEntries());
    ClipboardHistory.remove("first");
    assertEquals(Arrays.asList("second", "first"), ClipboardHistory.getEntries());
    assertFalse(ClipboardHistory.replace("first", "edited"));
    assertEquals(0, ClipboardHistory.removeAll(Arrays.asList("first", "second")));
    ClipboardHistory.clear();
    assertEquals(Arrays.asList("second", "first"), ClipboardHistory.getEntries());
    assertEquals(
        saved,
        new String(
            Files.readAllBytes(new File(moved, "clipboard-history.json").toPath()),
            StandardCharsets.UTF_8));

    Files.delete(dir.toPath());
    Files.move(moved.toPath(), dir.toPath());
    ClipboardHistory.record("retry");
    ClipboardHistory.init(dir);
    assertEquals(Arrays.asList("retry", "second", "first"), ClipboardHistory.getEntries());
  }

  @Test
  public void damagedHistoryCanRecoverPreviouslyImportedPreferences() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    SharedPreferences preferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("clipboard_history", Context.MODE_PRIVATE);
    preferences.edit()
        .putString("entries", "[\"recovered from preferences\"]")
        .putBoolean("sandfly_history_restored", true)
        .commit();
    File file = new File(context.getFilesDir(), "clipboard-history.json");
    Files.write(file.toPath(), "[".getBytes(StandardCharsets.UTF_8));

    ClipboardHistory.restoreLegacyHistory(context);
    ClipboardHistory.record("new");
    ClipboardHistory.init(folder.newFolder());
    ClipboardHistory.init(context.getFilesDir());
    assertEquals(Arrays.asList("new", "recovered from preferences"), ClipboardHistory.getEntries());
    preferences.edit().clear().commit();
  }
}
