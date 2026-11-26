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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatGptCollapseScriptTest {

  private static final Map<String, CollapseScript> SCRIPTS =
      Map.of(ChatGptCollapseScript.PACKAGE_NAME, new ChatGptCollapseScript());

  @Before
  public void setUp() {
    CollapseEngine.clearCacheForTesting();
  }

  private static AccessibilityNodeInfoCompat makeNode(
      String className, boolean clickable, boolean focusable, String text, String desc) {
    AccessibilityNodeInfo raw = AccessibilityNodeInfo.obtain();
    raw.setPackageName(ChatGptCollapseScript.PACKAGE_NAME);
    raw.setClassName(className);
    raw.setVisibleToUser(true);
    raw.setClickable(clickable);
    raw.setFocusable(focusable);
    if (text != null) {
      raw.setText(text);
    }
    if (desc != null) {
      raw.setContentDescription(desc);
    }
    raw.setBoundsInScreen(new Rect(0, 0, 1080, 200));
    return AccessibilityNodeInfoCompat.wrap(raw);
  }

  private static void addChild(
      AccessibilityNodeInfoCompat parent, AccessibilityNodeInfoCompat child) {
    Shadows.shadowOf(parent.unwrap()).addChild(child.unwrap());

  }

  private static AccessibilityNodeInfoCompat makeComposeActionButton(String description) {
    AccessibilityNodeInfoCompat btnContainer =
        makeNode("android.view.View", true, true, null, null);
    AccessibilityNodeInfoCompat labelView =
        makeNode("android.view.View", false, false, null, description);
    AccessibilityNodeInfoCompat emptyBtn =
        makeNode("android.widget.Button", false, false, null, null);
    addChild(btnContainer, labelView);
    addChild(btnContainer, emptyBtn);
    return btnContainer;
  }

  private static AccessibilityNodeInfoCompat makeThinkingButton(String label) {
    AccessibilityNodeInfoCompat btn = makeNode("android.view.View", true, true, null, null);
    AccessibilityNodeInfoCompat tv = makeNode("android.widget.TextView", false, false, label, null);
    addChild(btn, tv);
    return btn;
  }

  private static AccessibilityNodeInfoCompat makeComposeHeading(String title) {
    AccessibilityNodeInfoCompat wrapper = makeNode("android.view.View", false, false, null, null);
    wrapper.setHeading(true);
    wrapper.setScreenReaderFocusable(true);
    AccessibilityNodeInfoCompat tv = makeNode("android.widget.TextView", false, false, title, null);
    addChild(wrapper, tv);
    return wrapper;
  }

  private static List<String> actionLabels(CollapsedItem item) {
    List<String> labels = new ArrayList<>();
    for (CollapsedItem.ChildAction action : item.actions()) {
      labels.add(action.label.toString());
    }
    return labels;
  }

  @Test
  public void assistantReply_collapsesIntoSingleStopWithAllSixActionsAndCleanLabel() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat itemRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, itemRoot);

    AccessibilityNodeInfoCompat textView =
        makeNode(
            "android.widget.TextView",
            false,
            false,
            "Bản Development 2956\uFFFD đã có APK meridian.apk\uFFFD. Xem workflow\uFFFD.",
            null);
    addChild(itemRoot, textView);

    String[] actions = {
      "Sao chép", "Trả lời tốt", "Trả lời tệ", "Đọc to", "Chia sẻ", "Thêm hành động"
    };
    AccessibilityNodeInfoCompat actionRow =
        makeNode("android.view.View", false, false, null, null);
    addChild(itemRoot, actionRow);
    for (String label : actions) {
      addChild(actionRow, makeComposeActionButton(label));
    }

    CollapsedItem collapsed = CollapseEngine.matchItemRoot(itemRoot, SCRIPTS);

    assertNotNull(collapsed);
    assertEquals(itemRoot, collapsed.representative());
    assertEquals(
        "Bản Development 2956 đã có APK meridian.apk. Xem workflow.",
        collapsed.composedLabel().toString());
    assertEquals(6, collapsed.actions().size());
    assertEquals(List.of(actions), actionLabels(collapsed));
    assertNull(collapsed.activationTarget());
    assertEquals(textView, collapsed.selectionTarget());
  }

  @Test
  public void assistantReplyWithHeadings_splitsByHeadingAndSharesAllActionsIncludingThinkingButton() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat itemRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, itemRoot);

    AccessibilityNodeInfoCompat thinkingBtn = makeThinkingButton("Đã xử lý trong 56s");
    addChild(itemRoot, thinkingBtn);

    AccessibilityNodeInfoCompat preamble =
        makeNode("android.widget.TextView", false, false, "Tổng hợp cập nhật:", null);
    addChild(itemRoot, preamble);

    AccessibilityNodeInfoCompat h1 = makeComposeHeading("Tính năng mới");
    AccessibilityNodeInfoCompat b1Dot = makeNode("android.widget.TextView", false, false, "•", null);
    AccessibilityNodeInfoCompat b1Text =
        makeNode("android.widget.TextView", false, false, "Thêm điều hướng web cục bộ.", null);
    AccessibilityNodeInfoCompat b2Dot = makeNode("android.widget.TextView", false, false, "•", null);
    AccessibilityNodeInfoCompat b2Text =
        makeNode("android.widget.TextView", false, false, "Hỗ trợ Báo Mới.", null);
    addChild(itemRoot, h1);
    addChild(itemRoot, b1Dot);
    addChild(itemRoot, b1Text);
    addChild(itemRoot, b2Dot);
    addChild(itemRoot, b2Text);

    AccessibilityNodeInfoCompat h2 = makeComposeHeading("Sửa lỗi");
    AccessibilityNodeInfoCompat b3Dot = makeNode("android.widget.TextView", false, false, "•", null);
    AccessibilityNodeInfoCompat b3Text =
        makeNode("android.widget.TextView", false, false, "Khắc phục độ trễ cuộn.", null);
    addChild(itemRoot, h2);
    addChild(itemRoot, b3Dot);
    addChild(itemRoot, b3Text);

    String[] bottomActions = {"Sao chép", "Trả lời tốt", "Trả lời tệ", "Đọc to", "Chia sẻ"};
    AccessibilityNodeInfoCompat actionRow =
        makeNode("android.view.View", false, false, null, null);
    addChild(itemRoot, actionRow);
    for (String label : bottomActions) {
      addChild(actionRow, makeComposeActionButton(label));
    }

    List<CollapsedItem> sections = CollapseEngine.matchItemRootAll(itemRoot, SCRIPTS);
    assertEquals(3, sections.size());

    // Section 0: Preamble
    assertEquals(preamble, sections.get(0).representative());
    assertEquals("Tổng hợp cập nhật:", sections.get(0).composedLabel().toString());

    // Section 1: Heading 1 + its bullets (without standalone "•")
    assertEquals(h1, sections.get(1).representative());
    assertEquals(
        "Tính năng mới, Thêm điều hướng web cục bộ., Hỗ trợ Báo Mới.",
        sections.get(1).composedLabel().toString());

    // Section 2: Heading 2 + its bullets
    assertEquals(h2, sections.get(2).representative());
    assertEquals(
        "Sửa lỗi, Khắc phục độ trễ cuộn.",
        sections.get(2).composedLabel().toString());

    // Every section shares ALL 6 actions (thinking button + 5 bottom buttons).
    List<String> expectedActions =
        List.of("Đã xử lý trong 56s", "Sao chép", "Trả lời tốt", "Trả lời tệ", "Đọc to", "Chia sẻ");
    for (CollapsedItem section : sections) {
      assertEquals(expectedActions, actionLabels(section));
    }

    // Node filter accepts only the 3 section representatives; suppresses itemRoot, thinkingBtn,
    // standalone bullets, body TextViews, and bottom action buttons.
    Filter<AccessibilityNodeInfoCompat> filter =
        CollapseEngine.composeNodeFilter(Filter.node(n -> true), SCRIPTS);
    assertFalse(filter.accept(itemRoot));
    assertFalse(filter.accept(thinkingBtn));
    assertTrue(filter.accept(preamble));
    assertTrue(filter.accept(h1));
    assertFalse(filter.accept(b1Dot));
    assertFalse(filter.accept(b1Text));
    assertTrue(filter.accept(h2));
    assertFalse(filter.accept(b3Dot));
    assertFalse(filter.accept(b3Text));
    assertFalse(filter.accept(actionRow.getChild(0)));

    // Touch/restore on an inner body child resolves to its owning heading section representative.
    CollapsedItem resolvedH1 = CollapseEngine.matchContaining(b2Text, SCRIPTS);
    assertNotNull(resolvedH1);
    assertEquals(h1, resolvedH1.representative());

    CollapsedItem resolvedH2 = CollapseEngine.matchContaining(b3Text, SCRIPTS);
    assertNotNull(resolvedH2);
    assertEquals(h2, resolvedH2.representative());
  }

  @Test
  public void partiallyScrolledReplyWithOnlyThinkingButton_stillCollapsesAndAbsorbsThinking() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat itemRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, itemRoot);

    AccessibilityNodeInfoCompat thinkingBtn = makeThinkingButton("Đã xử lý trong 23m 51s");
    AccessibilityNodeInfoCompat p1 =
        makeNode("android.widget.TextView", false, false, "Đã đăng patch lên master.", null);
    AccessibilityNodeInfoCompat p2 =
        makeNode("android.widget.TextView", false, false, "Hai lần chạy đầu phát hiện lỗi.", null);
    addChild(itemRoot, thinkingBtn);
    addChild(itemRoot, p1);
    addChild(itemRoot, p2);

    List<CollapsedItem> sections = CollapseEngine.matchItemRootAll(itemRoot, SCRIPTS);
    assertEquals(1, sections.size());
    assertEquals(
        "Đã đăng patch lên master., Hai lần chạy đầu phát hiện lỗi.",
        sections.get(0).composedLabel().toString());
    assertEquals(List.of("Đã xử lý trong 23m 51s"), actionLabels(sections.get(0)));

    Filter<AccessibilityNodeInfoCompat> filter =
        CollapseEngine.composeNodeFilter(Filter.node(n -> true), SCRIPTS);
    assertTrue(filter.accept(itemRoot));
    assertFalse(filter.accept(thinkingBtn));
    assertFalse(filter.accept(p1));
    assertFalse(filter.accept(p2));
  }

  @Test
  public void partiallyScrolledMiddleWithHeadingsAndNoButtons_stillGroupsByHeading() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat itemRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, itemRoot);

    AccessibilityNodeInfoCompat h1 = makeComposeHeading("Thay đổi");
    AccessibilityNodeInfoCompat dot = makeNode("android.widget.TextView", false, false, "•", null);
    AccessibilityNodeInfoCompat body =
        makeNode("android.widget.TextView", false, false, "Tối ưu bộ nhớ đệm duyệt cây.", null);
    addChild(itemRoot, h1);
    addChild(itemRoot, dot);
    addChild(itemRoot, body);

    List<CollapsedItem> sections = CollapseEngine.matchItemRootAll(itemRoot, SCRIPTS);
    assertEquals(1, sections.size());
    assertEquals(h1, sections.get(0).representative());
    assertEquals(
        "Thay đổi, Tối ưu bộ nhớ đệm duyệt cây.", sections.get(0).composedLabel().toString());
    assertTrue(sections.get(0).actions().isEmpty());
  }

  @Test
  public void singleAttachmentBubble_isLeftUntouched() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat itemRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, itemRoot);

    AccessibilityNodeInfoCompat attachment =
        makeNode("android.view.View", true, true, null, null);
    AccessibilityNodeInfoCompat filename =
        makeNode(
            "android.widget.TextView",
            false,
            false,
            "meridian-braille-calibration-fix.patch",
            null);
    addChild(attachment, filename);
    addChild(itemRoot, attachment);

    CollapsedItem collapsed = CollapseEngine.matchItemRoot(itemRoot, SCRIPTS);

    assertNull(collapsed);
  }

  @Test
  public void plainUserPromptAndTimestamp_areLeftUntouched() {
    AccessibilityNodeInfoCompat list =
        makeNode("android.widget.ListView", false, false, null, null);
    list.setScrollable(true);

    AccessibilityNodeInfoCompat timestampRoot =
        makeNode("android.view.View", false, false, null, null);
    addChild(list, timestampRoot);
    addChild(
        timestampRoot,
        makeNode("android.widget.TextView", false, false, "Hôm nay 6:29 CH", null));

    assertNull(CollapseEngine.matchItemRoot(timestampRoot, SCRIPTS));
  }
}
