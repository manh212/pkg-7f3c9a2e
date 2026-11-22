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
import org.checkerframework.checker.nullness.qual.Nullable;

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
    return findCompetingScreenReader(context) != null;
  }

  public static @Nullable String findCompetingScreenReader(Context context) {
    AccessibilityManager manager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (manager == null) {
      return null;
    }
    try {
      String match =
          findCompetingScreenReader(
              manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK),
              context.getPackageName());
      if (match != null) {
        return match;
      }
      String enabled = Settings.Secure.getString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (enabled == null) {
        return null;
      }
      for (String entry : enabled.split(":")) {
        ComponentName component = ComponentName.unflattenFromString(entry);
        if (component != null
            && !context.getPackageName().equals(component.getPackageName())
            && isKnownScreenReaderService(component.getPackageName(), component.getClassName())) {
          return component.flattenToShortString();
        }
      }
      return null;
    } catch (SecurityException e) {
      return null;
    }
  }

  static boolean hasCompetingScreenReader(List<AccessibilityServiceInfo> services, String ownPackage) {
    return findCompetingScreenReader(services, ownPackage) != null;
  }

  static @Nullable String findCompetingScreenReader(
      List<AccessibilityServiceInfo> services, String ownPackage) {
    if (services == null) {
      return null;
    }
    for (AccessibilityServiceInfo info : services) {
      if (info == null
          || info.getResolveInfo() == null
          || info.getResolveInfo().serviceInfo == null) {
        continue;
      }
      String packageName = info.getResolveInfo().serviceInfo.packageName;
      String className = info.getResolveInfo().serviceInfo.name;
      if (isCompetingScreenReader(
          ownPackage,
          packageName,
          className,
          info.feedbackType,
          info.flags,
          info.getCapabilities())) {
        return className != null ? packageName + "/" + className : packageName;
      }
    }
    return null;
  }

  static boolean isCompetingScreenReader(
      String ownPackage, String candidatePackage, int feedbackType, int flags, int capabilities) {
    return isCompetingScreenReader(
        ownPackage, candidatePackage, /* candidateClassName= */ null, feedbackType, flags, capabilities);
  }

  static boolean isCompetingScreenReader(
      String ownPackage,
      String candidatePackage,
      @Nullable String candidateClassName,
      int feedbackType,
      int flags,
      int capabilities) {
    if (candidatePackage == null || ownPackage.equals(candidatePackage)) {
      return false;
    }
    if (isKnownScreenReaderService(candidatePackage, candidateClassName)) {
      return true;
    }
    // Only yield to other accessibility services when they actively request touch exploration
    // right now, not merely because their manifest declares touch-exploration capability.
    return (feedbackType & AccessibilityServiceInfo.FEEDBACK_SPOKEN) != 0
        && (flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
  }

  static boolean isKnownScreenReaderService(
      String packageName, @Nullable String className) {
    if ("com.nirenr.talkman".equals(packageName)) {
      return true;
    }
    if ("com.google.android.marvin.talkback".equals(packageName)
        || "com.samsung.android.accessibility.talkback".equals(packageName)) {
      if (className == null || className.isEmpty()) {
        return false;
      }
      String lower = className.toLowerCase(java.util.Locale.ROOT);
      return lower.endsWith(".talkbackservice")
          || lower.equals("talkbackservice")
          || lower.endsWith(".voiceassistantservice")
          || lower.endsWith(".screenreaderservice");
    }
    return false;
  }
}
