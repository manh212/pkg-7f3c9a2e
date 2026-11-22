package com.google.android.accessibility.talkback;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public final class OtherScreenReaderMonitor {
  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable onChange;
  private final ContentObserver observer =
      new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
          scheduleRefresh();
        }
      };
  private final Runnable refresh = this::refresh;
  private boolean running;
  private boolean otherReaderActive;

  public OtherScreenReaderMonitor(Context context, Runnable onChange) {
    this.context = context;
    this.onChange = onChange;
  }

  public void start() {
    if (running) {
      return;
    }
    running = true;
    otherReaderActive = isCompetingScreenReaderEnabled(context);
    context.getContentResolver().registerContentObserver(
        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        false,
        observer);
    scheduleRefresh();
  }

  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    handler.removeCallbacks(refresh);
    context.getContentResolver().unregisterContentObserver(observer);
  }

  private void scheduleRefresh() {
    if (!running) {
      return;
    }
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }

  private void refresh() {
    if (!running) {
      return;
    }
    boolean active = isCompetingScreenReaderEnabled(context);
    if (active != otherReaderActive) {
      otherReaderActive = active;
      onChange.run();
    }
    handler.postDelayed(refresh, 5000);
  }

  public static boolean isCompetingScreenReaderEnabled(Context context) {
    AccessibilityManager manager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (manager == null) {
      return false;
    }
    try {
      if (hasCompetingScreenReader(
          manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK),
          context.getPackageName())) {
        return true;
      }
      String enabled = Settings.Secure.getString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (enabled == null) {
        return false;
      }
      for (String entry : enabled.split(":")) {
        ComponentName component = ComponentName.unflattenFromString(entry);
        if (component != null
            && !context.getPackageName().equals(component.getPackageName())
            && isKnownScreenReader(component.getPackageName())) {
          return true;
        }
      }
      return false;
    } catch (SecurityException e) {
      return false;
    }
  }

  static boolean hasCompetingScreenReader(List<AccessibilityServiceInfo> services, String ownPackage) {
    if (services == null) {
      return false;
    }
    for (AccessibilityServiceInfo info : services) {
      if (info == null
          || info.getResolveInfo() == null
          || info.getResolveInfo().serviceInfo == null) {
        continue;
      }
      if (isCompetingScreenReader(
          ownPackage,
          info.getResolveInfo().serviceInfo.packageName,
          info.feedbackType,
          info.flags,
          info.getCapabilities())) {
        return true;
      }
    }
    return false;
  }

  static boolean isCompetingScreenReader(
      String ownPackage, String candidatePackage, int feedbackType, int flags, int capabilities) {
    return candidatePackage != null
        && !ownPackage.equals(candidatePackage)
        && (isKnownScreenReader(candidatePackage)
            || ((feedbackType & AccessibilityServiceInfo.FEEDBACK_SPOKEN) != 0
                && ((flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
                    || (capabilities
                            & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION)
                        != 0)));
  }

  private static boolean isKnownScreenReader(String packageName) {
    return "com.nirenr.talkman".equals(packageName)
        || "com.google.android.marvin.talkback".equals(packageName)
        || "com.samsung.android.accessibility.talkback".equals(packageName);
  }
}
