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
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accessibility helper for Facebook Messenger ({@code com.facebook.orca}).
 *
 * <p>Cleans noisy metadata ({@code , whats_up}, repeated message interaction hints, double spaces,
 * reaction-bar typo), hides decorative/duplicate story & avatar nodes, appends missing timestamps
 * to conversation rows, normalizes "GỌI LẠI" buttons, and sets proper tab roles on bottom tabs.
 */
public final class MessengerAccessibilityHelper {

  public static final String MESSENGER_PACKAGE = "com.facebook.orca";

  private static final Pattern WHATS_UP_PATTERN =
      Pattern.compile("(?i),?\\s*whats_up\\b");

  private static final Pattern MESSAGE_HINT_PATTERN =
      Pattern.compile(
          "(?i),?\\s*nhấn đúp để xem ngày giờ gửi/nhận,\\s*nhấn đúp và giữ để bày tỏ cảm xúc về tin nhắn\\.?");

  private static final Pattern DOUBLE_SPACE_BLOCK_PATTERN =
      Pattern.compile("Chặn\\s{2,}trên Facebook");

  private static final Pattern LEADING_TAB_PATTERN =
      Pattern.compile("(?i)^Tab\\s+");

  private static final Pattern TIMESTAMP_PATTERN =
      Pattern.compile(
          "(?i)^(\\d{1,2}:\\d{2}|\\d+\\s*(phút|giờ|ngày|tuần|tháng|năm)|hôm qua|hôm nay|cn|chủ nhật|thứ\\s*[2-7]|\\d{1,2}\\s*thg\\s*\\d{1,2}(\\s*,?\\s*\\d{4})?)$");

  private static final String REACTION_BAR_TYPO =
      "Bảng điều khiến chứa danh sách các cảm xúc bạn có thể bày tỏ về tin nhắn này.";

  private MessengerAccessibilityHelper() {}

  public static boolean isMessengerPackage(@Nullable CharSequence packageName) {
    return packageName != null && MESSENGER_PACKAGE.contentEquals(packageName);
  }

  /** Cleans visible text for Messenger nodes. */
  @Nullable
  public static CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence text) {
    if (node == null || !isMessengerPackage(node.getPackageName()) || TextUtils.isEmpty(text)) {
      return text;
    }
    return cleanCommonText(node, text.toString(), /* isContentDescription= */ false);
  }

  /** Cleans contentDescription for Messenger nodes and enriches conversation/tab/call labels. */
  @Nullable
  public static CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence contentDescription) {
    if (node == null
        || !isMessengerPackage(node.getPackageName())
        || TextUtils.isEmpty(contentDescription)) {
      return contentDescription;
    }
    String raw = contentDescription.toString();
    String cleaned = cleanCommonText(node, raw, /* isContentDescription= */ true);

    // Strip duplicate leading "Tab " on bottom navigation tabs (ROLE_ACTION_BAR_TAB already speaks "Tab").
    if (isBottomTabNode(node)) {
      cleaned = LEADING_TAB_PATTERN.matcher(cleaned).replaceFirst("").trim();
    }

    // Append child timestamp to conversation row labels when missing.
    if (isConversationRowCandidate(node)) {
      String timestamp = findDirectTimestampChild(node);
      if (timestamp != null
          && !cleaned.toLowerCase(Locale.ROOT).contains(timestamp.toLowerCase(Locale.ROOT))) {
        cleaned = cleaned.isEmpty() ? timestamp : cleaned + ", " + timestamp;
      }
    }

    return cleaned;
  }

  /** Returns {@link Role#ROLE_ACTION_BAR_TAB} for Messenger bottom navigation tabs. */
  @Nullable
  public static Integer getRoleOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isMessengerPackage(node.getPackageName())) {
      return null;
    }
    if (isBottomTabNode(node)) {
      return Role.ROLE_ACTION_BAR_TAB;
    }
    return null;
  }

  /** Groups Meta AI and Create Story composite cards so focus lands once on the card. */
  public static boolean isGroupedCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isMessengerPackage(node.getPackageName())) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    if (TextUtils.isEmpty(desc)) {
      return false;
    }
    String d = desc.toString().trim();
    if (node.isClickable() && node.isFocusable()) {
      if (d.equalsIgnoreCase("Meta AI") || d.startsWith("Hỏi Meta AI")) {
        return true;
      }
      if (d.startsWith("Tạo tin") || d.startsWith("Tạo ghi chú")) {
        return true;
      }
    }
    return false;
  }

  /** Hides decorative/duplicate nodes on Messenger Inbox and inside Chat. */
  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isMessengerPackage(node.getPackageName())) {
      return false;
    }
    CharSequence desc = node.getContentDescription();
    CharSequence text = node.getText();
    String d = desc == null ? "" : desc.toString().trim();
    String t = text == null ? "" : text.toString().trim();

    // 1. Hide silent empty clickable ViewGroup overlays with no text, description, or children.
    if (d.isEmpty()
        && t.isEmpty()
        && node.getChildCount() == 0
        && !node.isEditable()
        && isViewGroupClass(node.getClassName())) {
      return true;
    }

    // 2. Hide decorative "Gì vậy?" / "Tạo tin" text nodes inside the Create Story card.
    if ((t.equals("Gì vậy?") || t.equals("Tạo tin") || d.equals("Gì vậy?"))
        && !node.isClickable()
        && hasCreateStoryAncestor(node)) {
      return true;
    }

    // 3. Hide repeated "Mở trang cá nhân của ..." leaf avatar buttons next to chat messages,
    // without hiding Friends' Active / Notes cards in the horizontal rail on Messenger Home.
    if (d.startsWith("Mở trang cá nhân của ")
        && node.isClickable()
        && t.isEmpty()
        && node.getChildCount() == 0
        && !hasHorizontalScrollableAncestor(node)) {
      return true;
    }

    // 4. Deduplicate friend music-note cards ("🎵 ...") when parent already carries the same label.
    if ((t.startsWith("🎵") || d.startsWith("🎵")) && hasAncestorWithSameLabel(node, t.isEmpty() ? d : t)) {
      return true;
    }

    // 5. Deduplicate inner call-card text when parent already speaks the call summary.
    if (!t.isEmpty()
        && (t.startsWith("Cuộc gọi") || t.contains("Cuộc gọi nhỡ") || t.contains("Cuộc gọi thoại") || t.contains("Cuộc gọi video"))
        && !node.isClickable()
        && hasAncestorWithSameLabel(node, t)) {
      return true;
    }

    return false;
  }

  private static String cleanCommonText(
      AccessibilityNodeInfoCompat node, String raw, boolean isContentDescription) {
    String s = raw;
    if (REACTION_BAR_TYPO.equals(s.trim())) {
      return "Chọn cảm xúc";
    }
    s = WHATS_UP_PATTERN.matcher(s).replaceAll("");
    s = MESSAGE_HINT_PATTERN.matcher(s).replaceAll("");
    s = DOUBLE_SPACE_BLOCK_PATTERN.matcher(s).replaceAll("Chặn trên Facebook");

    // Normalize uppercase "GỌI LẠI" button to "Gọi lại <Tên>" when caller context is available.
    if ("GỌI LẠI".equals(s.trim())) {
      String caller = findCallerNameForCallBack(node);
      return caller != null ? "Gọi lại " + caller : "Gọi lại";
    }

    // Trim trailing commas/whitespace left over after stripping suffixes.
    s = s.trim();
    while (s.endsWith(",")) {
      s = s.substring(0, s.length() - 1).trim();
    }
    if (!isContentDescription && s.isEmpty()) {
      return raw.trim();
    }
    return s;
  }

  private static boolean isBottomTabNode(AccessibilityNodeInfoCompat node) {
    CharSequence desc = node.getContentDescription();
    if (TextUtils.isEmpty(desc)) {
      return false;
    }
    String d = desc.toString().trim();
    if (d.startsWith("Tab ") || d.startsWith("tab ")) {
      return true;
    }
    if (!node.isClickable() || !node.isFocusable()) {
      return false;
    }
    String lower = d.toLowerCase(Locale.ROOT);
    return (lower.equals("đoạn chat")
            || lower.startsWith("đoạn chat,")
            || lower.equals("tin")
            || lower.startsWith("tin,")
            || lower.equals("danh bạ")
            || lower.startsWith("danh bạ,")
            || lower.equals("menu")
            || lower.startsWith("menu,"))
        && node.isSelected();
  }

  private static boolean isConversationRowCandidate(AccessibilityNodeInfoCompat node) {
    return node.isClickable()
        && node.isLongClickable()
        && node.isFocusable()
        && node.getChildCount() > 0;
  }

  @Nullable
  private static String findDirectTimestampChild(AccessibilityNodeInfoCompat row) {
    int count = Math.min(row.getChildCount(), 12);
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = row.getChild(i);
      if (child == null) {
        continue;
      }
      String label = firstNonEmpty(child.getText(), child.getContentDescription());
      if (label != null && TIMESTAMP_PATTERN.matcher(label).matches()) {
        return label;
      }
      int subCount = Math.min(child.getChildCount(), 6);
      for (int j = 0; j < subCount; j++) {
        AccessibilityNodeInfoCompat grand = child.getChild(j);
        if (grand == null) {
          continue;
        }
        String subLabel = firstNonEmpty(grand.getText(), grand.getContentDescription());
        if (subLabel != null && TIMESTAMP_PATTERN.matcher(subLabel).matches()) {
          return subLabel;
        }
      }
    }
    return null;
  }

  @Nullable
  private static String findCallerNameForCallBack(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    for (int depth = 0; depth < 3 && parent != null; depth++) {
      String parentDesc = firstNonEmpty(parent.getContentDescription(), parent.getText());
      if (parentDesc != null && !parentDesc.equalsIgnoreCase("GỌI LẠI")) {
        int comma = parentDesc.indexOf(',');
        String candidate = (comma > 0 ? parentDesc.substring(0, comma) : parentDesc).trim();
        if (!candidate.isEmpty()
            && !candidate.startsWith("Cuộc gọi")
            && candidate.length() <= 40) {
          return candidate;
        }
      }
      parent = parent.getParent();
    }
    return null;
  }

  private static boolean hasCreateStoryAncestor(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 4 && cur != null; depth++) {
      String label = firstNonEmpty(cur.getContentDescription(), cur.getText());
      if (label != null && (label.startsWith("Tạo tin") || label.startsWith("Tạo ghi chú"))) {
        return true;
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean hasAncestorWithSameLabel(
      AccessibilityNodeInfoCompat node, String targetLabel) {
    String normalizedTarget = targetLabel.trim();
    if (normalizedTarget.isEmpty()) {
      return false;
    }
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 4 && cur != null; depth++) {
      // Only treat an ancestor as a duplicate owner when it can itself receive accessibility focus;
      // otherwise hiding the child would hide the only focusable node for a friend's Note card.
      if (cur.isFocusable() && cur.isVisibleToUser()) {
        String label = firstNonEmpty(cur.getContentDescription(), cur.getText());
        if (label != null && label.contains(normalizedTarget)) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean hasHorizontalScrollableAncestor(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 6 && cur != null; depth++) {
      if (cur.isScrollable()) {
        CharSequence cls = cur.getClassName();
        String s = cls == null ? "" : cls.toString();
        if (s.contains("HorizontalScrollView")) {
          return true;
        }
        AccessibilityNodeInfoCompat.CollectionInfoCompat info = cur.getCollectionInfo();
        if (info != null && info.getRowCount() == 1 && info.getColumnCount() > 1) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean isViewGroupClass(@Nullable CharSequence cls) {
    if (cls == null) {
      return false;
    }
    String s = cls.toString();
    return s.endsWith("ViewGroup") || s.endsWith("FrameLayout") || s.endsWith("LinearLayout");
  }

  @Nullable
  private static String firstNonEmpty(@Nullable CharSequence a, @Nullable CharSequence b) {
    if (!TextUtils.isEmpty(a)) {
      String s = a.toString().trim();
      if (!s.isEmpty()) {
        return s;
      }
    }
    if (!TextUtils.isEmpty(b)) {
      String s = b.toString().trim();
      if (!s.isEmpty()) {
        return s;
      }
    }
    return null;
  }
}
