package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DeviceLicenseManagerRecoveryTest {
  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  @Test public void recoveryRequestSurvivesLegacyPreferenceRemoval() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "123456789abcdef0");
    String request = DeviceLicenseManager.recoveryRequest(context);
    KeyPair issuer = keyPair();
    String oldCode = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .edit().putString("license", oldCode).remove("policy").commit();
    assertFalse(DeviceLicenseManager.isLicensed(context));
    assertFalse(DeviceLicenseManager.allows(context, "scripts"));
    assertFalse(context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .contains("license"));
    assertEquals(request, DeviceLicenseManager.recoveryRequest(context));
  }

  @Test public void lockedBootReadsMigratedPolicyAndVerification() {
    Context context = RuntimeEnvironment.getApplication();
    UserManager users = context.getSystemService(UserManager.class);
    shadowOf(users).setUserUnlocked(true);
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .edit().putString("policy", "SFLIC3.sample").putLong("time_floor", 1234L)
        .putString("license", "legacy").commit();
    context.getSharedPreferences("meridian_license_verification", Context.MODE_PRIVATE)
        .edit().putLong("server", 1234567L).putInt("boot", 5).commit();

    DeviceLicenseManager.prepareDirectBoot(context);
    Context lockedContext = new ContextWrapper(context) {
      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        throw new IllegalStateException("Credential storage unavailable");
      }
    };
    shadowOf(users).setUserUnlocked(false);
    try {
      SharedPreferences policy = ReflectionHelpers.callStaticMethod(
          DeviceLicenseManager.class, "prefs", ClassParameter.from(Context.class, lockedContext));
      SharedPreferences verification = ReflectionHelpers.callStaticMethod(
          DeviceLicenseManager.class, "verification",
          ClassParameter.from(Context.class, lockedContext));
      assertEquals("SFLIC3.sample", policy.getString("policy", ""));
      assertEquals(1234L, policy.getLong("time_floor", 0L));
      assertFalse(policy.contains("license"));
      assertEquals(1234567L, verification.getLong("server", 0L));
      assertEquals(5, verification.getInt("boot", -1));
    } finally {
      shadowOf(users).setUserUnlocked(true);
    }
  }

  @Test public void lockedBootWithPendingMigrationDoesNotTreatLegacyPolicyAsAbsent() {
    Context context = RuntimeEnvironment.getApplication();
    UserManager users = context.getSystemService(UserManager.class);
    shadowOf(users).setUserUnlocked(true);
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .edit().putString("policy", "SFLIC3.sample").commit();
    shadowOf(users).setUserUnlocked(false);
    try {
      assertTrue(DeviceLicenseManager.restricted(context));
    } finally {
      shadowOf(users).setUserUnlocked(true);
    }
  }

  @Test public void onlineCatalogRejectsLegacyFormatEvenWithValidIssuerSignature() throws Exception {
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    KeyPair issuer = keyPair();
    String oldCode = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    assertTrue(RecoveryProtocol.verify(request, oldCode, issuer.getPublic()));
    JSONObject record = new JSONObject().put("format", 2).put("value", oldCode);
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(record));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(new JSONObject().put("format", 3).put("value", oldCode)));
    try {
      DeviceLicenseManager.acceptRemote(RuntimeEnvironment.getApplication(), oldCode,
          System.currentTimeMillis());
      fail("Legacy remote tokens must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("Giấy phép trực tuyến không hợp lệ.", expected.getMessage());
    }
  }

  @Test public void onlineCatalogRequiresNewFormatAndServerRecord() throws Exception {
    assertTrue(LicenseRecovery.isOnlinePolicyRecord(
        new JSONObject().put("format", 3).put("value", "SFLIC3.sample")));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(new JSONObject()));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(null));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(
        new JSONObject().put("format", 2).put("value", "SFLIC3.sample")));
    try {
      DeviceLicenseManager.acceptRemote(RuntimeEnvironment.getApplication(),
          "SFLIC3.sample", 0L);
      fail("Missing server time must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("Giấy phép trực tuyến không hợp lệ.", expected.getMessage());
    }
  }

  @Test public void forgedLegacyTokenIsNotMadeValidByItsOwnPublicKey() throws Exception {
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    KeyPair attacker = keyPair();
    KeyPair trusted = keyPair();
    String forged = RecoveryProtocol.issue(request, attacker.getPrivate(), 1700000000L);
    assertFalse(RecoveryProtocol.verify(request, forged, trusted.getPublic()));
  }
}
