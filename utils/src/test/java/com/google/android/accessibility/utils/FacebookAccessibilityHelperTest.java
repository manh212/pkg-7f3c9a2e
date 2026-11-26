package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class FacebookAccessibilityHelperTest {

  private static AccessibilityNodeInfoCompat fbNode(String className, String desc, String text) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    raw.setPackageName(FacebookAccessibilityHelper.FACEBOOK_PACKAGE);
    raw.setClassName(className);
    if (desc != null) raw.setContentDescription(desc);
    if (text != null) raw.setText(text);
    return AccessibilityNodeInfoCompat.wrap(raw);
  }

  @Test
  public void leafFeedPost_isDetectedAndLabelCleaned() {
    AccessibilityNodeInfoCompat post =
        fbNode(
            "android.view.ViewGroup",
            "Tuấn Anh Bii đang cảm thấy đầy hy vọng., Ảnh đại diện của Tuấn Anh Bii, Gợi ý cho bạn•Thứ 2 lúc 20:52•Chia sẻ với: Công khai, Xin chào",
            null);
    post.setClickable(true);
    post.setLongClickable(true);
    post.setFocusable(true);
    post.addAction(new AccessibilityActionCompat(1001, "Mở toàn bộ bài viết"));
    post.addAction(new AccessibilityActionCompat(1002, "Mở menu hành động"));

    assertTrue(FacebookAccessibilityHelper.isLeafFeedPost(post));
    CharSequence cleaned =
        FacebookAccessibilityHelper.filterContentDescription(post, post.getContentDescription());
    assertEquals(
        "Tuấn Anh Bii đang cảm thấy đầy hy vọng., Gợi ý cho bạn, Thứ 2 lúc 20:52, Công khai, Xin chào",
        cleaned.toString());
  }

  @Test
  public void labelsAcrossScreens_areCleanedAndLocalized() {
    AccessibilityNodeInfoCompat search = fbNode("android.widget.ImageView", "Search", null);
    assertEquals(
        "Tìm kiếm trên trang cá nhân",
        FacebookAccessibilityHelper.filterContentDescription(search, "Search").toString());

    AccessibilityNodeInfoCompat chip =
        fbNode("android.widget.Button", "Action chip profile picture", null);
    assertEquals(
        "Trang cá nhân của bạn",
        FacebookAccessibilityHelper.filterContentDescription(chip, "Action chip profile picture")
            .toString());

    AccessibilityNodeInfoCompat menuHeader =
        fbNode(
            "android.widget.Button",
            "Tiêu đề: Lối tắt hàng đầu. Phần: đã thu gọn. Nhấn đúp để mở rộng phần này.",
            null);
    assertEquals(
        "Lối tắt hàng đầu, đã thu gọn",
        FacebookAccessibilityHelper.filterContentDescription(
                menuHeader, menuHeader.getContentDescription())
            .toString());

    AccessibilityNodeInfoCompat profilePrompt =
        fbNode("android.widget.Button", "Mạnh Mạnh ơi, hãy xem trang cá nhân của bạn nhé", null);
    assertEquals(
        "Trang cá nhân: Mạnh Mạnh",
        FacebookAccessibilityHelper.filterContentDescription(
                profilePrompt, profilePrompt.getContentDescription())
            .toString());
  }

  @Test
  public void emptyWhitespaceButtonAndCoverPhoto_areHidden() {
    AccessibilityNodeInfoCompat blankBtn = fbNode("android.widget.Button", null, " ");
    blankBtn.setClickable(true);
    blankBtn.setFocusable(true);
    assertTrue(FacebookAccessibilityHelper.shouldHideNode(blankBtn));

    AccessibilityNodeInfoCompat cover = fbNode("android.widget.ImageView", "Group Cover Photo", null);
    assertTrue(FacebookAccessibilityHelper.shouldHideNode(cover));

    AccessibilityNodeInfoCompat normalBtn = fbNode("android.widget.Button", "Tìm kiếm", null);
    normalBtn.setClickable(true);
    normalBtn.setFocusable(true);
    assertFalse(FacebookAccessibilityHelper.shouldHideNode(normalBtn));
  }
}
