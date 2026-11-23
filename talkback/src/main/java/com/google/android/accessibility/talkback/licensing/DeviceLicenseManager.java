package com.google.android.accessibility.talkback.licensing;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.os.UserManagerCompat;
import com.google.android.accessibility.luamemory.LuaMemory;
import com.google.android.accessibility.talkback.BuildConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import io.meridian.licensing.EndpointCodec;
import io.meridian.licensing.LicenseClock;
import io.meridian.licensing.LicenseLease;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.RecoveryProtocol;

public final class DeviceLicenseManager {
  enum VerificationState { VALID, REQUIRED, INVALID, EXPIRED, CLOCK_CHANGED, UNAVAILABLE }

  private static final String POLICY_PREFS = EndpointCodec.decode("WikJ4sOoml1OJjkS9NK-pXVRNBP7x7Y", 41);
  private static final String VERIFICATION_PREFS = EndpointCodec.decode("2rOHfVc7EP7wooRvTiQa7fiwgHZKJAjj_sq0k3U", 183);
  private static final String STORAGE_MIGRATED = EndpointCodec.decode("Mxf-2bWJfGckAvrGspV0byIH6t6qnmxM", 95);
  private static final String PUB_PREFIX = EndpointCodec.decode("JNDlgbEjHw", 119);
  private static final String LIC_PREFIX = EndpointCodec.decode("Gi7L74bXLQ", 73);

  private static final String KEY_POLICY = EndpointCodec.decode("RTsf-9Kp", 53);
  private static final String KEY_LICENSE = EndpointCodec.decode("LwvixdGtmA", 67);
  private static final String KEY_SERVER = EndpointCodec.decode("IBfjxqqc", 83);
  private static final String KEY_ELAPSED = EndpointCodec.decode("AOjCspJlew", 101);
  private static final String KEY_BOOT = EndpointCodec.decode("D-PEvg", 109);
  private static final String KEY_HIGHEST_WALL = EndpointCodec.decode("F_fatJ5pTQ8W-tk", 127);
  private static final String KEY_MAC = EndpointCodec.decode("5suq", 139);
  private static final String KEY_TRUSTED_TIME = EndpointCodec.decode("48Sgh2dXNS_7x6CJ", 151);
  private static final String KEY_TIME_FLOOR = EndpointCodec.decode("06-IYXwkDe_wzA", 167);
  private static final String KEY_TRUSTED_ELAPSED = EndpointCodec.decode("x6CEY1srCdPOpoh4VCMB", 179);
  private static final String KEY_TRUSTED_BOOT = EndpointCodec.decode("y6yIb08_HcfVuZpg", 191);
  private static final String STATE_ACTIVE = EndpointCodec.decode("pId3SzcF", 197);
  private static final String STATE_REVOKED = EndpointCodec.decode("oZdnXyQL6Q", 211);
  private static final String STATE_SUSPENDED = EndpointCodec.decode("rItuTD4U_d2z", 223);
  private static final String KIND_TRIAL = EndpointCodec.decode("kXZKIw0", 229);
  private static final String ROBO_FP = EndpointCodec.decode("nWFPIwfvyryVb0Y", 239);

  private static final long INTEGRITY_REFRESH_MS = 45_000L;
  private static final ExecutorService INTEGRITY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "m-pool");
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    return t;
  });
  private static final AtomicBoolean integrityInFlight = new AtomicBoolean(false);
  private static volatile int cachedIntegrityState;
  private static volatile long lastIntegrityElapsed = -INTEGRITY_REFRESH_MS;

  private static volatile CachedPolicy verifiedPolicy;
  private static final class CachedPolicy {
    final String token, request;
    final LicensePolicy policy;
    CachedPolicy(String token, String request, LicensePolicy policy) {
      this.token = token; this.request = request; this.policy = policy;
    }
  }
  private DeviceLicenseManager() {}
  public static boolean configured() {
    return recoveryConfigured();
  }
  private static String recoveryPublicKey() {
    String encoded = BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY_ENC;
    return encoded == null || encoded.isEmpty() ? "" : EndpointCodec.decode(encoded, 149);
  }

  public static boolean recoveryConfigured() {
    return recoveryPublicKey().startsWith(PUB_PREFIX);
  }

  private static boolean isUnitTestRuntime() {
    return ROBO_FP.equalsIgnoreCase(Build.FINGERPRINT);
  }

  private static boolean evaluateRuntimeIntegritySync(Context context) {
    if (!configured()) return false;
    if (ApkSignatureVerifier.runtimeSeal(context) != ApkSignatureVerifier.SEAL_OK) return false;
    if (LuaMemory.INSTANCE.isAvailable()) {
      if (LuaMemory.runtimeEnvStatus() != 0x3E7B1D49) return false;
    } else if (!isUnitTestRuntime()) {
      return false;
    }
    return true;
  }

  private static void scheduleIntegrityRefresh(Context context) {
    if (!integrityInFlight.compareAndSet(false, true)) return;
    Context app = context.getApplicationContext();
    Context target = app != null ? app : context;
    INTEGRITY_EXECUTOR.execute(() -> {
      try {
        boolean ok = evaluateRuntimeIntegritySync(target);
        cachedIntegrityState = ok ? 1 : -1;
        lastIntegrityElapsed = SystemClock.elapsedRealtime();
      } catch (Throwable ignored) {
        cachedIntegrityState = -1;
      } finally {
        integrityInFlight.set(false);
      }
    });
  }

  public static boolean verifyRuntimeIntegrity(Context context) {
    if (isUnitTestRuntime()) {
      return !configured() || evaluateRuntimeIntegritySync(context);
    }
    if (!configured()) return false;
    int state = cachedIntegrityState;
    if (state < 0) return false;
    long now = SystemClock.elapsedRealtime();
    if (state == 0 || now < lastIntegrityElapsed || now - lastIntegrityElapsed >= INTEGRITY_REFRESH_MS) {
      scheduleIntegrityRefresh(context);
    }
    return true;
  }

  public static String recoveryRequest(Context context) throws Exception {
    return RecoveryProtocol.request(context.getPackageName(),
        Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
  }
  public static void prepareDirectBoot(Context context) {
    if (!isUnitTestRuntime() && configured()) {
      scheduleIntegrityRefresh(context);
    }
    if (!UserManagerCompat.isUserUnlocked(context)) return;
    try {
      licenseStorage(context);
    } catch (IllegalStateException ignored) {
    }
  }

  private static synchronized Context licenseStorage(Context context) {
    Context storage = context.createDeviceProtectedStorageContext();
    if (storage.getSharedPreferences(VERIFICATION_PREFS, Context.MODE_PRIVATE)
        .getBoolean(STORAGE_MIGRATED, false)) {
      return storage;
    }
    if (!UserManagerCompat.isUserUnlocked(context)) {
      throw new IllegalStateException("License storage has not been migrated");
    }
    SharedPreferences legacy = context.getSharedPreferences(POLICY_PREFS, Context.MODE_PRIVATE);
    if ((legacy.contains(KEY_LICENSE) && !legacy.edit().remove(KEY_LICENSE).commit())
        || !storage.moveSharedPreferencesFrom(context, POLICY_PREFS)
        || !storage.moveSharedPreferencesFrom(context, VERIFICATION_PREFS)) {
      throw new IllegalStateException("Could not move license preferences");
    }
    SharedPreferences verification =
        storage.getSharedPreferences(VERIFICATION_PREFS, Context.MODE_PRIVATE);
    if (!verification.edit().putBoolean(STORAGE_MIGRATED, true).commit()) {
      verification.edit().remove(STORAGE_MIGRATED).apply();
      throw new IllegalStateException("Could not record license storage migration");
    }
    lastRestrictionCheck = -1;
    return storage;
  }

  private static SharedPreferences prefs(Context context) {
    return licenseStorage(context).getSharedPreferences(POLICY_PREFS, Context.MODE_PRIVATE);
  }

  private static SharedPreferences verification(Context context) {
    return licenseStorage(context).getSharedPreferences(VERIFICATION_PREFS, Context.MODE_PRIVATE);
  }

  private static String deviceBindId(Context context) {
    String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    return id == null ? "" : id;
  }

  private static String leaseMac(Context context, long server, long elapsed, long highestWall,
      int verifiedBoot) {
    try {
      if (!LuaMemory.INSTANCE.isAvailable()) return "";
      String mac = LuaMemory.computeLeaseMac(
          deviceBindId(context), server, elapsed, highestWall, verifiedBoot);
      return mac != null && mac.matches("[0-9a-f]{64}") ? mac : "";
    } catch (LinkageError | RuntimeException error) {
      return "";
    }
  }

  private static boolean matchesLeaseMac(String stored, String expected) {
    return stored != null && !expected.isEmpty() && MessageDigest.isEqual(
        stored.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
  }

  static synchronized VerificationState verificationState(Context context) {
    try {
      return readVerificationState(context);
    } catch (LinkageError error) {
      return VerificationState.UNAVAILABLE;
    } catch (RuntimeException error) {
      return VerificationState.INVALID;
    }
  }

  private static VerificationState readVerificationState(Context context) {
    SharedPreferences check = verification(context);
    long wall = System.currentTimeMillis();
    long server = check.getLong(KEY_SERVER, 0);
    long elapsed = check.getLong(KEY_ELAPSED, -1);
    int verifiedBoot = check.getInt(KEY_BOOT, -2);
    long highestWall = check.getLong(KEY_HIGHEST_WALL, 0);
    String storedMac = check.getString(KEY_MAC, "");
    if (server <= 0 || elapsed < 0 || storedMac == null || storedMac.isEmpty()) {
      return VerificationState.REQUIRED;
    }
    String expectedMac = leaseMac(context, server, elapsed, highestWall, verifiedBoot);
    if (expectedMac.isEmpty()) return VerificationState.UNAVAILABLE;
    if (!matchesLeaseMac(storedMac, expectedMac)) return VerificationState.INVALID;
    long nowElapsed = SystemClock.elapsedRealtime();
    int currentBoot = boot(context);
    boolean sameBoot = verifiedBoot >= 0 && verifiedBoot == currentBoot;
    if (wall <= 0 || nowElapsed < 0
        || wall + LicenseLease.CLOCK_TOLERANCE_MS < Math.max(server, highestWall)
        || (sameBoot && nowElapsed < elapsed) || (!sameBoot && wall < server)) {
      return VerificationState.CLOCK_CHANGED;
    }
    if (!LicenseLease.valid(wall, nowElapsed, currentBoot, server, elapsed, verifiedBoot, highestWall)) {
      return VerificationState.EXPIRED;
    }
    if (wall - highestWall >= 60_000L) {
      String updatedMac = leaseMac(context, server, elapsed, wall, verifiedBoot);
      if (updatedMac.isEmpty()) return VerificationState.UNAVAILABLE;
      check.edit().putLong(KEY_HIGHEST_WALL, wall).putString(KEY_MAC, updatedMac).apply();
    }
    return VerificationState.VALID;
  }

  private static boolean recentlyVerified(Context context) {
    return verificationState(context) == VerificationState.VALID;
  }

  static boolean needsOnlineVerification(Context context) {
    try {
      return policy(context) == null || !recentlyVerified(context);
    } catch (Exception error) {
      return true;
    }
  }

  static String verificationMessage(Context context) {
    return switch (verificationState(context)) {
      case REQUIRED -> "Cần xác minh lại giấy phép. Mở Khôi phục kích hoạt để kiểm tra.";
      case INVALID -> "Dữ liệu xác minh giấy phép không hợp lệ. Mở Khôi phục kích hoạt để xác minh lại.";
      case EXPIRED -> "Đã quá 7 ngày chưa xác minh giấy phép thành công. Mở Khôi phục kích hoạt để kiểm tra lại.";
      case CLOCK_CHANGED -> "Ngày giờ trên thiết bị chưa phù hợp. Bật ngày giờ tự động rồi khôi phục kích hoạt.";
      case UNAVAILABLE -> "Không thực hiện được xác minh trên thiết bị. Hãy thử khôi phục kích hoạt.";
      case VALID -> "Giấy phép đã được xác minh.";
    };
  }

  private static synchronized void recordVerification(Context context, long serverMillis) throws Exception {
    if (serverMillis <= 0) throw new IllegalStateException("Không nhận được thời gian xác minh từ máy chủ.");
    SharedPreferences check = verification(context);
    long prior = check.getLong(KEY_SERVER, 0);
    String priorMac = check.getString(KEY_MAC, "");
    boolean authenticated = priorMac != null && !priorMac.isEmpty() && matchesLeaseMac(priorMac,
        leaseMac(context, prior, check.getLong(KEY_ELAPSED, -1),
            check.getLong(KEY_HIGHEST_WALL, 0), check.getInt(KEY_BOOT, -2)));
    if (authenticated && !LicenseLease.newerVerification(serverMillis, prior)) {
      throw new IllegalStateException("Thời gian xác minh từ máy chủ cũ hơn lần xác minh đã lưu.");
    }
    long wall = System.currentTimeMillis();
    long elapsed = SystemClock.elapsedRealtime();
    int currentBoot = boot(context);
    long highestWall = Math.max(wall, serverMillis);
    String mac = leaseMac(context, serverMillis, elapsed, highestWall, currentBoot);
    if (mac.isEmpty()) throw new IllegalStateException("Không tạo được dữ liệu xác minh giấy phép.");
    SharedPreferences.Editor edit = check.edit()
        .putLong(KEY_SERVER, serverMillis)
        .putLong(KEY_ELAPSED, elapsed)
        .putInt(KEY_BOOT, currentBoot)
        .putLong(KEY_HIGHEST_WALL, highestWall)
        .putString(KEY_MAC, mac);
    if (!edit.commit()) throw new IllegalStateException("Không lưu được thời điểm xác minh giấy phép.");
    if (verificationState(context) != VerificationState.VALID) {
      throw new IllegalStateException(verificationMessage(context));
    }
  }

  public static boolean offlineLocked(Context context) {
    if (!configured()) return false;
    try {
      String token = prefs(context).getString(KEY_POLICY, "");
      if (!token.isEmpty()) {
        LicensePolicy policy = policy(context);
        return policy != null && policy.state.equals(STATE_ACTIVE)
            && (policy.expiresAt == 0 || policyTime(context, policy) < policy.expiresAt)
            && !recentlyVerified(context);
      }
      return false;
    } catch (Exception e) { return true; }
  }

  private static final long RESTRICTION_REFRESH_MS = 30_000L;
  private static volatile long lastRestrictionCheck = -1;
  private static volatile boolean lastRestriction;
  private static final AtomicBoolean restrictionRefreshQueued = new AtomicBoolean(false);

  private static boolean evaluateRestrictionSync(Context context) {
    try {
      String token = prefs(context).getString(KEY_POLICY, "");
      if (!token.isEmpty()) {
        return !isLicensed(context);
      }
      return false;
    } catch (Exception error) {
      return true;
    }
  }

  private static void scheduleRestrictionRefresh(Context context) {
    if (context == null || !restrictionRefreshQueued.compareAndSet(false, true)) return;
    final Context appContext = context.getApplicationContext() != null
        ? context.getApplicationContext() : context;
    INTEGRITY_EXECUTOR.execute(() -> {
      try {
        lastRestriction = evaluateRestrictionSync(appContext);
        lastRestrictionCheck = SystemClock.elapsedRealtime();
      } finally {
        restrictionRefreshQueued.set(false);
      }
    });
  }

  public static boolean restricted(Context context) {
    if (!configured()) return true;
    if (isUnitTestRuntime()) {
      boolean blocked = evaluateRestrictionSync(context);
      lastRestriction = blocked;
      lastRestrictionCheck = SystemClock.elapsedRealtime();
      return blocked;
    }
    long now = SystemClock.elapsedRealtime();
    long checkedAt = lastRestrictionCheck;
    if (checkedAt >= 0) {
      if (now < checkedAt || now - checkedAt >= RESTRICTION_REFRESH_MS) {
        scheduleRestrictionRefresh(context);
      }
      return lastRestriction;
    }
    boolean blocked = evaluateRestrictionSync(context);
    lastRestriction = blocked;
    lastRestrictionCheck = now;
    return blocked;
  }

  public static long remainingUntilExpiryMillis(Context context) {
    try {
      LicensePolicy policy = policy(context);
      if (policy == null || policy.expiresAt <= 0) return Long.MAX_VALUE;
      long remainingSeconds = policy.expiresAt - policyTime(context, policy);
      return remainingSeconds <= 0 ? 0 : Math.min(Long.MAX_VALUE / 1000, remainingSeconds) * 1000;
    } catch (Exception error) { return 0; }
  }

  public static boolean isLicensed(Context context) {
    try {
      prefs(context);
      if (!recoveryConfigured() || !ApkSignatureVerifier.isOfficial(context)
          || !recentlyVerified(context)) return false;
      LicensePolicy online = policy(context);
      return online != null && online.active(policyTime(context, online));
    } catch (Exception error) {
      return false;
    }
  }

  static synchronized boolean acceptRemote(Context context, String license, long serverMillis) throws Exception {
    if (license == null || !license.startsWith(LIC_PREFIX) || serverMillis <= 0) {
      throw new IllegalArgumentException("Giấy phép trực tuyến không hợp lệ.");
    }
    if (!ApkSignatureVerifier.isOfficial(context) || !recoveryConfigured()) {
      throw new IllegalStateException("Bản cài đặt chưa được xác thực.");
    }
    String request = recoveryRequest(context);
    String cached = prefs(context).getString(KEY_POLICY, "");
    String pubKey = recoveryPublicKey();
    LicensePolicy next = LicensePolicy.verify(request, license,
        RecoveryProtocol.publicKey(pubKey));
    LicensePolicy old = null;
    if (!cached.isEmpty()) {
      try {
        old = LicensePolicy.verify(request, cached, RecoveryProtocol.publicKey(pubKey));
      } catch (Exception ignored) {
      }
    }
    if (old != null && (next.revision < old.revision
        || (next.revision == old.revision && !cached.equals(license)))) {
      return false;
    }
    SharedPreferences.Editor edit = prefs(context).edit().putString(KEY_POLICY, license)
        .remove(KEY_LICENSE)
        .putLong(KEY_TRUSTED_TIME, serverMillis / 1000L)
        .putLong(KEY_TIME_FLOOR, Math.max(prefs(context).getLong(KEY_TIME_FLOOR, 0), serverMillis / 1000L))
        .putLong(KEY_TRUSTED_ELAPSED, SystemClock.elapsedRealtime() / 1000L)
        .putInt(KEY_TRUSTED_BOOT, boot(context));
    if (!edit.commit()) throw new IllegalStateException("Không lưu được trạng thái giấy phép.");
    recordVerification(context, serverMillis);
    lastRestrictionCheck = -1;
    return true;
  }

  private static LicensePolicy policy(Context context) throws Exception {
    String token = prefs(context).getString(KEY_POLICY, "");
    if (token.isEmpty()) return null;
    String request = recoveryRequest(context);
    CachedPolicy cached = verifiedPolicy;
    if (cached != null && cached.token.equals(token) && cached.request.equals(request)) return cached.policy;
    LicensePolicy policy = LicensePolicy.verify(request, token,
        RecoveryProtocol.publicKey(recoveryPublicKey()));
    verifiedPolicy = new CachedPolicy(token, request, policy);
    return policy;
  }

  private static int boot(Context context) {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  private static synchronized long policyTime(Context context, LicensePolicy policy) {
    long wall = System.currentTimeMillis() / 1000L;
    SharedPreferences data = prefs(context);
    long floor = data.getLong(KEY_TIME_FLOOR, 0);
    long trusted = data.getLong(KEY_TRUSTED_TIME, 0);
    int currentBoot = boot(context);
    wall = LicenseClock.now(wall, floor, trusted, SystemClock.elapsedRealtime() / 1000L,
        data.getLong(KEY_TRUSTED_ELAPSED, 0), currentBoot >= 0 && currentBoot == data.getInt(KEY_TRUSTED_BOOT, -2));
    if (wall == Long.MAX_VALUE) return wall;
    if (wall > floor + 60) data.edit().putLong(KEY_TIME_FLOOR, wall).commit();
    return wall;
  }

  public static boolean allows(Context context, String feature) {
    if (!LicensePolicy.FEATURES.contains(feature) || !recoveryConfigured()) return false;
    try {
      if (!recentlyVerified(context) || !ApkSignatureVerifier.isOfficial(context)) return false;
      LicensePolicy online = policy(context);
      return online != null && online.allows(feature, policyTime(context, online));
    } catch (Exception error) {
      return false;
    }
  }

  public static String status(Context context) {
    if (!recoveryConfigured()) return "Chưa cấu hình xác minh trực tuyến.";
    try {
      LicensePolicy policy = policy(context);
      if (policy == null) return "Chưa có giấy phép trực tuyến.";
      if (policy.state.equals(STATE_REVOKED)) return "Đã thu hồi giấy phép.";
      if (policy.state.equals(STATE_SUSPENDED)) return "Giấy phép đang bị tạm khóa.";
      if (policy.expiresAt > 0 && policyTime(context, policy) >= policy.expiresAt) {
        return "Giấy phép đã hết hạn.";
      }
      if (!recentlyVerified(context)) return verificationMessage(context);
      String state = policy.state.equals(STATE_REVOKED) ? "Đã thu hồi"
          : policy.state.equals(STATE_SUSPENDED) ? "Tạm khóa"
          : policy.active(policyTime(context, policy)) ? "Đang hoạt động" : "Đã hết hạn hoặc cần kiểm tra ngày giờ";
      return state + " · " + (policy.kind.equals(KIND_TRIAL) ? "Dùng thử" : "Chính thức")
          + " · Gói " + policy.packageName + "\n"
          + (policy.expiresAt == 0 ? "Không thời hạn" : "Hết hạn: "
              + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(policy.expiresAt * 1000L)));
    } catch (Exception e) { return "Không đọc được giấy phép đã lưu. Mở Khôi phục kích hoạt để kiểm tra lại."; }
  }
}
