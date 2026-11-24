package com.google.android.accessibility.talkback.focusmanagement.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverlayTouchTargetResolverTest {

  private static void initializeNode(
      AccessibilityNodeInfo info,
      String pkg,
      String className,
      String viewId,
      String contentDesc,
      Rect bounds,
      boolean clickable,
      boolean focusable) {
    info.setPackageName(pkg);
    info.setClassName(className);
    if (viewId != null) {
      info.setViewIdResourceName(viewId);
    }
    info.setContentDescription(contentDesc);
    info.setBoundsInScreen(new Rect(bounds));
    info.setClickable(clickable);
    info.setFocusable(focusable);
    info.setEnabled(true);
    info.setVisibleToUser(true);
  }

  private static class TestFrame extends FrameLayout {
    private final String pkg;
    private final String className;
    private final String viewId;
    private final Rect bounds;

    TestFrame(Context context, String pkg, String className, String viewId, Rect bounds) {
      super(context);
      this.pkg = pkg;
      this.className = className;
      this.viewId = viewId;
      this.bounds = bounds;
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      initializeNode(
          info, pkg, className, viewId, null, bounds, /* clickable= */ false, /* focusable= */ false);
    }
  }

  private static class TestNodeView extends View {
    private final String pkg;
    private final String className;
    private final String viewId;
    private final String contentDesc;
    private final Rect bounds;
    private final boolean clickable;
    private final boolean focusable;

    TestNodeView(
        Context context,
        String pkg,
        String className,
        String viewId,
        String contentDesc,
        Rect bounds,
        boolean clickable,
        boolean focusable) {
      super(context);
      this.pkg = pkg;
      this.className = className;
      this.viewId = viewId;
      this.contentDesc = contentDesc;
      this.bounds = bounds;
      this.clickable = clickable;
      this.focusable = focusable;
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      setClickable(clickable);
      setFocusable(focusable);
      setContentDescription(contentDesc);
      setEnabled(true);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      initializeNode(info, pkg, className, viewId, contentDesc, bounds, clickable, focusable);
    }
  }

  @Test
  public void virtualExplorationHitsCommentLikeAndEmptyVideoSpace() {
    String pkg = "com.ss.android.ugc.trill";
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    Context context = activity;

    TestFrame rootView =
        new TestFrame(
            context,
            pkg,
            "android.widget.FrameLayout",
            null,
            new Rect(0, 0, 1080, 2400));
    TestFrame pagerView =
        new TestFrame(
            context,
            pkg,
            "androidx.viewpager.widget.ViewPager",
            pkg + ":id/viewpager",
            new Rect(0, 0, 1080, 2250));
    TestNodeView likeView =
        new TestNodeView(
            context,
            pkg,
            "android.widget.Button",
            pkg + ":id/ga1",
            "Thích video. 199 lượt thích",
            new Rect(930, 1100, 1060, 1240),
            /* clickable= */ true,
            /* focusable= */ true);
    TestNodeView commentView =
        new TestNodeView(
            context,
            pkg,
            "android.widget.Button",
            pkg + ":id/era",
            "Đọc hoặc viết bình luận. 2 bình luận",
            new Rect(930, 1260, 1060, 1400),
            /* clickable= */ true,
            /* focusable= */ true);
    TestNodeView overlayView =
        new TestNodeView(
            context,
            pkg,
            "android.view.View",
            pkg + ":id/long_press_layout",
            "Video",
            new Rect(0, 0, 1080, 2250),
            /* clickable= */ true,
            /* focusable= */ true);

    rootView.addView(pagerView);
    pagerView.addView(likeView);
    pagerView.addView(commentView);
    pagerView.addView(overlayView);
    activity.setContentView(rootView);
    rootView.layout(0, 0, 1080, 2400);
    pagerView.layout(0, 0, 1080, 2250);
    likeView.layout(930, 1100, 1060, 1240);
    commentView.layout(930, 1260, 1060, 1400);
    overlayView.layout(0, 0, 1080, 2250);

    AccessibilityNodeInfoCompat root =
        AccessibilityNodeInfoCompat.wrap(rootView.createAccessibilityNodeInfo());
    AccessibilityNodeInfoCompat pager = root.getChild(0);
    assertNotNull(pager);
    AccessibilityNodeInfoCompat likeBtn = pager.getChild(0);
    AccessibilityNodeInfoCompat commentBtn = pager.getChild(1);
    AccessibilityNodeInfoCompat overlay = pager.getChild(2);
    assertNotNull(likeBtn);
    assertNotNull(commentBtn);
    assertNotNull(overlay);

    OverlayTouchTargetResolver resolver = new OverlayTouchTargetResolver();

    // Direct touch on Comment button.
    AccessibilityNodeInfoCompat initial = resolver.startVirtualExploration(root, overlay, 990, 1320);
    assertNotNull(initial);
    assertEquals("Đọc hoặc viết bình luận. 2 bình luận", initial.getContentDescription().toString());
    assertTrue(resolver.isActive());

    // Stay on Comment button -> null (no duplicate announcement).
    assertNull(resolver.resolveOnTouchMove(995, 1330));

    // Glide up to Like button.
    AccessibilityNodeInfoCompat movedToLike = resolver.resolveOnTouchMove(990, 1170);
    assertNotNull(movedToLike);
    assertEquals("Thích video. 199 lượt thích", movedToLike.getContentDescription().toString());

    // Glide left onto empty video area -> falls back to Video overlay.
    AccessibilityNodeInfoCompat movedToVideo = resolver.resolveOnTouchMove(400, 1000);
    assertNotNull(movedToVideo);
    assertEquals("Video", movedToVideo.getContentDescription().toString());
  }
}
