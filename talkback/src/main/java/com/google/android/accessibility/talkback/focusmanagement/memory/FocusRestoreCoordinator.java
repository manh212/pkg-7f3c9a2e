package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/**
 * Bộ điều phối khôi phục tiêu điểm duy nhất có hàng đợi thử lại ngắn (Single Arbitrator + Bounded
 * Retry) — phiên bản v2.1.
 *
 * <p>Các cải tiến trực tiếp từ nhật ký {@code meridian-diagnostics-20261121-111115.txt}:
 * <ul>
 *   <li>Tăng cửa sổ chờ mặc định từ {@code 450 ms} lên {@code 900 ms} (tối đa {@code 10} lần thử)
 *       để ứng dụng nặng như Zalo/MIUI/WebView kịp dựng xong cây UI, đồng thời vẫn tự hủy tức thì
 *       khi người dùng chạm hoặc vuốt.</li>
 *   <li>Chống giật ngược tiêu điểm khi tiêu đề cửa sổ nhấp nháy tạm thời (ví dụ Chrome đổi {@code
 *       "Chrome: Chat — Muse"} -> {@code "Chrome: Muse"} sau 400 ms): nếu cùng cửa sổ vừa khôi phục
 *       thành công trong {@value #TITLE_FLICKER_GUARD_MS} ms và chữ ký cấu trúc không đổi, bỏ qua
 *       {@code isPaneTransition}.</li>
 *   <li>Cung cấp cửa sổ bảo vệ {@link #shouldSuppressInputFocusSync(int, long)} ({@value
 *       #INPUT_SYNC_SUPPRESSION_MS} ms) để chặn {@code FOCUS_SYNCHRONIZATION} của ô {@code EditText}
 *       cướp tiêu điểm ngay sau khi {@code RESTORED_LAST_FOCUS} vừa thành công (như lỗi Zalo lúc
 *       11:01:40.778).</li>
 * </ul>
 */
public final class FocusRestoreCoordinator {

  private static final String TAG = "FocusMemoryV2Coord";
  public static final long DEFAULT_RETRY_WINDOW_MS = 900L;
  public static final long INPUT_SYNC_SUPPRESSION_MS = 750L;
  public static final long TITLE_FLICKER_GUARD_MS = 1200L;
  private static final int MAX_ATTEMPTS = 10;
  private static final int MAX_EXTERNAL_HIJACK_RECOVERIES = 2;

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
  private int lastRestoredWindowId = -1;
  private long lastRestoredAtUptimeMs = 0L;
  private int externalHijackRecoveries = 0;

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
  private static boolean isTransientPopupKey(@NonNull SemanticScreenKey key) {
    String desc = key.toString();
    return desc.contains("win='popup window'") && desc.contains("sig=''");
  }

  public synchronized void armPendingRestore(
      @Nullable SemanticScreenKey screenKey, long nowUptimeMs) {
    if (screenKey == null || isTransientPopupKey(screenKey)) {
      return;
    }
    if (store.lookup(screenKey) == null) {
      return;
    }
    long now = resolveNow(nowUptimeMs);
    pendingRestore = new PendingRestore(screenKey, now + retryWindowMs);
    LogUtils.d(TAG, "Armed pending restore for %s (window=%dms)", screenKey, retryWindowMs);
  }

  /**
   * Hủy ngay yêu cầu đang chờ và tắt cửa sổ chặn đồng bộ input khi người dùng chạm tay hoặc vuốt.
   */
  public synchronized void cancelOnUserInteraction() {
    if (pendingRestore != null) {
      LogUtils.d(TAG, "Cancelled pending restore on user interaction: %s", pendingRestore.screenKey);
      pendingRestore = null;
    }
    lastRestoredAtUptimeMs = 0L;
    externalHijackRecoveries = 0;
  }

  /**
   * Đánh dấu đã khôi phục xong cho một màn hình (tương thích ngược với v2.0).
   */
  public synchronized void markRestored(@Nullable SemanticScreenKey screenKey) {
    markRestored(screenKey, -1, 0L);
  }

  /**
   * Đánh dấu đã khôi phục xong cho một màn hình kèm {@code windowId} và thời điểm để kích hoạt
   * bảo vệ chống cướp tiêu điểm bởi {@code EditText} và chống nhấp nháy tiêu đề trang web.
   */
  public synchronized void markRestored(
      @Nullable SemanticScreenKey screenKey, int windowId, long nowUptimeMs) {
    lastCompletedScreenKey = screenKey;
    lastRestoredWindowId = windowId;
    lastRestoredAtUptimeMs = resolveNow(nowUptimeMs);
    externalHijackRecoveries = 0;
    pendingRestore = null;
  }

  /**
   * Kiểm tra xem có nên bỏ qua sự kiện {@code FOCUS_SYNCHRONIZATION} (từ {@code TYPE_VIEW_FOCUSED}
   * trên {@code EditText}) hay không vì vừa khôi phục thành công tiêu điểm đã nhớ trên cùng cửa sổ.
   */
  public synchronized boolean shouldSuppressInputFocusSync(int windowId, long nowUptimeMs) {
    if (lastRestoredAtUptimeMs <= 0L) {
      return false;
    }
    if (lastRestoredWindowId >= 0 && windowId >= 0 && lastRestoredWindowId != windowId) {
      return false;
    }
    long now = resolveNow(nowUptimeMs);
    return now >= lastRestoredAtUptimeMs
        && (now - lastRestoredAtUptimeMs) <= INPUT_SYNC_SUPPRESSION_MS;
  }

  /**
   * Kiểm tra xem tiêu điểm hiện tại có bị ứng dụng (như Messenger) tự ý kéo sang {@code EditText}
   * ngay sau khi vừa khôi phục {@code RESTORED_LAST_FOCUS} hay không.
   */
  synchronized boolean isGuardedEditTextHijack(
      @NonNull SemanticScreenKey currentKey, int windowId, long now) {
    return currentKey.equals(lastCompletedScreenKey)
        && shouldSuppressInputFocusSync(windowId, now)
        && externalHijackRecoveries < MAX_EXTERNAL_HIJACK_RECOVERIES;
  }

  public synchronized boolean hasPendingRestore() {
    return pendingRestore != null;
  }

  /**
   * Kiểm tra xem {@code currentKey} có phải chỉ là nhấp nháy tiêu đề tạm thời trên cùng cửa sổ vừa
   * khôi phục hay không (gói package-private để unit test kiểm chứng trực tiếp).
   */
  synchronized boolean isGuardedTitleFlicker(
      @NonNull SemanticScreenKey currentKey, int windowId, long now) {
    if (lastCompletedScreenKey == null || lastRestoredAtUptimeMs <= 0L) {
      return false;
    }
    if (lastRestoredWindowId >= 0 && windowId >= 0 && lastRestoredWindowId != windowId) {
      return false;
    }
    long elapsed = now - lastRestoredAtUptimeMs;
    if (elapsed < 0 || elapsed > TITLE_FLICKER_GUARD_MS) {
      return false;
    }
    return currentKey.isLikelyTitleFlickerOf(lastCompletedScreenKey);
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
    if (root == null || store.isEmpty()) {
      return null;
    }
    long now = resolveNow(nowUptimeMs);
    SemanticScreenKey currentKey = SemanticScreenKey.create(root, screenState, windowId);
    if (currentKey == null || isTransientPopupKey(currentKey)) {
      return null;
    }

    // 1. Nếu có yêu cầu đang chờ nhưng đã hết hạn hoặc vượt quá số lần thử -> dọn sạch.
    if (pendingRestore != null) {
      if (now > pendingRestore.deadlineUptimeMs || pendingRestore.attempts >= MAX_ATTEMPTS) {
        LogUtils.d(TAG, "Pending restore expired for %s", pendingRestore.screenKey);
        pendingRestore = null;
      } else if (!pendingRestore.screenKey.equals(currentKey)) {
        if (store.lookup(currentKey) != null) {
          pendingRestore = new PendingRestore(currentKey, now + retryWindowMs);
        } else {
          pendingRestore = null;
        }
      }
    }

    // 2. Bỏ qua nhấp nháy tiêu đề tạm thời (ví dụ Chrome đổi title khi reconnect) nếu không có
    //    pendingRestore đang chủ động chờ.
    if (pendingRestore == null && isGuardedTitleFlicker(currentKey, windowId, now)) {
      LogUtils.d(TAG, "Ignored transient title flicker on %s", currentKey);
      return null;
    }

    // 3. Nếu chưa có pending nhưng người dùng vừa chuyển sang một màn hình ngữ nghĩa khác
    //    (ví dụ nhấn Back từ phòng chat ra danh sách chat trong cùng windowId) -> thử khôi phục.
    boolean isPaneTransition =
        pendingRestore == null
            && !currentKey.equals(lastCompletedScreenKey)
            && store.lookup(currentKey) != null;
    // Nếu người dùng đang trực tiếp nhập liệu / đọc chính tả (DICTATE) trong ô EditText đã có
    // input focus, không tự ý kéo tiêu điểm ngược về tin nhắn cũ.
    if (pendingRestore == null
        && currentFocus != null
        && currentFocus.isEditable()
        && currentFocus.isFocused()
        && !isGuardedEditTextHijack(currentKey, windowId, now)) {
      markRestored(currentKey, windowId, now);
      return null;
    }
    boolean isEditTextHijack =
        pendingRestore == null
            && currentFocus != null
            && currentFocus.isEditable()
            && isGuardedEditTextHijack(currentKey, windowId, now);

    if (pendingRestore == null && !isPaneTransition && !isEditTextHijack) {
      return null;
    }

    ScreenFocusMemoryStore.MemoryEntry entry = store.lookup(currentKey);
    if (entry == null) {
      pendingRestore = null;
      return null;
    }

    // Nếu tiêu điểm hiện tại đã nằm đúng trên phần tử đã nhớ -> đánh dấu hoàn tất, không giật focus.
    if (currentFocus != null && entry.fingerprint.matchesNode(currentFocus)) {
      markRestored(currentKey, windowId, now);
      return null;
    }

    if (pendingRestore != null) {
      pendingRestore.attempts++;
    }

    AccessibilityNodeInfoCompat target = entry.fingerprint.findBestMatch(root);
    if (target != null && target.refresh() && target.isVisibleToUser()) {
      LogUtils.d(TAG, "Resolved restore on %s -> %s", currentKey, entry.fingerprint);
      if (isEditTextHijack) {
        externalHijackRecoveries++;
        pendingRestore = null;
      } else {
        markRestored(currentKey, windowId, now);
      }
      return target;
    }

    if (isPaneTransition) {
      armPendingRestore(currentKey, now);
    }
    return null;
  }

  private static long resolveNow(long nowUptimeMs) {
    return nowUptimeMs > 0L ? nowUptimeMs : SystemClock.uptimeMillis();
  }
}
