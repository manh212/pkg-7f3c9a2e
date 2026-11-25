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
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.MessengerAccessibilityHelper;
import com.google.android.accessibility.utils.Role;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MessengerCollapseScriptTest {

  private static AccessibilityNodeInfoCompat makeNode(
      String className, String text, String desc, boolean clickable, boolean longClickable) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName(MessengerCollapseScript.PACKAGE_NAME);
    info.setClassName(className);
    if (text != null) {
      info.setText(text);
    }
    if (desc != null) {
      info.setContentDescription(desc);
    }
    info.setVisibleToUser(true);
    info.setFocusable(true);
    info.setClickable(clickable);
    info.setLongClickable(longClickable);
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  @Test
  public void scriptRegisteredWithConversationAndMessageCatalogs() {
    CollapseScript script = CollapseScripts.forPackage(MessengerCollapseScript.PACKAGE_NAME);
    assertNotNull(script);
    assertEquals(7, script.menuCatalog().items.length);

    CollapseScript fallback = script.fallbackScript();
    assertNotNull(fallback);
    assertEquals(11, fallback.menuCatalog().items.length);
  }

  @Test
  public void menuAutomationMatchesReactionButtonByClassSelector() {
    AccessibilityNodeInfo rootInfo = AccessibilityNodeInfo.obtain();
    rootInfo.setPackageName(MessengerCollapseScript.PACKAGE_NAME);
    rootInfo.setClassName("android.widget.FrameLayout");
    rootInfo.setVisibleToUser(true);

    AccessibilityNodeInfoCompat heartBtn =
        makeNode("android.widget.Button", null, "❤", /* clickable= */ false, /* longClickable= */ false);
    AccessibilityNodeInfoCompat replyBtn =
        makeNode("android.widget.Button", "Trả lời", "Trả lời", /* clickable= */ true, /* longClickable= */ false);
    shadowOf(rootInfo).addChild(heartBtn.unwrap());
    shadowOf(rootInfo).addChild(replyBtn.unwrap());

    AccessibilityNodeInfoCompat sheetRoot = AccessibilityNodeInfoCompat.wrap(rootInfo);
    AccessibilityNodeInfoCompat matchedHeart =
        MenuAutomation.sheetToggleTargetRow(
            sheetRoot,
            MessengerCollapseScript.BUTTON_CLASS_SELECTOR,
            new String[] {"❤", "❤️"},
            new String[] {"❤"});
    assertNotNull(matchedHeart);
    assertEquals("❤", matchedHeart.getContentDescription().toString());

    AccessibilityNodeInfoCompat matchedReply =
        MenuAutomation.sheetToggleTargetRow(
            sheetRoot,
            MessengerCollapseScript.BUTTON_CLASS_SELECTOR,
            new String[] {"Trả lời", "Reply"},
            new String[] {"trả lời", "reply"});
    assertNotNull(matchedReply);
    assertEquals("Trả lời", matchedReply.getText().toString());
  }

  @Test
  public void helperCleansNoisyDescriptionsAndSetsTabRole() {
    AccessibilityNodeInfoCompat msgNode =
        makeNode(
            "android.view.ViewGroup",
            null,
            "Xin chào, nhấn đúp để xem ngày giờ gửi/nhận, nhấn đúp và giữ để bày tỏ cảm xúc về tin nhắn",
            true,
            true);
    assertEquals(
        "Xin chào",
        MessengerAccessibilityHelper.filterContentDescription(msgNode, msgNode.getContentDescription())
            .toString());

    AccessibilityNodeInfoCompat reactionBar =
        makeNode(
            "android.view.ViewGroup",
            null,
            "Bảng điều khiến chứa danh sách các cảm xúc bạn có thể bày tỏ về tin nhắn này.",
            true,
            false);
    assertEquals(
        "Chọn cảm xúc",
        MessengerAccessibilityHelper.filterContentDescription(
                reactionBar, reactionBar.getContentDescription())
            .toString());

    AccessibilityNodeInfoCompat tabNode =
        makeNode("android.view.ViewGroup", null, "Tab Đoạn chat, whats_up", true, false);
    assertEquals(Integer.valueOf(Role.ROLE_ACTION_BAR_TAB), MessengerAccessibilityHelper.getRoleOverride(tabNode));
    assertEquals(
        "Đoạn chat",
        MessengerAccessibilityHelper.filterContentDescription(tabNode, tabNode.getContentDescription())
            .toString());

    AccessibilityNodeInfoCompat avatarBtn =
        makeNode("android.widget.Button", null, "Mở trang cá nhân của An", true, false);
    assertTrue(MessengerAccessibilityHelper.shouldHideNode(avatarBtn));
    assertFalse(MessengerAccessibilityHelper.shouldHideNode(tabNode));
  }

  @Test
  public void nestedMessageBubbleAndCallCardMatchFallbackScripts() {
    AccessibilityNodeInfo listInfo = AccessibilityNodeInfo.obtain();
    listInfo.setPackageName(MessengerCollapseScript.PACKAGE_NAME);
    listInfo.setClassName("androidx.recyclerview.widget.RecyclerView");
    listInfo.setVisibleToUser(true);

    // Wrapped chat message row
    AccessibilityNodeInfo msgRowInfo = AccessibilityNodeInfo.obtain();
    msgRowInfo.setPackageName(MessengerCollapseScript.PACKAGE_NAME);
    msgRowInfo.setClassName("android.view.ViewGroup");
    msgRowInfo.setVisibleToUser(true);

    AccessibilityNodeInfoCompat msgBubble =
        makeNode(
            "android.view.ViewGroup",
            null,
            "Xin chào, nhấn đúp để xem ngày giờ gửi/nhận, nhấn đúp và giữ để bày tỏ cảm xúc về tin nhắn",
            true,
            true);
    shadowOf(msgRowInfo).addChild(msgBubble.unwrap());
    shadowOf(listInfo).addChild(msgRowInfo);

    // Wrapped call card row
    AccessibilityNodeInfo callRowInfo = AccessibilityNodeInfo.obtain();
    callRowInfo.setPackageName(MessengerCollapseScript.PACKAGE_NAME);
    callRowInfo.setClassName("android.view.ViewGroup");
    callRowInfo.setVisibleToUser(true);

    AccessibilityNodeInfoCompat callCard =
        makeNode(
            "android.view.ViewGroup",
            "Cuộc gọi thoại, 2 phút",
            "Cuộc gọi thoại, 2 phút",
            true,
            true);
    shadowOf(callRowInfo).addChild(callCard.unwrap());
    shadowOf(listInfo).addChild(callRowInfo);

    CollapsedItem msgItem =
        CollapseEngine.matchContaining(msgBubble, CollapseScripts.all());
    assertNotNull(msgItem);
    assertEquals(msgBubble, msgItem.representative());
    assertEquals(11, msgItem.actions().size());

    CollapsedItem callItem =
        CollapseEngine.matchContaining(callCard, CollapseScripts.all());
    assertNotNull(callItem);
    assertEquals(callCard, callItem.representative());
    assertEquals(2, callItem.actions().size());
  }
}
