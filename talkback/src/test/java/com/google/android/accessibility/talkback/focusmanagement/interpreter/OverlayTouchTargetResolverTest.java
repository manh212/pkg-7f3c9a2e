package com.google.android.accessibility.talkback.focusmanagement.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverlayTouchTargetResolverTest {

  private static AccessibilityNodeInfoCompat makeNode(
      String pkg, String className, String viewId, String contentDesc, Rect bounds,
      boolean clickable, boolean focusable, boolean visible) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName(pkg);
    info.setClassName(className);
    info.setViewIdResourceName(viewId);
    info.setContentDescription(contentDesc);
    info.setBoundsInScreen(bounds);
    info.setClickable(clickable);
    info.setFocusable(focusable);
    info.setEnabled(true);
    info.setVisibleToUser(visible);
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  @Test
  public void virtualExplorationHitsCommentLikeAndEmptyVideoSpace() {
    String pkg = "com.ss.android.ugc.trill";
    AccessibilityNodeInfo rootInfo = AccessibilityNodeInfo.obtain();
    rootInfo.setPackageName(pkg);
    rootInfo.setClassName("android.widget.FrameLayout");
    rootInfo.setBoundsInScreen(new Rect(0, 0, 1080, 2400));
    rootInfo.setVisibleToUser(true);
    rootInfo.setEnabled(true);

    AccessibilityNodeInfo pagerInfo = AccessibilityNodeInfo.obtain();
    pagerInfo.setPackageName(pkg);
    pagerInfo.setClassName("androidx.viewpager.widget.ViewPager");
    pagerInfo.setViewIdResourceName(pkg + ":id/viewpager");
    pagerInfo.setBoundsInScreen(new Rect(0, 0, 1080, 2250));
    pagerInfo.setVisibleToUser(true);
    pagerInfo.setEnabled(true);

    AccessibilityNodeInfoCompat likeBtn = makeNode(
        pkg, "android.widget.Button", pkg + ":id/ga1", "Thích video. 199 lượt thích",
        new Rect(930, 1100, 1060, 1240), true, true, true);
    AccessibilityNodeInfoCompat commentBtn = makeNode(
        pkg, "android.widget.Button", pkg + ":id/era", "Đọc hoặc viết bình luận. 2 bình luận",
        new Rect(930, 1260, 1060, 1400), true, true, true);
    AccessibilityNodeInfoCompat overlay = makeNode(
        pkg, "android.view.View", pkg + ":id/long_press_layout", "Video",
        new Rect(0, 0, 1080, 2250), true, true, true);

    pagerInfo.addChild(likeBtn.unwrap());
    pagerInfo.addChild(commentBtn.unwrap());
    pagerInfo.addChild(overlay.unwrap());
    rootInfo.addChild(pagerInfo);

    AccessibilityNodeInfoCompat root = AccessibilityNodeInfoCompat.wrap(rootInfo);
    OverlayTouchTargetResolver resolver = new OverlayTouchTargetResolver();

    // Direct touch on Comment button
    AccessibilityNodeInfoCompat initial = resolver.startVirtualExploration(root, overlay, 990, 1320);
    assertNotNull(initial);
    assertEquals("Đọc hoặc viết bình luận. 2 bình luận", initial.getContentDescription().toString());
    assertTrue(resolver.isActive());

    // Stay on Comment button -> null (no duplicate announcement)
    assertNull(resolver.resolveOnTouchMove(995, 1330));

    // Glide up to Like button
    AccessibilityNodeInfoCompat movedToLike = resolver.resolveOnTouchMove(990, 1170);
    assertNotNull(movedToLike);
    assertEquals("Thích video. 199 lượt thích", movedToLike.getContentDescription().toString());

    // Glide left onto empty video area -> falls back to Video overlay
    AccessibilityNodeInfoCompat movedToVideo = resolver.resolveOnTouchMove(400, 1000);
    assertNotNull(movedToVideo);
    assertEquals("Video", movedToVideo.getContentDescription().toString());
  }
}
