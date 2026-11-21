package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kho lưu trữ tiêu điểm theo {@link SemanticScreenKey} (bộ nhớ tập trung cho Focus Memory v2).
 *
 * <p>Nguyên tắc cốt lõi:
 * <ul>
 *   <li>Dung lượng đủ lớn ({@value #MAX_ENTRIES} màn hình theo LRU) thay vì giới hạn 10 mục dễ bị
 *       đẩy khỏi cache khi chuyển qua lại nhiều ứng dụng.</li>
 *   <li><strong>Chỉ ghi đè từ hành động có chủ đích của người dùng</strong> (vuốt, chạm khám phá,
 *       điều hướng). Không cho phép tiêu điểm mặc định do app tự đặt hoặc fallback tự động ghi đè
 *       mất điểm nhớ cũ.</li>
 *   <li>Hỗ trợ tra cứu 2 tầng: khớp chính xác {@code SemanticScreenKey} trước, sau đó fallback sang
 *       khóa không kèm {@code structureSignature} nếu thanh tiêu đề thay đổi nhẹ.</li>
 * </ul>
 */
public final class ScreenFocusMemoryStore {

  private static final String TAG = "FocusMemoryV2Store";
  private static final int MAX_ENTRIES = 64;

  /**
   * Mục nhớ tiêu điểm cho một màn hình ngữ nghĩa.
   */
  public static final class MemoryEntry {
    @NonNull public final SemanticScreenKey screenKey;
    @NonNull public final FocusTargetFingerprint fingerprint;
    public final long updatedAtUptimeMs;

    MemoryEntry(
        @NonNull SemanticScreenKey screenKey,
        @NonNull FocusTargetFingerprint fingerprint,
        long updatedAtUptimeMs) {
      this.screenKey = screenKey;
      this.fingerprint = fingerprint;
      this.updatedAtUptimeMs = updatedAtUptimeMs;
    }
  }

  private final LinkedHashMap<SemanticScreenKey, MemoryEntry> exactStore =
      new LinkedHashMap<SemanticScreenKey, MemoryEntry>(MAX_ENTRIES, 0.75f, /* accessOrder= */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SemanticScreenKey, MemoryEntry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  private final LinkedHashMap<SemanticScreenKey, MemoryEntry> relaxedStore =
      new LinkedHashMap<SemanticScreenKey, MemoryEntry>(MAX_ENTRIES, 0.75f, /* accessOrder= */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SemanticScreenKey, MemoryEntry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  /**
   * Kiểm tra nguồn gốc hành động tiêu điểm có phải là ý định của người dùng hay không.
   *
   * <p>Chỉ các hành động điều hướng logic, chạm khám phá hoặc tương tác chủ động mới được phép
   * cập nhật điểm nhớ. Các nguồn tự động như {@code SCREEN_STATE_CHANGE} hay {@code ENSURE_ON_SCREEN}
   * không được ghi đè điểm nhớ hiện có.
   */
  public static boolean isUserIntentionalSource(@Nullable FocusActionInfo info) {
    if (info == null) {
      return false;
    }
    switch (info.sourceAction) {
      case FocusActionInfo.LOGICAL_NAVIGATION:
      case FocusActionInfo.TOUCH_EXPLORATION:
      case FocusActionInfo.MANUAL_SCROLL:
        return true;
      default:
        return false;
    }
  }

  /**
   * Ghi nhận tiêu điểm mới vào bộ nhớ nếu xuất phát từ ý định người dùng.
   */
  public synchronized void recordIfUserIntentional(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable AccessibilityNodeInfoCompat focusedNode,
      @Nullable FocusActionInfo focusActionInfo,
      @Nullable ScreenState screenState,
      int windowId,
      long actionTimeUptimeMs) {
    if (!isUserIntentionalSource(focusActionInfo)) {
      return;
    }
    recordUnconditionally(root, focusedNode, screenState, windowId, actionTimeUptimeMs);
  }

  /**
   * Ghi nhận trực tiếp một điểm nhớ (dùng cho kiểm thử hoặc khi người dùng xác nhận tương tác).
   */
  public synchronized void recordUnconditionally(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable AccessibilityNodeInfoCompat focusedNode,
      @Nullable ScreenState screenState,
      int windowId,
      long actionTimeUptimeMs) {
    if (root == null || focusedNode == null) {
      return;
    }
    SemanticScreenKey key = SemanticScreenKey.create(root, screenState, windowId);
    if (key == null) {
      return;
    }
    long now = actionTimeUptimeMs > 0 ? actionTimeUptimeMs : SystemClock.uptimeMillis();
    FocusTargetFingerprint fp = FocusTargetFingerprint.capture(focusedNode, now);
    if (fp == null) {
      return;
    }

    MemoryEntry entry = new MemoryEntry(key, fp, now);
    exactStore.put(key, entry);
    if (key.hasStructureSignature()) {
      relaxedStore.put(key.withoutStructureSignature(), entry);
    }
    LogUtils.d(TAG, "Recorded %s -> %s", key, fp);
  }

  /**
   * Tra cứu bản ghi nhớ cho khóa màn hình hiện tại (ưu tiên khóa đầy đủ, fallback sang khóa rút gọn).
   */
  @Nullable
  public synchronized MemoryEntry lookup(@Nullable SemanticScreenKey key) {
    if (key == null) {
      return null;
    }
    MemoryEntry exact = exactStore.get(key);
    if (exact != null) {
      return exact;
    }
    if (key.hasStructureSignature()) {
      return relaxedStore.get(key.withoutStructureSignature());
    }
    return null;
  }

  /**
   * Tìm trực tiếp node cần khôi phục trên cây {@code root} dựa theo bộ nhớ ngữ nghĩa.
   */
  @Nullable
  public AccessibilityNodeInfoCompat findRestoreNode(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable ScreenState screenState,
      int windowId) {
    if (root == null) {
      return null;
    }
    SemanticScreenKey key = SemanticScreenKey.create(root, screenState, windowId);
    MemoryEntry entry = lookup(key);
    if (entry == null) {
      return null;
    }
    AccessibilityNodeInfoCompat matched = entry.fingerprint.findBestMatch(root);
    LogUtils.d(
        TAG,
        "Restore lookup for %s -> %s",
        key,
        matched != null ? "HIT(" + entry.fingerprint + ")" : "MISS(" + entry.fingerprint + ")");
    return matched;
  }

  public synchronized void clear() {
    exactStore.clear();
    relaxedStore.clear();
  }

  public synchronized int size() {
    return exactStore.size();
  }
}
