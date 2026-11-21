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
 * Kho lưu trữ tiêu điểm theo {@link SemanticScreenKey} (bộ nhớ tập trung cho Focus Memory v2.1).
 *
 * <p>Bổ sung {@link #isEmpty()} và {@link #hasAnyEntryForPackage(CharSequence)} (tương thích hướng
 * tối ưu commit {@code b101c86}) để bỏ qua thao tác lấy cây {@code root} khi chưa có bản ghi nào.
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
      MemoryEntry relaxed = relaxedStore.get(key.withoutStructureSignature());
      if (relaxed != null && canUseRelaxedMatch(key, relaxed)) {
        return relaxed;
      }
    }
    return null;
  }

  private static boolean canUseRelaxedMatch(
      @NonNull SemanticScreenKey key, @NonNull MemoryEntry entry) {
    if (!key.getPaneTitle().isEmpty()) {
      return true;
    }
    return sharesDistinctiveSignatureToken(
        key.getStructureSignature(), entry.screenKey.getStructureSignature());
  }

  private static boolean sharesDistinctiveSignatureToken(
      @NonNull String currentSig, @NonNull String storedSig) {
    if (currentSig.isEmpty() || storedSig.isEmpty()) {
      return false;
    }
    String[] currentTokens = currentSig.split("\\|");
    String[] storedTokens = storedSig.split("\\|");
    for (String currentToken : currentTokens) {
      if (!currentToken.startsWith("hdr:") && !currentToken.startsWith("sel:")) {
        continue;
      }
      for (String storedToken : storedTokens) {
        if (currentToken.equals(storedToken)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Kiểm tra nhanh xem kho có bản ghi nào không để tránh gọi IPC {@code getRootCompat()} không cần
   * thiết khi kho đang rỗng.
   */
  public synchronized boolean isEmpty() {
    return exactStore.isEmpty();
  }

  /**
   * Kiểm tra nhanh xem kho có ít nhất một bản ghi cho {@code packageName} hay không.
   */
  public synchronized boolean hasAnyEntryForPackage(@Nullable CharSequence packageName) {
    if (exactStore.isEmpty() || packageName == null || packageName.length() == 0) {
      return false;
    }
    String target = packageName.toString().trim().toLowerCase(java.util.Locale.ROOT);
    for (SemanticScreenKey key : exactStore.keySet()) {
      if (target.equals(key.getPackageName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tìm trực tiếp node cần khôi phục trên cây {@code root} dựa theo bộ nhớ ngữ nghĩa.
   */
  @Nullable
  public AccessibilityNodeInfoCompat findRestoreNode(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable ScreenState screenState,
      int windowId) {
    if (root == null || isEmpty()) {
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
