package com.google.android.accessibility.talkback.licensing;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.os.UserManagerCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.update.UpdateChecker;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * The owner's announcements to every user, published from the admin tool and stored on
 * Firebase as a list. When the screen reader starts or the settings open, the stored
 * announcements are fetched; each one the user has not seen yet is shown once, oldest
 * first, one dialog after another — so a user who updates after five announcements
 * sees exactly those five. An announcement is marked seen only when the user closes
 * its dialog. One the admin marked as a new feature waits at its place in the queue
 * until the user runs the newest build their update channel offers. The first run of
 * a build with this feature takes the plain announcements then current as seen,
 * silently, so an upgrade does not confront everyone with old texts.
 */
public final class AdminAnnouncement {
  private static final String TAG = "AdminAnnouncement";
  public static final String KEY_SEEN = "pref_admin_announcement_seen";
  private static final long MIN_FETCH_INTERVAL_MS = 5 * 60_000L;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicBoolean FETCHING = new AtomicBoolean();
  private static final ArrayDeque<Item> QUEUE = new ArrayDeque<>();

  private static WeakReference<AccessibilityService> serviceRef = new WeakReference<>(null);
  private static WeakReference<Activity> settingsRef = new WeakReference<>(null);
  private static AlertDialog dialog;
  private static Context dialogContext;
  private static long lastFetchElapsed = -MIN_FETCH_INTERVAL_MS;

  private AdminAnnouncement() {}

  /** One stored announcement, in the order it was published. */
  private static final class Item {
    final String text;
    final long updatedAt;
    final boolean requireLatest;

    Item(String text, long updatedAt, boolean requireLatest) {
      this.text = text;
      this.updatedAt = updatedAt;
      this.requireLatest = requireLatest;
    }
  }

  public static void startService(AccessibilityService service) {
    serviceRef = new WeakReference<>(service);
    check(service, false);
  }

  public static void stopService(AccessibilityService service) {
    if (serviceRef.get() == service) {
      serviceRef.clear();
      synchronized (QUEUE) {
        QUEUE.clear();
      }
      if (dialogContext == service) closeDialog();
    }
  }

  /** Settings may show the announcements even before the screen reader service is enabled. */
  public static void resumeSettings(Activity activity) {
    settingsRef = new WeakReference<>(activity);
    check(activity, true);
  }

  public static void pauseSettings(Activity activity) {
    if (settingsRef.get() == activity) {
      settingsRef.clear();
      synchronized (QUEUE) {
        QUEUE.clear();
      }
      if (dialogContext == activity) closeDialog();
    }
  }

  private static void check(Context owner, boolean inSettings) {
    if (!UserManagerCompat.isUserUnlocked(owner)) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastFetchElapsed < MIN_FETCH_INTERVAL_MS) return;
    if (!FETCHING.compareAndSet(false, true)) return;
    lastFetchElapsed = now;
    Context app = owner.getApplicationContext();
    new Thread(
            () -> {
              try {
                JSONObject stored = Enrollment.service(app).get("appAnnouncements");
                List<Item> pending = pending(app, stored);
                if (pending.isEmpty()) return;
                synchronized (QUEUE) {
                  QUEUE.clear();
                  QUEUE.addAll(pending);
                }
                MAIN.post(() -> showNext(owner, inSettings));
              } catch (Exception e) {
                // No network or no Firebase identity yet: the next start or settings
                // visit checks again, and nothing is marked seen.
                Log.w(TAG, "Announcement check failed", e);
              } finally {
                FETCHING.set(false);
              }
            },
            "meridian-announcement")
        .start();
  }

  /**
   * The announcements [stored] holds that the user has not seen, oldest first. Plain
   * announcements are baselined silently on the first run; a new-feature announcement
   * holds the queue at its place until the running build is the newest one offered.
   */
  private static List<Item> pending(Context app, JSONObject stored) {
    List<Item> all = new ArrayList<>();
    Iterator<String> keys = stored.keys();
    while (keys.hasNext()) {
      JSONObject entry = stored.optJSONObject(keys.next());
      if (entry == null) continue;
      String text = entry.optString("text", "").trim();
      long updatedAt = entry.optLong("updatedAt", 0L);
      if (!text.isEmpty() && updatedAt > 0) {
        all.add(new Item(text, updatedAt, entry.optBoolean("requireLatest", false)));
      }
    }
    all.sort(Comparator.comparingLong(item -> item.updatedAt));
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(app);
    boolean firstRun = !prefs.contains(KEY_SEEN);
    long watermark = prefs.getLong(KEY_SEEN, 0L);
    List<Item> pending = new ArrayList<>();
    Boolean currentBuild = null;
    for (Item item : all) {
      if (item.updatedAt <= watermark) continue;
      if (item.requireLatest) {
        if (currentBuild == null) currentBuild = isCurrentBuild(app);
        if (!currentBuild) break;
      } else if (firstRun) {
        watermark = item.updatedAt;
        prefs.edit().putLong(KEY_SEEN, watermark).apply();
        continue;
      }
      pending.add(item);
    }
    return pending;
  }

  /** Whether the running build is the newest one this user's update channel offers. */
  private static boolean isCurrentBuild(Context context) {
    try {
      return UpdateChecker.INSTANCE.check(context) == null;
    } catch (Exception e) {
      // Not known to be current: a new-feature announcement waits for a later check.
      return false;
    }
  }

  private static void showNext(Context owner, boolean inSettings) {
    Item item;
    synchronized (QUEUE) {
      item = QUEUE.poll();
    }
    if (item == null) return;
    Activity activity = settingsRef.get();
    AccessibilityService service = serviceRef.get();
    Context context;
    boolean overlay;
    if (inSettings && activity != null && activity == owner && !activity.isFinishing()) {
      context = activity;
      overlay = false;
    } else if (!inSettings && service != null && service == owner) {
      context = service;
      overlay = true;
    } else {
      // The screen that asked is gone; the queue is dropped and the next check starts
      // over, so nothing is lost.
      synchronized (QUEUE) {
        QUEUE.clear();
      }
      return;
    }
    if (dialog != null && dialog.isShowing()) return;
    if (!UserManagerCompat.isUserUnlocked(context)) return;
    try {
      AlertDialog next =
          new AlertDialog.Builder(context)
              .setTitle(R.string.title_admin_announcement)
              .setMessage(item.text)
              .setPositiveButton(
                  R.string.action_admin_announcement_close,
                  (d, w) -> {
                    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
                    prefs
                        .edit()
                        .putLong(KEY_SEEN, Math.max(item.updatedAt, prefs.getLong(KEY_SEEN, 0L)))
                        .apply();
                    MAIN.post(() -> showNext(owner, inSettings));
                  })
              .create();
      if (overlay) {
        Window window = next.getWindow();
        if (window == null) return;
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
      }
      next.setOnDismissListener(
          d -> {
            if (dialog == next) {
              dialog = null;
              dialogContext = null;
            }
          });
      next.show();
      dialog = next;
      dialogContext = context;
    } catch (RuntimeException e) {
      // Do not crash the screen reader if a device forbids accessibility overlays; the
      // announcement stays unseen and is offered again at the next check.
      Log.w(TAG, "Could not show announcement", e);
    }
  }

  private static void closeDialog() {
    AlertDialog old = dialog;
    dialog = null;
    dialogContext = null;
    if (old != null) {
      try {
        old.dismiss();
      } catch (RuntimeException e) {
        Log.w(TAG, "Could not dismiss announcement", e);
      }
    }
  }
}
