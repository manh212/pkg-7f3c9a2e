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
    return "robolectric".equalsIgnoreCase(Build.FINGERPRINT);
  }

  public static boolean verifyRuntimeIntegrity(Context context) {
    if (!configured()) return true;
    if (ApkSignatureVerifier.runtimeSeal(context) != ApkSignatureVerifier.SEAL_OK) return false;
    if (LuaMemory.INSTANCE.isAvailable()) {
      if (LuaMemory.runtimeEnvStatus() != 0x3E7B1D49) return false;
    } else if (!isUnitTestRuntime()) {
      return false;
    }
    return true;
  }
  public static String recoveryRequest(Context context) throws Exception {
    return RecoveryProtocol.request(context.getPackageName(),
        Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
  }
  public static void prepareDirectBoot(Context context) {
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
    if ((legacy.contains("license") && !legacy.edit().remove("license").commit())
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
    long server = check.getLong("server", 0);
    long elapsed = check.getLong("elapsed", -1);
    int verifiedBoot = check.getInt("boot", -2);
    long highestWall = check.getLong("highestWall", 0);
    String storedMac = check.getString("mac", "");
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
      check.edit().putLong("highestWall", wall).putString("mac", updatedMac).apply();
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
    long prior = check.getLong("server", 0);
    String priorMac = check.getString("mac", "");
    boolean authenticated = priorMac != null && !priorMac.isEmpty() && matchesLeaseMac(priorMac,
        leaseMac(context, prior, check.getLong("elapsed", -1),
            check.getLong("highestWall", 0), check.getInt("boot", -2)));
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
        .putLong("server", serverMillis)
        .putLong("elapsed", elapsed)
        .putInt("boot", currentBoot)
        .putLong("highestWall", highestWall)
        .putString("mac", mac);
    if (!edit.commit()) throw new IllegalStateException("Không lưu được thời điểm xác minh giấy phép.");
    if (verificationState(context) != VerificationState.VALID) {
      throw new IllegalStateException(verificationMessage(context));
    }
  }

  public static boolean offlineLocked(Context context) {
    if (!configured()) return false;
    try {
      String token = prefs(context).getString("policy", "");
      if (!token.isEmpty()) {
        LicensePolicy policy = policy(context);
        return policy != null && policy.state.equals("active")
            && (policy.expiresAt == 0 || policyTime(context, policy) < policy.expiresAt)
            && !recentlyVerified(context);
      }
      return false;
    } catch (Exception e) { return true; }
  }

  private static volatile long lastRestrictionCheck = -1;
  private static volatile boolean lastRestriction;

  public static boolean restricted(Context context) {
    if (!configured()) return true;
    long now = SystemClock.elapsedRealtime();
    if (lastRestrictionCheck >= 0 && now >= lastRestrictionCheck && now - lastRestrictionCheck < 1_000) {
      return lastRestriction;
    }
    boolean blocked;
    try {
      String token = prefs(context).getString("policy", "");
      if (!token.isEmpty()) {
        blocked = !isLicensed(context);
      } else {
        blocked = false;
      }
    } catch (Exception error) { blocked = true; }
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
    String cached = prefs(context).getString("policy", "");
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
    SharedPreferences.Editor edit = prefs(context).edit().putString("policy", license)
        .remove("license")
        .putLong("trusted_time", serverMillis / 1000L)
        .putLong("time_floor", Math.max(prefs(context).getLong("time_floor", 0), serverMillis / 1000L))
        .putLong("trusted_elapsed", SystemClock.elapsedRealtime() / 1000L)
        .putInt("trusted_boot", boot(context));
    if (!edit.commit()) throw new IllegalStateException("Không lưu được trạng thái giấy phép.");
    recordVerification(context, serverMillis);
    lastRestrictionCheck = -1;
    return true;
  }

  private static LicensePolicy policy(Context context) throws Exception {
    String token = prefs(context).getString("policy", "");
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
    long floor = data.getLong("time_floor", 0);
    long trusted = data.getLong("trusted_time", 0);
    int currentBoot = boot(context);
    wall = LicenseClock.now(wall, floor, trusted, SystemClock.elapsedRealtime() / 1000L,
        data.getLong("trusted_elapsed", 0), currentBoot >= 0 && currentBoot == data.getInt("trusted_boot", -2));
    if (wall == Long.MAX_VALUE) return wall;
    if (wall > floor + 60) data.edit().putLong("time_floor", wall).commit();
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
      if (policy.state.equals("revoked")) return "Đã thu hồi giấy phép.";
      if (policy.state.equals("suspended")) return "Giấy phép đang bị tạm khóa.";
      if (policy.expiresAt > 0 && policyTime(context, policy) >= policy.expiresAt) {
        return "Giấy phép đã hết hạn.";
      }
      if (!recentlyVerified(context)) return verificationMessage(context);
      String state = policy.state.equals("revoked") ? "Đã thu hồi"
          : policy.state.equals("suspended") ? "Tạm khóa"
          : policy.active(policyTime(context, policy)) ? "Đang hoạt động" : "Đã hết hạn hoặc cần kiểm tra ngày giờ";
      return state + " · " + (policy.kind.equals("trial") ? "Dùng thử" : "Chính thức")
          + " · Gói " + policy.packageName + "\n"
          + (policy.expiresAt == 0 ? "Không thời hạn" : "Hết hạn: "
              + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(policy.expiresAt * 1000L)));
    } catch (Exception e) { return "Không đọc được giấy phép đã lưu. Mở Khôi phục kích hoạt để kiểm tra lại."; }
  }
}
