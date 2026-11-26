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
public class ChatGptCollapseScriptTest {

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
    Shadows.shadowOf(child.unwrap()).setParent(parent.unwrap());
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

    CollapsedItem collapsed =
        CollapseEngine.matchItemRoot(
            itemRoot, Map.of(ChatGptCollapseScript.PACKAGE_NAME, new ChatGptCollapseScript()));

    assertNotNull(collapsed);
    assertEquals(itemRoot, collapsed.representative());
    assertEquals(
        "Bản Development 2956 đã có APK meridian.apk. Xem workflow.",
        collapsed.composedLabel().toString());
    assertEquals(6, collapsed.actions().size());
    List<String> actualLabels = new ArrayList<>();
    for (CollapsedItem.ChildAction action : collapsed.actions()) {
      actualLabels.add(action.label.toString());
    }
    assertEquals(List.of(actions), actualLabels);
    assertNull(collapsed.activationTarget());
    assertEquals(textView, collapsed.selectionTarget());
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

    CollapsedItem collapsed =
        CollapseEngine.matchItemRoot(
            itemRoot, Map.of(ChatGptCollapseScript.PACKAGE_NAME, new ChatGptCollapseScript()));

    assertNull(collapsed);
  }
}
