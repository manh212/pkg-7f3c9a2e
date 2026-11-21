package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Khóa màn hình theo ngữ nghĩa (Semantic Screen Key), tách khỏi {@code windowId} vốn thay đổi mỗi
 * khi Android tạo lại Activity/cửa sổ.
 *
 * <p>Phiên bản v2.1 khắc phục các lỗi từ nhật ký thực tế:
 * <ul>
 *   <li>Loại bỏ hoàn toàn số đếm động (ví dụ {@code "💬tất cả 21 chat chưa đọc"} -> {@code "💬tất cả
 *       chat chưa đọc"}) để tránh đổi khóa mỗi khi có tin nhắn mới.</li>
 *   <li>Bổ sung chữ ký cấu trúc fallback ({@code id:...}, {@code mode:input}) khi ứng dụng (như
 *       Zalo) không gắn heading/selected tab, giúp phân biệt danh sách chat với phòng chat dù dùng
 *       chung một {@code windowId}.</li>
 *   <li>Không phụ thuộc {@code android.text.TextUtils} trong các hàm chuẩn hóa để unit test JVM
 *       chạy độc lập không cần Android runtime.</li>
 * </ul>
 */
public final class SemanticScreenKey {

  private static final int MAX_SIGNATURE_SCAN_NODES = 80;
  private static final int MAX_SIGNATURE_TOKENS = 4;

  // Loại bỏ số đếm động trong tiêu đề/tab Việt & Anh:
  // "(3)", "(99+)", "21 chat chưa đọc", "2 tin nhắn mới", "1 lượt nhắc", "5 unread", v.v.
  private static final Pattern DYNAMIC_COUNT_PATTERN =
      Pattern.compile(
          "(?iu)(\\(\\s*\\d+\\+?\\s*\\)|"
              + "\\b\\d+\\+?\\s+(?=chat\\s+chưa\\s+đọc|tin\\s+nhắn\\s+(mới|chưa\\s+đọc)|"
              + "lượt\\s+nhắc|hội\\s+thoại|thông\\s+báo|unread|new\\s+messages?|messages?)\\b|"
              + "\\s+\\d+\\+?$)");

  // Loại bỏ giá tiền, phần trăm, đồng hồ đếm ngược / giờ phút để không biến nội dung động thành
  // chữ ký màn hình trên các ứng dụng React Native (như Shopee).
  private static final Pattern DYNAMIC_VALUE_ONLY_PATTERN =
      Pattern.compile(
          "(?iu)^([₫đ$€£¥]?\\s*[\\d.,]+\\s*[₫đ$€£¥%kKmM]?|"
              + "\\d{1,2}:\\d{2}(:\\d{2})?|"
              + "[\\d\\s.,:/+%-]+)$");

  @NonNull private final String packageName;
  @NonNull private final String windowTitle;
  @NonNull private final String paneTitle;
  @NonNull private final String structureSignature;
  private final int fallbackWindowId;

  SemanticScreenKey(
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
      if (winTitle.isEmpty()) {
        AccessibilityWindowInfo active = screenState.getActiveWindow();
        if (active != null && active.getId() == windowId && active.getTitle() != null) {
          winTitle = normalizeTitle(active.getTitle());
        }
      }
    }

    String sig = computeStructureSignature(root);

    int fbWinId =
        (pkg.isEmpty() && winTitle.isEmpty() && pane.isEmpty() && sig.isEmpty()) ? windowId : -1;

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

  /**
   * Kiểm tra xem hai khóa có cùng một trang ngữ nghĩa hay không khi chỉ tiêu đề cửa sổ nhấp nháy
   * tạm thời (ví dụ Chrome đổi {@code "chrome: chat — muse"} <-> {@code "chrome: muse"} trong khi
   * {@code packageName} và {@code structureSignature} không đổi).
   */
  public boolean isLikelyTitleFlickerOf(@Nullable SemanticScreenKey other) {
    if (other == null) {
      return false;
    }
    if (!packageName.equals(other.packageName) || !paneTitle.equals(other.paneTitle)) {
      return false;
    }
    if (structureSignature.isEmpty() || !structureSignature.equals(other.structureSignature)) {
      return false;
    }
    return !windowTitle.equals(other.windowTitle);
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
   * tiêu đề toolbar/actionbar, tab đang chọn, và (nếu không có) dấu mốc cấu trúc phân biệt màn
   * hình con như ô nhập liệu chat hay nút thanh công cụ đặc trưng.
   */
  @NonNull
  private static String computeStructureSignature(@NonNull AccessibilityNodeInfoCompat root) {
    List<String> primaryTokens = new ArrayList<>(MAX_SIGNATURE_TOKENS);
    List<String> landmarkTokens = new ArrayList<>(2);
    List<String> fallbackHeaderTokens = new ArrayList<>(2);
    boolean hasEditableComposer = false;

    List<AccessibilityNodeInfoCompat> queue = new ArrayList<>(MAX_SIGNATURE_SCAN_NODES);
    queue.add(root);

    int scanned = 0;
    for (int i = 0; i < queue.size() && scanned < MAX_SIGNATURE_SCAN_NODES; i++) {
      AccessibilityNodeInfoCompat node = queue.get(i);
      scanned++;
      if (node == null) {
        continue;
      }

      if (node.isEditable() && node.isVisibleToUser()) {
        hasEditableComposer = true;
      }

      if (primaryTokens.size() < MAX_SIGNATURE_TOKENS) {
        String token = extractStructuralToken(node);
        if (!token.isEmpty() && !primaryTokens.contains(token)) {
          primaryTokens.add(token);
        }
      }

      if (landmarkTokens.size() < 2) {
        String landmark = extractFallbackLandmark(node);
        if (!landmark.isEmpty() && !landmarkTokens.contains(landmark)) {
          landmarkTokens.add(landmark);
        }
      }

      if (fallbackHeaderTokens.size() < 2 && node.isVisibleToUser() && !node.isEditable()) {
        String headerLabel = normalizeHeaderFallbackLabel(extractDirectLabel(node));
        if (!headerLabel.isEmpty()) {
          String topToken = "top:" + truncate(headerLabel, 28);
          if (!fallbackHeaderTokens.contains(topToken)) {
            fallbackHeaderTokens.add(topToken);
          }
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

    if (!primaryTokens.isEmpty()) {
      return joinWithPipe(primaryTokens);
    }

    // Fallback cho ứng dụng React Native / Flutter (như Shopee) không gắn heading/selected/viewId:
    // dùng tối đa 2 nhãn tiêu đề ngắn ổn định ở đầu cây duyệt để phân biệt các màn hình con.
    if (landmarkTokens.isEmpty() && !fallbackHeaderTokens.isEmpty()) {
      if (hasEditableComposer) {
        fallbackHeaderTokens.add("mode:input");
      }
      return joinWithPipe(fallbackHeaderTokens);
    }

    // Fallback khi màn hình không có heading hay selected tab (ví dụ Zalo danh sách chat vs phòng
    // chat): kết hợp landmark ID và trạng thái có ô nhập liệu để tách biệt màn hình con.
    if (hasEditableComposer) {
      landmarkTokens.add("mode:input");
    }
    return landmarkTokens.isEmpty() ? "" : joinWithPipe(landmarkTokens);
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
            || viewId.contains("conversation_title")
            || viewId.contains("title_text"))) {
      return "hdr:" + truncate(label, 32);
    }

    return "";
  }

  @NonNull
  private static String extractFallbackLandmark(@NonNull AccessibilityNodeInfoCompat node) {
    String viewId = safeLower(node.getViewIdResourceName());
    if (viewId.isEmpty()) {
      return "";
    }
    int slash = viewId.lastIndexOf('/');
    String idName = slash >= 0 && slash + 1 < viewId.length() ? viewId.substring(slash + 1) : viewId;
    if (idName.contains("action_bar")
        || idName.contains("search_btn")
        || idName.contains("chat_input")
        || idName.contains("message_input")
        || idName.contains("call_")
        || idName.contains("tab_")) {
      return "id:" + truncate(idName, 28);
    }
    return "";
  }

  @Nullable
  private static CharSequence extractDirectLabel(@NonNull AccessibilityNodeInfoCompat node) {
    CharSequence text = node.getText();
    if (!isNullOrEmpty(text)) {
      return text;
    }
    return node.getContentDescription();
  }

  /**
   * Chuẩn hóa nhãn tiêu đề fallback cho các ứng dụng không có heading/viewId ổn định (như Shopee).
   * Trả về chuỗi rỗng nếu nhãn là nút điều hướng chung, giá tiền, bộ đếm hoặc đoạn văn quá dài.
   */
  @NonNull
  static String normalizeHeaderFallbackLabel(@Nullable CharSequence raw) {
    String normalized = normalizeTitle(raw);
    if (normalized.length() < 2 || normalized.length() > 40) {
      return "";
    }
    if (DYNAMIC_VALUE_ONLY_PATTERN.matcher(normalized).matches()) {
      return "";
    }
    if ("quay lại".equals(normalized)
        || "trở lại".equals(normalized)
        || "back".equals(normalized)
        || "navigate up".equals(normalized)
        || "đóng".equals(normalized)
        || "close".equals(normalized)
        || "tìm kiếm".equals(normalized)
        || "search".equals(normalized)
        || "chưa được gắn nhãn".equals(normalized)
        || "unlabelled".equals(normalized)
        || "tùy chọn khác".equals(normalized)
        || "more options".equals(normalized)) {
      return "";
    }
    return normalized;
  }

  @NonNull
  static String normalizeTitle(@Nullable CharSequence raw) {
    if (isNullOrEmpty(raw)) {
      return "";
    }
    String s = raw.toString().trim();
    s = DYNAMIC_COUNT_PATTERN.matcher(s).replaceAll(" ");
    s = s.replaceAll("\\s+", " ").trim();
    return s.toLowerCase(Locale.ROOT);
  }

  @NonNull
  private static String safeLower(@Nullable CharSequence raw) {
    return isNullOrEmpty(raw) ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isNullOrEmpty(@Nullable CharSequence raw) {
    return raw == null || raw.length() == 0;
  }

  @NonNull
  private static String joinWithPipe(@NonNull List<String> tokens) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      if (i > 0) {
        sb.append('|');
      }
      sb.append(tokens.get(i));
    }
    return sb.toString();
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
