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

import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role.RoleName;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hỗ trợ cải thiện khả năng tiếp cận cho ứng dụng Zalo ({@code com.zing.zalo}).
 *
 * <p>Xử lý đồng bộ các vấn đề thực tế trên toàn bộ các màn hình Zalo:
 *
 * <ul>
 *   <li>Gắn nhãn tiếng Việt cho các nút mất nhãn trên thanh công cụ (Quét mã QR, Thêm, Nhóm, Thêm
 *       bạn, Đăng bài mới, Thông báo, Cài đặt) và chặn dừng tiêu điểm kép vào icon con.
 *   <li>Gắn đủ tên, vai trò Thẻ và trạng thái Đã chọn cho 5 tab điều hướng dưới cùng (Tin nhắn,
 *       Danh bạ, Khám phá, Tường nhà, Cá nhân), đồng thời gộp chấm thông báo {@code badge_dot} /
 *       {@code numnotification} vào nhãn tab.
 *   <li>Gắn nhãn các nút bị thiếu trên Tường nhà (Bình luận, X lượt thích, Trang cá nhân), bảng
 *       biểu tượng cảm xúc / nhãn dán và thanh bày tỏ cảm xúc khi nhấn giữ tin nhắn.
 *   <li>Việt hóa và gom gọn màn hình cuộc gọi / cuộc gọi đến (bỏ qua logo/ảnh trang trí và chữ lặp
 *       bên dưới nút).
 *   <li>Lọc ký tự rỗng {@code NUL} trong danh sách tin nhắn và cho phép OCR tự động đọc các thẻ
 *       tin nhắn OA tự vẽ kích thước lớn ({@code chat_left_rich_message_mc_row}).
 * </ul>
 */
public final class ZaloAccessibilityHelper {

  public static final String ZALO_PACKAGE = "com.zing.zalo";

  private static final String ID_PREFIX = ZALO_PACKAGE + ":id/";

  private static final String ID_ACTION_BAR_SEARCH_BTN = ID_PREFIX + "action_bar_search_btn";
  private static final String ID_ACTION_BAR_QRCODE = ID_PREFIX + "action_bar_qrcode";
  private static final String ID_ACTION_BAR_PLUS_BTN = ID_PREFIX + "action_bar_plus_btn";
  private static final String ID_ACTION_BAR_GROUP_TAB_BTN = ID_PREFIX + "action_bar_group_tab_btn";
  private static final String ID_ACTION_BAR_ADD_FRIEND_BTN =
      ID_PREFIX + "action_bar_add_friend_btn";
  private static final String ID_ACTION_BAR_NEW_POST_BTN = ID_PREFIX + "action_bar_new_post_btn";
  private static final String ID_ACTION_BAR_SETTING_BTN = ID_PREFIX + "action_bar_setting_btn";
  private static final String ID_IMG_NEW_NOTIFICATION_LIST =
      ID_PREFIX + "imgButtonNewNotificationList";
  private static final String ID_IC_NEW_NOTIFY_FEED = ID_PREFIX + "ic_newNotifyFeed";

  private static final String ID_MAINTAB_MESSAGE = ID_PREFIX + "maintab_message";
  private static final String ID_MAINTAB_CONTACT = ID_PREFIX + "maintab_contact";
  private static final String ID_MAINTAB_DISCOVERY = ID_PREFIX + "maintab_discovery";
  private static final String ID_MAINTAB_TIMELINE = ID_PREFIX + "maintab_timeline";
  private static final String ID_MAINTAB_METAB = ID_PREFIX + "maintab_metab";
  private static final String ID_BADGE_DOT = ID_PREFIX + "badge_dot";
  private static final String ID_NUM_NOTIFICATION = ID_PREFIX + "numnotification";

  private static final String ID_TAB_RECYCLER_VIEW = ID_PREFIX + "tab_recycler_view";
  private static final String ID_CHIP_TABS_CONTAINER = ID_PREFIX + "chip_tabs_container";
  private static final String ID_CHIP_TAB_ALL = ID_PREFIX + "chip_tab_all";
  private static final String ID_CHIP_TAB_FRIEND = ID_PREFIX + "chip_tab_friend";
  private static final String ID_CHIP_TAB_OA = ID_PREFIX + "chip_tab_oa";
  private static final String ID_FIRST_TAB_ITEM = ID_PREFIX + "first_tab_item";
  private static final String ID_SECOND_TAB_ITEM = ID_PREFIX + "second_tab_item";

  private static final String ID_POST_TRIGGERING = ID_PREFIX + "post_triggering";
  private static final String ID_AVATAR = ID_PREFIX + "avatar";
  private static final String ID_BTN_LIKE = ID_PREFIX + "btn_like";
  private static final String ID_REACTION_INFO = ID_PREFIX + "reaction_info";
  private static final String ID_TOTAL_LIKE = ID_PREFIX + "total_like";
  private static final String ID_COMMENT_COMPONENT = ID_PREFIX + "comment_component";

  private static final String ID_ZDS_ACTION_BAR = ID_PREFIX + "zds_action_bar";
  private static final String ID_ACTIONBAR_TRAILING_CONTAINER =
      ID_PREFIX + "actionbar_trailing_container";
  private static final String ID_HIGHLIGHT_FRAME_LAYOUT = ID_PREFIX + "highlight_frame_layout";
  private static final String ID_POPUP_CONTENT_CONTAINER = ID_PREFIX + "popup_content_container";
  private static final String ID_REACTION_CONTAINER_VIEW = ID_PREFIX + "reaction_container_view";
  private static final String ID_CHAT_LEFT_RICH_MESSAGE_MC_ROW =
      ID_PREFIX + "chat_left_rich_message_mc_row";
  private static final String ID_CHATLINELIST = ID_PREFIX + "chatlinelist";

  private static final String ID_BLC_INPUT_EMOJI_TAB = ID_PREFIX + "blc_input_emoji_tab";
  private static final String ID_GOTO_STORE_BTN = ID_PREFIX + "goto_store_btn";
  private static final String ID_DELETE_EMOJI_BUTTON = ID_PREFIX + "delete_emoji_button";
  private static final String ID_MORE_CONTAINER = ID_PREFIX + "moreContainer";
  private static final String ID_STICKER_LIST_INDICATOR = ID_PREFIX + "sticker_list_indicator";
  private static final String ID_STICKER_PANEL_PAGER = ID_PREFIX + "sticker_panel_pager";
  private static final String ID_STICKER_ITEM_ELEMENT = ID_PREFIX + "sticker_item_element";
  private static final String ID_ICN_EMOJI_ITEM = ID_PREFIX + "icn_emoji_item";
  private static final String ID_HEADER_NAME = ID_PREFIX + "header_name";
  private static final String ID_STICKER_NAME = ID_PREFIX + "sticker_name";
  private static final String ID_LAYOUT_SOCIAL_FEED = ID_PREFIX + "layoutSocialFeed";
  private static final String ID_RV_STORY_BAR = ID_PREFIX + "rv_story_bar";
  private static final String ID_LIKE_COMPONENT = ID_PREFIX + "like_component";

  private static final String ID_CALL_MINIMIZE_BTN = ID_PREFIX + "call_minimizeBtn";
  private static final String ID_CALL_WATERMASK_ZALO = ID_PREFIX + "call_waterMaskZalo";
  private static final String ID_CALL_HD_LABEL = ID_PREFIX + "call_hd_label";
  private static final String ID_CALL_SWITCH_VIDEO_BTN = ID_PREFIX + "call_switchVideoBtn";
  private static final String ID_CALL_AVATAR_VIEW = ID_PREFIX + "call_avatarView";

  private static final String ID_ANDROID_HOME = "android:id/home";
  private static final String ID_MENU_CALL = ID_PREFIX + "menu_call";
  private static final String ID_MENU_PRIVACY_QUICK_SETTING =
      ID_PREFIX + "menu_privacy_quick_setting";
  private static final String ID_MENU_DRAWER = ID_PREFIX + "menu_drawer";
  private static final String ID_TV_SEND_MES_OB = ID_PREFIX + "tvSendMes_ob";
  private static final String ID_LV_MEDIA_STORE = ID_PREFIX + "lv_media_store";
  private static final String ID_LAYOUT_BIO = ID_PREFIX + "layout_bio";
  private static final String ID_ACTIONBAR_BTN_TRAILING_2 =
      ID_PREFIX + "actionbar_btn_trailing_2";
  private static final String ID_CONTACT_CONTAINER = ID_PREFIX + "contact_container";
  private static final String ID_CHAT_CONTACT_IMAGE = ID_PREFIX + "chat_contact_image";
  private static final String ID_CHAT_RIGHT_DRAWER_LAYOUT =
      ID_PREFIX + "chat_right_drawer_layout";
  private static final String ID_PAGER = ID_PREFIX + "pager";

  private static final Set<String> ACTION_BAR_ICON_PARENTS =
      new HashSet<>(
          Arrays.asList(
              ID_ACTION_BAR_QRCODE,
              ID_ACTION_BAR_PLUS_BTN,
              ID_ACTION_BAR_GROUP_TAB_BTN,
              ID_ACTION_BAR_ADD_FRIEND_BTN,
              ID_ACTION_BAR_NEW_POST_BTN,
              ID_ACTION_BAR_SETTING_BTN));

  private static final Set<String> MAIN_TABS =
      new HashSet<>(
          Arrays.asList(
              ID_MAINTAB_MESSAGE,
              ID_MAINTAB_CONTACT,
              ID_MAINTAB_DISCOVERY,
              ID_MAINTAB_TIMELINE,
              ID_MAINTAB_METAB));

  private static final Set<String> HIDDEN_WRAPPER_IDS =
      new HashSet<>(
          Arrays.asList(
              ID_ACTIONBAR_TRAILING_CONTAINER,
              ID_CHIP_TABS_CONTAINER,
              ID_LAYOUT_SOCIAL_FEED,
              ID_CONTACT_CONTAINER));

  private static final Set<String> CALL_ACTION_ENGLISH_LABELS =
      new HashSet<>(Arrays.asList("change speaker", "end call", "mute record", "accept call"));

  private static final Set<String> CALL_DUPLICATE_TEXT_LABELS =
      new HashSet<>(Arrays.asList("loa", "kết thúc", "mic", "từ chối", "trả lời"));

  private ZaloAccessibilityHelper() {}

  public static boolean isZaloPackage(@Nullable CharSequence packageName) {
    return packageName != null && ZALO_PACKAGE.contentEquals(packageName);
  }

  public static boolean isZaloNode(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && isZaloPackage(node.getPackageName());
  }

  /**
   * Trả về {@code true} khi {@code node} là thanh bày tỏ cảm xúc khi nhấn giữ tin nhắn trên Zalo
   * ({@code reaction_container_view}) được vẽ trực tiếp không có view con.
   */
  public static boolean isReactionBarNode(@Nullable AccessibilityNodeInfoCompat node) {
    return isZaloNode(node)
        && ID_REACTION_CONTAINER_VIEW.equals(node.getViewIdResourceName())
        && node.getChildCount() == 0;
  }

  /**
   * Ẩn các icon trang trí, icon con bị trùng tiêu điểm, chấm thông báo thừa, chữ lặp dưới nút cuộc
   * gọi và các khung bọc ngoài không nên dừng tiêu điểm trong Zalo.
   */
  public static boolean shouldHideNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node)) {
      return false;
    }
    String id = node.getViewIdResourceName();
    if (ID_ACTION_BAR_SEARCH_BTN.equals(id)
        || ID_IMG_NEW_NOTIFICATION_LIST.equals(id)
        || ID_IC_NEW_NOTIFY_FEED.equals(id)
        || ID_BADGE_DOT.equals(id)
        || ID_NUM_NOTIFICATION.equals(id)
        || ID_CALL_WATERMASK_ZALO.equals(id)
        || ID_CALL_HD_LABEL.equals(id)
        || ID_CALL_AVATAR_VIEW.equals(id)
        || ID_MENU_CALL.equals(id)
        || ID_MENU_PRIVACY_QUICK_SETTING.equals(id)
        || ID_MENU_DRAWER.equals(id)) {
      return true;
    }
    if (id != null && HIDDEN_WRAPPER_IDS.contains(id) && node.getChildCount() > 0) {
      return true;
    }

    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null) {
      String parentId = parent.getViewIdResourceName();
      if (parentId != null
          && ACTION_BAR_ICON_PARENTS.contains(parentId)
          && isImageViewClass(node.getClassName())) {
        return true;
      }
      if (isDuplicateCallLabelText(node, parent)) {
        return true;
      }
      if (!node.isClickable()
          && isImageViewClass(node.getClassName())
          && TextUtils.isEmpty(node.getContentDescription())
          && isInsideStickerPanelGrid(parent)) {
        return true;
      }
    }
    if (isRedundantClickableWrapper(node)) {
      return true;
    }
    return false;
  }

  /**
   * Gom các nút thanh công cụ, 5 tab điều hướng dưới cùng, nút thông báo và nút lượt thích thành 1
   * khối tiêu điểm duy nhất để không bị lọt vào view con bên trong.
   */
  public static boolean isGroupedCard(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node)) {
      return false;
    }
    String id = node.getViewIdResourceName();
    if (id != null
        && (MAIN_TABS.contains(id)
            || ACTION_BAR_ICON_PARENTS.contains(id)
            || ID_REACTION_INFO.equals(id)
            || ID_BTN_LIKE.equals(id))) {
      return true;
    }
    return isNotificationFeedButton(node) || resolveProfileMenuLabel(node) != null;
  }

  /**
   * Lọc ký tự rỗng {@code NUL} và khoảng trắng thừa trong văn bản tin nhắn / danh sách hội thoại
   * Zalo.
   */
  @Nullable
  public static CharSequence filterText(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence rawText) {
    if (rawText == null || !isZaloNode(node)) {
      return rawText;
    }
    String text = rawText.toString();
    boolean hasNull = text.indexOf(0) >= 0;
    boolean hasEdgeNewline =
        !text.isEmpty() && (text.charAt(0) == '\n' || text.charAt(text.length() - 1) == '\n');
    if (!hasNull && !hasEdgeNewline) {
      return rawText;
    }
    return sanitizeZaloText(text);
  }

  /** Gắn nhãn tiếng Việt sạch cho các nút mất nhãn, tab điều hướng và màn hình cuộc gọi Zalo. */
  @Nullable
  public static CharSequence filterContentDescription(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable CharSequence rawDescription) {
    if (!isZaloNode(node)) {
      return rawDescription;
    }
    String id = node.getViewIdResourceName();
    if (id != null) {
      switch (id) {
        case ID_ACTION_BAR_QRCODE:
          return "Quét mã QR";
        case ID_ACTION_BAR_PLUS_BTN:
          return "Thêm";
        case ID_ACTION_BAR_GROUP_TAB_BTN:
          return "Nhóm";
        case ID_ACTION_BAR_ADD_FRIEND_BTN:
          return "Thêm bạn";
        case ID_ACTION_BAR_NEW_POST_BTN:
          return "Đăng bài mới";
        case ID_ACTION_BAR_SETTING_BTN:
          return "Cài đặt";
        case ID_MAINTAB_MESSAGE:
          return buildMainTabLabel("Tin nhắn", node);
        case ID_MAINTAB_CONTACT:
          return buildMainTabLabel("Danh bạ", node);
        case ID_MAINTAB_DISCOVERY:
          return buildMainTabLabel("Khám phá", node);
        case ID_MAINTAB_TIMELINE:
          return buildMainTabLabel("Tường nhà", node);
        case ID_MAINTAB_METAB:
          return buildMainTabLabel("Cá nhân", node);
        case ID_COMMENT_COMPONENT:
          return buildCommentLabel(node);
        case ID_REACTION_INFO:
          return buildReactionInfoLabel(node);
        case ID_BLC_INPUT_EMOJI_TAB:
          return "Biểu tượng cảm xúc";
        case ID_GOTO_STORE_BTN:
          return "Cửa hàng nhãn dán";
        case ID_DELETE_EMOJI_BUTTON:
          return "Xóa";
        case ID_MORE_CONTAINER:
          return "Tùy chọn bộ nhãn dán";
        case ID_REACTION_CONTAINER_VIEW:
          if (node.getChildCount() == 0 && TextUtils.isEmpty(rawDescription)) {
            return "Bày tỏ cảm xúc";
          }
          break;
        case ID_CALL_MINIMIZE_BTN:
          return "Thu nhỏ cuộc gọi";
        case ID_CALL_SWITCH_VIDEO_BTN:
          return "Chuyển sang gọi video";
        case ID_ANDROID_HOME:
          return "Quay lại";
        case ID_ACTIONBAR_BTN_TRAILING_2:
          if (TextUtils.isEmpty(rawDescription)) {
            return "Tùy chọn khác";
          }
          break;
        case ID_CHAT_CONTACT_IMAGE:
          if (TextUtils.isEmpty(rawDescription)) {
            return "Ảnh đại diện";
          }
          break;
        case ID_AVATAR:
          AccessibilityNodeInfoCompat parent = node.getParent();
          if (parent != null && ID_POST_TRIGGERING.equals(parent.getViewIdResourceName())) {
            return "Trang cá nhân";
          }
          break;
        default:
          break;
      }
    }

    if (isNotificationFeedButton(node)) {
      return buildNotificationButtonLabel(node);
    }

    CharSequence profileMenuLabel = resolveProfileMenuLabel(node);
    if (profileMenuLabel != null) {
      return profileMenuLabel;
    }

    CharSequence stickerTabLabel = resolveStickerIndicatorTabLabel(node);
    if (stickerTabLabel != null) {
      return stickerTabLabel;
    }

    CharSequence callActionLabel = resolveCallActionLabel(node, rawDescription);
    if (callActionLabel != null) {
      return callActionLabel;
    }

    if (TextUtils.isEmpty(rawDescription) && TextUtils.isEmpty(node.getText())) {
      int meTabCardIndex = getMeTabCardIndex(node);
      if (meTabCardIndex == 0) {
        return "Trang cá nhân của tôi";
      }
      if (meTabCardIndex == 1) {
        return "Danh mục cá nhân";
      }
      CharSequence profileHeaderLabel = resolveProfileHeaderLabel(node);
      if (profileHeaderLabel != null) {
        return profileHeaderLabel;
      }
      CharSequence storyLabel = resolveStoryItemLabel(node);
      if (storyLabel != null) {
        return storyLabel;
      }
      CharSequence emojiLabel = resolveEmojiCellLabel(node);
      if (emojiLabel != null) {
        return emojiLabel;
      }
      CharSequence stickerLabel = resolveStickerItemLabel(node);
      if (stickerLabel != null) {
        return stickerLabel;
      }
    }

    return rawDescription;
  }

  /**
   * Trả về trạng thái {@code "Đã chọn"} cho tab đang mở ở thanh điều hướng 5 tab dưới cùng (Zalo
   * chỉ hiển thị TextView tên tab cho đúng tab đang được chọn).
   */
  @Nullable
  public static CharSequence getStateOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node)) {
      return null;
    }
    String id = node.getViewIdResourceName();
    if (id != null && MAIN_TABS.contains(id) && !node.isSelected() && hasActiveTabTitleText(node)) {
      return "Đã chọn";
    }
    return null;
  }

  /** Chuẩn hóa vai trò Nút / Thẻ cho các thành phần tương tác trong Zalo. */
  @Nullable
  @RoleName
  public static Integer getRoleOverride(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node)) {
      return null;
    }
    String id = node.getViewIdResourceName();
    if (id != null) {
      if (MAIN_TABS.contains(id)
          || ID_CHIP_TAB_ALL.equals(id)
          || ID_CHIP_TAB_FRIEND.equals(id)
          || ID_CHIP_TAB_OA.equals(id)
          || ID_FIRST_TAB_ITEM.equals(id)
          || ID_SECOND_TAB_ITEM.equals(id)
          || ID_BLC_INPUT_EMOJI_TAB.equals(id)) {
        return Role.ROLE_TAB_BAR;
      }
      if (ACTION_BAR_ICON_PARENTS.contains(id)
          || ID_BTN_LIKE.equals(id)
          || ID_REACTION_INFO.equals(id)
          || ID_COMMENT_COMPONENT.equals(id)
          || ID_GOTO_STORE_BTN.equals(id)
          || ID_DELETE_EMOJI_BUTTON.equals(id)
          || ID_MORE_CONTAINER.equals(id)
          || ID_REACTION_CONTAINER_VIEW.equals(id)
          || ID_CALL_MINIMIZE_BTN.equals(id)
          || ID_CALL_SWITCH_VIDEO_BTN.equals(id)
          || ID_ANDROID_HOME.equals(id)
          || ID_TV_SEND_MES_OB.equals(id)
          || ID_ACTIONBAR_BTN_TRAILING_2.equals(id)
          || ID_CHAT_CONTACT_IMAGE.equals(id)) {
        return Role.ROLE_BUTTON;
      }
    }
    if (isNotificationFeedButton(node)
        || resolveProfileMenuLabel(node) != null
        || getMeTabCardIndex(node) >= 0) {
      return Role.ROLE_BUTTON;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null) {
      String parentId = parent.getViewIdResourceName();
      if (ID_TAB_RECYCLER_VIEW.equals(parentId) || ID_STICKER_LIST_INDICATOR.equals(parentId)) {
        return Role.ROLE_TAB_BAR;
      }
    }
    if (resolveCallActionLabel(node, node.getContentDescription()) != null
        || ID_STICKER_ITEM_ELEMENT.equals(id)
        || resolveStoryItemLabel(node) != null
        || resolveEmojiCellLabel(node) != null) {
      return Role.ROLE_BUTTON;
    }
    return null;
  }

  /**
   * Cho phép OCR tự động của Meridian chạy trên các thẻ tin nhắn OA / thẻ nội dung tự vẽ kích thước
   * lớn không có chữ trong Zalo (ví dụ con của {@code chat_left_rich_message_mc_row}).
   */
  public static boolean shouldAllowAutoOcr(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node) || node.getChildCount() != 0) {
      return false;
    }
    if (!TextUtils.isEmpty(node.getText()) || !TextUtils.isEmpty(node.getContentDescription())) {
      return false;
    }
    if (!node.isClickable() && !node.isFocusable()) {
      return false;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    String parentId = parent.getViewIdResourceName();
    if (ID_CHAT_LEFT_RICH_MESSAGE_MC_ROW.equals(parentId) || ID_CHATLINELIST.equals(parentId)) {
      return true;
    }
    // Các thẻ/mục tự vẽ không nhãn trong Tab Cá nhân, Tường nhà hoặc danh sách Zalo.
    CharSequence parentClass = parent.getClassName();
    if (parentClass != null) {
      String pCls = parentClass.toString();
      if (pCls.endsWith("ScrollView")
          || pCls.endsWith("RecyclerView")
          || pCls.endsWith("LinearLayout")) {
        return true;
      }
    }
    return false;
  }

  // ===== Pure logic helpers (dễ kiểm thử unit test) =====

  public static String sanitizeZaloText(String raw) {
    if (raw == null) {
      return "";
    }
    String cleaned = raw.replace("\0", "").trim();
    return cleaned;
  }

  public static String formatMainTabLabel(
      String tabName, boolean hasBadgeDot, @Nullable String notificationText) {
    if (notificationText != null && !notificationText.isEmpty()) {
      String trimmed = notificationText.trim();
      if ("N".equalsIgnoreCase(trimmed)) {
        return tabName + ", có tin mới";
      }
      return tabName + ", " + trimmed + " thông báo mới";
    }
    if (hasBadgeDot) {
      return tabName + ", có thông báo mới";
    }
    return tabName;
  }

  public static String formatNotificationButtonLabel(@Nullable String countText) {
    if (countText == null || countText.isEmpty()) {
      return "Thông báo";
    }
    return "Thông báo, " + countText.trim() + " thông báo mới";
  }

  public static String formatCallActionLabel(
      @Nullable String englishDescription, @Nullable String nextSiblingText) {
    if (englishDescription == null || englishDescription.isEmpty()) {
      return null;
    }
    String lower = englishDescription.trim().toLowerCase(Locale.ROOT);
    switch (lower) {
      case "change speaker":
        return "Loa ngoài";
      case "mute record":
        return "Bật hoặc tắt mic";
      case "accept call":
        return "Trả lời cuộc gọi";
      case "end call":
        if (nextSiblingText != null
            && nextSiblingText.trim().equalsIgnoreCase("Từ chối")) {
          return "Từ chối cuộc gọi";
        }
        return "Kết thúc cuộc gọi";
      default:
        return null;
    }
  }

  // ===== Internal node inspection helpers =====

  private static boolean isImageViewClass(@Nullable CharSequence className) {
    if (className == null) {
      return false;
    }
    String name = className.toString();
    return name.endsWith("ImageView") || name.endsWith("ImageButton");
  }

  private static boolean isNotificationFeedButton(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() == 0) {
      return false;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && ID_IMG_NEW_NOTIFICATION_LIST.equals(child.getViewIdResourceName())) {
        return true;
      }
    }
    return false;
  }

  private static String buildNotificationButtonLabel(AccessibilityNodeInfoCompat node) {
    String count = null;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && ID_IC_NEW_NOTIFY_FEED.equals(child.getViewIdResourceName())) {
        CharSequence t = child.getText();
        if (!TextUtils.isEmpty(t)) {
          count = t.toString();
          break;
        }
      }
    }
    return formatNotificationButtonLabel(count);
  }

  private static String buildMainTabLabel(String tabName, AccessibilityNodeInfoCompat node) {
    boolean hasBadgeDot = false;
    String notifyText = null;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String childId = child.getViewIdResourceName();
      if (ID_BADGE_DOT.equals(childId)) {
        hasBadgeDot = true;
      } else if (ID_NUM_NOTIFICATION.equals(childId) && !TextUtils.isEmpty(child.getText())) {
        notifyText = child.getText().toString();
      }
    }
    return formatMainTabLabel(tabName, hasBadgeDot, notifyText);
  }

  private static boolean hasActiveTabTitleText(AccessibilityNodeInfoCompat tabNode) {
    for (int i = 0; i < tabNode.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = tabNode.getChild(i);
      if (child == null) {
        continue;
      }
      String childId = child.getViewIdResourceName();
      if (ID_NUM_NOTIFICATION.equals(childId) || ID_BADGE_DOT.equals(childId)) {
        continue;
      }
      CharSequence t = child.getText();
      if (!TextUtils.isEmpty(t) && t.toString().trim().length() > 1) {
        return true;
      }
    }
    return false;
  }

  private static String buildCommentLabel(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && !TextUtils.isEmpty(child.getText())) {
        String count = child.getText().toString().trim();
        if (!count.isEmpty()) {
          return count + " bình luận";
        }
      }
    }
    return "Bình luận";
  }

  private static String buildReactionInfoLabel(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null
          && ID_TOTAL_LIKE.equals(child.getViewIdResourceName())
          && !TextUtils.isEmpty(child.getText())) {
        return child.getText().toString().trim() + " lượt thích";
      }
    }
    return "Lượt thích";
  }

  @Nullable
  private static CharSequence resolveStickerIndicatorTabLabel(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null || !ID_STICKER_LIST_INDICATOR.equals(parent.getViewIdResourceName())) {
      return null;
    }
    if (!TextUtils.isEmpty(node.getText()) || !TextUtils.isEmpty(node.getContentDescription())) {
      return null;
    }
    int index = -1;
    int emojiTabIndex = -1;
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      if (ID_BLC_INPUT_EMOJI_TAB.equals(child.getViewIdResourceName())) {
        emojiTabIndex = i;
      }
      if (child.equals(node)) {
        index = i;
      }
    }
    if (index < 0) {
      return null;
    }
    if (index == 0) {
      return "Tìm kiếm nhãn dán";
    }
    int stickerNumber = (emojiTabIndex >= 0 && index > emojiTabIndex) ? (index - emojiTabIndex) : index;
    return "Bộ nhãn dán " + stickerNumber;
  }

  @Nullable
  private static CharSequence resolveCallActionLabel(
      AccessibilityNodeInfoCompat node, @Nullable CharSequence rawDescription) {
    if (TextUtils.isEmpty(rawDescription) || !isImageViewClass(node.getClassName())) {
      return null;
    }
    String desc = rawDescription.toString();
    if (!CALL_ACTION_ENGLISH_LABELS.contains(desc.trim().toLowerCase(Locale.ROOT))) {
      return null;
    }
    String nextSiblingText = null;
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null) {
      int count = parent.getChildCount();
      for (int i = 0; i < count - 1; i++) {
        AccessibilityNodeInfoCompat child = parent.getChild(i);
        if (child != null && child.equals(node)) {
          AccessibilityNodeInfoCompat next = parent.getChild(i + 1);
          if (next != null && !TextUtils.isEmpty(next.getText())) {
            nextSiblingText = next.getText().toString();
          }
          break;
        }
      }
    }
    return formatCallActionLabel(desc, nextSiblingText);
  }

  private static boolean isDuplicateCallLabelText(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat parent) {
    CharSequence text = node.getText();
    if (TextUtils.isEmpty(text)) {
      return false;
    }
    if (!CALL_DUPLICATE_TEXT_LABELS.contains(text.toString().trim().toLowerCase(Locale.ROOT))) {
      return false;
    }
    int count = parent.getChildCount();
    for (int i = 1; i < count; i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child != null && child.equals(node)) {
        AccessibilityNodeInfoCompat prev = parent.getChild(i - 1);
        if (prev != null && !TextUtils.isEmpty(prev.getContentDescription())) {
          String prevDesc = prev.getContentDescription().toString().trim().toLowerCase(Locale.ROOT);
          return CALL_ACTION_ENGLISH_LABELS.contains(prevDesc);
        }
        break;
      }
    }
    return false;
  }

  private static boolean isRedundantClickableWrapper(AccessibilityNodeInfoCompat node) {
    if (node.getChildCount() == 0
        || !TextUtils.isEmpty(node.getText())
        || !TextUtils.isEmpty(node.getContentDescription())) {
      return false;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String childId = child.getViewIdResourceName();
      if (ID_LIKE_COMPONENT.equals(childId)
          || ID_COMMENT_COMPONENT.equals(childId)
          || ID_STICKER_PANEL_PAGER.equals(childId)
          || ID_STICKER_LIST_INDICATOR.equals(childId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isInsideStickerPanelGrid(AccessibilityNodeInfoCompat grid) {
    CharSequence cls = grid.getClassName();
    if (cls == null || !cls.toString().endsWith("GridView")) {
      return false;
    }
    AccessibilityNodeInfoCompat gp = grid.getParent();
    return gp != null && ID_STICKER_PANEL_PAGER.equals(gp.getViewIdResourceName());
  }

  @Nullable
  private static CharSequence resolveStoryItemLabel(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() != 0) {
      return null;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    AccessibilityNodeInfoCompat grandParent = parent.getParent();
    if (grandParent == null || !ID_RV_STORY_BAR.equals(grandParent.getViewIdResourceName())) {
      return null;
    }
    for (int i = 0; i < grandParent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat item = grandParent.getChild(i);
      if (item != null && item.equals(parent)) {
        return "Khoảnh khắc " + (i + 1);
      }
    }
    return "Khoảnh khắc";
  }

  @Nullable
  private static CharSequence resolveEmojiCellLabel(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() != 0) {
      return null;
    }
    AccessibilityNodeInfoCompat row = node.getParent();
    if (row == null || !ID_ICN_EMOJI_ITEM.equals(row.getViewIdResourceName())) {
      return null;
    }
    int col = -1;
    for (int i = 0; i < row.getChildCount(); i++) {
      AccessibilityNodeInfoCompat c = row.getChild(i);
      if (c != null && c.equals(node)) {
        col = i + 1;
        break;
      }
    }
    int rowNum = -1;
    AccessibilityNodeInfoCompat list = row.getParent();
    if (list != null) {
      int emojiRowCount = 0;
      for (int i = 0; i < list.getChildCount(); i++) {
        AccessibilityNodeInfoCompat r = list.getChild(i);
        if (r != null && ID_ICN_EMOJI_ITEM.equals(r.getViewIdResourceName())) {
          emojiRowCount++;
          if (r.equals(row)) {
            rowNum = emojiRowCount;
            break;
          }
        }
      }
    }
    if (rowNum > 0 && col > 0) {
      return "Biểu tượng cảm xúc hàng " + rowNum + ", cột " + col;
    }
    return col > 0 ? ("Biểu tượng cảm xúc " + col) : "Biểu tượng cảm xúc";
  }

  @Nullable
  private static CharSequence resolveStickerItemLabel(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable()) {
      return null;
    }
    String id = node.getViewIdResourceName();
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    if (ID_STICKER_ITEM_ELEMENT.equals(id)) {
      AccessibilityNodeInfoCompat grid = parent.getParent();
      if (grid != null) {
        String packName = findStickerPackName(grid);
        int index = findChildIndex(grid, parent, /* skipHeaders= */ true);
        return formatStickerLabel(packName, index);
      }
      return "Nhãn dán";
    }
    if (isImageViewClass(node.getClassName()) && isInsideStickerPanelGrid(parent)) {
      int index = findClickableImageIndex(parent, node);
      return index > 0 ? ("Nhãn dán gần đây " + index) : "Nhãn dán gần đây";
    }
    return null;
  }

  @Nullable
  private static String findStickerPackName(AccessibilityNodeInfoCompat grid) {
    for (int i = 0; i < grid.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = grid.getChild(i);
      if (child == null) {
        continue;
      }
      for (int j = 0; j < child.getChildCount(); j++) {
        AccessibilityNodeInfoCompat grand = child.getChild(j);
        if (grand == null) {
          continue;
        }
        String gid = grand.getViewIdResourceName();
        if ((ID_HEADER_NAME.equals(gid) || ID_STICKER_NAME.equals(gid))
            && !TextUtils.isEmpty(grand.getText())) {
          return grand.getText().toString().trim();
        }
      }
    }
    return null;
  }

  private static int findChildIndex(
      AccessibilityNodeInfoCompat grid, AccessibilityNodeInfoCompat target, boolean skipHeaders) {
    int count = 0;
    for (int i = 0; i < grid.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = grid.getChild(i);
      if (child == null) {
        continue;
      }
      if (skipHeaders && !hasStickerElementChild(child)) {
        continue;
      }
      count++;
      if (child.equals(target)) {
        return count;
      }
    }
    return -1;
  }

  private static boolean hasStickerElementChild(AccessibilityNodeInfoCompat container) {
    for (int i = 0; i < container.getChildCount(); i++) {
      AccessibilityNodeInfoCompat c = container.getChild(i);
      if (c != null && ID_STICKER_ITEM_ELEMENT.equals(c.getViewIdResourceName())) {
        return true;
      }
    }
    return false;
  }

  private static int findClickableImageIndex(
      AccessibilityNodeInfoCompat grid, AccessibilityNodeInfoCompat target) {
    int count = 0;
    for (int i = 0; i < grid.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = grid.getChild(i);
      if (child != null && child.isClickable() && isImageViewClass(child.getClassName())) {
        count++;
        if (child.equals(target)) {
          return count;
        }
      }
    }
    return -1;
  }

  public static String formatStickerLabel(@Nullable String packName, int index) {
    String base = index > 0 ? ("Nhãn dán " + index) : "Nhãn dán";
    if (packName != null && !packName.isEmpty()) {
      return base + ", " + packName.trim();
    }
    return base;
  }

  /**
   * Trả về {@code 0} cho khối "Trang cá nhân của tôi" (ảnh đại diện / tên / Trạng thái) hoặc
   * {@code 1} cho khối "Danh mục cá nhân" (zCloud, zStyle, My Documents, Dữ liệu trên máy, Ví QR,
   * Tài khoản và bảo mật, Quyền riêng tư) trong Tab Cá nhân Zalo; trả về {@code -1} nếu không phải.
   */
  public static int getMeTabCardIndex(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node)
        || !node.isClickable()
        || !node.isLongClickable()
        || node.getChildCount() != 0
        || !TextUtils.isEmpty(node.getText())
        || !TextUtils.isEmpty(node.getContentDescription())) {
      return -1;
    }
    AccessibilityNodeInfoCompat scroll = node.getParent();
    if (scroll == null || scroll.getChildCount() != 2) {
      return -1;
    }
    CharSequence scrollClass = scroll.getClassName();
    if (scrollClass == null || !scrollClass.toString().endsWith("ScrollView")) {
      return -1;
    }
    AccessibilityNodeInfoCompat page = scroll.getParent();
    AccessibilityNodeInfoCompat pager = page != null ? page.getParent() : null;
    if (pager == null || !ID_PAGER.equals(pager.getViewIdResourceName())) {
      return -1;
    }
    AccessibilityNodeInfoCompat c0 = scroll.getChild(0);
    AccessibilityNodeInfoCompat c1 = scroll.getChild(1);
    if (c0 != null && c0.equals(node)) {
      return 0;
    }
    if (c1 != null && c1.equals(node)) {
      return 1;
    }
    return -1;
  }

  /**
   * Trả về danh sách nhãn các nút ngang trong hàng thao tác nhanh của màn hình Thông tin nhóm /
   * cuộc trò chuyện (ví dụ {@code "Tìm tin nhắn\nThêm thành viên\nTắt thông báo"}), hoặc {@code
   * null} nếu node không phải hàng nút nhanh.
   */
  @Nullable
  public static String[] getChatInfoQuickActionLabels(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isZaloNode(node) || !node.isClickable() || node.getChildCount() != 0) {
      return null;
    }
    CharSequence raw = node.getText();
    if (TextUtils.isEmpty(raw)) {
      return null;
    }
    String text = raw.toString().trim();
    if (!text.contains("\n")) {
      return null;
    }
    if (!hasAncestorWithId(node, ID_CHAT_RIGHT_DRAWER_LAYOUT)) {
      return null;
    }
    String[] parts = text.split("\n");
    java.util.List<String> labels = new java.util.ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        labels.add(trimmed);
      }
    }
    if (labels.size() < 3 || labels.size() > 5) {
      return null;
    }
    for (String label : labels) {
      if (label.length() > 32) {
        return null;
      }
    }
    return labels.toArray(new String[0]);
  }

  private static boolean hasAncestorWithId(AccessibilityNodeInfoCompat node, String targetId) {
    AccessibilityNodeInfoCompat cur = node.getParent();
    int depth = 0;
    while (cur != null && depth < 8) {
      if (targetId.equals(cur.getViewIdResourceName())) {
        return true;
      }
      cur = cur.getParent();
      depth++;
    }
    return false;
  }

  @Nullable
  private static CharSequence resolveProfileMenuLabel(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() == 0) {
      return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      String childId = child.getViewIdResourceName();
      if (ID_MENU_CALL.equals(childId)) {
        return "Gọi điện thoại";
      }
      if (ID_MENU_PRIVACY_QUICK_SETTING.equals(childId)) {
        return "Cài đặt quyền riêng tư";
      }
      if (ID_MENU_DRAWER.equals(childId)) {
        return "Tùy chọn khác";
      }
    }
    return null;
  }

  @Nullable
  private static CharSequence resolveProfileHeaderLabel(AccessibilityNodeInfoCompat node) {
    if (!node.isClickable() || node.getChildCount() != 0) {
      return null;
    }
    AccessibilityNodeInfoCompat list = node.getParent();
    if (list == null || !ID_LV_MEDIA_STORE.equals(list.getViewIdResourceName())) {
      return null;
    }
    boolean isProfileHeader = false;
    for (int i = 0; i < list.getChildCount() - 1; i++) {
      AccessibilityNodeInfoCompat child = list.getChild(i);
      if (child != null && child.equals(node)) {
        AccessibilityNodeInfoCompat next = list.getChild(i + 1);
        if (next != null && ID_LAYOUT_BIO.equals(next.getViewIdResourceName())) {
          isProfileHeader = true;
        }
        break;
      }
    }
    if (!isProfileHeader) {
      return null;
    }
    AccessibilityNodeInfoCompat container = list.getParent();
    if (container != null) {
      for (int i = 0; i < container.getChildCount() - 1; i++) {
        AccessibilityNodeInfoCompat child = container.getChild(i);
        if (child != null && ID_ANDROID_HOME.equals(child.getViewIdResourceName())) {
          AccessibilityNodeInfoCompat titleNode = container.getChild(i + 1);
          if (titleNode != null && !TextUtils.isEmpty(titleNode.getText())) {
            return "Trang cá nhân " + titleNode.getText().toString().trim();
          }
          break;
        }
      }
    }
    return "Ảnh bìa và tên trang cá nhân";
  }
}
