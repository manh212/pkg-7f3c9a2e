/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cải thiện khả năng tiếp cận toàn diện cho 5 màn hình chính của Shopee ({@code com.shopee.*}):
 * Trang chủ, Kết quả tìm kiếm, Chi tiết sản phẩm, Trang đánh giá và Giỏ hàng.
 */
public final class ShopeeAccessibilityHelper {

  private static final String SHOPEE_PACKAGE_PREFIX = "com.shopee.";
  private static final String POPUP_BANNER_VIEW_ID_SUFFIX = "popup_banner_image";
  private static final int MAX_CARD_TEXT_SCAN = 28;
  private static final Pattern ICON_STAR_PATTERN = Pattern.compile("(?i)icon_star([1-5])$");
  private static final Pattern DIGITS_ONLY_PATTERN = Pattern.compile("^\\d+$");
  private static final Pattern LEADING_BANGS_PATTERN = Pattern.compile("^!+\\s*");
  private static final Pattern TRAILING_BANGS_PATTERN = Pattern.compile("\\s*!+$");
  private static final Pattern INNER_BANG_PATTERN = Pattern.compile("(?<=\\S)!(?=\\S)");

  private ShopeeAccessibilityHelper() {}

  public static boolean isShopeePackage(@Nullable CharSequence packageName) {
    if (packageName == null || packageName.length() < SHOPEE_PACKAGE_PREFIX.length()) {
      return false;
    }
    return packageName.toString().toLowerCase(Locale.ROOT).startsWith(SHOPEE_PACKAGE_PREFIX);
  }

  /**
   * Kiểm tra xem {@code description} có phải là mã kỹ thuật / {@code testID} của Shopee hay không.
   */
  public static boolean isTechnicalDescription(@Nullable CharSequence description) {
    if (description == null || description.length() == 0) {
      return false;
    }
    String raw = description.toString().trim();
    if (raw.isEmpty()) {
      return false;
    }
    int nl = raw.indexOf('\n');
    String head = (nl >= 0 ? raw.substring(0, nl) : raw).trim().toLowerCase(Locale.ROOT);
    if (head.isEmpty()) {
      return false;
    }
    return head.startsWith("dd_module_")
        || head.startsWith("home_square_icon_")
        || head.startsWith("tab_bar_button_")
        || head.startsWith("mini_live_streaming_")
        || head.startsWith("mini_video_")
        || head.equals("shopee_live_see_more")
        || head.equals("shopee_video_see_more")
        || head.equals("img-overlay")
        || head.equals("popup_banner_image");
  }

  /**
   * Làm sạch dấu chấm than giữ chỗ ({@code "!!!!4.9!27,7k đánh giá!"}, {@code "!!!!Combo..."})
   * trong văn bản Shopee mà không làm hỏng văn bản bình thường của ứng dụng khác.
   */
  @Nullable
  public static CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence text) {
    if (node == null
        || text == null
        || text.length() == 0
        || !isShopeePackage(node.getPackageName())) {
      return text;
    }
    return cleanPlaceholderBangs(text);
  }

  @NonNull
  static CharSequence cleanPlaceholderBangs(@NonNull CharSequence text) {
    String raw = text.toString();
    if (raw.indexOf('!') < 0) {
      return text;
    }
    boolean hasLeadingBangs = raw.startsWith("!!");
    boolean hasSeparatedRating = raw.contains("!") && raw.startsWith("!");
    if (!hasLeadingBangs && !hasSeparatedRating) {
      return text;
    }
    String cleaned = LEADING_BANGS_PATTERN.matcher(raw).replaceFirst("");
    cleaned = TRAILING_BANGS_PATTERN.matcher(cleaned).replaceFirst("");
    cleaned = INNER_BANG_PATTERN.matcher(cleaned).replaceAll(", ");
    cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
    return cleaned.isEmpty() ? text : cleaned;
  }

  /**
   * Lọc {@code contentDescription} và cung cấp nhãn thân thiện cho các nút rỗng trên Shopee.
   */
  @Nullable
  public static CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence description) {
    if (node == null || !isShopeePackage(node.getPackageName())) {
      return description;
    }
    if (isTechnicalDescription(description)) {
      if (hasViewIdSuffix(node, POPUP_BANNER_VIEW_ID_SUFFIX)) {
        return "Biểu ngữ nổi";
      }
      return "";
    }
    if (description != null && description.length() > 0) {
      return description;
    }
    String synthetic = resolveSyntheticLabel(node);
    return synthetic != null ? synthetic : description;
  }

  /**
   * Cung cấp trạng thái {@code Đã chọn / Chưa chọn} cho hộp kiểm tùy biến trong Giỏ hàng Shopee.
   */
  @Nullable
  public static CharSequence getStateOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isShopeePackage(node.getPackageName())) {
      return null;
    }
    String idName = shortViewId(node);
    if ("buttonCheckBoxChecked".equals(idName)) {
      return "Đã chọn";
    }
    if ("buttonCheckBoxNotChecked".equals(idName)) {
      return "Chưa chọn";
    }
    return null;
  }

  /**
   * Trả về vai trò ghi đè cho tab đáy và hộp kiểm tùy biến của Shopee.
   */
  @Nullable
  public static Integer getRoleOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isShopeePackage(node.getPackageName())) {
      return null;
    }
    String idName = shortViewId(node);
    if ("buttonCheckBoxNotChecked".equals(idName) || "buttonCheckBoxChecked".equals(idName)) {
      return Role.ROLE_CHECK_BOX;
    }
    if (isBottomTab(node)) {
      return Role.ROLE_TAB;
    }
    return null;
  }

  /**
   * Ẩn các node rác / trang trí / trùng lặp trên Shopee để vuốt trái/phải gọn gàng.
   */
  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isShopeePackage(node.getPackageName())) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    if (desc != null) {
      String d = desc.toString().trim().toLowerCase(Locale.ROOT);
      if ("img-overlay".equals(d)) {
        return true;
      }
      if (isHomeCardPrefix(d) && !hasReadableTextDescendant(node, MAX_CARD_TEXT_SCAN)) {
        return true;
      }
    }
    String idName = shortViewId(node);
    if ("user_avatar".equals(idName) && hasParentId(node, "rating_card")) {
      return true;
    }
    if (isSecondaryReviewStar(node, idName)) {
      return true;
    }
    if (isPopupBannerWrapperSibling(node)) {
      return true;
    }
    if (isEmptyOverlayAfterMainPic(node)) {
      return true;
    }
    if (isEmptyLogoInShopAds(node)) {
      return true;
    }
    return false;
  }

  /**
   * Gom mỗi thẻ sản phẩm (Trang chủ & Kết quả tìm kiếm), thẻ Live và thẻ Video thành 1 điểm dừng.
   */
  public static boolean isGroupedCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isShopeePackage(node.getPackageName())) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    if (desc != null && desc.length() > 0) {
      String d = desc.toString().trim().toLowerCase(Locale.ROOT);
      if (isHomeCardPrefix(d) && hasReadableTextDescendant(node, MAX_CARD_TEXT_SCAN)) {
        return true;
      }
    }
    return isSearchResultProductCard(node);
  }

  /**
   * Nhận diện các mục tab thanh điều hướng dưới cùng của Shopee (chứa icon
   * {@code tab_bar_button_*}).
   */
  public static boolean isBottomTab(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null
        || !isShopeePackage(node.getPackageName())
        || !node.isClickable()
        || node.getChildCount() < 2
        || node.getChildCount() > 4) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls == null || !"android.widget.FrameLayout".contentEquals(cls)) {
      return false;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      CharSequence childDesc = child.getContentDescription();
      if (childDesc != null
          && childDesc.toString().trim().toLowerCase(Locale.ROOT).startsWith("tab_bar_button_")) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static String resolveSyntheticLabel(@NonNull AccessibilityNodeInfoCompat node) {
    String idName = shortViewId(node);

    // 1. Nhãn theo ID rõ ràng trên Chi tiết sản phẩm, Đánh giá, Tìm kiếm, Giỏ hàng
    if (POPUP_BANNER_VIEW_ID_SUFFIX.equals(idName)) {
      return "Biểu ngữ nổi";
    }
    if ("buttonItemLike".equals(idName)) {
      return "Yêu thích";
    }
    if ("buttonSearchBarImageSearch".equals(idName)) {
      return "Tìm kiếm bằng hình ảnh";
    }
    if ("imageProductOfficialShopLabel".equals(idName) || "imageOfficialShopLabel".equals(idName)) {
      return "Shopee Mall";
    }
    if (idName.startsWith("imageProduct_")) {
      return "Ảnh sản phẩm";
    }
    if ("buttonSearchBar".equals(idName)) {
      String hint = findFirstChildText(node);
      return hint.isEmpty() ? "Tìm kiếm" : "Tìm kiếm, " + hint;
    }

    // Số sao trong trang Đánh giá (icon_star1..icon_star5)
    Matcher starMatcher = ICON_STAR_PATTERN.matcher(idName);
    if (starMatcher.matches()) {
      return starMatcher.group(1) + " sao";
    }

    // Số sao trong Chi tiết sản phẩm (5 ImageView imagefullFilledStar_undefined /
    // imageEmptyStar_undefined).
    if (isReviewStarId(idName) && !isSecondaryReviewStar(node, idName)) {
      int filled = countFilledSiblingStars(node);
      if (filled > 0) {
        return filled + " sao";
      }
    }

    // Hộp kiểm Giỏ hàng (kèm ngữ cảnh shop / sản phẩm / tất cả)
    if ("buttonCheckBoxNotChecked".equals(idName) || "buttonCheckBoxChecked".equals(idName)) {
      return resolveCartCheckBoxLabel(node);
    }

    // Thanh công cụ trên cùng: Quay lại, Chia sẻ, Giỏ hàng, Tùy chọn khác, Tin nhắn
    if (("buttonActionBarIcon".equals(idName) || "buttonActionBarIconItem".equals(idName))
        && hasParentId(node, "buttonActionBarBack")) {
      return "Quay lại";
    }
    if ("buttonActionBarView".equals(idName) && hasParentId(node, "buttonActionBarRight")) {
      String onlyNumber = extractSingleNumericChildText(node);
      if (onlyNumber != null) {
        return "Giỏ hàng, " + onlyNumber + " sản phẩm";
      }
    }
    if ("buttonActionBarIconItem".equals(idName) && hasParentId(node, "buttonActionBarRight")) {
      return resolveActionBarRightIconLabel(node);
    }

    // Nếu node đã có chữ riêng thì không ghi đè thêm nhãn tổng hợp
    CharSequence ownText = node.getText();
    if (ownText != null && ownText.length() > 0) {
      return null;
    }

    // Các nút ViewGroup rỗng dựa theo ngữ cảnh anh em / cha
    if (node.isClickable() && isUnlabeledEmptyViewGroup(node)) {
      if (hasParentId(node, "more_icon")) {
        return "Tùy chọn khác";
      }
      if (hasParentId(node, "buttonActionBarRight")) {
        return "Tin nhắn";
      }
      if (hasParentId(node, "rating_card")) {
        return "Ảnh đánh giá";
      }
      if (isPopupBannerCloseSibling(node)) {
        return "Đóng biểu ngữ nổi";
      }
      String searchBarNeighbor = resolveSearchBarNeighborLabel(node);
      if (searchBarNeighbor != null) {
        return searchBarNeighbor;
      }
      String qtyLabel = resolveQuantityButtonLabel(node);
      if (qtyLabel != null) {
        return qtyLabel;
      }
      if (isHelpfulIconAfterLabel(node)) {
        return "Đánh dấu hữu ích";
      }
    }
    return null;
  }

  @NonNull
  private static String resolveCartCheckBoxLabel(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return "Chọn";
    }
    int idx = indexInParent(parent, node);
    if (idx >= 0 && idx + 1 < parent.getChildCount()) {
      AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
      if (next != null) {
        String nextText = findNodeOrChildText(next);
        if ("Tất cả".equalsIgnoreCase(nextText)) {
          return "Chọn tất cả";
        }
        if (!nextText.isEmpty()) {
          return "Chọn shop " + nextText;
        }
      }
    }
    // Hộp kiểm nằm trong vỏ bọc bên trong thẻ sản phẩm của Giỏ hàng
    AccessibilityNodeInfoCompat grandParent = parent.getParent();
    if (grandParent != null) {
      int pIdx = indexInParent(grandParent, parent);
      if (pIdx >= 0) {
        for (int i = pIdx + 1; i < Math.min(grandParent.getChildCount(), pIdx + 4); i++) {
          AccessibilityNodeInfoCompat sibling = grandParent.getChild(i);
          if (sibling == null || sibling.getChildCount() > 0) {
            continue;
          }
          CharSequence t = sibling.getText();
          if (t != null) {
            String s = t.toString().trim();
            if (s.length() > 10 && !s.startsWith("Chỉ còn")) {
              return "Chọn " + s;
            }
          }
        }
      }
    }
    return "Chọn sản phẩm";
  }

  @Nullable
  private static String resolveActionBarRightIconLabel(@NonNull AccessibilityNodeInfoCompat node) {
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
      if (next != null && "buttonActionBarView".equals(shortViewId(next))) {
        return "Chia sẻ";
      }
    }
    if (idx == parent.getChildCount() - 1) {
      return "Tùy chọn khác";
    }
    return null;
  }

  @Nullable
  private static String resolveSearchBarNeighborLabel(@NonNull AccessibilityNodeInfoCompat node) {
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
      if (next != null && "inputSearchBar".equals(shortViewId(next))) {
        return "Quay lại";
      }
    }
    if (idx > 0) {
      AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
      if (prev != null && "inputSearchBar".equals(shortViewId(prev))) {
        return "Bộ lọc tìm kiếm";
      }
    }
    return null;
  }

  @Nullable
  private static String resolveQuantityButtonLabel(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0) {
      return null;
    }
    if (idx + 1 < parent.getChildCount() && isNumericEditText(parent.getChild(idx + 1))) {
      return "Giảm số lượng";
    }
    if (idx > 0 && isNumericEditText(parent.getChild(idx - 1))) {
      return "Tăng số lượng";
    }
    return null;
  }

  private static boolean isNumericEditText(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !node.isEditable()) {
      return false;
    }
    CharSequence text = node.getText();
    return text != null && DIGITS_ONLY_PATTERN.matcher(text.toString().trim()).matches();
  }

  private static boolean isHelpfulIconAfterLabel(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    if (prev == null || prev.getText() == null) {
      return false;
    }
    return prev.getText().toString().trim().toLowerCase(Locale.ROOT).startsWith("hữu ích");
  }

  private static boolean isSearchResultProductCard(@NonNull AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() < 3) {
      return false;
    }
    int limit = Math.min(node.getChildCount(), 6);
    for (int i = 0; i < limit; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && shortViewId(child).startsWith("itemCardItemTitle_")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isReviewStarId(@NonNull String idName) {
    return idName.startsWith("imagefullFilledStar") || idName.startsWith("imageEmptyStar");
  }

  private static boolean isSecondaryReviewStar(
      @NonNull AccessibilityNodeInfoCompat node, @NonNull String idName) {
    if (!isReviewStarId(idName)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    return prev != null && isReviewStarId(shortViewId(prev));
  }

  private static int countFilledSiblingStars(@NonNull AccessibilityNodeInfoCompat firstStar) {
    AccessibilityNodeInfoCompat parent = firstStar.getParent();
    if (parent == null) {
      return 0;
    }
    int start = indexInParent(parent, firstStar);
    if (start < 0) {
      return 0;
    }
    int filled = 0;
    for (int i = start; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat sibling = parent.getChild(i);
      if (sibling == null) {
        break;
      }
      String sid = shortViewId(sibling);
      if (!isReviewStarId(sid)) {
        break;
      }
      if (sid.startsWith("imagefullFilledStar")) {
        filled++;
      }
    }
    return filled;
  }

  private static boolean isEmptyOverlayAfterMainPic(@NonNull AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || !isUnlabeledEmptyViewGroup(node)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    return prev != null && shortViewId(prev).endsWith("_mainpic");
  }

  private static boolean isEmptyLogoInShopAds(@NonNull AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || !isUnlabeledEmptyViewGroup(node)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    return parent != null && hasParentId(parent, "shopAds");
  }

  private static boolean isHomeCardPrefix(@NonNull String lowerDesc) {
    return lowerDesc.startsWith("dd_module_product_card_")
        || lowerDesc.startsWith("mini_live_streaming_item_card_")
        || lowerDesc.startsWith("mini_video_item_card_");
  }

  private static boolean hasViewIdSuffix(
      @NonNull AccessibilityNodeInfoCompat node, @NonNull String suffix) {
    String vid = node.getViewIdResourceName();
    return vid != null && vid.endsWith(suffix);
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

  private static boolean hasParentId(
      @NonNull AccessibilityNodeInfoCompat node, @NonNull String expectedParentId) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    return parent != null && expectedParentId.equals(shortViewId(parent));
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

  private static boolean isUnlabeledEmptyViewGroup(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.getChildCount() != 0) {
      return false;
    }
    CharSequence text = node.getText();
    CharSequence desc = node.getContentDescription();
    String vid = node.getViewIdResourceName();
    return (text == null || text.length() == 0)
        && (desc == null || desc.length() == 0)
        && (vid == null || vid.isEmpty());
  }

  private static boolean isPopupBannerCloseSibling(@NonNull AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx <= 0) {
      return false;
    }
    AccessibilityNodeInfoCompat prev = parent.getChild(idx - 1);
    return prev != null && hasViewIdSuffix(prev, POPUP_BANNER_VIEW_ID_SUFFIX);
  }

  private static boolean isPopupBannerWrapperSibling(@NonNull AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || !isUnlabeledEmptyViewGroup(node)) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int idx = indexInParent(parent, node);
    if (idx < 0 || idx + 1 >= parent.getChildCount()) {
      return false;
    }
    AccessibilityNodeInfoCompat next = parent.getChild(idx + 1);
    return next != null && hasViewIdSuffix(next, POPUP_BANNER_VIEW_ID_SUFFIX);
  }

  @Nullable
  private static String extractSingleNumericChildText(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.getChildCount() != 1) {
      return null;
    }
    AccessibilityNodeInfoCompat child = node.getChild(0);
    if (child == null || child.getText() == null) {
      return null;
    }
    String s = child.getText().toString().trim();
    return DIGITS_ONLY_PATTERN.matcher(s).matches() ? s : null;
  }

  @NonNull
  private static String findFirstChildText(@NonNull AccessibilityNodeInfoCompat node) {
    int count = Math.min(node.getChildCount(), 4);
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && child.getText() != null) {
        String s = child.getText().toString().trim();
        if (!s.isEmpty()) {
          return s;
        }
      }
    }
    return "";
  }

  @NonNull
  private static String findNodeOrChildText(@NonNull AccessibilityNodeInfoCompat node) {
    if (node.getText() != null) {
      String s = node.getText().toString().trim();
      if (!s.isEmpty()) {
        return s;
      }
    }
    return findFirstChildText(node);
  }

  private static boolean hasReadableTextDescendant(
      @NonNull AccessibilityNodeInfoCompat root, int budget) {
    int remaining = budget;
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty() && remaining > 0) {
      AccessibilityNodeInfoCompat current = queue.poll();
      remaining--;
      if (current == null) {
        continue;
      }
      if (current != root) {
        CharSequence text = current.getText();
        if (text != null && text.toString().trim().length() > 0) {
          return true;
        }
      }
      int childCount = current.getChildCount();
      for (int i = 0; i < childCount && queue.size() < remaining; i++) {
        AccessibilityNodeInfoCompat child = current.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return false;
  }
}
