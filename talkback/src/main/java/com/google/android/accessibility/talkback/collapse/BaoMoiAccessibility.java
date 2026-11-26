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

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Built-in accessibility labels and text cleanup for Báo Mới ({@code com.epi}). Supplies spoken
 * labels for unlabeled controls across the home, category, article, video, and utility screens,
 * and removes stray punctuation placeholders (such as {@code " · "}) on non-collapsed news cards.
 */
public final class BaoMoiAccessibility {
  public static final String PACKAGE_NAME = "com.epi";
  private static final String ID_PREFIX = PACKAGE_NAME + ":id/";

  static final String ID_CONTENT_ROOT = ID_PREFIX + "content_root";
  static final String ID_CONTENT_ADS = ID_PREFIX + "content_v_ads";
  static final String ID_CONTENT_TITLE = ID_PREFIX + "content_tv_title";
  static final String ID_CONTENT_PUBLISHER = ID_PREFIX + "content_tv_publisher";
  static final String ID_CONTENT_TIME = ID_PREFIX + "content_tv_time";
  static final String ID_CONTENT_SPONSORED = ID_PREFIX + "content_tv_sponsored";
  static final String ID_CONTENT_REMOVE = ID_PREFIX + "content_iv_remove";

  static final String ID_VIDEO_ROOT = ID_PREFIX + "cslRoot";
  static final String ID_VIDEO_SURFACE = ID_PREFIX + "flVideo";
  static final String ID_VIDEO_UP_VOTE = ID_PREFIX + "ivUpVote";
  static final String ID_VIDEO_UP_VOTE_COUNT = ID_PREFIX + "up_vote_count";
  static final String ID_VIDEO_DOWN_VOTE = ID_PREFIX + "ivDownVote";
  static final String ID_VIDEO_PUBLIC_ICON = ID_PREFIX + "ivPublicIcon";
  static final String ID_VIDEO_PUBLIC_LOGO = ID_PREFIX + "tvPublicLogo";
  static final String ID_VIDEO_FOLLOW = ID_PREFIX + "btnFollow";
  static final String ID_VIDEO_TITLE = ID_PREFIX + "videoTitle";
  static final String ID_VIDEO_SHARE = ID_PREFIX + "ivShare";
  static final String ID_VIDEO_MENU = ID_PREFIX + "ivMenu";
  static final String ID_VIDEO_AD_WRAPPER = ID_PREFIX + "lytNativeAdWrapper";
  static final String ID_VIDEO_AD_CLOSE = ID_PREFIX + "btnNativeAdClose";

  static final String ID_CALENDAR_ROOT = ID_PREFIX + "utility_widget_calendar_root";
  static final String ID_CALENDAR_THUMB = ID_PREFIX + "utility_widget_calendar_thumb";
  static final String ID_CALENDAR_DOW = ID_PREFIX + "utility_widget_calendar_tv_day_of_week";
  static final String ID_CALENDAR_DAY = ID_PREFIX + "utility_widget_calendar_tv_day";
  static final String ID_CALENDAR_MONTH_YEAR = ID_PREFIX + "utility_widget_calendar_tv_month_year";
  static final String ID_CALENDAR_LUNAR = ID_PREFIX + "utility_widget_calendar_tv_amlich";
  static final String ID_CALENDAR_EVENT = ID_PREFIX + "utility_widget_calendar_empty_event";
  static final String ID_BTN_ACTION_TV = ID_PREFIX + "btn_action_tv";

  private static final String ID_HOME_CATE = ID_PREFIX + "zonetabcontent_iv_cate";
  private static final String ID_HOME_SEARCH = ID_PREFIX + "zonetabcontent_iv_search";
  private static final String ID_HOME_AVATAR = ID_PREFIX + "zonetabcontent_fl_avatar";
  private static final String ID_UTILITY_AVATAR = ID_PREFIX + "widget_utility_tab_iv_avatar";
  private static final String ID_USERZONE_BACK = ID_PREFIX + "userzone_iv_back";
  private static final String ID_USERZONE_CHANGE_MODE = ID_PREFIX + "userzone_img_change_mode";
  private static final String ID_USERZONE_EZMODE_SWITCH = ID_PREFIX + "userzone_ezmode_checkbox_cb";

  private static final String ID_ARTICLE_BACK = ID_PREFIX + "contentpage_iv_back";
  private static final String ID_ARTICLE_PUBLISHER = ID_PREFIX + "contentpage_ll_publisher";
  private static final String ID_ARTICLE_FOLLOW = ID_PREFIX + "contentpage_fl_follow1";
  private static final String ID_ARTICLE_CONFIG = ID_PREFIX + "contentpage_iv_change_config_button";
  private static final String ID_ARTICLE_SETTING = ID_PREFIX + "contentpage_iv_setting";
  private static final String ID_ARTICLE_UP_VOTE = ID_PREFIX + "content_up_vote";
  private static final String ID_ARTICLE_UP_VOTE_COUNT = ID_PREFIX + "content_up_vote_count";
  private static final String ID_ARTICLE_DOWN_VOTE = ID_PREFIX + "content_down_vote";

  private BaoMoiAccessibility() {}

  /**
   * Returns a built-in Vietnamese accessibility label for a known unlabeled Báo Mới node (or a
   * cleaned card label for a non-collapsed news card), or {@code null} when untouched.
   */
  public static @Nullable CharSequence labelForNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !PACKAGE_NAME.contentEquals(safeString(node.getPackageName()))) {
      return null;
    }
    if (!TextUtils.isEmpty(node.getContentDescription()) || !TextUtils.isEmpty(node.getText())) {
      return null;
    }
    String viewId = node.getViewIdResourceName();
    if (viewId == null) {
      return null;
    }
    switch (viewId) {
      case ID_HOME_CATE:
        return "Chuyên mục";
      case ID_HOME_SEARCH:
        return "Tìm kiếm";
      case ID_HOME_AVATAR:
      case ID_UTILITY_AVATAR:
        return "Tài khoản";
      case ID_USERZONE_BACK:
      case ID_ARTICLE_BACK:
        return "Quay lại";
      case ID_USERZONE_CHANGE_MODE:
        return "Đổi kiểu hiển thị";
      case ID_USERZONE_EZMODE_SWITCH:
        return "Chế độ đơn giản";
      case ID_ARTICLE_PUBLISHER:
        return "Nguồn báo";
      case ID_ARTICLE_FOLLOW:
        return "Theo dõi nguồn báo";
      case ID_ARTICLE_CONFIG:
        return "Cỡ chữ và giao diện";
      case ID_ARTICLE_SETTING:
        return "Tùy chọn khác";
      case ID_ARTICLE_UP_VOTE:
        return voteLabel(siblingText(node, ID_ARTICLE_UP_VOTE_COUNT));
      case ID_ARTICLE_DOWN_VOTE:
      case ID_VIDEO_DOWN_VOTE:
        return "Không thích";
      case ID_CONTENT_REMOVE:
        return "Tùy chọn tin";
      case ID_VIDEO_UP_VOTE:
        return voteLabel(siblingText(node, ID_VIDEO_UP_VOTE_COUNT));
      case ID_VIDEO_SHARE:
        return "Chia sẻ";
      case ID_VIDEO_MENU:
        return "Tùy chọn khác";
      case ID_VIDEO_PUBLIC_ICON:
        return publisherPageLabel(siblingText(node, ID_VIDEO_PUBLIC_LOGO));
      case ID_VIDEO_AD_CLOSE:
        return "Đóng quảng cáo";
      case ID_CALENDAR_THUMB:
        return "Mở Lịch Việt";
      case ID_CONTENT_ROOT:
        return cleanArticleCardLabel(node);
      default:
        return null;
    }
  }

  static String voteLabel(@Nullable CharSequence count) {
    String trimmed = cleanText(count);
    return TextUtils.isEmpty(trimmed) ? "Thích" : "Thích, " + trimmed;
  }

  static String publisherPageLabel(@Nullable CharSequence publisher) {
    String trimmed = cleanText(publisher);
    return TextUtils.isEmpty(trimmed) ? "Nguồn báo" : "Trang " + trimmed;
  }

  static boolean isNoisePublisher(@Nullable CharSequence raw) {
    if (TextUtils.isEmpty(raw)) {
      return true;
    }
    String s = raw.toString();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        return false;
      }
    }
    return true;
  }

  static String cleanText(@Nullable CharSequence raw) {
    if (TextUtils.isEmpty(raw)) {
      return "";
    }
    return raw.toString().replaceAll("\\s{2,}", " ").trim();
  }

  static @Nullable String siblingText(
      AccessibilityNodeInfoCompat node, String siblingViewId) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return null;
    }
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child != null && siblingViewId.equals(child.getViewIdResourceName())) {
        String text = cleanText(ItemScan.spokenLabel(child));
        if (!TextUtils.isEmpty(text)) {
          return text;
        }
      }
    }
    return null;
  }

  private static @Nullable CharSequence cleanArticleCardLabel(
      AccessibilityNodeInfoCompat cardRoot) {
    String title = null;
    String publisher = null;
    String time = null;
    for (int i = 0; i < cardRoot.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = cardRoot.getChild(i);
      if (child == null) {
        continue;
      }
      String id = child.getViewIdResourceName();
      if (ID_CONTENT_TITLE.equals(id)) {
        title = cleanText(ItemScan.spokenLabel(child));
      } else if (ID_CONTENT_PUBLISHER.equals(id)) {
        String rawPub = cleanText(ItemScan.spokenLabel(child));
        if (!isNoisePublisher(rawPub)) {
          publisher = rawPub;
        }
      } else if (ID_CONTENT_TIME.equals(id)) {
        time = cleanText(ItemScan.spokenLabel(child));
      }
    }
    if (TextUtils.isEmpty(title)) {
      return null;
    }
    StringBuilder sb = new StringBuilder(title);
    if (!TextUtils.isEmpty(publisher)) {
      sb.append(", ").append(publisher);
    }
    if (!TextUtils.isEmpty(time)) {
      sb.append(", ").append(time);
    }
    return sb.toString();
  }

  private static String safeString(@Nullable CharSequence value) {
    return value == null ? "" : value.toString();
  }
}
