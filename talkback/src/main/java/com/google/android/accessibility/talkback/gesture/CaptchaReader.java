package com.google.android.accessibility.talkback.gesture;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr;
import com.google.android.accessibility.talkback.licensing.DeviceLicenseManager;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

final class CaptchaReader {
  private static final Executor OCR = Executors.newSingleThreadExecutor();
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{2,24}");
  private boolean reading;

  void read(
      TalkBackService service, @Nullable AccessibilityNodeInfoCompat node, Consumer<String> speak) {
    if (reading) {
      speak.accept(service.getString(R.string.captcha_reading));
      return;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        || !DeviceLicenseManager.allows(service, "ocr")
        || !TesseractOcr.isDataDownloaded(service)) {
      speak.accept(service.getString(R.string.captcha_ocr_unavailable));
      return;
    }
    if (node == null || !isImage(node)) {
      speak.accept(service.getString(R.string.captcha_focus_image));
      return;
    }
    reading = true;
    capture(service, node, speak);
  }

  private static boolean isImage(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || node.isEditable()) return false;
    int role = Role.getRole(node);
    CharSequence description = node.getContentDescription();
    return role == Role.ROLE_IMAGE || role == Role.ROLE_IMAGE_BUTTON
        || (description != null && description.toString().toLowerCase(Locale.ROOT).contains("captcha"));
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private void capture(
      TalkBackService service, AccessibilityNodeInfoCompat node, Consumer<String> speak) {
    Rect screenBounds = new Rect();
    node.getBoundsInScreen(screenBounds);
    ScreenshotCapture.takeScreenshotByNode(service, node,
        new ScreenshotCapture.WindowOrFullScreenScreenshotCallback() {
          @Override public void onFailure(int errorCode) {
            super.onFailure(errorCode);
            finish(service, speak, null);
          }

          @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
            super.onSuccess(screenshot);
            Bitmap full = getBitmap();
            if (full == null) {
              finish(service, speak, null);
              return;
            }
            Rect bounds = new Rect(screenBounds);
            if (isCaptureByWindow()) node.getBoundsInWindow(bounds);
            if (!bounds.intersect(0, 0, full.getWidth(), full.getHeight())) {
              full.recycle();
              finish(service, speak, null);
              return;
            }
            OCR.execute(() -> {
              String code = null;
              Bitmap crop = null;
              try {
                crop = Bitmap.createBitmap(
                    full, bounds.left, bounds.top, bounds.width(), bounds.height());
                code = extractCode(TesseractOcr.recognizeText(service, crop));
              } catch (RuntimeException ignored) {
              } finally {
                if (crop != null && crop != full) crop.recycle();
                full.recycle();
                String result = code;
                service.getMainExecutor().execute(() -> finish(service, speak, result));
              }
            });
          }
        });
  }

  static @Nullable String extractCode(@Nullable String result) {
    if (result == null) return null;
    String code = result.trim().replaceAll("[ \t]", "");
    return CODE.matcher(code).matches() ? code : null;
  }

  private void finish(TalkBackService service, Consumer<String> speak, @Nullable String code) {
    reading = false;
    if (TalkBackService.getInstance() != service || !TalkBackService.isServiceActive()) return;
    if (code == null) {
      speak.accept(service.getString(R.string.captcha_unreadable));
      return;
    }
    List<String> letters = new ArrayList<>();
    for (int i = 0; i < code.length(); i++) {
      char letter = code.charAt(i);
      letters.add(Character.isUpperCase(letter)
          ? service.getString(R.string.captcha_uppercase, letter) : String.valueOf(letter));
    }
    speak.accept(service.getString(R.string.captcha_result, String.join(", ", letters)));
  }
}
