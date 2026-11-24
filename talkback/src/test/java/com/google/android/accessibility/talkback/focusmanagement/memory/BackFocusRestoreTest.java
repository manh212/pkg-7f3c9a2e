package com.google.android.accessibility.talkback.focusmanagement.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class BackFocusRestoreTest {

  private static AccessibilityNodeInfoCompat makeNode(
      AccessibilityNodeInfoCompat parent, String cls, String text, Rect bounds) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName("com.example.app");
    info.setClassName(cls);
    info.setVisibleToUser(true);
    info.setEnabled(true);
    info.setFocusable(true);
    info.setClickable(true);
    info.setText(text);
    info.setBoundsInScreen(bounds);
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(info);
    }
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  @Test
  @Config(sdk = 34)
  public void findBestMatch_rejectsAmbiguousPrimaryTokenTie() {
    AccessibilityNodeInfoCompat root =
        makeNode(null, "android.widget.FrameLayout", "", new Rect(0, 0, 1080, 2400));
    root.setFocusable(false);
    root.setClickable(false);

    AccessibilityNodeInfoCompat original =
        makeNode(root, "android.widget.Button", "Nhóm. Cuộc trò chuyện cũ", new Rect(0, 200, 1080, 350));
    FocusTargetFingerprint fp = FocusTargetFingerprint.capture(original, 1000L);
    assertNotNull(fp);

    // Rebuild tree without the exact original item, leaving two unrelated items sharing the same
    // primaryToken ("nhóm"): findBestMatch must return null rather than jumping to the wrong one.
    AccessibilityNodeInfoCompat newRoot =
        makeNode(null, "android.widget.FrameLayout", "", new Rect(0, 0, 1080, 2400));
    newRoot.setFocusable(false);
    newRoot.setClickable(false);
    makeNode(newRoot, "android.widget.Button", "Nhóm. Tin mới 1", new Rect(0, 200, 1080, 350));
    makeNode(newRoot, "android.widget.Button", "Nhóm. Tin mới 2", new Rect(0, 360, 1080, 510));

    assertNull(fp.findBestMatch(newRoot));
  }

  @Test
  @Config(sdk = 34)
  public void recordPreCaptured_andArmPendingRestore_preserveFastTapTarget() {
    ScreenFocusMemoryStore store = new ScreenFocusMemoryStore();
    FocusRestoreCoordinator coordinator = new FocusRestoreCoordinator(store);

    AccessibilityNodeInfoCompat root =
        makeNode(null, "android.widget.FrameLayout", "", new Rect(0, 0, 1080, 2400));
    root.setFocusable(false);
    root.setClickable(false);
    AccessibilityNodeInfoCompat header =
        makeNode(root, "android.widget.TextView", "Hộp thư đến", new Rect(0, 0, 1080, 150));
    header.setHeading(true);
    AccessibilityNodeInfoCompat item =
        makeNode(root, "android.widget.Button", "Hóa đơn tháng 11", new Rect(0, 300, 1080, 450));

    FocusTargetFingerprint preCaptured = FocusTargetFingerprint.capture(item, 5000L);
    store.recordPreCaptured(root, preCaptured, /* screenState= */ null, /* windowId= */ 7, 5000L);

    SemanticScreenKey key = SemanticScreenKey.create(root, /* screenState= */ null, /* windowId= */ 7);
    assertNotNull(key);
    assertTrue(coordinator.armPendingRestore(key, 5100L));
    assertTrue(coordinator.hasPendingRestore(5150L));

    AccessibilityNodeInfoCompat restored = store.findRestoreNode(root, /* screenState= */ null, /* windowId= */ 7);
    assertNotNull(restored);
    assertEquals("Hóa đơn tháng 11", restored.getText().toString());

    coordinator.cancelOnUserInteraction();
    assertFalse(coordinator.hasPendingRestore(5200L));
  }
}
