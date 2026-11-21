package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/**
 * Bộ điều phối khôi phục tiêu điểm duy nhất có hàng đợi thử lại ngắn (Single Arbitrator + Bounded
 * Retry).
 *
 * <p>Giải quyết trực tiếp hai nút thắt trong nhật ký Meridian:
 * <ul>
 *   <li>{@code FocusActorForScreen: Do not restore focus from the invalid node null} khi cây UI mới
 *       chưa dựng xong lúc vừa chuyển màn hình.</li>
 *   <li>{@code EnsureOnScreen: App UI (...) is not stable so stop searching focus on it} khiến yêu
 *       cầu khôi phục bị bỏ hẳn mà không thử lại sau khi UI ổn định.</li>
 * </ul>
 *
 * <p>Mọi yêu cầu chờ sẽ tự hết hạn sau {@value #DEFAULT_RETRY_WINDOW_MS} ms và <strong>tự hủy ngay
 * lập tức</strong> khi người dùng chạm tay hoặc vuốt ({@link #cancelOnUserInteraction()}).
 */
public final class FocusRestoreCoordinator {

  private static final String TAG = "FocusMemoryV2Coord";
  public static final long DEFAULT_RETRY_WINDOW_MS = 450L;
  private static final int MAX_ATTEMPTS = 6;

  private static final class PendingRestore {
    @NonNull final SemanticScreenKey screenKey;
    final long deadlineUptimeMs;
    int attempts;

    PendingRestore(@NonNull SemanticScreenKey screenKey, long deadlineUptimeMs) {
      this.screenKey = screenKey;
      this.deadlineUptimeMs = deadlineUptimeMs;
      this.attempts = 0;
    }
  }

  @NonNull private final ScreenFocusMemoryStore store;
  private final long retryWindowMs;
  @Nullable private PendingRestore pendingRestore;
  @Nullable private SemanticScreenKey lastCompletedScreenKey;

  public FocusRestoreCoordinator(@NonNull ScreenFocusMemoryStore store) {
    this(store, DEFAULT_RETRY_WINDOW_MS);
  }

  public FocusRestoreCoordinator(@NonNull ScreenFocusMemoryStore store, long retryWindowMs) {
    this.store = store;
    this.retryWindowMs = retryWindowMs;
  }

  /**
   * Kích hoạt trạng thái chờ thử lại khi lần khôi phục đầu tiên trên {@code screenKey} chưa tìm
   * thấy node (do UI đang tải hoặc chưa ổn định).
   */
  public synchronized void armPendingRestore(
      @Nullable SemanticScreenKey screenKey, long nowUptimeMs) {
    if (screenKey == null) {
      return;
    }
    if (store.lookup(screenKey) == null) {
      return;
    }
    long now = nowUptimeMs > 0 ? nowUptimeMs : SystemClock.uptimeMillis();
    pendingRestore = new PendingRestore(screenKey, now + retryWindowMs);
    LogUtils.d(TAG, "Armed pending restore for %s (window=%dms)", screenKey, retryWindowMs);
  }

  /**
   * Hủy ngay yêu cầu đang chờ khi người dùng chạm tay, vuốt, hoặc điều hướng chủ động.
   */
  public synchronized void cancelOnUserInteraction() {
    if (pendingRestore != null) {
      LogUtils.d(TAG, "Cancelled pending restore on user interaction: %s", pendingRestore.screenKey);
      pendingRestore = null;
    }
  }

  /**
   * Đánh dấu đã khôi phục xong cho một màn hình để tránh khôi phục lặp trên các sự kiện vẽ lại kế tiếp.
   */
  public synchronized void markRestored(@Nullable SemanticScreenKey screenKey) {
    lastCompletedScreenKey = screenKey;
    pendingRestore = null;
  }

  public synchronized boolean hasPendingRestore() {
    return pendingRestore != null;
  }

  /**
   * Thử hoàn tất yêu cầu khôi phục đang chờ (hoặc khôi phục khi vừa đổi pane ngữ nghĩa) trong
   * {@code ensureFocusOnScreen}.
   *
   * @return node cần đưa tiêu điểm tới, hoặc {@code null} nếu không cần can thiệp.
   */
  @Nullable
  public synchronized AccessibilityNodeInfoCompat tryResolvePendingOrPaneChange(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable AccessibilityNodeInfoCompat currentFocus,
      @Nullable ScreenState screenState,
      int windowId,
      long nowUptimeMs) {
    if (root == null) {
      return null;
    }
    long now = nowUptimeMs > 0 ? nowUptimeMs : SystemClock.uptimeMillis();
    SemanticScreenKey currentKey = SemanticScreenKey.create(root, screenState, windowId);
    if (currentKey == null) {
      return null;
    }

    // 1. Nếu có yêu cầu đang chờ nhưng đã hết hạn hoặc vượt quá số lần thử -> dọn sạch.
    if (pendingRestore != null) {
      if (now > pendingRestore.deadlineUptimeMs || pendingRestore.attempts >= MAX_ATTEMPTS) {
        LogUtils.d(TAG, "Pending restore expired for %s", pendingRestore.screenKey);
        pendingRestore = null;
      } else if (!pendingRestore.screenKey.equals(currentKey)) {
        // Màn hình đã chuyển sang trang khác -> cập nhật nếu trang mới cũng có bản ghi.
        if (store.lookup(currentKey) != null) {
          pendingRestore = new PendingRestore(currentKey, now + retryWindowMs);
        } else {
          pendingRestore = null;
        }
      }
    }

    // 2. Nếu chưa có pending nhưng người dùng vừa chuyển sang một màn hình ngữ nghĩa khác
    //    (ví dụ nhấn Back từ phòng chat ra danh sách chat trong cùng windowId) -> thử khôi phục 1 lần.
    boolean isPaneTransition =
        pendingRestore == null
            && !currentKey.equals(lastCompletedScreenKey)
            && store.lookup(currentKey) != null;

    if (pendingRestore == null && !isPaneTransition) {
      return null;
    }

    ScreenFocusMemoryStore.MemoryEntry entry = store.lookup(currentKey);
    if (entry == null) {
      pendingRestore = null;
      return null;
    }

    // Nếu tiêu điểm hiện tại đã nằm đúng trên phần tử đã nhớ -> đánh dấu hoàn tất, không giật focus.
    if (currentFocus != null && entry.fingerprint.matchesNode(currentFocus)) {
      markRestored(currentKey);
      return null;
    }

    if (pendingRestore != null) {
      pendingRestore.attempts++;
    }

    AccessibilityNodeInfoCompat target = entry.fingerprint.findBestMatch(root);
    if (target != null) {
      LogUtils.d(TAG, "Resolved restore on %s -> %s", currentKey, entry.fingerprint);
      markRestored(currentKey);
      return target;
    }

    // Nếu là lần đầu phát hiện đổi pane mà cây danh sách chưa tải xong -> bật hàng đợi thử lại ngắn.
    if (isPaneTransition) {
      armPendingRestore(currentKey, now);
    }
    return null;
  }
}
