/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ tối ưu tiêu điểm và tốc độ dùng chung cho toàn bộ ứng dụng Android trong Meridian.
 *
 * <p>Tổng hợp các quy tắc an toàn, không phụ thuộc từng ứng dụng cụ thể:
 *
 * <ul>
 *   <li>Công tắc tổng {@link #isFocusFilterEnabled()} (mặc định bật) gắn với cài đặt "Lọc nội dung
 *       tiêu điểm" trong Cài đặt điều hướng. Khi tắt, toàn bộ lọc ẩn node và gom thẻ (cả chung lẫn
 *       riêng từng ứng dụng như TikTok, Shopee, Zalo) được bỏ qua để trả về tiêu điểm đầy đủ.
 *   <li>Thoát sớm khi {@code !node.isVisibleToUser()} và dùng bộ đệm ảnh chụp anh em ngắn hạn
 *       ({@link ParentSiblingSnapshot}, TTL 400 ms) để giảm truy vấn IPC từ O(N²) xuống O(N) trên
 *       các cây phẳng lớn (Lynx, React Native, Flutter).
 *   <li>Ẩn node trang trí rỗng, container cuộn rỗng gây dừng câm, ảnh trang trí rỗng nằm trong cha
 *       đã có nhãn, node chỉ chứa khoảng trắng, node con lặp nguyên văn text của TextView cha, và
 *       các mảnh đồng hồ đếm ngược / giá tiền bị tách lẻ cạnh nhãn đầy đủ.
 *   <li>Làm sạch ký tự rác {@code \uFFFC} / {@code \u0000}, tự động giải mã mô tả tài nguyên thô
 *       dạng {@code @21318...} cho mọi ứng dụng và lọc mô tả kỹ thuật dạng {@code snake_case}.
 * </ul>
 */
public final class UniversalAccessibilityHelper {

  private static final Pattern RAW_RES_ID_PATTERN = Pattern.compile("^@(\\d{8,10})$");
  private static final Pattern COUNTDOWN_TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}:\\d{2}$");
  private static final Pattern COUNTDOWN_PART_PATTERN = Pattern.compile("^(:|\\d{1,2})$");
  private static final Pattern TECHNICAL_SNAKE_CASE_PATTERN =
      Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+){2,}$");

  private static final long SNAPSHOT_TTL_MS = 400L;
  private static final int MAX_SNAPSHOT_CACHE = 12;
  private static final int MAX_SNAPSHOT_CHILDREN = 160;

  private static final ConcurrentHashMap<String, String> RESOLVED_RES_CACHE =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, ParentSiblingSnapshot> SIBLING_SNAPSHOT_CACHE =
      new ConcurrentHashMap<>();

  private static volatile boolean focusFilterEnabled = true;
  @Nullable private static volatile Context appContext;

  /**
   * Ảnh chụp ngắn hạn (400 ms) danh sách con của một container phẳng để tra cứu anh em trong O(1),
   * tránh gọi lặp {@code parent.getChild(i)} qua IPC hàng nghìn lần mỗi lần vuốt.
   */
  private static final class ParentSiblingSnapshot {
    final long expiresAtMs;
    final int childCount;
    final Map<AccessibilityNodeInfoCompat, Integer> indexByNode = new HashMap<>();
    final Set<String> clickableLowerLabels = new HashSet<>();
    final Map<String, Integer> firstIndexByLowerLabel = new HashMap<>();
    final Set<String> priceLowerLabels = new HashSet<>();
    boolean hasCountdownTime;

    ParentSiblingSnapshot(long expiresAtMs, int childCount) {
      this.expiresAtMs = expiresAtMs;
      this.childCount = childCount;
    }
  }

  private UniversalAccessibilityHelper() {}

  /** Lưu {@link Context} ứng dụng để tự động giải mã các chuỗi tài nguyên thô {@code @21318...}. */
  public static void init(@Nullable Context context) {
    if (context != null && appContext == null) {
      appContext = context.getApplicationContext();
    }
  }

  /** Trả về {@code true} khi bật tùy chọn "Lọc nội dung tiêu điểm" (mặc định {@code true}). */
  public static boolean isFocusFilterEnabled() {
    return focusFilterEnabled;
  }

  /** Bật/tắt tùy chọn "Lọc nội dung tiêu điểm" cho cả bộ lọc chung lẫn các helper theo ứng dụng. */
  public static void setFocusFilterEnabled(boolean enabled) {
    if (focusFilterEnabled != enabled) {
      focusFilterEnabled = enabled;
      SIBLING_SNAPSHOT_CACHE.clear();
    }
  }

  /** Xóa bộ đệm snapshot anh em (dùng trong kiểm thử hoặc khi đổi cài đặt). */
  public static void clearSnapshotCache() {
    SIBLING_SNAPSHOT_CACHE.clear();
  }

  /** Kiểm tra xem chuỗi có phải mã tài nguyên Android thô dạng {@code @2131829637} hay không. */
  public static boolean isRawResourceIdDescription(@Nullable CharSequence description) {
    if (description == null || description.length() < 9) {
      return false;
    }
    return RAW_RES_ID_PATTERN.matcher(description.toString().trim()).matches();
  }

  /**
   * Kiểm tra xem mô tả có phải mã kỹ thuật nội bộ dạng {@code snake_case} từ 3 cụm trở lên không
   * (ví dụ {@code dd_module_item}, {@code wallet_bar_cell_left}, {@code home_square_icon_1}).
   */
  public static boolean isTechnicalSnakeCaseDescription(@Nullable CharSequence description) {
    if (description == null || description.length() < 5) {
      return false;
    }
    String raw = description.toString().trim();
    if (raw.isEmpty() || raw.indexOf(' ') >= 0 || raw.indexOf('_') < 0) {
      return false;
    }
    int nl = raw.indexOf('\n');
    String head = (nl >= 0 ? raw.substring(0, nl) : raw).trim();
    return TECHNICAL_SNAKE_CASE_PATTERN.matcher(head).matches();
  }

  /**
   * Tự động tra cứu chuỗi tài nguyên gốc từ gói ứng dụng khi gặp mô tả thô dạng {@code @21318...}.
   */
  @Nullable
  public static String resolveRawResourceId(
      @Nullable CharSequence packageName, @NonNull String rawResDesc) {
    if (packageName == null || packageName.length() == 0) {
      return null;
    }
    Matcher m = RAW_RES_ID_PATTERN.matcher(rawResDesc.trim());
    if (!m.matches()) {
      return null;
    }
    String pkg = packageName.toString();
    String cacheKey = pkg + ":" + rawResDesc.trim();
    String cached = RESOLVED_RES_CACHE.get(cacheKey);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    Context ctx = appContext;
    if (ctx == null) {
      return null;
    }
    try {
      int resId = Integer.parseInt(m.group(1));
      Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
      String value = res.getString(resId);
      if (value != null) {
        value = cleanControlAndPlaceholderChars(value).toString().trim();
      }
      RESOLVED_RES_CACHE.put(cacheKey, value != null ? value : "");
      return (value != null && !value.isEmpty()) ? value : null;
    } catch (Exception ignored) {
      RESOLVED_RES_CACHE.put(cacheKey, "");
      return null;
    }
  }

  /**
   * Làm sạch ký tự rỗng {@code \u0000} (NUL) và ký tự giữ chỗ {@code \uFFFC} (￼) trong văn bản cho
   * mọi ứng dụng Android.
   */
  @Nullable
  public static CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence text) {
    if (node == null || text == null || text.length() == 0) {
      return text;
    }
    return cleanControlAndPlaceholderChars(text);
  }

  /**
   * Lọc {@code contentDescription} dùng chung cho mọi ứng dụng: giải mã {@code @21318...}, bỏ mã
   * kỹ thuật {@code snake_case}, và làm sạch {@code \uFFFC} / {@code \u0000}.
   */
  @Nullable
  public static CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence description) {
    if (node == null || description == null || description.length() == 0) {
      return description;
    }
    if (isRawResourceIdDescription(description)) {
      CharSequence ownText = node.getText();
      if (ownText != null && ownText.toString().trim().length() > 0) {
        return "";
      }
      String resolved = resolveRawResourceId(node.getPackageName(), description.toString().trim());
      if (resolved != null && !resolved.isEmpty()) {
        return resolved;
      }
      return description;
    }
    if (isTechnicalSnakeCaseDescription(description)) {
      return "";
    }
    return cleanControlAndPlaceholderChars(description);
  }

  @NonNull
  static CharSequence cleanControlAndPlaceholderChars(@NonNull CharSequence text) {
    String raw = text.toString();
    boolean hasObj = raw.indexOf('\uFFFC') >= 0;
    boolean hasNul = raw.indexOf('\u0000') >= 0;
    if (!hasObj && !hasNul) {
      return text;
    }
    String cleaned = raw;
    if (hasNul) {
      cleaned = cleaned.replace('\u0000', ' ');
    }
    if (hasObj) {
      cleaned = cleaned.replace('\uFFFC', ' ');
    }
    return cleaned.replaceAll("\\s{2,}", " ").trim();
  }

  /**
   * Ẩn các node rác / trang trí / trùng lặp phổ biến trên mọi ứng dụng Android để giữ lại các thành
   * phần thiết yếu. Trả về {@code false} ngay lập tức nếu tắt "Lọc nội dung tiêu điểm" hoặc node
   * không hiển thị trên màn hình.
   */
  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (!focusFilterEnabled || node == null || !node.isVisibleToUser()) {
      return false;
    }

    CharSequence text = node.getText();
    CharSequence desc = node.getContentDescription();

    // 1. Ẩn node lá chỉ chứa khoảng trắng thừa, không có mô tả và không nhập liệu/chọn được
    if (text != null
        && text.length() > 0
        && text.toString().trim().isEmpty()
        && isEmptyTrimmed(desc)
        && node.getChildCount() == 0
        && !node.isEditable()
        && !node.isCheckable()) {
      return true;
    }

    // 2. Ẩn container cuộn rỗng hoặc ViewPager không nhãn gây dừng câm & quét thừa hàng chục tab ẩn
    if (isEmptyNonClickableScrollableLeaf(node, text, desc)
        || isUnlabeledNonClickableViewPager(node, text, desc)) {
      return true;
    }

    // 3. Tối ưu chung cho cây phẳng Lynx (TikTok, CapCut, Douyin và các app dùng Lynx)
    if (isLynxClass(node)) {
      // Ẩn mọi node Lynx rỗng không bấm được (kể cả UIScrollView / UIList / XSwiperUI có con)
      // để tránh dừng tiêu điểm phát âm thanh nhưng không có lời đọc khi di tay hoặc vuốt.
      if (!node.isClickable()
          && !node.isEditable()
          && !node.isCheckable()
          && isEmptyTrimmed(text)
          && isEmptyTrimmed(desc)
          && isEmptyTrimmed(node.getStateDescription())) {
        return true;
      }
      if (isEmptyLeafNode(node, text, desc)) {
        return true;
      }
      if (isDuplicateFlatSibling(node, /* includeAllPrefixes= */ true)) {
        return true;
      }
      if (isSplitCountdownSibling(node)) {
        return true;
      }
    } else {
      // 4. Với app Android thông thường: ẩn mảnh đồng hồ đếm ngược hoặc ký hiệu "₫" bị tách lẻ
      // cạnh một node anh em đã chứa đầy đủ trong cùng container phẳng
      if (isSplitCountdownSibling(node) || isSplitCurrencySibling(node)) {
        return true;
      }
    }

    // 5. Ẩn ImageView trang trí rỗng không bấm được nằm trong phần tử cha đã có nhãn
    if (isEmptyDecorativeImageInsideLabeledParent(node, text, desc)) {
      return true;
    }

    // 6. Ẩn node con lặp y hệt văn bản của TextView cha
    if (isDuplicateChildOfTextParent(node)) {
      return true;
    }

    return false;
  }

  /** Nhận diện các lớp giao diện của engine Lynx. */
  public static boolean isLynxClass(@NonNull AccessibilityNodeInfoCompat node) {
    CharSequence cls = node.getClassName();
    if (cls == null) {
      return false;
    }
    String c = cls.toString();
    return c.contains("Lynx")
        || c.endsWith("FlattenUIText")
        || c.endsWith("FlattenUIImage")
        || c.endsWith("EcomFlattenUIImage")
        || c.endsWith("EcomUIImage")
        || c.endsWith("UIView")
        || c.endsWith("UIText")
        || c.endsWith("UISvg")
        || c.endsWith("UIComponent")
        || c.endsWith("UICanvas")
        || c.endsWith("XSwiperUI")
        || c.endsWith("UIScrollView")
        || c.endsWith("UIList");
  }

  private static boolean isEmptyLeafNode(
      @NonNull AccessibilityNodeInfoCompat node,
      @Nullable CharSequence text,
      @Nullable CharSequence desc) {
    return node.getChildCount() == 0 && isEmptyTrimmed(text) && isEmptyTrimmed(desc);
  }

  private static boolean isUnlabeledNonClickableViewPager(
      @NonNull AccessibilityNodeInfoCompat node,
      @Nullable CharSequence text,
      @Nullable CharSequence desc) {
    if (node.isClickable()
        || node.isEditable()
        || node.isCheckable()
        || !isEmptyTrimmed(text)
        || !isEmptyTrimmed(desc)
        || !isEmptyTrimmed(node.getStateDescription())) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls == null) {
      return false;
    }
    String c = cls.toString();
    return c.endsWith("ViewPager") || c.endsWith("ViewPager2");
  }

  private static boolean isEmptyNonClickableScrollableLeaf(
      @NonNull AccessibilityNodeInfoCompat node,
      @Nullable CharSequence text,
      @Nullable CharSequence desc) {
    if (node.isClickable()
        || node.isEditable()
        || node.isCheckable()
        || !isEmptyLeafNode(node, text, desc)) {
      return false;
    }
    if (!node.isScrollable()) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls == null) {
      return false;
    }
    String c = cls.toString();
    return c.endsWith("RecyclerView")
        || c.endsWith("ScrollView")
        || c.endsWith("HorizontalScrollView")
        || c.endsWith("ViewPager")
        || c.endsWith("XSwiperUI")
        || c.endsWith("UIScrollView")
        || c.endsWith("UIList");
  }

  @Nullable
  private static ParentSiblingSnapshot getOrBuildSnapshot(
      @NonNull AccessibilityNodeInfoCompat parent) {
    int count = parent.getChildCount();
    if (count <= 1) {
      return null;
    }
    long now = SystemClock.uptimeMillis();
    int key = parent.hashCode();
    ParentSiblingSnapshot cached = SIBLING_SNAPSHOT_CACHE.get(key);
    if (cached != null && cached.childCount == count && now <= cached.expiresAtMs) {
      return cached;
    }
    if (SIBLING_SNAPSHOT_CACHE.size() >= MAX_SNAPSHOT_CACHE) {
      SIBLING_SNAPSHOT_CACHE.clear();
    }
    ParentSiblingSnapshot snap = new ParentSiblingSnapshot(now + SNAPSHOT_TTL_MS, count);
    int limit = Math.min(count, MAX_SNAPSHOT_CHILDREN);
    for (int i = 0; i < limit; i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      snap.indexByNode.put(child, i);
      String label = extractTrimmedLabel(child);
      if (label.isEmpty()) {
        continue;
      }
      String lower = label.toLowerCase(Locale.ROOT);
      if (child.isClickable()) {
        snap.clickableLowerLabels.add(lower);
      }
      if (!snap.firstIndexByLowerLabel.containsKey(lower)) {
        snap.firstIndexByLowerLabel.put(lower, i);
      }
      if (lower.contains("₫") && lower.length() > 1) {
        snap.priceLowerLabels.add(lower);
      }
      if (!snap.hasCountdownTime && COUNTDOWN_TIME_PATTERN.matcher(label).matches()) {
        snap.hasCountdownTime = true;
      }
    }
    SIBLING_SNAPSHOT_CACHE.put(key, snap);
    return snap;
  }

  private static boolean isDuplicateFlatSibling(
      @NonNull AccessibilityNodeInfoCompat node, boolean includeAllPrefixes) {
    if (node.isClickable()) {
      return false;
    }
    String own = extractTrimmedLabel(node);
    if (own.isEmpty()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    ParentSiblingSnapshot snap = getOrBuildSnapshot(parent);
    if (snap == null) {
      return false;
    }
    Integer idxObj = snap.indexByNode.get(node);
    int idx = idxObj != null ? idxObj : -1;
    String ownLower = own.toLowerCase(Locale.ROOT);

    if (snap.clickableLowerLabels.contains(ownLower)) {
      return true;
    }
    Integer firstIdx = snap.firstIndexByLowerLabel.get(ownLower);
    if (firstIdx != null && idx >= 0 && firstIdx < idx) {
      return true;
    }
    if (includeAllPrefixes) {
      for (String clickLabel : snap.clickableLowerLabels) {
        if (clickLabel.length() > ownLower.length()
            && (clickLabel.startsWith(ownLower) || clickLabel.endsWith(ownLower))) {
          return true;
        }
      }
    }
    if ("₫".equals(ownLower) && !snap.priceLowerLabels.isEmpty()) {
      return true;
    }
    if (includeAllPrefixes) {
      for (String priceLabel : snap.priceLowerLabels) {
        if (priceLabel.length() > ownLower.length() && priceLabel.startsWith(ownLower)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isSplitCurrencySibling(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.isClickable() || node.getChildCount() > 0) {
      return false;
    }
    String own = extractTrimmedLabel(node);
    if (!"₫".equals(own)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    ParentSiblingSnapshot snap = getOrBuildSnapshot(parent);
    return snap != null && !snap.priceLowerLabels.isEmpty();
  }

  private static boolean isSplitCountdownSibling(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.isClickable() || node.getChildCount() > 0) {
      return false;
    }
    String own = extractTrimmedLabel(node);
    if (!COUNTDOWN_PART_PATTERN.matcher(own).matches()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || parent.getChildCount() < 3) {
      return false;
    }
    ParentSiblingSnapshot snap = getOrBuildSnapshot(parent);
    return snap != null && snap.hasCountdownTime;
  }

  private static boolean isEmptyDecorativeImageInsideLabeledParent(
      @NonNull AccessibilityNodeInfoCompat node,
      @Nullable CharSequence text,
      @Nullable CharSequence desc) {
    if (node.isClickable()
        || !isImageViewClass(node.getClassName())
        || !isEmptyLeafNode(node, text, desc)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    return !isEmptyTrimmed(parent.getContentDescription()) || !isEmptyTrimmed(parent.getText());
  }

  private static boolean isDuplicateChildOfTextParent(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.isClickable()) {
      return false;
    }
    String own = extractTrimmedLabel(node);
    if (own.isEmpty()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || !isTextViewClass(parent.getClassName())) {
      return false;
    }
    return own.equals(extractTrimmedLabel(parent));
  }

  private static boolean isImageViewClass(@Nullable CharSequence cls) {
    if (cls == null) {
      return false;
    }
    String c = cls.toString();
    return c.endsWith("ImageView") || c.endsWith("ImageButton");
  }

  private static boolean isTextViewClass(@Nullable CharSequence cls) {
    return cls != null && cls.toString().endsWith("TextView");
  }

  private static boolean isEmptyTrimmed(@Nullable CharSequence cs) {
    return cs == null || TextUtils.getTrimmedLength(cs) == 0;
  }

  @NonNull
  private static String extractTrimmedLabel(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return "";
    }
    CharSequence desc = node.getContentDescription();
    if (!isEmptyTrimmed(desc)) {
      return desc.toString().trim();
    }
    CharSequence text = node.getText();
    if (!isEmptyTrimmed(text)) {
      return text.toString().trim();
    }
    return "";
  }
}
