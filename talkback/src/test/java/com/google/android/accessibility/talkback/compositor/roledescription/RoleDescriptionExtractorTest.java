package com.google.android.accessibility.talkback.compositor.roledescription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.compositor.GlobalVariables;
import com.google.android.accessibility.talkback.imagecaption.ImageContents;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.monitor.CollectionState;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A seek control that is not yet accessibility-focused must still be fully described for its own
 * focus event. Early speech reads the node before the framework marks it focused, and
 * SeekBarDescription ignores unfocused seek controls — without the focus-event exemption, swiping
 * to a slider announces only its label, with no role and no value, and the app's focus event is
 * then suppressed as already spoken.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RoleDescriptionExtractorTest {

  /** Minimal service for Robolectric to instantiate the collaborators against. */
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private Context context;
  private RoleDescriptionExtractor extractor;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    AccessibilityService service = Robolectric.setupService(TestService.class);
    GlobalVariables globalVariables =
        new GlobalVariables(service, null, new CollectionState(), null);
    extractor =
        new RoleDescriptionExtractor(context, new ImageContents(null, null), globalVariables);
  }

  /** An unfocused seek bar, as early speech sees it: labelled, with a range, not yet focused. */
  private static AccessibilityNodeInfoCompat seekBarNode(boolean focused) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName("android.widget.SeekBar");
    info.setPackageName("io.github.aaron_gh.meridian");
    info.setContentDescription("Âm lượng giọng nói");
    info.setRangeInfo(
        new AccessibilityNodeInfo.RangeInfo(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, 50));
    info.addAction(
        new AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_SET_PROGRESS, null));
    info.setAccessibilityFocused(focused);
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  @Test
  public void focusEventDescribesUnfocusedSeekBarWithRoleAndValue() {
    AccessibilityNodeInfoCompat node = seekBarNode(/* focused= */ false);
    AccessibilityEvent focusEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);

    String description = extractor.nodeRoleDescriptionText(node, focusEvent).toString();

    assertTrue(description.contains("Âm lượng giọng nói"));
    assertTrue(description.contains("Slider"));
    assertTrue(description.contains("50"));
  }

  @Test
  public void nonFocusEventStillIgnoresUnfocusedSeekBar() {
    AccessibilityNodeInfoCompat node = seekBarNode(/* focused= */ false);
    AccessibilityEvent contentChangedEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

    assertEquals("", extractor.nodeRoleDescriptionText(node, contentChangedEvent).toString());
  }

  @Test
  public void focusedSeekBarIsDescribedForNonFocusEventsToo() {
    AccessibilityNodeInfoCompat node = seekBarNode(/* focused= */ true);
    AccessibilityEvent contentChangedEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

    String description = extractor.nodeRoleDescriptionText(node, contentChangedEvent).toString();

    assertTrue(description.contains("Âm lượng giọng nói"));
    assertTrue(description.contains("50"));
  }
}
