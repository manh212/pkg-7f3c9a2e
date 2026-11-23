package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.luamemory.LuaMemory;
import io.meridian.licensing.LicenseLease;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.LicenseRefreshSchedule;
import io.meridian.licensing.RecoveryProtocol;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystem;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = Application.class,
    instrumentedPackages = {
      "com.google.android.accessibility.talkback.licensing",
      "com.google.android.accessibility.luamemory",
      "io.meridian.licensing"
    },
    shadows = {
      LicenseVerificationUpgradeTest.SignatureShadow.class,
      LicenseVerificationUpgradeTest.NativeShadow.class,
      LicenseVerificationUpgradeTest.IssuerShadow.class,
      LicenseVerificationUpgradeTest.RecoveryShadow.class
    })
public final class LicenseVerificationUpgradeTest {
  private static final int BOOT = 7;
  private static KeyPair issuer;
  private Context context;
  private SharedPreferences policies;
  private SharedPreferences verification;
  private String token;

  @Implements(value = ApkSignatureVerifier.class, isInAndroidSdk = false)
  public static final class SignatureShadow {
    static boolean official;

    @Implementation
    protected static boolean isOfficial(Context context) {
      return official;
    }
  }

  @Implements(value = LuaMemory.class, isInAndroidSdk = false)
  public static final class NativeShadow {
    static boolean available;
    static boolean failMac;

    @Implementation
    protected boolean isAvailable() {
      return available;
    }

    @Implementation
    protected static String computeLeaseMac(String device, long server, long elapsed,
        long highestWall, int boot) throws Exception {
      if (failMac) return "";
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          "license-verification-test-key".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      String payload = device + "|" + server + "|" + elapsed + "|" + highestWall + "|" + boot;
      byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte value : bytes) {
        hex.append(Character.forDigit((value >>> 4) & 15, 16));
        hex.append(Character.forDigit(value & 15, 16));
      }
      return hex.toString();
    }
  }

  @Implements(value = RecoveryProtocol.class, isInAndroidSdk = false)
  public static final class IssuerShadow {
    @Implementation
    protected static PublicKey publicKey(String encoded) {
      return issuer.getPublic();
    }
  }

  @Implements(value = LicenseRecovery.class, isInAndroidSdk = false)
  public static final class RecoveryShadow {
    static int requests;
    static int status;
    static long date;
    static String value;

    @Implementation
    protected static HttpURLConnection openConnection(URL url) throws Exception {
      requests++;
      byte[] body = new JSONObject().put("format", 3).put("value", value)
          .toString().getBytes(StandardCharsets.UTF_8);
      int responseStatus = status;
      long responseDate = date;
      return new HttpURLConnection(url) {
        @Override public int getResponseCode() { return responseStatus; }
        @Override public long getDate() { return responseDate; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
      };
    }
  }

  public static final class ReminderService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    SystemClock.setCurrentTimeMillis(1_800_000_000_000L);
    shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
    Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "123456789abcdef0");
    Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    issuer = generator.generateKeyPair();
    SignatureShadow.official = true;
    NativeShadow.available = true;
    NativeShadow.failMac = false;
    RecoveryShadow.requests = 0;
    RecoveryShadow.status = 200;
    RecoveryShadow.date = ShadowSystem.currentTimeMillis();
    Context storage = context.createDeviceProtectedStorageContext();
    policies = storage.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE);
    verification = storage.getSharedPreferences("meridian_license_verification", Context.MODE_PRIVATE);
    policies.edit().clear().commit();
    verification.edit().clear().putBoolean("license_storage_migrated", true).commit();
    ReflectionHelpers.setStaticField(DeviceLicenseManager.class, "verifiedPolicy", null);
    ReflectionHelpers.setStaticField(DeviceLicenseManager.class, "lastRestrictionCheck", -1L);
    ReflectionHelpers.setStaticField(LicenseRecovery.class, "lastAttempt", -LicenseRefreshSchedule.RETRY_MS);
    ReflectionHelpers.setStaticField(LicenseRecovery.class, "lastResult", LicenseRecovery.Result.VERIFICATION_REQUIRED);
    token = signedPolicy(DeviceLicenseManager.recoveryRequest(context), "active", 1, 0, issuer);
    policies.edit().putString("policy", token).commit();
    saveLease(ShadowSystem.currentTimeMillis() - 60_000L, SystemClock.elapsedRealtime(),
        ShadowSystem.currentTimeMillis(), BOOT, false);
    RecoveryShadow.value = token;
    LicenseRecovery.checked(context);
  }

  @After
  public void finishWorker() throws Exception {
    worker().submit(() -> {}).get(10, TimeUnit.SECONDS);
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void upgradeWithoutMacRefreshesImmediatelyDespiteRecentOnlineCheck() throws Exception {
    assertEquals(DeviceLicenseManager.VerificationState.REQUIRED,
        DeviceLicenseManager.verificationState(context));
    assertFalse(DeviceLicenseManager.isLicensed(context));
    assertFalse(DeviceLicenseManager.allows(context, "scripts"));

    assertEquals(LicenseRecovery.Result.RESTORED, recover(false));

    assertEquals(1, RecoveryShadow.requests);
    assertTrue(verification.getString("mac", "").matches("[0-9a-f]{64}"));
    assertEquals(token, policies.getString("policy", ""));
    assertTrue(DeviceLicenseManager.isLicensed(context));
    assertTrue(DeviceLicenseManager.allows(context, "scripts"));
    assertFalse(DeviceLicenseManager.offlineLocked(context));
  }

  @Test
  public void upgradeNotificationRequestsVerificationWithoutClaimingSevenDays() {
    ReminderService service = Robolectric.buildService(ReminderService.class).create().get();
    ReflectionHelpers.callStaticMethod(ActivationReminder.class, "showOfflineNotification",
        ClassParameter.from(AccessibilityService.class, service));
    List<Notification> alerts = shadowOf(service.getSystemService(NotificationManager.class)).getAllNotifications();
    assertEquals(1, alerts.size());
    String text = alerts.get(0).extras.getCharSequence(Notification.EXTRA_TEXT).toString();
    assertTrue(text.contains("Cần xác minh lại"));
    assertFalse(text.contains("7 ngày"));
  }

  @Test
  public void authenticatedExpiredLeaseReportsSevenDays() throws Exception {
    long oldServer = ShadowSystem.currentTimeMillis() - LicenseLease.MAX_OFFLINE_MS - 1_000L;
    saveLease(oldServer, 0L, oldServer, BOOT - 1, true);
    assertEquals(DeviceLicenseManager.VerificationState.EXPIRED,
        DeviceLicenseManager.verificationState(context));
    assertTrue(DeviceLicenseManager.status(context).contains("7 ngày"));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void clockRollbackIsNotReportedAsSevenDays() throws Exception {
    long wall = ShadowSystem.currentTimeMillis();
    saveLease(wall, SystemClock.elapsedRealtime(),
        wall + LicenseLease.CLOCK_TOLERANCE_MS + 60_000L, BOOT, true);
    assertEquals(DeviceLicenseManager.VerificationState.CLOCK_CHANGED,
        DeviceLicenseManager.verificationState(context));
    assertTrue(DeviceLicenseManager.status(context).contains("Ngày giờ"));
    assertFalse(DeviceLicenseManager.status(context).contains("7 ngày"));
  }

  @Test
  public void modifiedLeaseCannotGrantAccessOrClaimAnExpiredWindow() throws Exception {
    long wall = ShadowSystem.currentTimeMillis();
    saveLease(wall, SystemClock.elapsedRealtime(), wall, BOOT, true);
    verification.edit().putLong("server", wall + 1_000L).commit();
    assertEquals(DeviceLicenseManager.VerificationState.INVALID,
        DeviceLicenseManager.verificationState(context));
    assertFalse(DeviceLicenseManager.isLicensed(context));
    assertFalse(DeviceLicenseManager.status(context).contains("7 ngày"));
  }

  @Test
  public void authenticatedLeaseRequiresWorkingNativeVerification() throws Exception {
    long wall = ShadowSystem.currentTimeMillis();
    saveLease(wall, SystemClock.elapsedRealtime(), wall, BOOT, true);
    NativeShadow.available = false;
    assertEquals(DeviceLicenseManager.VerificationState.UNAVAILABLE,
        DeviceLicenseManager.verificationState(context));
    assertFalse(DeviceLicenseManager.isLicensed(context));
    assertFalse(DeviceLicenseManager.status(context).contains("7 ngày"));
  }

  @Test
  public void freshSignedResponseReplacesAnUnauthenticatedFutureAnchor() throws Exception {
    long future = ShadowSystem.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
    saveLease(future, SystemClock.elapsedRealtime(), future, BOOT, false);
    assertEquals(LicenseRecovery.Result.RESTORED, recover(false));
    assertEquals(RecoveryShadow.date, verification.getLong("server", 0));
    assertTrue(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void authenticatedServerAnchorCannotBeMovedBackwards() throws Exception {
    long future = ShadowSystem.currentTimeMillis() + 60_000L;
    saveLease(future, SystemClock.elapsedRealtime(), future, BOOT, true);
    String savedMac = verification.getString("mac", "");
    assertEquals(LicenseRecovery.Result.VERIFICATION_FAILED, recover(true));
    assertEquals(future, verification.getLong("server", 0));
    assertEquals(savedMac, verification.getString("mac", ""));
  }

  @Test
  public void invalidRemoteSignatureCannotCreateMacOrEraseTheSavedPolicy() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    RecoveryShadow.value = signedPolicy(DeviceLicenseManager.recoveryRequest(context),
        "active", 2, 0, generator.generateKeyPair());
    assertEquals(LicenseRecovery.Result.INVALID, recover(false));
    assertFalse(verification.contains("mac"));
    assertEquals(token, policies.getString("policy", ""));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void anotherDevicesSignedPolicyCannotCreateMac() throws Exception {
    RecoveryShadow.value = signedPolicy(RecoveryProtocol.request(context.getPackageName(), "223456789abcdef0"),
        "active", 2, 0, issuer);
    assertEquals(LicenseRecovery.Result.INVALID, recover(false));
    assertFalse(verification.contains("mac"));
    assertEquals(token, policies.getString("policy", ""));
  }

  @Test
  public void brokenCachedPolicyCanBeRecoveredWithoutRegisteringAgain() throws Exception {
    policies.edit().putString("policy", "SFLIC3.broken").commit();
    assertTrue(DeviceLicenseManager.offlineLocked(context));
    assertTrue(DeviceLicenseManager.status(context).contains("Không đọc được giấy phép đã lưu"));
    assertEquals(LicenseRecovery.Result.RESTORED, recover(false));
    assertEquals(token, policies.getString("policy", ""));
    assertTrue(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void revokedPolicyCannotBeReplacedByAnOlderGrant() throws Exception {
    String revoked = signedPolicy(DeviceLicenseManager.recoveryRequest(context), "revoked", 2, 0, issuer);
    policies.edit().putString("policy", revoked).commit();
    assertEquals(LicenseRecovery.Result.INVALID, recover(false));
    assertEquals(revoked, policies.getString("policy", ""));
    assertFalse(verification.contains("mac"));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void conflictingGrantCannotReplaceARevocationAtTheSameRevision() throws Exception {
    String revoked = signedPolicy(DeviceLicenseManager.recoveryRequest(context), "revoked", 1, 0, issuer);
    policies.edit().putString("policy", revoked).commit();
    assertEquals(LicenseRecovery.Result.INVALID, recover(false));
    assertEquals(revoked, policies.getString("policy", ""));
    assertFalse(verification.contains("mac"));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void verifiedDeniedOrExpiredPoliciesDoNotUnlockDuringMigration() throws Exception {
    for (String state : new String[] {"revoked", "suspended", "active"}) {
      long expires = state.equals("active") ? ShadowSystem.currentTimeMillis() / 1000L - 60L : 0;
      RecoveryShadow.value = signedPolicy(DeviceLicenseManager.recoveryRequest(context), state,
          RecoveryShadow.requests + 2, expires, issuer);
      assertEquals(LicenseRecovery.Result.RESTORED, recover(true));
      assertFalse(DeviceLicenseManager.isLicensed(context));
      assertFalse(DeviceLicenseManager.allows(context, "scripts"));
    }
  }

  @Test
  public void nativeMacFailureCannotBeReportedAsSuccessfulRecovery() throws Exception {
    NativeShadow.failMac = true;
    assertEquals(LicenseRecovery.Result.VERIFICATION_FAILED, recover(false));
    assertFalse(verification.contains("mac"));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void missingServerTimeCannotRefreshTheLease() throws Exception {
    RecoveryShadow.date = 0L;
    assertEquals(LicenseRecovery.Result.INVALID, recover(false));
    assertFalse(verification.contains("mac"));
    assertFalse(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void failedUpgradeRefreshRetriesAfterFifteenMinutesInsteadOfSixHours() throws Exception {
    RecoveryShadow.status = 503;
    assertEquals(LicenseRecovery.Result.OFFLINE, recover(false));
    assertEquals(LicenseRecovery.Result.OFFLINE, recover(false));
    assertEquals(1, RecoveryShadow.requests);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(15));
    RecoveryShadow.status = 200;
    RecoveryShadow.date = ShadowSystem.currentTimeMillis();
    assertEquals(LicenseRecovery.Result.RESTORED, recover(false));
    assertEquals(2, RecoveryShadow.requests);
    assertTrue(DeviceLicenseManager.isLicensed(context));
  }

  @Test
  public void manualRecoveryBypassesRetryDelayAndVerifiedLeaseKeepsItsSchedule() throws Exception {
    RecoveryShadow.status = 503;
    assertEquals(LicenseRecovery.Result.OFFLINE, recover(false));
    RecoveryShadow.status = 200;
    assertEquals(LicenseRecovery.Result.RESTORED, recover(true));
    assertEquals(LicenseRecovery.Result.ALREADY_LICENSED, recover(false));
    assertEquals(2, RecoveryShadow.requests);
  }

  @Test
  public void installationFailureCompletesTheCallbackWithoutStartingNetworkWork() throws Exception {
    SignatureShadow.official = false;
    assertEquals(LicenseRecovery.Result.UNVERIFIED_INSTALLATION, recover(false));
    assertEquals(0, RecoveryShadow.requests);
    assertFalse(verification.contains("mac"));
  }

  private LicenseRecovery.Result recover(boolean force) throws Exception {
    AtomicReference<LicenseRecovery.Result> result = new AtomicReference<>();
    LicenseRecovery.start(context, force, result::set);
    worker().submit(() -> {}).get(10, TimeUnit.SECONDS);
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(result.get());
    return result.get();
  }

  private static ExecutorService worker() {
    return ReflectionHelpers.getStaticField(LicenseRecovery.class, "WORKER");
  }

  private void saveLease(long server, long elapsed, long highestWall, int boot,
      boolean authenticated) throws Exception {
    SharedPreferences.Editor edit = verification.edit().putLong("server", server)
        .putLong("elapsed", elapsed).putLong("highestWall", highestWall).putInt("boot", boot);
    if (authenticated) {
      edit.putString("mac", NativeShadow.computeLeaseMac("123456789abcdef0", server, elapsed, highestWall, boot));
    } else {
      edit.remove("mac");
    }
    assertTrue(edit.commit());
  }

  private static String signedPolicy(String request, String state, long revision, long expires,
      KeyPair key) throws Exception {
    long issued = ShadowSystem.currentTimeMillis() / 1000L - TimeUnit.DAYS.toSeconds(1);
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.getPrivate());
    return new LicensePolicy(request, revision, issued, expires, state, "standard", "standard",
        LicensePolicy.FEATURES).sign(signer);
  }
}
