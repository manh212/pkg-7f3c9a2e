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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BaoMoiCollapseScriptTest {

  private static final Map<String, CollapseScript> SCRIPTS =
      Map.of(BaoMoiCollapseScript.PACKAGE_NAME, new BaoMoiCollapseScript());

  private static AccessibilityNodeInfoCompat makeNode(
      String id, String className, boolean clickable, boolean focusable, String text) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    raw.setPackageName(BaoMoiCollapseScript.PACKAGE_NAME);
    raw.setClassName(className);
    raw.setVisibleToUser(true);
    raw.setClickable(clickable);
    raw.setFocusable(focusable);
    if (id != null) {
      raw.setViewIdResourceName(id);
    }
    if (text != null) {
      raw.setText(text);
    }
    raw.setBoundsInScreen(new Rect(0, 0, 1080, 200));
    return AccessibilityNodeInfoCompat.wrap(raw);
  }

  private static void addChild(
      AccessibilityNodeInfoCompat parent, AccessibilityNodeInfoCompat child) {
    Shadows.shadowOf(parent.unwrap()).addChild(child.unwrap());
  }

  private static List<String> actionLabels(CollapsedItem item) {
    List<String> labels = new ArrayList<>();
    for (CollapsedItem.ChildAction action : item.actions()) {
      labels.add(action.label.toString());
    }
    return labels;
  }

  @Test
  public void homeArticle_collapsesWithCleanLabelAndOptionsAction() {
    AccessibilityNodeInfoCompat grid =
        makeNode("com.epi:id/zonecontenttab_rv", "android.widget.GridView", false, true, null);
    grid.setScrollable(true);

    AccessibilityNodeInfoCompat card =
        makeNode("com.epi:id/content_root", "android.widget.FrameLayout", true, true, null);
    addChild(grid, card);
    addChild(
        card,
        makeNode(
            "com.epi:id/content_tv_title",
            "android.widget.TextView",
            false,
            false,
            "Giá xăng dầu đồng loạt giảm"));
    addChild(
        card,
        makeNode("com.epi:id/content_tv_publisher", "android.widget.TextView", false, false, " · "));
    addChild(
        card,
        makeNode("com.epi:id/content_tv_time", "android.widget.TextView", false, false, "4 phút"));
    addChild(
        card,
        makeNode("com.epi:id/content_iv_remove", "android.widget.ImageView", true, true, null));

    CollapsedItem item = CollapseEngine.matchItemRoot(card, SCRIPTS);
    assertNotNull(item);
    assertEquals(card, item.representative());
    assertNull(item.activationTarget());
    assertEquals("Giá xăng dầu đồng loạt giảm, 4 phút", item.composedLabel());
    assertEquals(List.of("Tùy chọn tin"), actionLabels(item));
  }

  @Test
  public void sponsoredCard_collapsesWithSponsoredLabelAndOptionsAction() {
    AccessibilityNodeInfoCompat grid =
        makeNode("com.epi:id/zonecontenttab_rv", "android.widget.GridView", false, true, null);
    grid.setScrollable(true);

    AccessibilityNodeInfoCompat card =
        makeNode("com.epi:id/content_v_ads", "android.widget.FrameLayout", true, true, null);
    addChild(grid, card);
    addChild(
        card,
        makeNode(
            "com.epi:id/content_tv_title",
            "android.widget.TextView",
            false,
            false,
            "Tinh dầu quế nguyên chất"));
    addChild(
        card,
        makeNode("com.epi:id/content_tv_sponsored", "android.widget.TextView", false, false, "Tài trợ"));
    addChild(
        card,
        makeNode(
            "com.epi:id/content_tv_publisher",
            "android.widget.TextView",
            false,
            false,
            "Tiện ích 247"));
    addChild(
        card,
        makeNode("com.epi:id/content_iv_remove", "android.widget.ImageView", true, true, null));

    CollapsedItem item = CollapseEngine.matchItemRoot(card, SCRIPTS);
    assertNotNull(item);
    assertEquals("Tài trợ, Tinh dầu quế nguyên chất, Tiện ích 247", item.composedLabel());
    assertEquals(List.of("Tùy chọn tin"), actionLabels(item));
  }

  @Test
  public void videoItem_collapsesAllButtonsIntoActionsAndTargetsVideoSurface() {
    AccessibilityNodeInfoCompat list =
        makeNode(
            "com.epi:id/vp2Vertical",
            "androidx.recyclerview.widget.RecyclerView",
            false,
            true,
            null);
    list.setScrollable(true);
    list.unwrap().setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(10, 1, false));

    AccessibilityNodeInfoCompat page =
        makeNode(null, "android.view.ViewGroup", false, false, null);
    addChild(list, page);

    AccessibilityNodeInfoCompat root =
        makeNode("com.epi:id/cslRoot", "android.view.ViewGroup", true, true, null);
    addChild(page, root);

    AccessibilityNodeInfoCompat flVideo =
        makeNode("com.epi:id/flVideo", "android.widget.FrameLayout", true, true, null);
    addChild(root, flVideo);
    addChild(root, makeNode("com.epi:id/ivUpVote", "android.widget.ImageView", true, true, null));
    addChild(
        root, makeNode("com.epi:id/up_vote_count", "android.widget.TextView", false, false, "33"));
    addChild(root, makeNode("com.epi:id/ivDownVote", "android.widget.ImageView", true, true, null));
    addChild(
        root, makeNode("com.epi:id/ivPublicIcon", "android.widget.ImageView", true, true, null));
    addChild(
        root,
        makeNode("com.epi:id/tvPublicLogo", "android.widget.TextView", true, true, "Tiền Phong"));
    addChild(
        root, makeNode("com.epi:id/btnFollow", "android.widget.TextView", true, true, "Theo dõi"));
    addChild(
        root,
        makeNode(
            "com.epi:id/videoTitle",
            "android.widget.TextView",
            true,
            true,
            "Ba trung đoàn không quân hợp luyện"));
    addChild(root, makeNode("com.epi:id/ivShare", "android.widget.ImageView", true, true, null));
    addChild(root, makeNode("com.epi:id/ivMenu", "android.widget.ImageView", true, true, null));

    AccessibilityNodeInfoCompat adWrapper =
        makeNode("com.epi:id/lytNativeAdWrapper", "android.widget.FrameLayout", true, true, null);
    addChild(root, adWrapper);
    addChild(
        adWrapper,
        makeNode("com.epi:id/btnNativeAdClose", "android.widget.ImageView", true, true, null));
    addChild(root, makeNode("com.epi:id/seekBar", "android.widget.SeekBar", false, true, null));

    CollapsedItem item = CollapseEngine.matchItemRoot(page, SCRIPTS);
    assertNotNull(item);
    assertEquals(page, item.representative());
    assertEquals(flVideo, item.activationTarget());
    assertEquals(
        "Ba trung đoàn không quân hợp luyện, Tiền Phong, 33 lượt thích", item.composedLabel());
    assertEquals(
        List.of(
            "Thích, 33",
            "Không thích",
            "Trang Tiền Phong",
            "Theo dõi",
            "Chia sẻ",
            "Tùy chọn khác",
            "Đóng quảng cáo"),
        actionLabels(item));
  }

  @Test
  public void lichVietCard_collapsesWithCalendarActions() {
    AccessibilityNodeInfoCompat list =
        makeNode(
            "com.epi:id/widget_utility_tab_rv",
            "androidx.recyclerview.widget.RecyclerView",
            false,
            true,
            null);
    list.setScrollable(true);
    list.unwrap().setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(10, 1, false));

    AccessibilityNodeInfoCompat card =
        makeNode(
            "com.epi:id/utility_widget_calendar_root",
            "android.widget.RelativeLayout",
            false,
            false,
            null);
    addChild(list, card);

    AccessibilityNodeInfoCompat thumb =
        makeNode(
            "com.epi:id/utility_widget_calendar_thumb",
            "android.widget.ImageView",
            true,
            true,
            null);
    addChild(card, thumb);
    addChild(
        card,
        makeNode(
            "com.epi:id/utility_widget_calendar_tv_day_of_week",
            "android.widget.TextView",
            false,
            false,
            "THỨ NĂM"));
    addChild(
        card,
        makeNode(
            "com.epi:id/utility_widget_calendar_tv_day",
            "android.widget.TextView",
            false,
            false,
            "26"));
    addChild(
        card,
        makeNode(
            "com.epi:id/utility_widget_calendar_tv_month_year",
            "android.widget.TextView",
            false,
            false,
            "TH.11 - 2026"));
    addChild(
        card,
        makeNode(
            "com.epi:id/utility_widget_calendar_tv_amlich",
            "android.widget.TextView",
            true,
            true,
            "Ngày 18 tháng 10 (Đ), năm Bính Ngọ"));
    addChild(
        card,
        makeNode(
            "com.epi:id/utility_widget_calendar_empty_event",
            "android.widget.TextView",
            true,
            true,
            "Không có sự kiện"));

    AccessibilityNodeInfoCompat changeDateBtn =
        makeNode(null, "android.widget.LinearLayout", true, true, null);
    addChild(
        changeDateBtn,
        makeNode("com.epi:id/btn_action_tv", "android.widget.TextView", false, false, "Đổi ngày"));
    addChild(card, changeDateBtn);

    AccessibilityNodeInfoCompat monthBtn =
        makeNode(null, "android.widget.LinearLayout", true, true, null);
    addChild(
        monthBtn,
        makeNode(
            "com.epi:id/btn_action_tv", "android.widget.TextView", false, false, "Xem lịch tháng"));
    addChild(card, monthBtn);

    CollapsedItem item = CollapseEngine.matchItemRoot(card, SCRIPTS);
    assertNotNull(item);
    assertEquals(card, item.representative());
    assertEquals(thumb, item.activationTarget());
    assertEquals(
        "THỨ NĂM, 26, TH.11 - 2026, Ngày 18 tháng 10 (Đ), năm Bính Ngọ, Không có sự kiện",
        item.composedLabel());
    assertEquals(List.of("Mở Lịch Việt", "Đổi ngày", "Xem lịch tháng"), actionLabels(item));
  }

  @Test
  public void standaloneUnlabeledControls_receiveVietnameseLabels() {
    assertEquals(
        "Chuyên mục",
        BaoMoiAccessibility.labelForNode(
            makeNode("com.epi:id/zonetabcontent_iv_cate", "android.widget.ImageView", true, true, null)));
    assertEquals(
        "Tìm kiếm",
        BaoMoiAccessibility.labelForNode(
            makeNode("com.epi:id/zonetabcontent_iv_search", "android.widget.ImageView", true, true, null)));
    assertEquals(
        "Tài khoản",
        BaoMoiAccessibility.labelForNode(
            makeNode("com.epi:id/zonetabcontent_fl_avatar", "android.widget.FrameLayout", true, true, null)));
    assertEquals(
        "Chế độ đơn giản",
        BaoMoiAccessibility.labelForNode(
            makeNode("com.epi:id/userzone_ezmode_checkbox_cb", "android.widget.Switch", true, true, null)));
    assertEquals(
        "Cỡ chữ và giao diện",
        BaoMoiAccessibility.labelForNode(
            makeNode(
                "com.epi:id/contentpage_iv_change_config_button",
                "android.widget.ImageView",
                true,
                true,
                null)));

    AccessibilityNodeInfoCompat footer =
        makeNode("com.epi:id/content_page_footer_view_v2", "android.widget.FrameLayout", true, true, null);
    AccessibilityNodeInfoCompat upVote =
        makeNode("com.epi:id/content_up_vote", "android.widget.ImageView", true, true, null);
    addChild(footer, upVote);
    addChild(
        footer,
        makeNode("com.epi:id/content_up_vote_count", "android.widget.TextView", false, false, "39"));
    assertEquals("Thích, 39", BaoMoiAccessibility.labelForNode(upVote));

    AccessibilityNodeInfoCompat trendingCard =
        makeNode("com.epi:id/content_root", "android.widget.FrameLayout", true, true, null);
    addChild(
        trendingCard,
        makeNode("com.epi:id/content_tv_title", "android.widget.TextView", false, false, "Tin xu hướng"));
    addChild(
        trendingCard,
        makeNode("com.epi:id/content_tv_publisher", "android.widget.TextView", false, false, " · "));
    addChild(
        trendingCard,
        makeNode("com.epi:id/content_tv_time", "android.widget.TextView", false, false, "3 giờ"));
    assertEquals("Tin xu hướng, 3 giờ", BaoMoiAccessibility.labelForNode(trendingCard));
  }
}
