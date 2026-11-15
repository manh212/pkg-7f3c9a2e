package com.google.android.accessibility.talkback.gesture.extended;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.widget.Toast;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationSettings;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

public final class ExtendedGesturesController {
  private final TalkBackService service;
  private ExtendedTouchBackend backend;
  private boolean activeExtendedGestures;
  private boolean activeRotation;
  private boolean activeSimulatedDoubleTap;

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
    boolean simulatedDoubleTap = available && LatencyExperiments.Feature.SIMULATED_DOUBLE_TAP.enabled();
    if (!extended && !rotation && !simulatedDoubleTap) { stop(); return; }
    if (backend != null && extended == activeExtendedGestures && rotation == activeRotation
        && simulatedDoubleTap == activeSimulatedDoubleTap) return;
    stop();
    try {
      activeExtendedGestures = extended;
      activeRotation = rotation;
      activeSimulatedDoubleTap = simulatedDoubleTap;
      backend = new ExtendedTouchBackend(service, this::failed, rotation, extended, simulatedDoubleTap);
      backend.start();
    } catch (RuntimeException error) {
      LogUtils.w("ExtendedTouchBackend", "Could not start touch controller: %s", error);
      failed();
    }
  }

  public boolean performClick(int displayId) {
    return Build.VERSION.SDK_INT >= 33 && displayId == android.view.Display.DEFAULT_DISPLAY
        && backend != null && backend.performClick();
  }

  public boolean scheduleSimulatedTap(Runnable tap, int delayMs) {
    return Build.VERSION.SDK_INT >= 33 && backend != null && backend.scheduleSimulatedTap(tap, delayMs);
  }

  public void stop() {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) {
      backend.stop();
      backend = null;
    }
    activeExtendedGestures = false;
    activeRotation = false;
    activeSimulatedDoubleTap = false;
  }

  public void configurationChanged(Configuration configuration) {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) backend.configurationChanged(configuration);
  }

  private void failed() {
    boolean simulating = activeSimulatedDoubleTap;
    boolean rotationFailed = activeRotation
        || TwoFingerRotationSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    stop();
    if (simulating) {
      SharedPreferencesUtils.getSharedPreferences(service).edit()
          .putBoolean(LatencyExperiments.Feature.SIMULATED_DOUBLE_TAP.key, false)
          .apply();
      Toast.makeText(service, R.string.simulated_double_tap_failed, Toast.LENGTH_LONG).show();
      return;
    }
    SharedPreferencesUtils.getSharedPreferences(service).edit()
        .putBoolean(ExtendedGestures.ENABLED, false)
        .putBoolean(TwoFingerRotationSettings.ENABLED, false)
        .apply();
    Toast.makeText(service, rotationFailed ? R.string.two_finger_rotation_failed :
        R.string.extended_gestures_failed, Toast.LENGTH_LONG).show();
  }
}
