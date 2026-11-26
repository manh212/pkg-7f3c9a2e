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

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class FacebookAccessibilityHelper {

  public static final String FACEBOOK_PACKAGE = "com.facebook.katana";
  public static final String FEED_POST_ACTION_MENU = "Mở menu hành động";
  public static final String FEED_POST_OPEN_FULL = "Mở toàn bộ bài viết";

  private static final int MAX_GROUP_ANCESTOR_DEPTH = 6;
  private static final int MAX_SCAN_DEPTH = 5;

  private FacebookAccessibilityHelper() {}

  public static boolean isFacebookPackage(@Nullable CharSequence packageName) {
    return packageName != null && FACEBOOK_PACKAGE.contentEquals(packageName);
  }

  public static boolean isLeafFeedPost(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isFacebookPackage(node.getPackageName())) {
      return false;
    }
    if (!node.isClickable() || !node.isLongClickable() || !node.isFocusable()) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    if (TextUtils.isEmpty(desc)) {
      return false;
    }
    return hasCustomActionLabel(node, FEED_POST_ACTION_MENU)
        || hasCustomActionLabel(node, FEED_POST_OPEN_FULL);
  }

  public static boolean hasCustomActionLabel(
      @Nullable AccessibilityNodeInfoCompat node, String targetLabel) {
    if (node == null) {
      return false;
    }
    List<AccessibilityActionCompat> actions = node.getActionList();
    if (actions == null) {
      return false;
    }
    for (AccessibilityActionCompat action : actions) {
      CharSequence label = action.getLabel();
      if (label != null && targetLabel.equalsIgnoreCase(label.toString().trim())) {
        return true;
      }
    }
    return false;
  }

  public static boolean isGroupedCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isFacebookPackage(node.getPackageName())) {
      return false;
    }
    return isStoryCard(node)
        || isPymkCard(node)
        || isFriendRequestCard(node)
        || isNotificationCard(node)
        || isNestedProfileOptionsButton(node);
  }

  public static boolean isStoryCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || Role.getRole(node) != Role.ROLE_BUTTON) {
      return false;
    }
    String desc = trimString(node.getContentDescription());
    return desc.startsWith("Tin của ") || desc.equals("Tạo tin");
  }

  public static boolean isPymkCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || Role.getRole(node) == Role.ROLE_BUTTON) {
      return false;
    }
    String desc = trimString(node.getContentDescription());
    if (desc.isEmpty()) {
      return false;
    }
    return findDescendantButtonByDesc(node, "Thêm bạn bè", 0) != null
        || findDescendantButtonByDesc(node, "Gỡ gợi ý kết bạn", 0) != null;
  }

  public static boolean isFriendRequestCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || Role.getRole(node) != Role.ROLE_BUTTON || !node.isClickable()) {
      return false;
    }
    String desc = trimString(node.getContentDescription());
    return desc.startsWith("Xem trang cá nhân của ")
        && (findDescendantButtonByPrefix(node, "Xác nhận lời mời kết bạn", 0) != null
            || findDescendantButtonByPrefix(node, "Xóa lời mời kết bạn", 0) != null);
  }

  public static boolean isNotificationCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || Role.getRole(node) != Role.ROLE_BUTTON || !node.isClickable()) {
      return false;
    }
    String desc = trimString(node.getContentDescription());
    if (desc.isEmpty()) {
      return false;
    }
    if (desc.endsWith(". Chưa đọc") || desc.endsWith(". Đã đọc")) {
      return true;
    }
    return findDescendantButtonByDesc(node, "Quản lý cài đặt của thông báo", 0) != null;
  }

  private static boolean isNestedProfileOptionsButton(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || Role.getRole(node) != Role.ROLE_BUTTON) {
      return false;
    }
    String desc = trimString(node.getContentDescription());
    return desc.equals("Xem cài đặt khác của trang cá nhân")
        && findDescendantButtonByDesc(node, "Xem lựa chọn", 0) != null;
  }

  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isFacebookPackage(node.getPackageName())) {
      return false;
    }

    if (hasGroupedCardAncestor(node)) {
      return true;
    }

    String desc = trimString(node.getContentDescription());
    String text = trimString(node.getText());
    int role = Role.getRole(node);

    if (desc.isEmpty() && text.isEmpty()) {
      if (role == Role.ROLE_BUTTON) {
        return node.getChildCount() == 0;
      }
      if (role == Role.ROLE_VIEW_GROUP && node.isClickable() && node.isFocusable()) {
        if (node.getChildCount() == 0 || hasOnlyInteractiveChildren(node)) {
          return true;
        }
      }
      if (role == Role.ROLE_VIEW_GROUP && node.isFocusable() && hasHeadingChild(node)) {
        return true;
      }
    }

    if (desc.equals("Group Cover Photo")
        || desc.equals("Hình minh họa màu xám, phông nền")
        || (role == Role.ROLE_IMAGE && desc.equals("Ảnh đại diện"))) {
      return true;
    }
    if (text.equals("·") || desc.equals("·")) {
      return true;
    }

    if (role == Role.ROLE_IMAGE_BUTTON && desc.startsWith("Ảnh đại diện của ")) {
      AccessibilityNodeInfoCompat parent = node.getParent();
      if (parent != null && hasSiblingAuthorNode(parent, desc.substring("Ảnh đại diện của ".length()).trim())) {
        return true;
      }
    }

    if (role == Role.ROLE_VIEW_GROUP && node.isFocusable() && !node.isClickable()) {
      if (hasClickableButtonChild(node) && (!desc.isEmpty() || !text.isEmpty())) {
        if (desc.contains("•Theo dõi") || hasButtonChildWithText(node, text.isEmpty() ? desc : text)) {
          return true;
        }
      }
    }

    if (role == Role.ROLE_BUTTON) {
      AccessibilityNodeInfoCompat parent = node.getParent();
      if (parent != null && Role.getRole(parent) == Role.ROLE_BUTTON) {
        String parentDesc = trimString(parent.getContentDescription());
        if (!parentDesc.isEmpty()
            && (parentDesc.equals(desc)
                || parentDesc.equals(text)
                || (!text.isEmpty() && parentDesc.startsWith(text + ", ")))) {
          return true;
        }
      }
    }

    if (role == Role.ROLE_VIEW_GROUP && !text.isEmpty() && text.matches("\\d+")) {
      AccessibilityNodeInfoCompat parent = node.getParent();
      if (parent != null && hasSiblingWithText(parent, "Lời mời kết bạn")) {
        return true;
      }
    }

    if (role == Role.ROLE_BUTTON && desc.contains("RESHARE, ")) {
      return true;
    }
    if (role == Role.ROLE_IMAGE_BUTTON && desc.startsWith("Xem thước phim của ")) {
      return true;
    }
    if (role == Role.ROLE_BUTTON && !desc.isEmpty() && isDuplicateReelsActionButton(node, desc)) {
      return true;
    }

    return false;
  }

  public static @Nullable CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence text) {
    if (node == null || !isFacebookPackage(node.getPackageName()) || TextUtils.isEmpty(text)) {
      return text;
    }
    String raw = text.toString();
    String trimmed = raw.trim();
    if (trimmed.equals("Lời mời kết bạn")) {
      String count = findSiblingDigits(node.getParent());
      if (count != null) {
        return "Lời mời kết bạn, " + count;
      }
    }
    if (trimmed.contains("•Chia sẻ với: ")) {
      return trimmed.replace("•Chia sẻ với: ", ", ").replace("•", ", ");
    }
    return text;
  }

  public static @Nullable CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence description) {
    if (node == null || !isFacebookPackage(node.getPackageName())) {
      return description;
    }

    if (isStoryCard(node)) {
      return composeStoryLabel(node);
    }
    if (isPymkCard(node)) {
      return composePymkLabel(node);
    }
    if (isFriendRequestCard(node)) {
      return composeFriendRequestLabel(node);
    }
    if (isNotificationCard(node)) {
      return cleanNotificationLabel(trimString(description));
    }
    if (isNestedProfileOptionsButton(node)) {
      return "Lựa chọn khác của trang cá nhân";
    }

    if (TextUtils.isEmpty(description)) {
      return description;
    }
    String desc = description.toString().trim();

    if (desc.equals("Tạo, nhấn đúp để tạo bài viết, tin hoặc thước phim mới")) {
      return "Tạo bài viết hoặc tin";
    }
    if (desc.equals("Chọn ảnh hoặc video cho bài viết của bạn")) {
      return "Thêm ảnh hoặc video";
    }
    if (desc.equals("Công cụ chuyển trang cá nhân, nhấn đúp để mở danh sách trang cá nhân của bạn")) {
      return "Chuyển trang cá nhân";
    }
    if (desc.equals("Search")) {
      return "Tìm kiếm trên trang cá nhân";
    }
    if (desc.equals("Action chip profile picture")) {
      return "Trang cá nhân của bạn";
    }
    if (desc.equals("Chuyển đến trang cá nhân Reels của bạn")) {
      return "Trang cá nhân Reels";
    }
    if (desc.equals("Nhấn để xem Gói đăng ký hiện có của người sáng tạo nội dung này")) {
      return "Đăng ký kênh";
    }
    if (desc.equals("Liên kết để xem tất cả gợi ý cho bạn bè")) {
      return "Xem tất cả gợi ý kết bạn";
    }
    if (desc.equals("Bộ sưu tập đáng chú ý Đáng chú ý")) {
      return "Bộ sưu tập đáng chú ý";
    }
    if (desc.startsWith("Tìm kiếm trong ") && desc.length() > 25) {
      return "Tìm kiếm trong nhóm";
    }
    if (desc.startsWith("đã tham gia nhóm ")) {
      return "Đã tham gia";
    }
    if (desc.startsWith("mời người khác tham gia ")) {
      return "Mời bạn bè";
    }
    if (desc.startsWith("Lựa chọn khác cho bài viết của ")) {
      return "Lựa chọn khác";
    }

    if (desc.endsWith(" ơi, hãy xem trang cá nhân của bạn nhé")) {
      String name = desc.substring(0, desc.length() - " ơi, hãy xem trang cá nhân của bạn nhé".length()).trim();
      return "Trang cá nhân: " + name;
    }
    if (desc.endsWith(" ơi, hãy chuyển sang trang của bạn")) {
      String name = desc.substring(0, desc.length() - " ơi, hãy chuyển sang trang của bạn".length()).trim();
      return "Chuyển sang trang: " + name;
    }
    if (desc.startsWith("Mở công cụ chuyển trang cá nhân, ")) {
      return "Chuyển tài khoản, " + desc.substring("Mở công cụ chuyển trang cá nhân, ".length());
    }
    if (desc.startsWith("Tiêu đề: ")) {
      String rest = desc.substring("Tiêu đề: ".length());
      if (rest.contains(". Phần: đã thu gọn.")) {
        return rest.substring(0, rest.indexOf(". Phần: đã thu gọn.")).trim() + ", đã thu gọn";
      }
      if (rest.contains(". Phần: đã mở rộng.")) {
        return rest.substring(0, rest.indexOf(". Phần: đã mở rộng.")).trim() + ", đã mở rộng";
      }
    }

    if (desc.startsWith("Nút Thích.")) {
      String count = findFirstChildText(node);
      return TextUtils.isEmpty(count) ? "Thích" : "Thích, " + count;
    }
    if (desc.equals("Bình luận")) {
      String count = findFirstChildText(node);
      return TextUtils.isEmpty(count) ? "Bình luận" : "Bình luận, " + count;
    }
    if (desc.startsWith("Nút Chia sẻ.")) {
      String count = findFirstChildText(node);
      return TextUtils.isEmpty(count) ? "Chia sẻ" : "Chia sẻ, " + count;
    }
    if (desc.startsWith("Đang hiển thị ") && desc.contains(" bình luận.")) {
      String filter = desc.substring("Đang hiển thị ".length(), desc.indexOf(" bình luận.")).trim();
      return "Bộ lọc bình luận: " + filter;
    }

    if (desc.equals("Lời mời kết bạn")) {
      String count = findSiblingDigits(node.getParent());
      if (count != null) {
        return "Lời mời kết bạn, " + count;
      }
    }

    if (isLeafFeedPost(node) || desc.contains("•Chia sẻ với: ")) {
      String cleaned = desc.replaceAll(",\\s*Ảnh đại diện của [^,]+,", ",");
      cleaned = cleaned.replace("•Chia sẻ với: ", ", ");
      cleaned = cleaned.replace("•", ", ");
      return cleaned;
    }

    return description;
  }

  private static String composeStoryLabel(AccessibilityNodeInfoCompat card) {
    String base = trimString(card.getContentDescription());
    if (base.equals("Tạo tin")) {
      return "Tạo tin";
    }
    String author = base.startsWith("Tin của ")
        ? base.substring("Tin của ".length()).replace(", Chưa xem", "").replace(", Đã xem", "").trim()
        : "";
    String note = findExtraTextDescendant(card, author, 0);
    return TextUtils.isEmpty(note) ? base : base + ", " + note;
  }

  private static String composePymkLabel(AccessibilityNodeInfoCompat card) {
    String name = trimString(card.getContentDescription());
    String mutual = findMatchingTextDescendant(card, "bạn chung", 0);
    return TextUtils.isEmpty(mutual) ? name : name + ", " + mutual;
  }

  private static String composeFriendRequestLabel(AccessibilityNodeInfoCompat card) {
    String rawDesc = trimString(card.getContentDescription());
    String name = rawDesc.startsWith("Xem trang cá nhân của ")
        ? rawDesc.substring("Xem trang cá nhân của ".length()).trim()
        : rawDesc;
    StringBuilder sb = new StringBuilder(name);
    String meta = findFriendRequestMeta(card, name, 0);
    if (!TextUtils.isEmpty(meta)) {
      sb.append(", ").append(meta.replace(" · ", ", "));
    }
    String active = findAvatarActiveStatus(card, 0);
    if (!TextUtils.isEmpty(active)) {
      sb.append(", ").append(active);
    }
    return sb.toString();
  }

  private static String cleanNotificationLabel(String desc) {
    return desc.replace(".. ", ", ").replace(" · ", ", ");
  }

  private static boolean hasGroupedCardAncestor(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat current = node.getParent();
    int depth = 0;
    while (current != null && depth < MAX_GROUP_ANCESTOR_DEPTH) {
      if (isGroupedCard(current)) {
        return true;
      }
      current = current.getParent();
      depth++;
    }
    return false;
  }

  private static boolean hasOnlyInteractiveChildren(AccessibilityNodeInfoCompat parent) {
    int count = parent.getChildCount();
    if (count == 0) {
      return false;
    }
    boolean foundInteractive = false;
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      if (child.isClickable() || child.isFocusable()) {
        foundInteractive = true;
      } else if (!TextUtils.isEmpty(child.getText()) || !TextUtils.isEmpty(child.getContentDescription())) {
        return false;
      }
    }
    return foundInteractive;
  }

  private static boolean hasHeadingChild(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && child.isHeading()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSiblingAuthorNode(AccessibilityNodeInfoCompat parent, String authorName) {
    if (authorName.isEmpty()) {
      return false;
    }
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      String t = trimString(child.getText());
      String d = trimString(child.getContentDescription());
      if (t.equals(authorName) || d.equals(authorName) || t.startsWith(authorName + "•")) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasClickableButtonChild(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && Role.getRole(child) == Role.ROLE_BUTTON && child.isClickable()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasButtonChildWithText(AccessibilityNodeInfoCompat node, String target) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null
          && Role.getRole(child) == Role.ROLE_BUTTON
          && target.equals(trimString(child.getText()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSiblingWithText(AccessibilityNodeInfoCompat parent, String target) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child != null
          && (target.equals(trimString(child.getText()))
              || target.equals(trimString(child.getContentDescription())))) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String findSiblingDigits(@Nullable AccessibilityNodeInfoCompat parent) {
    if (parent == null) {
      return null;
    }
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      String t = trimString(child.getText());
      if (!t.isEmpty() && t.matches("\\d+")) {
        return t;
      }
    }
    return null;
  }

  private static boolean isDuplicateReelsActionButton(
      AccessibilityNodeInfoCompat node, String desc) {
    if (!desc.endsWith(" cảm xúc") && !desc.startsWith("Chia sẻ, ")) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || parent.getChildCount() < 2) {
      return false;
    }
    int sameDescCount = 0;
    AccessibilityNodeInfoCompat preferred = null;
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat sibling = parent.getChild(i);
      if (sibling == null || Role.getRole(sibling) != Role.ROLE_BUTTON) {
        continue;
      }
      if (desc.equals(trimString(sibling.getContentDescription()))) {
        sameDescCount++;
        if (preferred == null || (sibling.isLongClickable() && !preferred.isLongClickable())) {
          preferred = sibling;
        }
      }
    }
    return sameDescCount >= 2 && preferred != null && !node.equals(preferred);
  }

  public static @Nullable AccessibilityNodeInfoCompat findDescendantButtonByDesc(
      @Nullable AccessibilityNodeInfoCompat root, String exactDesc, int depth) {
    if (root == null || depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null) {
        continue;
      }
      if (Role.getRole(child) == Role.ROLE_BUTTON
          && exactDesc.equals(trimString(child.getContentDescription()))) {
        return child;
      }
      AccessibilityNodeInfoCompat found = findDescendantButtonByDesc(child, exactDesc, depth + 1);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  public static @Nullable AccessibilityNodeInfoCompat findDescendantButtonByPrefix(
      @Nullable AccessibilityNodeInfoCompat root, String prefix, int depth) {
    if (root == null || depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null) {
        continue;
      }
      if (Role.getRole(child) == Role.ROLE_BUTTON
          && trimString(child.getContentDescription()).startsWith(prefix)) {
        return child;
      }
      AccessibilityNodeInfoCompat found = findDescendantButtonByPrefix(child, prefix, depth + 1);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  public static @Nullable AccessibilityNodeInfoCompat findDescendantByDescPrefix(
      @Nullable AccessibilityNodeInfoCompat root, String prefix, boolean requireLongClickable, int depth) {
    if (root == null || depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null) {
        continue;
      }
      if (trimString(child.getContentDescription()).startsWith(prefix)
          && (!requireLongClickable || child.isLongClickable())) {
        return child;
      }
      AccessibilityNodeInfoCompat found =
          findDescendantByDescPrefix(child, prefix, requireLongClickable, depth + 1);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static @Nullable String findFirstChildText(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String t = trimString(child.getText());
      if (!t.isEmpty()) {
        return t;
      }
    }
    return null;
  }

  private static @Nullable String findExtraTextDescendant(
      AccessibilityNodeInfoCompat node, String exclude, int depth) {
    if (depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String t = trimString(child.getText());
      if (!t.isEmpty() && !t.equals(exclude)) {
        return t;
      }
      String nested = findExtraTextDescendant(child, exclude, depth + 1);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static @Nullable String findMatchingTextDescendant(
      AccessibilityNodeInfoCompat node, String contains, int depth) {
    if (depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String t = trimString(child.getText());
      if (!t.isEmpty() && t.contains(contains)) {
        return t;
      }
      String nested = findMatchingTextDescendant(child, contains, depth + 1);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static @Nullable String findFriendRequestMeta(
      AccessibilityNodeInfoCompat node, String personName, int depth) {
    if (depth > MAX_SCAN_DEPTH) {
      return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null || Role.getRole(child) == Role.ROLE_BUTTON) {
        continue;
      }
      String t = trimString(child.getText());
      if (!t.isEmpty()
          && !t.equals(personName)
          && !t.equals("Chấp nhận")
          && !t.equals("Xóa")
          && (t.contains("bạn chung")
              || t.contains("Người theo dõi")
              || t.endsWith("ngày")
              || t.endsWith("tuần")
              || t.endsWith("tháng")
              || t.endsWith("năm"))) {
        return t;
      }
      String nested = findFriendRequestMeta(child, personName, depth + 1);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static @Nullable String findAvatarActiveStatus(
      AccessibilityNodeInfoCompat node, int depth) {
    if (depth > 3) {
      return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String d = trimString(child.getContentDescription());
      if (d.startsWith("Ảnh đại diện, ")) {
        return d.substring("Ảnh đại diện, ".length()).trim();
      }
      String nested = findAvatarActiveStatus(child, depth + 1);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static String trimString(@Nullable CharSequence cs) {
    return cs == null ? "" : cs.toString().trim();
  }
}
