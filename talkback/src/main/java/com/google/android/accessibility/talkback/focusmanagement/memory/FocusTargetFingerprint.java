package com.google.android.accessibility.talkback.focusmanagement.memory;

import android.graphics.Rect;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dấu vân tay bền vững của một phần tử tiêu điểm (tách khỏi đối tượng {@link
 * AccessibilityNodeInfoCompat} vốn dễ chết/detached khi rời màn hình hoặc bị {@code RecyclerView}
 * tái sử dụng).
 *
 * <p>Phiên bản v2.1:
 * <ul>
 *   <li>Mở rộng lọc chuỗi động Việt/Anh trong Nekogram & Zalo ({@code "2 tin nhắn mới"}, {@code "1
 *       lượt nhắc"}, {@code "đã tắt âm"}, {@code "truy cập 59 phút trước"}, {@code "hoạt động
 *       11:03"}, {@code "trực tuyến"}).</li>
 *   <li>Bổ sung dấu chấm ({@code '.'}) vào danh sách phân tách {@code primaryToken} để hàng chat
 *       dạng {@code "Meridian. 2 tin nhắn mới. Đã nhận 11:02..."} tách đúng tên hội thoại {@code
 *       "meridian"}.</li>
 *   <li>Không phụ thuộc {@code android.text.TextUtils} để unit test JVM chạy mượt mà.</li>
 * </ul>
 */
public final class FocusTargetFingerprint {

  private static final int MAX_TREE_SCAN_NODES = 350;

  private static final Pattern DYNAMIC_STATUS_PATTERN =
      Pattern.compile(
          "(?iu)(đã\\s+(nhận|gửi|xem|đọc)(\\s+lúc)?\\s*\\d{1,2}:\\d{2}|"
              + "đã\\s+(nhận|gửi|xem|đọc|tắt\\s+âm)|"
              + "truy\\s+cập\\s+\\d+\\s*(giây|phút|giờ|ngày|tuần)\\s*trước|"
              + "hoạt\\s+động\\s+\\d{1,2}:\\d{2}|"
              + "\\btrực\\s+tuyến\\b|"
              + "\\b\\d{1,2}:\\d{2}(\\s*(am|pm))?\\b|"
              + "\\b\\d+\\s*(giây|phút|giờ|ngày|tuần|sec|min|hr|day)s?\\s*(trước|ago)?\\b|"
              + "\\b\\d+\\+?\\s+(tin\\s+nhắn\\s+(mới|chưa\\s+đọc)|lượt\\s+nhắc|chat\\s+chưa\\s+đọc)\\b)");

  @NonNull private final String uniqueId;
  @NonNull private final String viewId;
  @NonNull private final String className;
  @NonNull private final String normalizedLabel;
  @NonNull private final String primaryToken;
  @NonNull private final String parentViewId;
  private final boolean editable;
  private final int centerY;
  private final long recordedAtUptimeMs;

  private FocusTargetFingerprint(
      @NonNull String uniqueId,
      @NonNull String viewId,
      @NonNull String className,
      @NonNull String normalizedLabel,
      @NonNull String primaryToken,
      @NonNull String parentViewId,
      boolean editable,
      int centerY,
      long recordedAtUptimeMs) {
    this.uniqueId = uniqueId;
    this.viewId = viewId;
    this.className = className;
    this.normalizedLabel = normalizedLabel;
    this.primaryToken = primaryToken;
    this.parentViewId = parentViewId;
    this.editable = editable;
    this.centerY = centerY;
    this.recordedAtUptimeMs = recordedAtUptimeMs;
  }

  @Nullable
  public static FocusTargetFingerprint capture(
      @Nullable AccessibilityNodeInfoCompat node, long uptimeMs) {
    if (node == null) {
      return null;
    }

    String unique = safeString(node.getUniqueId());
    String vid = safeString(node.getViewIdResourceName());
    String cls = safeString(node.getClassName());
    String rawLabel = extractLabel(node);
    String normLabel = normalizeDynamicLabel(rawLabel);
    String primary = extractPrimaryToken(normLabel);

    String pVid = "";
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null) {
      pVid = safeString(parent.getViewIdResourceName());
    }

    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    int cy = bounds.isEmpty() ? -1 : bounds.centerY();

    if (unique.isEmpty() && vid.isEmpty() && normLabel.isEmpty()) {
      return null;
    }

    return new FocusTargetFingerprint(
        unique, vid, cls, normLabel, primary, pVid, node.isEditable(), cy, uptimeMs);
  }

  @NonNull
  public String getUniqueId() {
    return uniqueId;
  }

  @NonNull
  public String getViewId() {
    return viewId;
  }

  @NonNull
  public String getClassName() {
    return className;
  }

  @NonNull
  public String getNormalizedLabel() {
    return normalizedLabel;
  }

  @NonNull
  public String getPrimaryToken() {
    return primaryToken;
  }

  public boolean isEditable() {
    return editable;
  }

  public long getRecordedAtUptimeMs() {
    return recordedAtUptimeMs;
  }

  /**
   * Kiểm tra nhanh xem {@code candidate} có mô tả cùng phần tử với dấu vân tay này hay không.
   */
  public boolean matchesNode(@Nullable AccessibilityNodeInfoCompat candidate) {
    if (candidate == null) {
      return false;
    }
    if (!uniqueId.isEmpty() && uniqueId.equals(safeString(candidate.getUniqueId()))) {
      return true;
    }
    String candVid = safeString(candidate.getViewIdResourceName());
    String candLabel = normalizeDynamicLabel(extractLabel(candidate));
    if (!viewId.isEmpty() && viewId.equals(candVid)) {
      if (editable && candidate.isEditable()) {
        return true;
      }
      if (!normalizedLabel.isEmpty() && normalizedLabel.equals(candLabel)) {
        return true;
      }
    }
    return !normalizedLabel.isEmpty()
        && normalizedLabel.equals(candLabel)
        && className.equals(safeString(candidate.getClassName()));
  }

  /**
   * Tìm node phù hợp nhất trong cây {@code root} khớp với dấu vân tay này.
   */
  @Nullable
  public AccessibilityNodeInfoCompat findBestMatch(@Nullable AccessibilityNodeInfoCompat root) {
    if (root == null) {
      return null;
    }

    AccessibilityNodeInfoCompat bestNode = null;
    int bestScore = 0;

    List<AccessibilityNodeInfoCompat> queue = new ArrayList<>(64);
    queue.add(root);

    int scanned = 0;
    for (int i = 0; i < queue.size() && scanned < MAX_TREE_SCAN_NODES; i++) {
      AccessibilityNodeInfoCompat node = queue.get(i);
      scanned++;
      if (node == null) {
        continue;
      }

      Rect nodeBounds = new Rect();
      node.getBoundsInScreen(nodeBounds);
      if (node.isVisibleToUser()
          && !nodeBounds.isEmpty()
          && AccessibilityNodeInfoUtils.shouldFocusNode(node)) {
        int score = scoreCandidate(node);
        if (score > bestScore) {
          bestScore = score;
          bestNode = node;
          if (bestScore >= 100) {
            return bestNode;
          }
        }
      }

      int childCount = node.getChildCount();
      for (int c = 0; c < childCount && queue.size() < MAX_TREE_SCAN_NODES; c++) {
        AccessibilityNodeInfoCompat child = node.getChild(c);
        if (child != null) {
          queue.add(child);
        }
      }
    }

    return bestScore >= 55 ? bestNode : null;
  }

  private int scoreCandidate(@NonNull AccessibilityNodeInfoCompat candidate) {
    String candUnique = safeString(candidate.getUniqueId());
    if (!uniqueId.isEmpty() && uniqueId.equals(candUnique)) {
      return 100;
    }

    String candVid = safeString(candidate.getViewIdResourceName());
    String candCls = safeString(candidate.getClassName());
    String candLabel = normalizeDynamicLabel(extractLabel(candidate));

    boolean sameVid = !viewId.isEmpty() && viewId.equals(candVid);
    boolean sameCls = !className.isEmpty() && className.equals(candCls);
    boolean sameLabel = !normalizedLabel.isEmpty() && normalizedLabel.equals(candLabel);

    // 1. Khớp cả viewId và văn bản chuẩn hóa
    if (sameVid && sameLabel) {
      return 95;
    }

    // 2. Ô nhập liệu khớp viewId + editable
    if (sameVid && editable && candidate.isEditable()) {
      return 90;
    }

    // 3. Khớp văn bản chuẩn hóa + className
    if (sameLabel && sameCls) {
      int bonus = matchesParentViewId(candidate) ? 5 : 0;
      return 85 + bonus;
    }

    // 4. Khớp văn bản chuẩn hóa dù class thay đổi nhẹ
    if (sameLabel && normalizedLabel.length() >= 4) {
      return 75;
    }

    // 5. Khớp tiền tố/tên hội thoại (primaryToken) trong cùng container/class
    //    (ví dụ "Meridian. 2 tin nhắn mới..." -> primaryToken="meridian")
    if (!primaryToken.isEmpty()
        && primaryToken.length() >= 2
        && sameCls
        && matchesPrimaryToken(candLabel, primaryToken)) {
      int bonus = matchesParentViewId(candidate) ? 8 : 0;
      return 62 + bonus;
    }

    // 6. Khớp viewId duy nhất không có văn bản
    if (sameVid && normalizedLabel.isEmpty() && candLabel.isEmpty() && sameCls) {
      return 60 + proximityTieBreaker(candidate);
    }

    return 0;
  }

  private static boolean matchesPrimaryToken(
      @NonNull String candLabel, @NonNull String primaryToken) {
    if (!candLabel.startsWith(primaryToken)) {
      return false;
    }
    if (candLabel.length() == primaryToken.length()) {
      return true;
    }
    char next = candLabel.charAt(primaryToken.length());
    return next == '.' || next == ',' || next == ':' || next == ' ' || next == '\n' || next == '|';
  }

  private int proximityTieBreaker(@NonNull AccessibilityNodeInfoCompat candidate) {
    if (centerY < 0) {
      return 0;
    }
    Rect bounds = new Rect();
    candidate.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      return 0;
    }
    return Math.abs(bounds.centerY() - centerY) <= 120 ? 2 : 0;
  }

  private boolean matchesParentViewId(@NonNull AccessibilityNodeInfoCompat candidate) {
    if (parentViewId.isEmpty()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = candidate.getParent();
    return parent != null && parentViewId.equals(safeString(parent.getViewIdResourceName()));
  }

  @NonNull
  static String normalizeDynamicLabel(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    String cleaned = DYNAMIC_STATUS_PATTERN.matcher(raw).replaceAll(" ");
    cleaned = cleaned.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
  }

  @NonNull
  static String extractPrimaryToken(@NonNull String normalizedLabel) {
    if (normalizedLabel.isEmpty()) {
      return "";
    }
    // Lấy mệnh đề đầu tiên (trước dấu chấm, xuống dòng, phẩy, hai chấm, •, |) làm định danh ổn định.
    int cut = normalizedLabel.length();
    for (char sep : new char[] {'.', '\n', ',', ':', '•', '|'}) {
      int idx = normalizedLabel.indexOf(sep);
      if (idx > 0 && idx < cut) {
        cut = idx;
      }
    }
    String token = normalizedLabel.substring(0, cut).trim();
    return token.length() <= 40 ? token : token.substring(0, 40);
  }

  @NonNull
  private static String extractLabel(@NonNull AccessibilityNodeInfoCompat node) {
    CharSequence text = node.getText();
    if (!isNullOrEmpty(text)) {
      return text.toString();
    }
    CharSequence desc = node.getContentDescription();
    if (!isNullOrEmpty(desc)) {
      return desc.toString();
    }
    StringBuilder sb = new StringBuilder();
    int childCount = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < childCount; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      CharSequence ct = !isNullOrEmpty(child.getText()) ? child.getText() : child.getContentDescription();
      if (!isNullOrEmpty(ct)) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        sb.append(ct);
        if (sb.length() >= 100) {
          break;
        }
      }
    }
    return sb.toString();
  }

  private static boolean isNullOrEmpty(@Nullable CharSequence raw) {
    return raw == null || raw.length() == 0;
  }

  @NonNull
  private static String safeString(@Nullable CharSequence raw) {
    return isNullOrEmpty(raw) ? "" : raw.toString().trim();
  }

  @NonNull
  @Override
  public String toString() {
    return "FocusTargetFingerprint{vid='"
        + viewId
        + "', cls='"
        + className
        + "', label='"
        + normalizedLabel
        + "'}";
  }
}
