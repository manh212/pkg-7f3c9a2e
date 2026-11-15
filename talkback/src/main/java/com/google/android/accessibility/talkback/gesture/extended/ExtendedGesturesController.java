package com.google.android.accessibility.talkback.gesture.extended;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.widget.Toast;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationSettings;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

public final class ExtendedGesturesController {
  private final TalkBackService service;
  private ExtendedTouchBackend backend;
  private boolean activeExtendedGestures;
  private boolean activeRotation;
  private boolean activeTouchExplore;

  public ExtendedGesturesController(TalkBackService service) { this.service = service; }

  public void update(boolean active) {
    if (Build.VERSION.SDK_INT < 33) return;
    AccessibilityServiceInfo info = service.getServiceInfo();
    boolean available = active && info != null &&
        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
    boolean extended = available &&
        ExtendedGestures.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    boolean rotation = available &&
        TwoFingerRotationSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    boolean touchExplore = available &&
        TouchExploreSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    if (!extended && !rotation && !touchExplore) { stop(); return; }
    if (backend != null && extended == activeExtendedGestures && rotation == activeRotation
        && touchExplore == activeTouchExplore) {
      if (touchExplore) {
        backend.setExploreDelayMs(
            TouchExploreSettings.delayMs(SharedPreferencesUtils.getSharedPreferences(service)));
      }
      return;
    }
    stop();
    try {
      backend = new ExtendedTouchBackend(service, this::failed, rotation, extended);
      activeExtendedGestures = extended;
      activeRotation = rotation;
      activeTouchExplore = touchExplore;
      if (touchExplore) {
        backend.setExploreDelayMs(
            TouchExploreSettings.delayMs(SharedPreferencesUtils.getSharedPreferences(service)));
      }
      backend.start();
    } catch (RuntimeException error) {
      failed();
    }
  }

  public boolean performClick(int displayId) {
    return Build.VERSION.SDK_INT >= 33 && displayId == android.view.Display.DEFAULT_DISPLAY
        && backend != null && backend.performClick();
  }

  public void stop() {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) {
      backend.stop();
      backend = null;
    }
    activeExtendedGestures = false;
    activeRotation = false;
    activeTouchExplore = false;
  }

  public void configurationChanged(Configuration configuration) {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) backend.configurationChanged(configuration);
  }

  private void failed() {
    stop();
    boolean rotationFailed = activeRotation ||
        TwoFingerRotationSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    boolean touchExploreFailed = activeTouchExplore ||
        TouchExploreSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    SharedPreferencesUtils.getSharedPreferences(service).edit()
        .putBoolean(ExtendedGestures.ENABLED, false)
        .putBoolean(TwoFingerRotationSettings.ENABLED, false)
        .putBoolean(TouchExploreSettings.ENABLED, false)
        .apply();
    Toast.makeText(service, rotationFailed ? R.string.two_finger_rotation_failed :
        touchExploreFailed ? R.string.touch_explore_failed :
        R.string.extended_gestures_failed, Toast.LENGTH_LONG).show();
  }
}
