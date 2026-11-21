package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Khóa màn hình theo ngữ nghĩa (Semantic Screen Key), tách khỏi {@code windowId} vốn thay đổi mỗi
 * khi Android tạo lại Activity/cửa sổ.
 *
 * <p>Khóa kết hợp: {@code packageName + windowTitle + paneTitle + cấu trúc chữ ký thanh điều hướng /
 * tab đang chọn}. Nhờ vậy:
 * <ul>
 *   <li>Quay lại cùng một ứng dụng sau khi chuyển app vẫn khớp khóa cũ dù {@code windowId} đã đổi.</li>
 *   <li>Hai màn hình con dùng chung một {@code windowId} (như danh sách chat vs. phòng chat trong
 *       Nekogram/Zalo, hoặc các trang Settings) vẫn có khóa tách biệt nhờ tiêu đề/tab signature.</li>
 *   <li>Dialog/popup không có tiêu đề lẫn ngữ cảnh sẽ fallback về {@code windowId} để tránh đè
 *       lên màn hình chính.</li>
 * </ul>
 */
public final class SemanticScreenKey {

  private static final int MAX_SIGNATURE_SCAN_NODES = 48;
  private static final int MAX_SIGNATURE_TOKENS = 4;

  @NonNull private final String packageName;
  @NonNull private final String windowTitle;
  @NonNull private final String paneTitle;
  @NonNull private final String structureSignature;
  private final int fallbackWindowId;

  private SemanticScreenKey(
      @NonNull String packageName,
      @NonNull String windowTitle,
      @NonNull String paneTitle,
      @NonNull String structureSignature,
      int fallbackWindowId) {
    this.packageName = packageName;
    this.windowTitle = windowTitle;
    this.paneTitle = paneTitle;
    this.structureSignature = structureSignature;
    this.fallbackWindowId = fallbackWindowId;
  }

  /**
   * Tạo khóa ngữ nghĩa từ {@link AccessibilityNodeInfoCompat} gốc và {@link ScreenState}.
   */
  @Nullable
  public static SemanticScreenKey create(
      @Nullable AccessibilityNodeInfoCompat root,
      @Nullable ScreenState screenState,
      int windowId) {
    if (root == null) {
      return null;
    }

    String pkg = safeLower(root.getPackageName());
    String winTitle = "";
    String pane = "";

    if (screenState != null) {
      winTitle = normalizeTitle(screenState.getWindowTitle(windowId));
      pane = normalizeTitle(screenState.getAccessibilityPaneTitle(windowId));
      if (pkg.isEmpty()) {
        AccessibilityWindowInfo active = screenState.getActiveWindow();
        if (active != null && active.getId() == windowId && active.getTitle() != null) {
          winTitle = normalizeTitle(active.getTitle());
        }
      }
    }

    String sig = computeStructureSignature(root);

    // Nếu cả package, title, pane và signature đều rỗng (dialog/overlay ẩn danh),
    // giữ fallbackWindowId để không gom chung vào một khóa rỗng duy nhất.
    int fbWinId = (pkg.isEmpty() && winTitle.isEmpty() && pane.isEmpty() && sig.isEmpty())
        ? windowId
        : -1;

    return new SemanticScreenKey(pkg, winTitle, pane, sig, fbWinId);
  }

  /**
   * Tạo khóa rút gọn không kèm {@code structureSignature} để tra cứu fallback khi tab/header thay
   * đổi nhẹ trong cùng màn hình.
   */
  @NonNull
  public SemanticScreenKey withoutStructureSignature() {
    return new SemanticScreenKey(packageName, windowTitle, paneTitle, "", fallbackWindowId);
  }

  @NonNull
  public String getPackageName() {
    return packageName;
  }

  @NonNull
  public String getWindowTitle() {
    return windowTitle;
  }

  @NonNull
  public String getPaneTitle() {
    return paneTitle;
  }

  @NonNull
  public String getStructureSignature() {
    return structureSignature;
  }

  public boolean hasStructureSignature() {
    return !structureSignature.isEmpty();
  }

  /**
   * Quét nhanh tối đa {@value #MAX_SIGNATURE_SCAN_NODES} node đầu tiên theo BFS để trích xuất:
   * tiêu đề toolbar/actionbar (heading/toolbar title) và các tab đang được chọn (`isSelected()`).
   * Chi phí thấp (O(1) bị chặn trên) và không phụ thuộc vào nội dung cuộn bên dưới.
   */
  @NonNull
  private static String computeStructureSignature(@NonNull AccessibilityNodeInfoCompat root) {
    List<String> tokens = new ArrayList<>(MAX_SIGNATURE_TOKENS);
    List<AccessibilityNodeInfoCompat> queue = new ArrayList<>(MAX_SIGNATURE_SCAN_NODES);
    queue.add(root);

    int scanned = 0;
    for (int i = 0; i < queue.size() && scanned < MAX_SIGNATURE_SCAN_NODES; i++) {
      AccessibilityNodeInfoCompat node = queue.get(i);
      scanned++;
      if (node == null) {
        continue;
      }

      if (tokens.size() < MAX_SIGNATURE_TOKENS) {
        String token = extractStructuralToken(node);
        if (!token.isEmpty() && !tokens.contains(token)) {
          tokens.add(token);
        }
      }

      int childCount = node.getChildCount();
      for (int c = 0; c < childCount && queue.size() < MAX_SIGNATURE_SCAN_NODES; c++) {
        AccessibilityNodeInfoCompat child = node.getChild(c);
        if (child != null) {
          queue.add(child);
        }
      }
    }

    if (tokens.isEmpty()) {
      return "";
    }
    return TextUtils.join("|", tokens);
  }

  @NonNull
  private static String extractStructuralToken(@NonNull AccessibilityNodeInfoCompat node) {
    String viewId = safeLower(node.getViewIdResourceName());
    String label = normalizeTitle(extractDirectLabel(node));

    // 1. Tab đang chọn (BottomNavigation, TabLayout, v.v.)
    if (node.isSelected() && !label.isEmpty()) {
      return "sel:" + truncate(label, 32);
    }

    // 2. Tiêu đề thanh công cụ / tiêu đề phòng chat / heading đầu màn hình
    if (!label.isEmpty()
        && (node.isHeading()
            || viewId.contains("toolbar")
            || viewId.contains("action_bar_title")
            || viewId.contains("header_title")
            || viewId.contains("chat_title")
            || viewId.contains("title_text"))) {
      return "hdr:" + truncate(label, 32);
    }

    return "";
  }

  @Nullable
  private static CharSequence extractDirectLabel(@NonNull AccessibilityNodeInfoCompat node) {
    CharSequence text = node.getText();
    if (!TextUtils.isEmpty(text)) {
      return text;
    }
    return node.getContentDescription();
  }

  @NonNull
  private static String normalizeTitle(@Nullable CharSequence raw) {
    if (TextUtils.isEmpty(raw)) {
      return "";
    }
    String s = raw.toString().trim().replaceAll("\\s+", " ");
    // Loại bỏ số đếm động ở cuối (ví dụ "Tin nhắn (3)" -> "Tin nhắn")
    s = s.replaceAll("\\s*\\(\\d+\\+?\\)$", "");
    return s.toLowerCase(Locale.ROOT);
  }

  @NonNull
  private static String safeLower(@Nullable CharSequence raw) {
    return TextUtils.isEmpty(raw) ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
  }

  @NonNull
  private static String truncate(@NonNull String s, int maxLen) {
    return s.length() <= maxLen ? s : s.substring(0, maxLen);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SemanticScreenKey)) {
      return false;
    }
    SemanticScreenKey that = (SemanticScreenKey) o;
    return fallbackWindowId == that.fallbackWindowId
        && packageName.equals(that.packageName)
        && windowTitle.equals(that.windowTitle)
        && paneTitle.equals(that.paneTitle)
        && structureSignature.equals(that.structureSignature);
  }

  @Override
  public int hashCode() {
    return Objects.hash(packageName, windowTitle, paneTitle, structureSignature, fallbackWindowId);
  }

  @NonNull
  @Override
  public String toString() {
    return "SemanticScreenKey{pkg='"
        + packageName
        + "', win='"
        + windowTitle
        + "', pane='"
        + paneTitle
        + "', sig='"
        + structureSignature
        + "'"
        + (fallbackWindowId >= 0 ? ", fbWin=" + fallbackWindowId : "")
        + "}";
  }
}
