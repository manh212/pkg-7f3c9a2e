package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.os.UserManagerCompat;
import io.meridian.licensing.RecoveryProtocol;
import io.meridian.licensing.LicenseRefreshSchedule;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class LicenseRecovery {
  enum Result {
    RESTORED, ALREADY_LICENSED, NOT_FOUND, OFFLINE, INVALID, NOT_CONFIGURED,
    VERIFICATION_REQUIRED, VERIFICATION_FAILED, UNVERIFIED_INSTALLATION
  }
  interface Callback { void completed(Result result); }
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static boolean running;
  private static long lastAttempt = -LicenseRefreshSchedule.RETRY_MS;
  private static Result lastResult = Result.VERIFICATION_REQUIRED;
  private static final List<Callback> WAITING = new ArrayList<>();

  private LicenseRecovery() {}

  private static int boot(Context context) {
    return android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
  }

  static void checked(Context context) {
    context.getSharedPreferences("meridian_license_checks", Context.MODE_PRIVATE).edit()
        .putLong("wall", System.currentTimeMillis()).putLong("elapsed", SystemClock.elapsedRealtime())
        .putInt("boot", boot(context)).apply();
  }

  static synchronized void start(Context context, boolean force, Callback callback) {
    Context app = context.getApplicationContext();
    if (!UserManagerCompat.isUserUnlocked(app)) {
      if (callback != null) callback.completed(Result.VERIFICATION_REQUIRED);
      return;
    }
    if (!ApkSignatureVerifier.isOfficial(app)) {
      if (callback != null) callback.completed(Result.UNVERIFIED_INSTALLATION);
      return;
    }
    if (!DeviceLicenseManager.recoveryConfigured()) {
      if (callback != null) callback.completed(Result.NOT_CONFIGURED);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (running) {
      if (callback != null) WAITING.add(callback);
      return;
    }
    android.content.SharedPreferences checks = app.getSharedPreferences("meridian_license_checks", Context.MODE_PRIVATE);
    boolean due = LicenseRefreshSchedule.due(System.currentTimeMillis(), now, boot(app),
        checks.getLong("wall", 0), checks.getLong("elapsed", 0), checks.getInt("boot", -2));
    boolean verificationRequired = DeviceLicenseManager.needsOnlineVerification(app);
    if (!force && ((!due && !verificationRequired)
        || now - lastAttempt < LicenseRefreshSchedule.RETRY_MS)) {
      Result cached = verificationRequired
          ? (lastResult == Result.RESTORED ? Result.VERIFICATION_REQUIRED : lastResult)
          : Result.ALREADY_LICENSED;
      if (callback != null) callback.completed(cached);
      return;
    }
    if (callback != null) WAITING.add(callback);
    running = true;
    lastAttempt = now;
    WORKER.execute(() -> {
      Result result = recover(app);
      if (result == Result.RESTORED) checked(app);
      MAIN.post(() -> {
        List<Callback> callbacks;
        synchronized (LicenseRecovery.class) {
          running = false;
          lastResult = result;
          callbacks = new ArrayList<>(WAITING);
          WAITING.clear();
        }
        ActivationReminder.onPolicyChanged();
        for (Callback waiting : callbacks) waiting.completed(result);
      });
    });
  }

  static boolean isOnlinePolicyRecord(JSONObject record) {
    return record != null && record.optInt("format") == 3
        && record.optString("value", "").startsWith(
            io.meridian.licensing.EndpointCodec.decode("Gi7L74bXLQ", 73));
  }

  private static Result recover(Context context) {
    return recoverFrom(context,
        io.meridian.licensing.DistributionEndpoints.dataCatalogRoot());
  }
  
  static HttpURLConnection openConnection(URL url) throws java.io.IOException {
    return (HttpURLConnection) url.openConnection();
  }

  private static Result recoverFrom(Context context, String catalogRoot) {
    HttpURLConnection connection = null;
    try {
      String request = DeviceLicenseManager.recoveryRequest(context);
      URL url = new URL(catalogRoot
          + RecoveryProtocol.recordPath(request) + "?v=" + System.currentTimeMillis());
      connection = openConnection(url);
      connection.setConnectTimeout(8_000);
      connection.setReadTimeout(8_000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Cache-Control", "no-cache");
      int status = connection.getResponseCode();
      if (status == 404) return Result.NOT_FOUND;
      if (status != 200) return Result.OFFLINE;
      if (connection.getContentLengthLong() > 8192) return Result.INVALID;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 8192) return Result.INVALID;
          bytes.write(buffer, 0, count);
        }
      }
      JSONObject record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      if (!isOnlinePolicyRecord(record)) return Result.INVALID;
      String license = record.getString("value");
      long serverMillis = connection.getDate();
      if (serverMillis <= 0 || !DeviceLicenseManager.acceptRemote(context, license, serverMillis)) {
        return Result.INVALID;
      }
      return Result.RESTORED;
    } catch (java.io.IOException e) {
      return Result.OFFLINE;
    } catch (IllegalStateException e) {
      return Result.VERIFICATION_FAILED;
    } catch (Exception e) {
      return Result.INVALID;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }
}
