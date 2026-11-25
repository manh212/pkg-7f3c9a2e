/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.collapse;

import android.graphics.Rect;
import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import java.util.ArrayDeque;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collapse script for Facebook Messenger ({@code com.facebook.orca}):
 *
 * <ul>
 *   <li>Primary script: Inbox conversation rows — exposes 7 long-press sheet actions (Ghim/Bỏ ghim,
 *       Lưu trữ, Tắt/Bật thông báo, Đánh dấu đã đọc/chưa đọc, Hạn chế/Bỏ hạn chế, Chặn, Xóa).
 *   <li>Fallback 1 ({@link MessengerMessageCollapseScript}): In-chat message rows (including
 *       message bubbles nested inside a RecyclerView row wrapper) — exposes 4 message actions (Trả
 *       lời, Sao chép, Đặt lời nhắc, Khác) plus all 7 reactions (Thả tim, Haha, Wow, Buồn, Phẫn
 *       nộ, Thích, Biểu tượng cảm xúc khác).
 *   <li>Fallback 2 ({@link MessengerCallCardCollapseScript}): In-chat call cards — exposes the
 *       call-card long-press menu actions (Xóa, Đặt lời nhắc).
 * </ul>
 */
final class MessengerCollapseScript extends CollapseScript {

  static final String PACKAGE_NAME = "com.facebook.orca";
  static final String BUTTON_CLASS_SELECTOR =
      MenuAutomation.CLASS_SELECTOR_PREFIX + "android.widget.Button";
  static final String CONVERSATION_SHEET_CONTAINER =
      MenuAutomation.CLASS_SELECTOR_PREFIX + "android.widget.AbsListView";

  private static final int MAX_TARGET_SCAN_NODES = 32;

  private static final CollapseScript MESSAGE_SCRIPT = new MessengerMessageCollapseScript();

  private static final MenuCatalog CONVERSATION_CATALOG =
      new MenuCatalog(
          new MenuCatalog.Item[] {
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_pin,
                new String[] {"Ghim", "Bỏ ghim", "Pin", "Unpin"},
                new String[] {"ghim", "pin"}),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_archive,
                new String[] {"Lưu trữ", "Archive"},
                new String[] {"lưu trữ", "archive"}),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_notifications,
                new String[] {
                  "Tắt thông báo", "Bật thông báo", "Mute notifications", "Unmute notifications"
                },
                new String[] {"thông báo", "mute"},
                /* restoreFocusOnClose= */ false),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_read,
                new String[] {
                  "Đánh dấu là chưa đọc",
                  "Đánh dấu là đã đọc",
                  "Đánh dấu chưa đọc",
                  "Đánh dấu đã đọc",
                  "Mark as unread",
                  "Mark as read"
                },
                new String[] {"chưa đọc", "đã đọc", "unread", "mark as read"}),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_restrict,
                new String[] {"Hạn chế", "Bỏ hạn chế", "Restrict", "Unrestrict"},
                new String[] {"hạn chế", "restrict"}),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_block,
                new String[] {"Chặn", "Bỏ chặn", "Block", "Unblock"},
                new String[] {"chặn", "block"},
                /* restoreFocusOnClose= */ false),
            new MenuCatalog.Item(
                R.string.title_collapse_messenger_delete,
                new String[] {"Xóa", "Xóa cuộc trò chuyện", "Delete", "Delete chat"},
                new String[] {"xóa", "delete"},
                /* restoreFocusOnClose= */ false),
          },
          BUTTON_CLASS_SELECTOR,
          /* openMenuLabelResId= */ 0,
          CONVERSATION_SHEET_CONTAINER,
          /* longClick= */ true);

  MessengerCollapseScript() {
    super(PACKAGE_NAME, Pattern.ROOT_MENU, /* maxActions= */ 7);
  }

  @Override
  public @Nullable CollapseScript fallbackScript() {
    // In-chat messages and call cards are handled directly by MessengerMessageActionsMenu.
    return null;
  }

  @Override
  public MenuCatalog menuCatalog() {
    return CONVERSATION_CATALOG;
  }

  @Override
  public boolean isMenuItemRoot(AccessibilityNodeInfoCompat node) {
    if (node == null
        || !TextUtils.equals(PACKAGE_NAME, node.getPackageName())
        || !node.isVisibleToUser()
        || !node.isFocusable()
        || !node.isClickable()
        || !node.isLongClickable()) {
      return false;
    }
    if (isMessageHintPresent(node) || isActiveOrNonConversationCard(node)) {
      return false;
    }
    CharSequence spoken = ItemScan.spokenLabel(node);
    if (TextUtils.isEmpty(spoken)) {
      return false;
    }
    if (hasHorizontalScrollableAncestor(node) || isNarrowTileBounds(node)) {
      return false;
    }
    return hasScrollableListAncestor(node);
  }

  private static boolean isActiveOrNonConversationCard(AccessibilityNodeInfoCompat node) {
    if (isProfileAvatarButton(node)) {
      return true;
    }
    CharSequence desc = node.getContentDescription();
    String d = desc == null ? "" : desc.toString().trim();
    CharSequence text = node.getText();
    String t = text == null ? "" : text.toString().trim();
    if (d.startsWith("Tạo tin")
        || d.startsWith("Tạo ghi chú")
        || d.equalsIgnoreCase("Meta AI")
        || d.startsWith("Hỏi Meta AI")
        || d.startsWith("🎵")
        || t.startsWith("🎵")
        || d.equals("Tin nhắn mới")
        || d.equals("New message")
        || d.startsWith("Tab ")) {
      return true;
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

  /**
   * Active-people / Notes cards in the top horizontal rail are square or vertical tiles
   * (width < height * 1.5), whereas Inbox conversation rows are full-width horizontal rows.
   */
  private static boolean isNarrowTileBounds(AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
      return false;
    }
    return bounds.width() * 2 < bounds.height() * 3;
  }

  static boolean isMessageHintPresent(AccessibilityNodeInfoCompat node) {
    CharSequence desc = node.getContentDescription();
    if (desc != null) {
      String lower = desc.toString().toLowerCase(Locale.ROOT);
      if (lower.contains("bày tỏ cảm xúc về tin nhắn")
          || lower.contains("xem ngày giờ gửi/nhận")
          || lower.contains("react to this message")) {
        return true;
      }
    }
    return false;
  }

  static boolean isCallCardLabel(@Nullable CharSequence label) {
    if (TextUtils.isEmpty(label)) {
      return false;
    }
    String lower = label.toString().toLowerCase(Locale.ROOT);
    return lower.contains("cuộc gọi")
        || lower.contains("gọi nhỡ")
        || lower.contains("gọi thoại")
        || lower.contains("gọi video")
        || lower.contains("audio call")
        || lower.contains("video call")
        || lower.contains("missed call");
  }

  private static boolean isProfileAvatarButton(AccessibilityNodeInfoCompat node) {
    CharSequence desc = node.getContentDescription();
    if (desc == null) {
      return false;
    }
    String s = desc.toString().trim();
    return s.startsWith("Mở trang cá nhân của ") || s.startsWith("Open profile");
  }

  static boolean hasScrollableListAncestor(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 6 && cur != null; depth++) {
      CharSequence cls = cur.getClassName();
      if (cls != null) {
        String s = cls.toString();
        if (s.contains("RecyclerView") || s.contains("ListView")) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  /**
   * Searches {@code itemRoot} and its descendants (BFS) for the first visible, focusable,
   * long-clickable chat item target.
   */
  private static @Nullable AccessibilityNodeInfoCompat findNestedLongClickableTarget(
      AccessibilityNodeInfoCompat itemRoot, boolean wantCallCard) {
    if (itemRoot == null
        || !TextUtils.equals(PACKAGE_NAME, itemRoot.getPackageName())
        || !hasScrollableListAncestor(itemRoot)) {
      return null;
    }
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(itemRoot);
    int scanned = 0;
    AccessibilityNodeInfoCompat fallbackNonHint = null;
    while (!queue.isEmpty() && scanned < MAX_TARGET_SCAN_NODES) {
      AccessibilityNodeInfoCompat cur = queue.poll();
      scanned++;
      if (cur == null || !cur.isVisibleToUser()) {
        continue;
      }
      boolean isSelf = cur.equals(itemRoot);
      if (cur.isFocusable() && cur.isLongClickable() && !isProfileAvatarButton(cur)) {
        CharSequence label = ItemScan.spokenLabel(cur);
        boolean hasHint = isMessageHintPresent(cur);
        boolean isCall = isCallCardLabel(label);
        if (wantCallCard) {
          // Call cards inside chat are nested inside a non-longClickable RecyclerView row wrapper
          // (direct longClickable RecyclerView rows in Inbox are handled by CONVERSATION_CATALOG).
          if (!isSelf && isCall) {
            return cur;
          }
        } else {
          if (hasHint) {
            return cur;
          }
          if (!isSelf && !isCall && !TextUtils.isEmpty(label) && fallbackNonHint == null) {
            fallbackNonHint = cur;
          }
        }
      }
      int childCount = cur.getChildCount();
      for (int i = 0; i < childCount && scanned + queue.size() < MAX_TARGET_SCAN_NODES; i++) {
        AccessibilityNodeInfoCompat child = cur.getChild(i);
        if (child != null && child.isVisibleToUser()) {
          queue.add(child);
        }
      }
    }
    return wantCallCard ? null : fallbackNonHint;
  }

  /** Fallback 1: Individual messages inside a Messenger chat thread (4 actions + 7 reactions). */
  static final class MessengerMessageCollapseScript extends CollapseScript {

    private static final CollapseScript CALL_CARD_SCRIPT = new MessengerCallCardCollapseScript();

    private static final MenuCatalog MESSAGE_CATALOG =
        new MenuCatalog(
            new MenuCatalog.Item[] {
              // 4 message actions
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_reply,
                  new String[] {"Trả lời", "Reply"},
                  new String[] {"trả lời", "reply"},
                  /* restoreFocusOnClose= */ false),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_copy,
                  new String[] {"Sao chép", "Copy"},
                  new String[] {"sao chép", "copy"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_remind,
                  new String[] {"Đặt lời nhắc", "Lời nhắc", "Set reminder", "Remind"},
                  new String[] {"lời nhắc", "reminder"},
                  /* restoreFocusOnClose= */ false),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_more,
                  new String[] {"Khác", "More"},
                  new String[] {"khác", "more"},
                  /* restoreFocusOnClose= */ false),
              // 7 reaction buttons (from sibling reaction bar in the same sheet window)
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_heart,
                  new String[] {"❤", "❤️"},
                  new String[] {"❤"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_haha,
                  new String[] {"😆"},
                  new String[] {"😆"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_wow,
                  new String[] {"😮"},
                  new String[] {"😮"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_sad,
                  new String[] {"😢"},
                  new String[] {"😢"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_angry,
                  new String[] {"😡"},
                  new String[] {"😡"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_like,
                  new String[] {"👍"},
                  new String[] {"👍"}),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_react_more,
                  new String[] {
                    "Thêm biểu tượng cảm xúc",
                    "Biểu tượng cảm xúc khác",
                    "More reactions",
                    "Add reaction"
                  },
                  new String[] {"biểu tượng cảm xúc", "reaction", "cảm xúc khác"},
                  /* restoreFocusOnClose= */ false),
            },
            BUTTON_CLASS_SELECTOR,
            /* openMenuLabelResId= */ 0,
            /* sheetContainerViewId= */ null,
            /* longClick= */ true);

    MessengerMessageCollapseScript() {
      super(PACKAGE_NAME, Pattern.ROOT_MENU, /* maxActions= */ 11);
    }

    @Override
    public CollapseScript fallbackScript() {
      return CALL_CARD_SCRIPT;
    }

    @Override
    public MenuCatalog menuCatalog() {
      return MESSAGE_CATALOG;
    }

    @Override
    public @Nullable AccessibilityNodeInfoCompat menuItemTarget(
        AccessibilityNodeInfoCompat itemRoot) {
      return findNestedLongClickableTarget(itemRoot, /* wantCallCard= */ false);
    }
  }

  /** Fallback 2: Call cards inside a Messenger chat thread (Xóa, Đặt lời nhắc). */
  static final class MessengerCallCardCollapseScript extends CollapseScript {

    private static final MenuCatalog CALL_CARD_CATALOG =
        new MenuCatalog(
            new MenuCatalog.Item[] {
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_delete_message,
                  new String[] {"Xóa", "Delete"},
                  new String[] {"xóa", "delete"},
                  /* restoreFocusOnClose= */ false),
              new MenuCatalog.Item(
                  R.string.title_collapse_messenger_remind,
                  new String[] {"Đặt lời nhắc", "Lời nhắc", "Set reminder", "Remind"},
                  new String[] {"lời nhắc", "reminder"},
                  /* restoreFocusOnClose= */ false),
            },
            BUTTON_CLASS_SELECTOR,
            /* openMenuLabelResId= */ 0,
            CONVERSATION_SHEET_CONTAINER,
            /* longClick= */ true);

    MessengerCallCardCollapseScript() {
      super(PACKAGE_NAME, Pattern.ROOT_MENU, /* maxActions= */ 2);
    }

    @Override
    public MenuCatalog menuCatalog() {
      return CALL_CARD_CATALOG;
    }

    @Override
    public @Nullable AccessibilityNodeInfoCompat menuItemTarget(
        AccessibilityNodeInfoCompat itemRoot) {
      return findNestedLongClickableTarget(itemRoot, /* wantCallCard= */ true);
    }
  }
}
