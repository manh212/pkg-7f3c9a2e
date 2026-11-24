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
 * Cải thiện khả năng tiếp cận toàn diện cho TikTok ({@code com.ss.android.ugc.trill},
 * {@code com.zhiliaoapp.musically}, {@code com.ss.android.ugc.aweme}) trên 13 màn hình chính:
 * Bản tin, Video gắn giỏ hàng, Cửa hàng, Chi tiết sản phẩm, Thanh toán, Hộp thoại thoát thanh toán,
 * Quay video, Hộp thư, Hồ sơ, Phòng chat, Bình luận, Xem LIVE, Tìm kiếm và Kết quả tìm kiếm.
 *
 * <p>Vì TikTok mã hóa phần lớn {@code viewIdResName} thành chuỗi 3 ký tự thay đổi theo phiên bản,
 * helper ưu tiên nhận diện theo cấu trúc cây, vai trò, vị trí anh em và các ID ổn định, đồng thời
 * tự động giải mã các nhãn tài nguyên bị hiện thô dạng {@code @21318...}.
 */
public final class TikTokAccessibilityHelper {

  private static final String PKG_TRILL = "com.ss.android.ugc.trill";
  private static final String PKG_MUSICALLY = "com.zhiliaoapp.musically";
  private static final String PKG_AWEME = "com.ss.android.ugc.aweme";

  private static final Pattern RAW_RES_ID_PATTERN = Pattern.compile("^@(\\d{8,10})$");
  private static final Pattern DIGITS_ONLY_PATTERN = Pattern.compile("^\\d+$");
  private static final Pattern COUNTDOWN_TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}:\\d{2}$");
  private static final Pattern COUNTDOWN_PART_PATTERN = Pattern.compile("^(:|\\d{1,2})$");

  private static final long LYNX_SNAPSHOT_TTL_MS = 400L;
  private static final int MAX_LYNX_SNAPSHOT_CACHE = 8;

  private static final ConcurrentHashMap<String, String> RESOLVED_RES_CACHE =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, LynxParentSnapshot> LYNX_SNAPSHOT_CACHE =
      new ConcurrentHashMap<>();
  @Nullable private static volatile Context appContext;

  /**
   * Ảnh chụp ngắn hạn (400ms) danh sách con của một khung Lynx lớn để tránh quét O(N²)
   * {@code parent.getChild(i)} hàng chục nghìn lần mỗi lần vuốt trên TikTok Shop.
   */
  private static final class LynxParentSnapshot {
    final long expiresAtMs;
    final int childCount;
    final Map<AccessibilityNodeInfoCompat, Integer> indexByNode = new HashMap<>();
    final Set<String> clickableLowerLabels = new HashSet<>();
    final Map<String, Integer> firstIndexByLowerLabel = new HashMap<>();
    final Set<String> priceLowerLabels = new HashSet<>();
    boolean hasCountdownTime;

    LynxParentSnapshot(long expiresAtMs, int childCount) {
      this.expiresAtMs = expiresAtMs;
      this.childCount = childCount;
    }
  }

  private TikTokAccessibilityHelper() {}

  /** Lưu {@link Context} ứng dụng để tự động giải mã các chuỗi tài nguyên thô {@code @21318...}. */
  public static void init(@Nullable Context context) {
    if (context != null && appContext == null) {
      appContext = context.getApplicationContext();
    }
  }

  public static boolean isTikTokPackage(@Nullable CharSequence packageName) {
    if (packageName == null || packageName.length() == 0) {
      return false;
    }
    String pkg = packageName.toString();
    return PKG_TRILL.equals(pkg) || PKG_MUSICALLY.equals(pkg) || PKG_AWEME.equals(pkg);
  }

  private static boolean isTikTokNode(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && isTikTokPackage(node.getPackageName());
  }

  /** Kiểm tra xem mô tả có phải chuỗi kỹ thuật nội bộ của TikTok không (ví dụ storyring*). */
  public static boolean isTechnicalDescription(@Nullable CharSequence description) {
    if (description == null || description.length() == 0) {
      return false;
    }
    String lower = description.toString().trim().toLowerCase(Locale.ROOT);
    return lower.startsWith("storyring") || lower.startsWith("storybadge");
  }

  /** Kiểm tra xem chuỗi có phải mã tài nguyên Android thô dạng {@code @2131829637} hay không. */
  public static boolean isRawResourceIdDescription(@Nullable CharSequence description) {
    if (description == null || description.length() < 9) {
      return false;
    }
    return RAW_RES_ID_PATTERN.matcher(description.toString().trim()).matches();
  }

  /** Làm sạch ký tự giữ chỗ {@code \uFFFC} (￼) và khoảng trắng thừa trong văn bản TikTok. */
  @Nullable
  public static CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence text) {
    if (!isTikTokNode(node) || text == null || text.length() == 0) {
      return text;
    }
    return cleanTikTokText(text);
  }

  @NonNull
  static CharSequence cleanTikTokText(@NonNull CharSequence text) {
    String raw = text.toString();
    if (raw.indexOf('\uFFFC') < 0) {
      return text;
    }
    String cleaned = raw.replace('\uFFFC', ' ').replaceAll("\\s{2,}", " ").trim();
    return cleaned;
  }

  /** Sửa các trạng thái lỗi/thô trên TikTok (ví dụ {@code layoutOff} và câu ghép lỗi 0 lượt lưu). */
  @Nullable
  public static CharSequence getStateOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isTikTokNode(node)) {
      return null;
    }
    CharSequence state = node.getStateDescription();
    if (state == null || state.length() == 0) {
      return null;
    }
    return cleanTikTokState(state);
  }

  @Nullable
  static CharSequence cleanTikTokState(@NonNull CharSequence state) {
    String s = state.toString().trim();
    if ("layoutOff".equalsIgnoreCase(s)) {
      return "tắt";
    }
    if ("layoutOn".equalsIgnoreCase(s)) {
      return "bật";
    }
    String brokenFavoriteCount = "Có Lưu người đã thêm video này vào mục Yêu thích.";
    if (s.contains(brokenFavoriteCount)) {
      String cleaned = s.replace(brokenFavoriteCount, "").replaceAll("\\s{2,}", " ").trim();
      return cleaned.isEmpty() ? null : cleaned;
    }
    return null;
  }

  /**
   * Lọc {@code contentDescription}, giải mã {@code @21318...}, sửa câu lỗi và cấp nhãn tiếng Việt
   * thân thiện cho các nút trống trên 13 màn hình TikTok.
   */
  @Nullable
  public static CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence description) {
    if (!isTikTokNode(node)) {
      return description;
    }

    // 1. Nút gắn giỏ hàng sản phẩm trên video (feed_multi_tag_layout)
    String idName = shortViewId(node);
    if ("feed_multi_tag_layout".equals(idName)) {
      String productText = findFirstDescendantText(node, 4);
      return productText.isEmpty() ? "Giỏ hàng sản phẩm" : "Giỏ hàng sản phẩm: " + productText;
    }

    // 2. Tab có chấm đếm thông báo mới (Hộp thư + 18, Bạn bè + 6)
    if (description != null && description.length() > 0) {
      String trimmedDesc = description.toString().trim();
      if ("Hộp thư".equals(trimmedDesc) || "Bạn bè".equals(trimmedDesc)) {
        String badge = findNumericChildText(node);
        if (badge != null) {
          return trimmedDesc + ", " + badge + " thông báo mới";
        }
      }
      // Sửa câu ghép lỗi khi 0 lượt chia sẻ
      if ("Chia sẻ video. Chia sẻ lượt chia sẻ".equals(trimmedDesc)) {
        return "Chia sẻ video";
      }
    }

    // 3. Lọc mô tả kỹ thuật nội bộ (storyring*, storybadge*)
    if (isTechnicalDescription(description)) {
      return "";
    }

    // 4. Tự động giải mã nhãn tài nguyên thô @21318... (ví dụ @2131829637, @2131823528...)
    if (isRawResourceIdDescription(description)) {
      // Nếu node đã có sẵn chữ thật (ví dụ tiêu đề "Hộp thư" bị dính @2131823528) thì bỏ desc lỗi
      CharSequence ownText = node.getText();
      if (ownText != null && ownText.toString().trim().length() > 0) {
        return "";
      }
      String resolved = resolveRawResourceId(node.getPackageName(), description.toString().trim());
      if (resolved != null && !resolved.isEmpty()) {
        return resolved;
      }
      String fallback = resolveRawResourceFallback(node);
      return fallback != null ? fallback : "";
    }

    // 5. Làm sạch \uFFFC trong mô tả nếu có
    if (description != null && description.length() > 0) {
      if (description.toString().indexOf('\uFFFC') >= 0) {
        return cleanTikTokText(description);
      }
      return description;
    }

    // 6. Cấp nhãn ngữ cảnh cho các nút rỗng
    String synthetic = resolveSyntheticLabel(node, idName);
    return synthetic != null ? synthetic : description;
  }

  /** Ghi đè vai trò cho các nút sản phẩm / tab / thanh tua nếu cần. */
  @Nullable
  public static Integer getRoleOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isTikTokNode(node)) {
      return null;
    }
    String idName = shortViewId(node);
    if ("feed_multi_tag_layout".equals(idName)
        || "upload_hot_area".equals(idName)
        || "touchpoint_normal_pendant".equals(idName)) {
      return Role.ROLE_BUTTON;
    }
    return null;
  }

  /** Gom gọn các thẻ/tab có nhiều con phụ thành 1 điểm dừng duy nhất. */
  public static boolean isGroupedCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isTikTokNode(node)) {
      return false;
    }
    String idName = shortViewId(node);
    if ("feed_multi_tag_layout".equals(idName)) {
      return true;
    }
    CharSequence desc = node.getContentDescription();
    if (desc != null && node.isClickable() && node.getChildCount() >= 2) {
      String d = desc.toString().trim();
      if (("Hộp thư".equals(d) || "Bạn bè".equals(d)) && findNumericChildText(node) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Ẩn các node rác, khung bọc rỗng, icon trang trí và văn bản Lynx lặp trên TikTok để vuốt gọn.
   */
  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isTikTokNode(node) || !node.isVisibleToUser()) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    if (isTechnicalDescription(desc)) {
      return true;
    }

    String idName = shortViewId(node);
    if ("user_avatar_border".equals(idName)) {
      return true;
    }

    // Ẩn nút chỉ có khoảng trắng (ví dụ nút t_7 cạnh "Thêm tên" trong Hồ sơ)
    CharSequence text = node.getText();
    if (text != null
        && text.length() > 0
        && text.toString().trim().isEmpty()
        && isEmptyTrimmed(desc)
        && node.getChildCount() == 0) {
      return true;
    }

    // Ẩn các node Lynx rỗng hoặc lặp chữ trong TikTok Shop / LIVE / Hộp thoại thoát thanh toán
    if (isLynxClass(node)) {
      if (isEmptyLynxDecorativeNode(node)) {
        return true;
      }
      if (isDuplicateLynxSibling(node)) {
        return true;
      }
      if (isSplitCountdownSibling(node)) {
        return true;
      }
    }

    // Ẩn các ImageView rỗng nằm bên trong nút cha đã có nhãn (6 nút Camera: Lật, Flash...)
    if (isEmptyDecorativeImageInsideLabeledParent(node)) {
      return true;
    }

    // Ẩn icon avatar rỗng đứng ngay trước TextView trong dòng chat LIVE
    if (isEmptyLiveChatAvatar(node)) {
      return true;
    }

    // Ẩn icon dấu tích/mũi tên rỗng kẹp bên cạnh dòng chính sách thanh toán hoặc đánh giá sản phẩm
    if (isEmptyShopPolicyOrRatingIcon(node)) {
      return true;
    }

    // Ẩn avatar lặp đúng tên người chat ngay cạnh tiêu đề trong phòng chat DM
    if (isDuplicateChatHeaderAvatar(node)) {
      return true;
    }

    // Ẩn View con có description lặp y hệt text của TextView cha (ví dụ "Nhận vào 26 tháng 11")
    if (isDuplicateChildOfTextParent(node)) {
      return true;
    }

    // Ẩn khung bọc rỗng (không có nhãn riêng, không thuộc nút có nhãn tổng hợp) chỉ bọc ngoài con
    // có nhãn hoặc hoàn toàn rỗng (như lkp, eyv, ibm, ia4, wg9, v4m, g5_, v2h, n5a, erb, pr9, n1b)
    if (isRedundantEmptyWrapper(node, idName)) {
      return true;
    }

    return false;
  }

  @Nullable
  private static String resolveSyntheticLabel(
      @NonNull AccessibilityNodeInfoCompat node, @NonNull String idName) {
    // 1. Các ID ổn định của TikTok
    if ("upload_hot_area".equals(idName)) {
      return "Tải lên";
    }
    if ("touchpoint_profile_icon".equals(idName)) {
      return "Phần thưởng";
    }
    if ("touchpoint_normal_pendant".equals(idName)) {
      return "Phần thưởng TikTok";
    }
    if ("tv_request_unread_count".equals(idName)) {
      CharSequence d = node.getContentDescription();
      if (d != null && DIGITS_ONLY_PATTERN.matcher(d.toString().trim()).matches()) {
        return d.toString().trim() + " yêu cầu chưa đọc";
      }
    }

    // 2. Thanh tua video (SeekBar) không nhãn
    CharSequence cls = node.getClassName();
    if (cls != null && cls.toString().endsWith("SeekBar")) {
      return "Thanh tiến trình video";
    }

    // Nếu node đã có chữ riêng thì không ghi đè
    if (!isEmptyTrimmed(node.getText())) {
      return null;
    }

    // 3. Màn hình Tìm kiếm & Kết quả tìm kiếm: ImageView rỗng ngay trước EditText -> Quay lại
    if (node.isClickable() && isImageViewClass(cls) && isImmediatelyBeforeEditText(node)) {
      return "Quay lại";
    }

    // 4. Nút xóa từng dòng lịch sử tìm kiếm (FrameLayout rỗng ngay sau tv_his / tvl_his)
    if (node.isClickable() && node.getChildCount() == 0) {
      String historyKeyword = findPrecedingSearchHistoryKeyword(node);
      if (historyKeyword != null) {
        return "Xóa " + historyKeyword + " khỏi lịch sử tìm kiếm";
      }
    }

    // 5. Nút góc trên bên trái màn hình Bản tin (ngay trước HorizontalScrollView chứa Đề xuất/Bạn bè)
    if (node.isClickable() && isImageViewClass(cls) && isBeforeFeedTabScroller(node)) {
      return "Phát trực tiếp";
    }

    // 6. Nút "Thêm vào giỏ hàng" trên thanh đáy Chi tiết sản phẩm (giữa "Chat" và "Mua ngay")
    if (node.isClickable() && node.getChildCount() == 0 && isBetweenChatAndBuyNow(node)) {
      return "Thêm vào giỏ hàng";
    }

    // 7. Hộp kiểm đăng ký VIP trên Trang Thanh toán (CheckBox rỗng ngay trước "Đăng ký VIP...")
    if (node.isCheckable() && isBeforeVipSignupText(node)) {
      String price = findPrecedingPriceText(node);
      return price != null
          ? "Đăng ký TikTok Shop VIP, " + price
          : "Đăng ký TikTok Shop VIP";
    }

    // 8. Nút cuối thanh nhập tin nhắn Phòng chat DM (ngay sau "Mở nhãn dán, GIF và biểu tượng cảm xúc")
    if (node.isClickable() && isButtonClass(cls) && isAfterStickerButtonInChat(node)) {
      return "Chọn ảnh từ thư viện";
    }

    // 9. Nút biểu tượng cảm xúc trong ô nhập chat LIVE (ImageView ngay sau "Nhập...")
    if (node.isClickable() && isImageViewClass(cls) && isAfterLiveInputHint(node)) {
      return "Biểu tượng cảm xúc";
    }

    // 10. Người đóng góp top trong phòng LIVE (chứa tv_audience_contribute_num_new)
    if (node.isClickable() && node.getChildCount() == 1) {
      AccessibilityNodeInfoCompat onlyChild = node.getChild(0);
      if (onlyChild != null
          && "tv_audience_contribute_num_new".equals(shortViewId(onlyChild))
          && !isEmptyTrimmed(onlyChild.getText())) {
        return "Người đóng góp top " + onlyChild.getText().toString().trim();
      }
    }

    // 11. Nút thanh công cụ đáy LIVE (ttlive_toolbar_default) không có nhãn con
    if ("ttlive_toolbar_default".equals(idName) && !hasReadableDescendant(node, 3)) {
      return resolveLiveToolbarLabel(node);
    }

    // 12. Ô chọn phân loại sản phẩm (FrameLayout rỗng trong RecyclerView ngang ở Chi tiết sản phẩm)
    if (node.isClickable() && node.getChildCount() == 0) {
      int variantIdx = findProductVariantIndex(node);
      if (variantIdx >= 0) {
        return "Phân loại " + (variantIdx + 1);
      }
    }

    // 13. Ảnh chính sản phẩm trong ViewPager ở Chi tiết sản phẩm
    if (node.isClickable() && isProductDetailHeroImageSlide(node)) {
      return "Ảnh sản phẩm";
    }

    return null;
  }

  @Nullable
  private static String resolveRawResourceId(
      @Nullable CharSequence packageName, @NonNull String rawDesc) {
    Matcher m = RAW_RES_ID_PATTERN.matcher(rawDesc);
    if (!m.matches() || packageName == null) {
      return null;
    }
    String pkg = packageName.toString();
    String cacheKey = pkg + ":" + rawDesc;
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
      if (value != null && !value.trim().isEmpty() && !isRawResourceIdDescription(value)) {
        String clean = cleanTikTokText(value).toString().trim();
        RESOLVED_RES_CACHE.put(cacheKey, clean);
        return clean;
      }
    } catch (Exception ignored) {
      // Tài nguyên không tồn tại hoặc không đọc được trên thiết bị này.
    }
    RESOLVED_RES_CACHE.put(cacheKey, "");
    return null;
  }

  @Nullable
  private static String resolveRawResourceFallback(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null && "touchpoint_normal_pendant".equals(shortViewId(parent))) {
      return "Đóng phần thưởng";
    }
    if (parent != null && isBetweenShareAndMore(parent)) {
      return "Giỏ hàng";
    }
    return "Đóng";
  }

  private static boolean isBetweenShareAndMore(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    String prev = findFirstDescendantLabel(parent.getChild(idx - 1));
    String next = findFirstDescendantLabel(parent.getChild(idx + 1));
    return "Chia sẻ".equalsIgnoreCase(prev) && "Khác".equalsIgnoreCase(next);
  }

  private static boolean isImmediatelyBeforeEditText(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
    return next != null && next.isEditable();
  }

  @Nullable
  private static String findPrecedingSearchHistoryKeyword(
      @NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return null;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    if (prev == null) {
      return null;
    }
    String prevId = shortViewId(prev);
    if (("tv_his".equals(prevId) || "tvl_his".equals(prevId)) && !isEmptyTrimmed(prev.getText())) {
      return prev.getText().toString().trim();
    }
    return null;
  }

  private static boolean isBeforeFeedTabScroller(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
    if (next == null || next.getClassName() == null
        || !next.getClassName().toString().endsWith("HorizontalScrollView")) {
      return false;
    }
    int count = Math.min(next.getChildCount(), 6);
    for (int i = 0; i < count; i++) {
      String label = findFirstDescendantLabel(next.getChild(i));
      if ("Đề xuất".equalsIgnoreCase(label)
          || "Đã follow".equalsIgnoreCase(label)
          || "Bạn bè".equalsIgnoreCase(label)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBetweenChatAndBuyNow(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    String prev = findFirstDescendantLabel(parent.getChild(idx - 1));
    String next = findFirstDescendantLabel(parent.getChild(idx + 1));
    return "Chat".equalsIgnoreCase(prev)
        && next.toLowerCase(Locale.ROOT).startsWith("mua ngay");
  }

  private static boolean isBeforeVipSignupText(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
    if (next == null || next.getText() == null) {
      return false;
    }
    String lower = next.getText().toString().toLowerCase(Locale.ROOT);
    return lower.contains("vip") || lower.contains("đăng ký");
  }

  @Nullable
  private static String findPrecedingPriceText(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return null;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    if (prev != null && prev.getText() != null) {
      String t = prev.getText().toString().trim();
      if (t.contains("₫") || t.contains("đ")) {
        return t;
      }
    }
    return null;
  }

  private static boolean isAfterStickerButtonInChat(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    if (prev == null || prev.getContentDescription() == null) {
      return false;
    }
    String prevDesc = prev.getContentDescription().toString().toLowerCase(Locale.ROOT);
    return prevDesc.contains("nhãn dán") || prevDesc.contains("biểu tượng cảm xúc");
  }

  private static boolean isAfterLiveInputHint(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    return prev != null && prev.getText() != null && "Nhập...".equals(prev.getText().toString().trim());
  }

  @Nullable
  private static String resolveLiveToolbarLabel(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0) {
      return null;
    }
    if (idx + 1 < parent.getChildCount()) {
      AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
      if (next != null
          && "ttlive_toolbar_default".equals(shortViewId(next))
          && "Chia sẻ".equalsIgnoreCase(findFirstDescendantLabel(next))) {
        return "Quà tặng";
      }
    }
    return "Tiện ích phát trực tiếp";
  }

  private static int findProductVariantIndex(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null
        || parent.getClassName() == null
        || !parent.getClassName().toString().endsWith("RecyclerView")
        || parent.getChildCount() < 2) {
      return -1;
    }
    // Kiểm tra anh em đều là FrameLayout rỗng (dải ảnh chọn phân loại trên trang sản phẩm)
    int emptyCount = 0;
    int checkLimit = Math.min(parent.getChildCount(), 5);
    for (int i = 0; i < checkLimit; i++) {
      AccessibilityNodeInfoCompat c = parent.getChild(i);
      if (c != null
          && c.isClickable()
          && c.getChildCount() == 0
          && isEmptyTrimmed(c.getText())
          && isEmptyTrimmed(c.getContentDescription())) {
        emptyCount++;
      }
    }
    if (emptyCount < checkLimit) {
      return -1;
    }
    // Xác nhận nằm trong trang chi tiết sản phẩm (ông/bà có anh em chứa nút "1/9" hoặc ViewPager)
    AccessibilityNodeInfoCompat grandParent = parent.getParent();
    if (grandParent == null || grandParent.getParent() == null) {
      return -1;
    }
    AccessibilityNodeInfoCompat list = grandParent.getParent();
    int gpIdx = indexInParent(list, grandParent);
    if (gpIdx > 0) {
      AccessibilityNodeInfoCompat prevSection = list.getChild(gpIdx - 1);
      if (prevSection != null && hasViewPagerChild(prevSection)) {
        return indexInParent(parent, node);
      }
    }
    return -1;
  }

  private static boolean isProductDetailHeroImageSlide(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null
        || parent.getClassName() == null
        || !parent.getClassName().toString().endsWith("ViewPager")) {
      return false;
    }
    if (node.getChildCount() == 0) {
      return false;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat c = node.getChild(i);
      if (c == null || !isImageViewClass(c.getClassName())) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasViewPagerChild(@NonNull AccessibilityNodeInfoCompat node) {
    int limit = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < limit; i++) {
      AccessibilityNodeInfoCompat c = node.getChild(i);
      if (c != null && c.getClassName() != null && c.getClassName().toString().endsWith("ViewPager")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLynxClass(@NonNull AccessibilityNodeInfoCompat node) {
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
        || c.endsWith("XSwiperUI");
  }

  private static boolean isEmptyLynxDecorativeNode(@NonNull AccessibilityNodeInfoCompat node) {
    return node.getChildCount() == 0
        && isEmptyTrimmed(node.getText())
        && isEmptyTrimmed(node.getContentDescription());
  }

  @Nullable
  private static LynxParentSnapshot getOrBuildLynxSnapshot(
      @NonNull AccessibilityNodeInfoCompat parent) {
    int count = parent.getChildCount();
    if (count <= 1) {
      return null;
    }
    long now = SystemClock.uptimeMillis();
    int key = parent.hashCode();
    LynxParentSnapshot cached = LYNX_SNAPSHOT_CACHE.get(key);
    if (cached != null && cached.childCount == count && now <= cached.expiresAtMs) {
      return cached;
    }
    if (LYNX_SNAPSHOT_CACHE.size() >= MAX_LYNX_SNAPSHOT_CACHE) {
      LYNX_SNAPSHOT_CACHE.clear();
    }
    LynxParentSnapshot snap = new LynxParentSnapshot(now + LYNX_SNAPSHOT_TTL_MS, count);
    int limit = Math.min(count, 160);
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
    LYNX_SNAPSHOT_CACHE.put(key, snap);
    return snap;
  }

  private static boolean isDuplicateLynxSibling(@NonNull AccessibilityNodeInfoCompat node) {
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
    LynxParentSnapshot snap = getOrBuildLynxSnapshot(parent);
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
    // Ẩn mảnh chữ ngắn nằm trong một nút clickable hoặc nhãn giá đầy đủ (ví dụ "Trả sau" trong
    // "Trả sau-99%", "-99%" trong "Trả sau-99%", "810K" và "₫" trong "810K₫")
    for (String clickLabel : snap.clickableLowerLabels) {
      if (clickLabel.length() > ownLower.length()
          && (clickLabel.startsWith(ownLower) || clickLabel.endsWith(ownLower))) {
        return true;
      }
    }
    if ("₫".equals(ownLower) && !snap.priceLowerLabels.isEmpty()) {
      return true;
    }
    for (String priceLabel : snap.priceLowerLabels) {
      if (priceLabel.length() > ownLower.length() && priceLabel.startsWith(ownLower)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSplitCountdownSibling(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.isClickable()) {
      return false;
    }
    String own = extractTrimmedLabel(node);
    if (!COUNTDOWN_PART_PATTERN.matcher(own).matches()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    LynxParentSnapshot snap = getOrBuildLynxSnapshot(parent);
    return snap != null && snap.hasCountdownTime;
  }

  private static boolean isEmptyDecorativeImageInsideLabeledParent(
      @NonNull AccessibilityNodeInfoCompat node) {
    if (!isImageViewClass(node.getClassName())
        || node.getChildCount() > 0
        || !isEmptyTrimmed(node.getText())
        || !isEmptyTrimmed(node.getContentDescription())) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    return !isEmptyTrimmed(parent.getContentDescription()) || !isEmptyTrimmed(parent.getText());
  }

  private static boolean isEmptyLiveChatAvatar(@NonNull AccessibilityNodeInfoCompat node) {
    if (!isImageViewClass(node.getClassName())
        || node.getChildCount() > 0
        || !isEmptyTrimmed(node.getText())
        || !isEmptyTrimmed(node.getContentDescription())) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || !parent.isLongClickable()) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
    return next != null && isTextViewClass(next.getClassName()) && !isEmptyTrimmed(next.getText());
  }

  private static boolean isEmptyShopPolicyOrRatingIcon(@NonNull AccessibilityNodeInfoCompat node) {
    if (!isImageViewClass(node.getClassName())
        || node.getChildCount() > 0
        || !isEmptyTrimmed(node.getText())
        || !isEmptyTrimmed(node.getContentDescription())) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx + 1 < parent.getChildCount()) {
      String nextText = extractTrimmedLabel(parent.getChild(idx + 1));
      if (nextText.startsWith("Trả hàng")
          || nextText.startsWith("Hoàn tiền")
          || nextText.startsWith("Tự động hoàn tiền")
          || nextText.matches("^\\d\\.\\d\\s*\\(.*\\)$")) {
        return true;
      }
    }
    if (idx > 0) {
      String prevText = extractTrimmedLabel(parent.getChild(idx - 1));
      if (prevText.startsWith("Đã bán online")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDuplicateChatHeaderAvatar(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.isClickable() || node.getChildCount() > 0) {
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
    int idx = indexInParent(parent, node);
    if (idx >= 0 && idx + 1 < parent.getChildCount()) {
      String nextLabel = findFirstDescendantLabel(parent.getChild(idx + 1));
      return own.equals(nextLabel);
    }
    return false;
  }

  private static boolean isDuplicateChildOfTextParent(@NonNull AccessibilityNodeInfoCompat node) {
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

  private static boolean isRedundantEmptyWrapper(
      @NonNull AccessibilityNodeInfoCompat node, @NonNull String idName) {
    if (!isEmptyTrimmed(node.getText())
        || !isEmptyTrimmed(node.getContentDescription())
        || !isEmptyTrimmed(node.getStateDescription())
        || node.isEditable()
        || node.isCheckable()
        || node.isScrollable()) {
      return false;
    }
    if ("upload_hot_area".equals(idName)
        || "touchpoint_profile_icon".equals(idName)
        || "touchpoint_normal_pendant".equals(idName)
        || "feed_multi_tag_layout".equals(idName)
        || "ttlive_toolbar_default".equals(idName)) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls == null) {
      return false;
    }
    String c = cls.toString();
    boolean isContainer =
        c.endsWith("FrameLayout")
            || c.endsWith("LinearLayout")
            || c.endsWith("RelativeLayout")
            || c.endsWith("ViewGroup")
            || c.equals("android.view.View");
    if (!isContainer) {
      return false;
    }
    // Không ẩn các nút rỗng có nhãn ngữ cảnh hợp lệ (Thêm vào giỏ hàng, Xóa lịch sử, Phân loại...)
    if (node.getChildCount() == 0) {
      if (isBetweenChatAndBuyNow(node)
          || findPrecedingSearchHistoryKeyword(node) != null
          || findProductVariantIndex(node) >= 0) {
        return false;
      }
      // Ẩn mọi khung bọc hoàn toàn rỗng (0 con, 0 chữ, 0 mô tả) như j7e, erb, pr9, n1b, y8u
      return true;
    }
    // Nếu khung bọc rỗng có con đã tự clickable/focusable (lkp, eyv, ibm, ia4, wg9, v4m, g5_, v2h...)
    // thì ẩn lớp vỏ bọc ngoài để không tạo điểm dừng thừa.
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && (child.isClickable() || child.isFocusable())) {
        return true;
      }
    }
    return false;
  }

  @NonNull
  static String shortViewId(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return "";
    }
    String vid = node.getViewIdResourceName();
    if (vid == null || vid.isEmpty()) {
      return "";
    }
    int slash = vid.lastIndexOf('/');
    return slash >= 0 && slash + 1 < vid.length() ? vid.substring(slash + 1) : vid;
  }

  private static boolean isEmptyTrimmed(@Nullable CharSequence cs) {
    return cs == null || cs.toString().trim().isEmpty();
  }

  @NonNull
  private static String extractTrimmedLabel(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return "";
    }
    if (!isEmptyTrimmed(node.getText())) {
      return node.getText().toString().trim();
    }
    if (!isEmptyTrimmed(node.getContentDescription())) {
      return node.getContentDescription().toString().trim();
    }
    return "";
  }

  @NonNull
  private static String findFirstDescendantLabel(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return "";
    }
    String own = extractTrimmedLabel(node);
    if (!own.isEmpty()) {
      return own;
    }
    int limit = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < limit; i++) {
      String childLabel = findFirstDescendantLabel(node.getChild(i));
      if (!childLabel.isEmpty()) {
        return childLabel;
      }
    }
    return "";
  }

  @NonNull
  private static String findFirstDescendantText(
      @Nullable AccessibilityNodeInfoCompat node, int depth) {
    if (node == null || depth < 0) {
      return "";
    }
    if (!isEmptyTrimmed(node.getText())) {
      return node.getText().toString().trim();
    }
    int limit = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < limit; i++) {
      String t = findFirstDescendantText(node.getChild(i), depth - 1);
      if (!t.isEmpty()) {
        return t;
      }
    }
    return "";
  }

  @Nullable
  private static String findNumericChildText(@NonNull AccessibilityNodeInfoCompat node) {
    int limit = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < limit; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && child.getText() != null) {
        String s = child.getText().toString().trim();
        if (DIGITS_ONLY_PATTERN.matcher(s).matches()) {
          return s;
        }
      }
    }
    return null;
  }

  private static boolean hasReadableDescendant(
      @NonNull AccessibilityNodeInfoCompat node, int depth) {
    if (depth <= 0) {
      return false;
    }
    int limit = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < limit; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      if (!extractTrimmedLabel(child).isEmpty() || hasReadableDescendant(child, depth - 1)) {
        return true;
      }
    }
    return false;
  }

  private static int indexInParent(
      @NonNull AccessibilityNodeInfoCompat parent, @NonNull AccessibilityNodeInfoCompat target) {
    int count = parent.getChildCount();
    for (int i = 0; i < count; i++) {
      if (target.equals(parent.getChild(i))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isImageViewClass(@Nullable CharSequence cls) {
    return cls != null && cls.toString().endsWith("ImageView");
  }

  private static boolean isTextViewClass(@Nullable CharSequence cls) {
    return cls != null && cls.toString().endsWith("TextView");
  }

  private static boolean isButtonClass(@Nullable CharSequence cls) {
    return cls != null && cls.toString().endsWith("Button");
  }
}
