package com.google.android.accessibility.talkback.gesture.extended;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

final class EdgeHaptics {
  private final Context context;

  EdgeHaptics(Context context) {
    this.context = context;
  }

  void onEdgeTapBeat() {
    vibratePredefined(VibrationEffect.EFFECT_TICK, 10);
  }

  void onSwipeStep() {
    vibratePredefined(VibrationEffect.EFFECT_TICK, 14);
  }

  void onSwipeConfirm() {
    vibratePredefined(VibrationEffect.EFFECT_CLICK, 22);
  }

  void onTapConfirm(int taps) {
    if (taps >= 3) {
      vibratePredefined(VibrationEffect.EFFECT_HEAVY_CLICK, 34);
    } else if (taps == 2) {
      vibratePredefined(VibrationEffect.EFFECT_DOUBLE_CLICK, 28);
    } else {
      vibratePredefined(VibrationEffect.EFFECT_CLICK, 20);
    }
  }

  private void vibratePredefined(int effectId, long fallbackMs) {
    if (!isVibrationEnabled()) {
      return;
    }
    try {
      Vibrator vibrator = getVibrator();
      if (vibrator == null || !vibrator.hasVibrator()) {
        return;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        vibrator.vibrate(VibrationEffect.createPredefined(effectId));
      } else {
        vibrator.vibrate(
            VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE));
      }
    } catch (RuntimeException ignored) {
    }
  }

  private boolean isVibrationEnabled() {
    try {
      SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
      return SharedPreferencesUtils.getBooleanPref(
          prefs,
          context.getResources(),
          R.string.pref_vibration_key,
          R.bool.pref_vibration_default);
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  private Vibrator getVibrator() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      VibratorManager manager = context.getSystemService(VibratorManager.class);
      return manager != null ? manager.getDefaultVibrator() : null;
    }
    return context.getSystemService(Vibrator.class);
  }
}
