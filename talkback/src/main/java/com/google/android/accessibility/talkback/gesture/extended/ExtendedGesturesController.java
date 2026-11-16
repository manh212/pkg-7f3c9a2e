package com.google.android.accessibility.talkback.gesture.extended;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
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
  private boolean activeFastDoubleTap;
  private boolean activeEdges;
  private boolean edgesFailed;

  public ExtendedGesturesController(TalkBackService service) { this.service = service; }

  public void update(boolean active) {
    if (Build.VERSION.SDK_INT < 33) return;
    AccessibilityServiceInfo info = service.getServiceInfo();
    boolean available = active && info != null &&
        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    boolean extended = available && ExtendedGestures.isEnabled(prefs);
    boolean rotation = available && TwoFingerRotationSettings.isEnabled(prefs);
    boolean fastDoubleTap = available && LatencyExperiments.Feature.FAST_DOUBLE_TAP.enabled();
    boolean edges = available && !edgesFailed && EdgeGesture.hasAssignments(service, prefs);
    if (!extended && !rotation && !fastDoubleTap && !edges) { stop(); return; }
    if (backend != null && extended == activeExtendedGestures && rotation == activeRotation
        && fastDoubleTap == activeFastDoubleTap) {
      if (activeEdges != edges) {
        backend.updateEdgeGestures(edges);
        activeEdges = edges;
      }
      return;
    }
    stop();
    try {
      activeExtendedGestures = extended;
      activeRotation = rotation;
      activeFastDoubleTap = fastDoubleTap;
      activeEdges = edges;
      backend = new ExtendedTouchBackend(service, this::failed, rotation, extended, fastDoubleTap);
      backend.updateEdgeGestures(edges);
      backend.start();
    } catch (RuntimeException error) {
      LogUtils.w("ExtendedTouchBackend", "Could not start touch controller: %s", error);
      failed();
    }
  }

  public void refreshEdges(boolean active) {
    edgesFailed = false;
    update(active);
    if (backend != null && activeEdges) {
      backend.updateEdgeGestures(true);
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
    activeFastDoubleTap = false;
    activeEdges = false;
  }

  public void configurationChanged(Configuration configuration) {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) backend.configurationChanged(configuration);
  }

  private void failed() {
    if (activeEdges) {
      edgesFailed = true;
      stop();
      update(TalkBackService.isServiceActive()
          && !com.google.android.accessibility.talkback.pause.PauseController.isPaused());
      return;
    }
    boolean fastDoubleTap = activeFastDoubleTap;
    boolean rotationFailed = activeRotation
        || TwoFingerRotationSettings.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    stop();
    if (fastDoubleTap) {
      SharedPreferencesUtils.getSharedPreferences(service).edit()
          .putBoolean(LatencyExperiments.Feature.FAST_DOUBLE_TAP.key, false)
          .apply();
      Toast.makeText(service, R.string.fast_double_tap_failed, Toast.LENGTH_LONG).show();
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
