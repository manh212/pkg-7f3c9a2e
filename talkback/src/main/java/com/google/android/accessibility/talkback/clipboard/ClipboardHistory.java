/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AtomicFile;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

public final class ClipboardHistory {

  private static final String TAG = "ClipboardHistory";
  private static final String STORE_FILE_NAME = "clipboard-history.json";
  private static final List<String> entries = new ArrayList<>();
  private static final List<Runnable> listeners = new ArrayList<>();
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

  private static AtomicFile store;
  private static boolean readable;
  private static boolean pending;
  private static boolean recovering;

  private ClipboardHistory() {}

  public static void init(File directory) {
    synchronized (entries) {
      if (directory == null) {
        store = null;
        readable = false;
        pending = false;
        recovering = false;
        return;
      }
      File file = new File(directory, STORE_FILE_NAME);
      boolean sameStore = store != null && store.getBaseFile().equals(file);
      boolean keepPending = pending && (store == null || sameStore);
      List<String> unsaved = keepPending ? new ArrayList<>(entries) : List.of();
      if (!keepPending && (store == null || !store.getBaseFile().equals(file))) {
        entries.clear();
      }
      store = new AtomicFile(file);
      readable = false;
      recovering = false;
      try {
        List<String> restored = parse(new String(store.readFully(), StandardCharsets.UTF_8));
        readable = true;
        if (keepPending) {
          for (String value : restored) {
            if (!unsaved.contains(value)) {
              unsaved.add(value);
            }
          }
          pending = !saveLocked(unsaved);
        } else {
          entries.clear();
          entries.addAll(restored);
          pending = false;
        }
      } catch (FileNotFoundException e) {
        readable = !file.exists();
        if (readable) {
          if (keepPending || (sameStore && !entries.isEmpty())) {
            pending = !saveLocked(new ArrayList<>(entries));
          } else {
            entries.clear();
            pending = false;
          }
        } else {
          logFailure("read", e);
        }
      } catch (JSONException e) {
        logFailure("parse", e);
        try {
          File damaged = new File(directory, STORE_FILE_NAME + ".corrupt-" + System.currentTimeMillis());
          Files.copy(file.toPath(), damaged.toPath());
          readable = true;
          recovering = true;
          pending = !saveLocked(new ArrayList<>(entries));
        } catch (IOException | RuntimeException failure) {
          logFailure("preserve damaged history", failure);
        }
      } catch (IOException | RuntimeException e) {
        logFailure("read", e);
      }
    }
    notifyListeners();
  }

  public static void restoreLegacyHistory(Context context) {
    if (context == null) {
      return;
    }
    init(context.getFilesDir());
    SharedPreferences preferences =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("clipboard_history", Context.MODE_PRIVATE);
    synchronized (entries) {
      if (preferences.getBoolean("sandfly_history_restored", false) && !recovering) {
        return;
      }
      String content = preferences.getString("entries", null);
      if (TextUtils.isEmpty(content)) {
        if (readable) {
          preferences.edit().putBoolean("sandfly_history_restored", true).apply();
          recovering = false;
        }
        return;
      }
      List<String> merged;
      try {
        merged = parse(content);
      } catch (JSONException | RuntimeException e) {
        logFailure("import", e);
        return;
      }
      for (String value : entries) {
        if (!merged.contains(value)) {
          merged.add(value);
        }
      }
      if (!saveLocked(merged)) {
        return;
      }
      preferences.edit().putBoolean("sandfly_history_restored", true).apply();
      recovering = false;
    }
    notifyListeners();
  }

  public static void record(CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return;
    }
    synchronized (entries) {
      List<String> updated = new ArrayList<>(entries);
      String value = text.toString();
      updated.remove(value);
      updated.add(0, value);
      if (!saveLocked(updated)) {
        return;
      }
    }
    notifyListeners();
  }

  public static void remove(String text) {
    if (text == null) {
      return;
    }
    synchronized (entries) {
      List<String> updated = new ArrayList<>(entries);
      if (!updated.remove(text) || !saveLocked(updated)) {
        return;
      }
    }
    notifyListeners();
  }

  public static boolean replace(String original, CharSequence replacement) {
    if (original == null || replacement == null || replacement.toString().trim().isEmpty()) {
      return false;
    }
    String value = replacement.toString();
    synchronized (entries) {
      List<String> updated = new ArrayList<>(entries);
      int position = updated.indexOf(original);
      if (position < 0 || (!original.equals(value) && updated.contains(value))) {
        return false;
      }
      if (original.equals(value)) {
        return true;
      }
      updated.set(position, value);
      if (!saveLocked(updated)) {
        return false;
      }
    }
    notifyListeners();
    return true;
  }

  public static int removeAll(Collection<String> selected) {
    if (selected == null || selected.isEmpty()) {
      return 0;
    }
    int removed;
    synchronized (entries) {
      List<String> updated = new ArrayList<>(entries);
      updated.removeAll(selected);
      removed = entries.size() - updated.size();
      if (removed == 0 || !saveLocked(updated)) {
        return 0;
      }
    }
    notifyListeners();
    return removed;
  }

  public static List<String> getEntries() {
    synchronized (entries) {
      return new ArrayList<>(entries);
    }
  }

  public static void clear() {
    boolean changed;
    synchronized (entries) {
      changed = !entries.isEmpty();
      if (!saveLocked(new ArrayList<>())) {
        return;
      }
    }
    if (changed) {
      notifyListeners();
    }
  }

  public static String backupJson(Context context) {
    synchronized (entries) {
      if (store == null) {
        init(context.getFilesDir());
      }
      return store != null && readable ? new JSONArray(entries).toString() : null;
    }
  }

  public static boolean restoreBackup(Context context, String json) {
    List<String> restored;
    try {
      restored = parse(json);
    } catch (JSONException | RuntimeException e) {
      return false;
    }
    boolean changed;
    synchronized (entries) {
      if (store == null) {
        init(context.getFilesDir());
        if (store == null) {
          return false;
        }
      }
      changed = !entries.equals(restored);
      readable = true;
      if (!saveLocked(restored)) {
        return false;
      }
    }
    if (changed) {
      notifyListeners();
    }
    return true;
  }

  public static void addListener(Runnable listener) {
    synchronized (listeners) {
      if (!listeners.contains(listener)) {
        listeners.add(listener);
      }
    }
  }

  public static void removeListener(Runnable listener) {
    synchronized (listeners) {
      listeners.remove(listener);
    }
  }

  private static List<String> parse(String json) throws JSONException {
    JSONArray stored = new JSONArray(json);
    List<String> restored = new ArrayList<>();
    for (int i = 0; i < stored.length(); i++) {
      String value = stored.optString(i, null);
      if (!TextUtils.isEmpty(value) && !restored.contains(value)) {
        restored.add(value);
      }
    }
    return restored;
  }

  private static void notifyListeners() {
    List<Runnable> snapshot;
    synchronized (listeners) {
      snapshot = new ArrayList<>(listeners);
    }
    if (snapshot.isEmpty()) {
      return;
    }
    mainHandler.post(
        () -> {
          for (Runnable listener : snapshot) {
            listener.run();
          }
        });
  }

  private static boolean saveLocked(List<String> updated) {
    if (store == null) {
      entries.clear();
      entries.addAll(updated);
      pending = true;
      return true;
    }
    if (!readable) {
      LogUtils.w(TAG, "History is unavailable; saved entries were kept");
      return false;
    }
    FileOutputStream output = null;
    try {
      byte[] data = new JSONArray(updated).toString().getBytes(StandardCharsets.UTF_8);
      output = store.startWrite();
      output.write(data);
      store.finishWrite(output);
      output = null;
      if (!Arrays.equals(data, store.readFully())) {
        LogUtils.w(TAG, "History verification failed");
        return false;
      }
      entries.clear();
      entries.addAll(updated);
      pending = false;
      return true;
    } catch (IOException | RuntimeException e) {
      if (output != null) {
        store.failWrite(output);
      }
      logFailure("write", e);
      return false;
    }
  }

  private static void logFailure(String operation, Exception exception) {
    LogUtils.w(TAG, "Could not %s history (%s)", operation, exception.getClass().getSimpleName());
  }
}
