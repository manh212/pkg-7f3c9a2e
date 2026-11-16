package com.google.android.libraries.accessibility.utils.concurrent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager.BadTokenException;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Utils for thread checking and running items on the main thread. */
public class ThreadUtils {

  private static final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

  // Contains the mapping from the runnable provided to ThreadUtils via #runOnMainThreadDelayed to
  // the wrapped runnables posted to the mainThreadHandler by ThreadUtils. One runnable may be
  // posted several times, so each post gets its own wrapped runnable. We need to store this
  // information to remove the specified runnable when #removeCallbacks is called.
  // Guarded by wrappedRunnableMapLock: every access to the map and to the lists it holds runs
  // under this lock, because posts, removals and the wrappers' own cleanup run on any thread.
  private static final Object wrappedRunnableMapLock = new Object();

  private static final Map<Runnable, List<Runnable>> wrappedRunnableMap = new HashMap<>();

  /** Checks to see if the current thread is the main thread. */
  public static boolean isMainThread() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  /**
   * Runs the provided runnable on the main thread. If a BadTokenException or IllegalStateException
   * occurs while the shutdownable is not active, the error will be ignored.
   *
   * @param shutdownable The object queried to determine if the service is active
   * @param runnable The runnable to run on the main thread
   */
  public static void runOnMainThread(Shutdownable shutdownable, Runnable runnable) {
    if (isMainThread()) {
      getWrappedRunnable(shutdownable, runnable).run();
    } else {
      mainThreadHandler.post(getWrappedRunnable(shutdownable, runnable));
    }
  }

  /**
   * Runs the provided runnable on the main thread after a delay. If a BadTokenException or
   * IllegalStateException occurs while the shutdownable is not active, the error will be ignored.
   * Note, the runnable provided may be removed by calling #removeCallbacks.
   *
   * @param shutdownable The object queried to determine if the service is active
   * @param runnable The runnable to run on the main thread
   * @param delayMilliseconds The delay (in milliseconds) after which the runnable should be run
   */
  public static void runOnMainThreadDelayed(
      Shutdownable shutdownable, Runnable runnable, long delayMilliseconds) {
    Runnable wrappedRunnable = getWrappedRunnable(shutdownable, runnable);
    synchronized (wrappedRunnableMapLock) {
      List<Runnable> wrappedRunnables = wrappedRunnableMap.get(runnable);
      if (wrappedRunnables == null) {
        wrappedRunnables = new ArrayList<>();
        wrappedRunnableMap.put(runnable, wrappedRunnables);
      }
      wrappedRunnables.add(wrappedRunnable);
    }
    mainThreadHandler.postDelayed(wrappedRunnable, delayMilliseconds);
  }

  /**
   * Removes a runnable from the main thread handler's queue.
   *
   * @param runnable The runnable that should be removed from the main thread handler's queue
   */
  public static void removeCallbacks(Runnable runnable) {
    List<Runnable> wrappedRunnables;
    synchronized (wrappedRunnableMapLock) {
      wrappedRunnables = wrappedRunnableMap.remove(runnable);
    }
    if (wrappedRunnables != null) {
      for (Runnable wrappedRunnable : wrappedRunnables) {
        mainThreadHandler.removeCallbacks(wrappedRunnable);
      }
    }
  }

  /**
   * Removes a runnable from the main thread handler's queue. If token is null, all callbacks and
   * messages will be removed.
   *
   * @param token The object that should be removed from the main thread handler's queue
   */
  public static void removeCallbacksAndMessages(@Nullable Object token) {
    mainThreadHandler.removeCallbacksAndMessages(token);
  }

  /**
   * Logs the exception to logcat if AccessibilityService is not active. If the service is active,
   * the exception will be re-thrown.
   *
   * @param shutdownable The object queried to determine if the service is active
   * @param exception The exception that occurred
   * @param logOutputIfIgnored The output that will be logged to logcat if SwitchAccessService is
   *     not active or shutdownable is null
   */
  public static void ignoreExceptionIfShuttingDown(
      Shutdownable shutdownable, RuntimeException exception, String logOutputIfIgnored) {
    if (shutdownable == null || !shutdownable.isActive()) {
      Log.e(ThreadUtils.class.toString(), String.format("%s %s", logOutputIfIgnored, exception));
    } else {
      Log.d("ThreadUtils", "exception not ignored because service is active.");
      throw exception;
    }
  }

  private static Runnable getWrappedRunnable(Shutdownable shutdownable, Runnable runnable) {
    final Runnable[] self = new Runnable[1];
    self[0] =
        () -> {
          try {
            runnable.run();
          } catch (BadTokenException | IllegalStateException exception) {
            ignoreExceptionIfShuttingDown(
                shutdownable,
                exception,
                "Exception while trying to run runnable on main thread during shutdown. Ignoring.");
          } finally {
            synchronized (wrappedRunnableMapLock) {
              List<Runnable> wrappedRunnables = wrappedRunnableMap.get(runnable);
              if (wrappedRunnables != null) {
                wrappedRunnables.remove(self[0]);
                if (wrappedRunnables.isEmpty()) {
                  wrappedRunnableMap.remove(runnable);
                }
              }
            }
          }
        };
    return self[0];
  }

  /**
   * Queried to determine whether the calling service is active at the time the posted runnables are
   * run. This is used to ignore BadTokenExceptions when the service is not active.
   */
  public interface Shutdownable {
    /*
     * Returns {@code true} if the service is active.
     */
    boolean isActive();
  }
}
