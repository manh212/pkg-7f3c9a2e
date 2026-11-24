package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;

/**
 * Coordinates deferred fast-transition focus restore retries so returning windows have a brief
 * grace window to bind recycled list items before falling back to the first element on screen.
 */
public final class WindowFocusRestoreOrchestrator {
  public static final long FAST_RESTORE_RETRY_DELAY_MS = 120L;
  public static final long FAST_TRANSITION_WINDOW_MS = 150L;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean inDeferredRetry;

  /**
   * Schedules a single short retry when a fast window transition arms a pending semantic restore.
   * Returns {@code true} if the retry was scheduled (caller should suppress immediate fallback).
   */
  public boolean scheduleFastRestoreRetry(
      boolean armed,
      long transitionStartTimeMs,
      long nowMs,
      @NonNull FocusRestoreCoordinator coordinator,
      @NonNull Runnable retryAction) {
    if (!armed || inDeferredRetry || (nowMs - transitionStartTimeMs) >= FAST_TRANSITION_WINDOW_MS) {
      return false;
    }
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(
        () -> {
          if (!coordinator.hasPendingRestore(SystemClock.uptimeMillis())) {
            return;
          }
          inDeferredRetry = true;
          try {
            retryAction.run();
          } finally {
            inDeferredRetry = false;
          }
        },
        FAST_RESTORE_RETRY_DELAY_MS);
    return true;
  }

  /** Cancels any pending deferred retry once focus has been restored or superseded. */
  public void cancelPendingRetry() {
    handler.removeCallbacksAndMessages(null);
  }
}
