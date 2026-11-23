package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import com.google.android.accessibility.luamemory.LuaMemory;
import com.google.android.accessibility.talkback.BuildConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ApkSignatureVerifier {
  static final int SEAL_OK = 0x5A3C9E17;

  // XOR-masked official signing certificate SHA-256 digest (no plaintext hex in DEX).
  private static final byte[] MASKED_CERT_SHA256 = new byte[] {
    (byte) 0xd8, (byte) 0x1a, (byte) 0xb1, (byte) 0x91, (byte) 0xb3, (byte) 0xe9, (byte) 0x30, (byte) 0x5d, (byte) 0x3d, (byte) 0xb3, (byte) 0xd2, (byte) 0x2e, (byte) 0xfa, (byte) 0xdd, (byte) 0xa5, (byte) 0x3c, (byte) 0x18, (byte) 0xee, (byte) 0x23, (byte) 0xd6, (byte) 0x9d, (byte) 0x3e, (byte) 0x11, (byte) 0x4d, (byte) 0x14, (byte) 0xe9, (byte) 0xfe, (byte) 0x9b, (byte) 0x93, (byte) 0x98, (byte) 0xfe, (byte) 0xd2
  };

  private static volatile int verifiedSeal;

  private ApkSignatureVerifier() {}

  static boolean isOfficial(Context context) {
    return runtimeSeal(context) == SEAL_OK;
  }

  static int runtimeSeal(Context context) {
    if (context == null || !BuildConfig.APPLICATION_ID.equals(context.getPackageName())) {
      return 0;
    }
    if (verifiedSeal == SEAL_OK) {
      return SEAL_OK;
    }
    try {
      PackageManager packageManager = context.getPackageManager();
      Signature[] signatures;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageInfo packageInfo =
            packageManager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo signingInfo = packageInfo.signingInfo;
        signatures = signingInfo == null ? null : signingInfo.getApkContentsSigners();
      } else {
        PackageInfo packageInfo =
            packageManager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNATURES);
        signatures = packageInfo.signatures;
      }
      if (signatures == null || signatures.length != 1 || signatures[0] == null) {
        return 0;
      }
      byte[] certBytes = signatures[0].toByteArray();
      boolean javaMatch = matchesCertificateDigest(certBytes, unmaskCertificateDigest());
      if (!javaMatch) {
        return 0;
      }
      if (LuaMemory.INSTANCE.isAvailable()) {
        int nativeSeal = LuaMemory.verifyRuntimeSeal(context.getPackageName(), certBytes);
        if (nativeSeal != SEAL_OK) {
          return 0;
        }
      } else if (!"robolectric".equalsIgnoreCase(Build.FINGERPRINT)) {
        // Reject if libsandfly_lua_memory.so was stripped/deleted from the APK on a real device.
        return 0;
      }
      verifiedSeal = SEAL_OK;
      return SEAL_OK;
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      return 0;
    }
  }

  static boolean matchesCertificateDigest(byte[] certificate, byte[] expectedSha256) {
    if (certificate == null || certificate.length == 0
        || expectedSha256 == null || expectedSha256.length != 32) {
      return false;
    }
    try {
      byte[] actualSha256 = MessageDigest.getInstance("SHA-256").digest(certificate);
      return MessageDigest.isEqual(actualSha256, expectedSha256);
    } catch (NoSuchAlgorithmException e) {
      return false;
    }
  }

  private static byte[] unmaskCertificateDigest() {
    byte[] out = new byte[MASKED_CERT_SHA256.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) (MASKED_CERT_SHA256[i] ^ ((0xA7 + i * 29) & 0xFF));
    }
    return out;
  }
}
