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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.accessibility.utils.BatteryUsageTracker;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.report.DiagnosticsExport;
import com.google.android.accessibility.talkback.report.LatencyMeasurementSession;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DiagnosticsPreferenceActions {
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private DiagnosticsPreferenceActions() {}

  static void bind(PreferenceFragmentCompat fragment) {
    Preference export =
        fragment.findPreference(
            fragment.getString(R.string.pref_export_diagnostics_to_downloads_key));
    if (export != null) {
      export.setOnPreferenceClickListener(
          preference -> {
            exportToDownloads(fragment.requireContext().getApplicationContext());
            return true;
          });
    }
    Preference copy =
        fragment.findPreference(fragment.getString(R.string.pref_copy_diagnostics_key));
    if (copy != null) {
      copy.setOnPreferenceClickListener(
          preference -> {
            copyToClipboard(fragment.requireContext().getApplicationContext());
            return true;
          });
    }
    Preference battery =
        fragment.findPreference(fragment.getString(R.string.pref_battery_usage_report_key));
    if (battery != null) {
      Context appCtx = fragment.requireContext().getApplicationContext();
      battery.setSummary(BatteryUsageTracker.buildShortSubtitle(appCtx));
      battery.setOnPreferenceClickListener(
          preference -> {
            showBatteryUsageDialog(fragment, battery);
            return true;
          });
    }
    Preference delete =
        fragment.findPreference(fragment.getString(R.string.pref_delete_debug_log_key));
    if (delete != null) {
      delete.setOnPreferenceClickListener(
          preference -> {
            deleteDebugLog(fragment.requireContext().getApplicationContext());
            return true;
          });
    }
  }

  private static void exportToDownloads(Context context) {
    EXECUTOR.execute(
        () -> {
          String text = DiagnosticsExport.INSTANCE.buildForExport(context);
          boolean saved = DiagnosticsExport.INSTANCE.saveToDownloads(context, text);
          MAIN_HANDLER.post(
              () ->
                  Toast.makeText(
                          context,
                          saved
                              ? R.string.message_diagnostics_saved_to_downloads
                              : R.string.message_diagnostics_save_failed,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }

  // Giới hạn an toàn dưới trần 1 MB của Android Binder để tránh TransactionTooLargeException
  // khi sao chép nhật ký dung lượng lớn vào bảng nhớ tạm.
  private static final int MAX_CLIPBOARD_CHARS = 200_000;
  private static final int CLIPBOARD_HEADER_CHARS = 40_000;

  private static void copyToClipboard(Context context) {
    EXECUTOR.execute(
        () -> {
          String rawText = DiagnosticsExport.INSTANCE.build(context);
          String text = truncateForClipboard(rawText);
          MAIN_HANDLER.post(
              () -> {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                  try {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.title_pref_export_diagnostics), text));
                  } catch (RuntimeException ignored) {
                    String shorter = truncateForClipboard(rawText, 80_000, 20_000);
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.title_pref_export_diagnostics), shorter));
                  }
                }
                Toast.makeText(context, R.string.message_diagnostics_copied, Toast.LENGTH_LONG)
                    .show();
              });
        });
  }

  static String truncateForClipboard(String text) {
    return truncateForClipboard(text, MAX_CLIPBOARD_CHARS, CLIPBOARD_HEADER_CHARS);
  }

  static String truncateForClipboard(String text, int maxChars, int headerChars) {
    if (text == null || text.length() <= maxChars) {
      return text;
    }
    int tailChars = Math.max(0, maxChars - headerChars);
    int omitted = text.length() - headerChars - tailChars;
    return text.substring(0, headerChars)
        + "\n\n... [Đã cắt bớt "
        + omitted
        + " ký tự ở giữa để tránh lỗi TransactionTooLargeException; hãy dùng 'Xuất nhật ký' để lưu trọn tệp] ...\n\n"
        + text.substring(text.length() - tailChars);
  }

  private static final long RECENT_LOG_CLEAR_WINDOW_MS = 30_000L;
  private static volatile long lastLogClearedUptimeMs = 0L;

  /** Returns true if debug logs were cleared within the last 30 seconds. */
  public static boolean wasLogClearedRecently() {
    long cleared = lastLogClearedUptimeMs;
    return cleared > 0L && (SystemClock.uptimeMillis() - cleared) < RECENT_LOG_CLEAR_WINDOW_MS;
  }

  /** Exposed for unit testing. */
  public static void setLastLogClearedUptimeMsForTesting(long uptimeMs) {
    lastLogClearedUptimeMs = uptimeMs;
  }

  private static void deleteDebugLog(Context context) {
    EXECUTOR.execute(
        () -> {
          boolean deleted = LogUtils.clearLogFiles();
          if (deleted) {
            lastLogClearedUptimeMs = SystemClock.uptimeMillis();
          }
          if (SharedPreferencesUtils.getSharedPreferences(context)
              .getBoolean(LatencyExperiments.Feature.RESET_SESSION.key, false)) {
            MAIN_HANDLER.post(LatencyMeasurementSession::reset);
          }
          MAIN_HANDLER.post(
              () ->
                  Toast.makeText(
                          context,
                          deleted
                              ? R.string.message_debug_log_deleted
                              : R.string.message_debug_log_nothing_to_delete,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }

  private static void showBatteryUsageDialog(
      PreferenceFragmentCompat fragment, Preference batteryPref) {
    Context uiContext = fragment.getContext();
    if (uiContext == null) {
      return;
    }
    Context appContext = uiContext.getApplicationContext();
    EXECUTOR.execute(
        () -> {
          String report = DiagnosticsExport.INSTANCE.buildBatteryReport(appContext);
          String subtitle = BatteryUsageTracker.buildShortSubtitle(appContext);
          MAIN_HANDLER.post(
              () -> {
                if (!fragment.isAdded()) {
                  return;
                }
                batteryPref.setSummary(subtitle);
                float density = uiContext.getResources().getDisplayMetrics().density;
                int pad = (int) (20 * density);
                TextView textView = new TextView(uiContext);
                textView.setText(report);
                textView.setTextIsSelectable(true);
                textView.setFocusable(true);
                textView.setPadding(pad, pad, pad, pad);
                ScrollView scroll = new ScrollView(uiContext);
                scroll.addView(textView);
                new AlertDialog.Builder(uiContext)
                    .setTitle(R.string.title_pref_battery_usage_report)
                    .setView(scroll)
                    .setPositiveButton(R.string.location_close, null)
                    .setNeutralButton(
                        R.string.button_battery_export_report,
                        (dialog, which) -> exportBatteryReport(appContext, report))
                    .setNegativeButton(
                        R.string.button_battery_reset_session,
                        (dialog, which) -> {
                          BatteryUsageTracker.resetSession(appContext);
                          batteryPref.setSummary(BatteryUsageTracker.buildShortSubtitle(appContext));
                          Toast.makeText(
                                  appContext,
                                  R.string.message_battery_session_reset,
                                  Toast.LENGTH_SHORT)
                              .show();
                        })
                    .show();
              });
        });
  }

  private static void exportBatteryReport(Context context, String report) {
    EXECUTOR.execute(
        () -> {
          boolean saved = DiagnosticsExport.INSTANCE.saveBatteryReportToDownloads(context, report);
          MAIN_HANDLER.post(
              () ->
                  Toast.makeText(
                          context,
                          saved
                              ? R.string.message_battery_report_saved
                              : R.string.message_diagnostics_save_failed,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }
}
