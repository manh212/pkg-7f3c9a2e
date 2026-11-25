/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.menurules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.MessengerAccessibilityHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MessengerMessageActionsMenuTest {

  private static AccessibilityNodeInfo makeNode(
      String className, String text, String desc, boolean clickable, boolean longClickable) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName(MessengerAccessibilityHelper.MESSENGER_PACKAGE);
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
    return info;
  }

  @Test
  public void acceptsChatMessageWithHintAndCallCardInsideChatWindow() {
    AccessibilityNodeInfo root = makeNode("android.widget.FrameLayout", null, null, false, false);
    AccessibilityNodeInfo backBtn = makeNode("android.widget.Button", null, "Quay lại", true, false);
    AccessibilityNodeInfo list =
        makeNode("androidx.recyclerview.widget.RecyclerView", null, null, false, false);
    AccessibilityNodeInfo msgRow = makeNode("android.view.ViewGroup", null, null, false, false);
    AccessibilityNodeInfo msgBubble =
        makeNode(
            "android.view.ViewGroup",
            null,
            "Chào bạn, nhấn đúp để xem ngày giờ gửi/nhận, nhấn đúp và giữ để bày tỏ cảm xúc về tin nhắn",
            true,
            true);
    shadowOf(msgRow).addChild(msgBubble);

    AccessibilityNodeInfo callRow = makeNode("android.view.ViewGroup", null, null, false, false);
    AccessibilityNodeInfo callCard =
        makeNode("android.view.ViewGroup", "Cuộc gọi thoại, 2 phút", "Cuộc gọi thoại, 2 phút", true, true);
    shadowOf(callRow).addChild(callCard);

    shadowOf(list).addChild(msgRow);
    shadowOf(list).addChild(callRow);
    shadowOf(root).addChild(backBtn);
    shadowOf(root).addChild(list);

    AccessibilityNodeInfoCompat msgCompat = AccessibilityNodeInfoCompat.wrap(msgBubble);
    AccessibilityNodeInfoCompat callCompat = AccessibilityNodeInfoCompat.wrap(callCard);

    assertTrue(MessengerMessageActionsMenu.isMessengerChatItemNode(msgCompat));
    assertFalse(MessengerMessageActionsMenu.isCallCardNode(msgCompat));

    assertTrue(MessengerMessageActionsMenu.isMessengerChatItemNode(callCompat));
    assertTrue(MessengerMessageActionsMenu.isCallCardNode(callCompat));
  }

  @Test
  public void rejectsInboxConversationRow() {
    AccessibilityNodeInfo root = makeNode("android.widget.FrameLayout", null, null, false, false);
    AccessibilityNodeInfo newMsgBtn =
        makeNode("android.widget.Button", null, "Tin nhắn mới", true, false);
    AccessibilityNodeInfo list =
        makeNode("androidx.recyclerview.widget.RecyclerView", null, null, false, false);
    AccessibilityNodeInfo convRow =
        makeNode("android.view.ViewGroup", null, "Thanh Niên Thoan, Bạn: Alo", true, true);
    shadowOf(list).addChild(convRow);
    shadowOf(root).addChild(newMsgBtn);
    shadowOf(root).addChild(list);

    assertFalse(
        MessengerMessageActionsMenu.isMessengerChatItemNode(
            AccessibilityNodeInfoCompat.wrap(convRow)));
  }

  @Test
  public void recognizesChatMessageWhenOffscreenInboxNewMessageExists() {
    AccessibilityNodeInfo root = makeNode("android.widget.FrameLayout", null, null, false, false);
    AccessibilityNodeInfo offscreenNewMsg =
        makeNode("android.widget.Button", null, "Tin nhắn mới", true, false);
    offscreenNewMsg.setVisibleToUser(false);
    AccessibilityNodeInfo composer =
        makeNode("android.widget.EditText", null, "Nhắn tin", true, false);
    composer.setEditable(true);
    AccessibilityNodeInfo scrollContainer =
        makeNode("android.view.ViewGroup", null, null, false, false);
    scrollContainer.setScrollable(true);
    AccessibilityNodeInfo rowWrapper =
        makeNode("android.view.ViewGroup", null, null, false, true);
    AccessibilityNodeInfo innerHolder =
        makeNode("android.view.ViewGroup", null, null, false, false);
    AccessibilityNodeInfo msgNode =
        makeNode("android.view.ViewGroup", "Alo bạn ơi", null, true, false);
    shadowOf(innerHolder).addChild(msgNode);
    shadowOf(rowWrapper).addChild(innerHolder);
    shadowOf(scrollContainer).addChild(rowWrapper);
    shadowOf(root).addChild(offscreenNewMsg);
    shadowOf(root).addChild(scrollContainer);
    shadowOf(root).addChild(composer);

    assertTrue(
        MessengerMessageActionsMenu.isMessengerChatItemNode(
            AccessibilityNodeInfoCompat.wrap(msgNode)));
  }

  @Test
  public void doesNotHideFriendNoteWhenOuterWrapperIsNotFocusable() {
    AccessibilityNodeInfo outerWrapper =
        makeNode("android.view.ViewGroup", null, "🎵 Lạc Trôi - Sơn Tùng", false, false);
    outerWrapper.setFocusable(false);
    AccessibilityNodeInfo noteCard =
        makeNode("android.view.ViewGroup", "🎵 Lạc Trôi - Sơn Tùng", null, true, false);
    noteCard.setFocusable(true);
    shadowOf(outerWrapper).addChild(noteCard);

    assertFalse(
        MessengerAccessibilityHelper.shouldHideNode(
            AccessibilityNodeInfoCompat.wrap(noteCard)));
  }

  @Test
  public void doesNotHideProfileCardInsideHorizontalActiveRail() {
    AccessibilityNodeInfo hScroll =
        makeNode("android.widget.HorizontalScrollView", null, null, false, false);
    hScroll.setScrollable(true);
    AccessibilityNodeInfo friendCard =
        makeNode("android.view.ViewGroup", null, "Mở trang cá nhân của Nam", true, false);
    shadowOf(hScroll).addChild(friendCard);

    assertFalse(
        MessengerAccessibilityHelper.shouldHideNode(
            AccessibilityNodeInfoCompat.wrap(friendCard)));
  }

  @Test
  public void recognizesWrappedInboxConversationRowAndRejectsActiveRailCard() {
    AccessibilityNodeInfo root = makeNode("android.widget.FrameLayout", null, null, false, false);
    AccessibilityNodeInfo newMsgBtn =
        makeNode("android.widget.Button", null, "Tin nhắn mới", true, false);

    AccessibilityNodeInfo activeRail =
        makeNode("androidx.recyclerview.widget.RecyclerView", null, null, false, false);
    activeRail.setScrollable(true);
    activeRail.setCollectionInfo(
        AccessibilityNodeInfo.CollectionInfo.obtain(
            /* rowCount= */ 1, /* columnCount= */ 6, /* hierarchical= */ false));
    AccessibilityNodeInfo activeCard =
        makeNode("android.view.ViewGroup", null, "Nam, Đang hoạt động", true, true);
    activeCard.setBoundsInScreen(new Rect(16, 200, 216, 440));
    shadowOf(activeRail).addChild(activeCard);

    AccessibilityNodeInfo inboxList =
        makeNode("androidx.recyclerview.widget.RecyclerView", null, null, false, false);
    inboxList.setScrollable(true);
    AccessibilityNodeInfo rowWrapper =
        makeNode("android.view.ViewGroup", null, null, false, true);
    AccessibilityNodeInfo convRow =
        makeNode("android.view.ViewGroup", null, "Thanh Niên Thoan, Bạn: Alo, 19:20", true, false);
    convRow.setBoundsInScreen(new Rect(0, 500, 1080, 680));
    shadowOf(rowWrapper).addChild(convRow);
    shadowOf(inboxList).addChild(rowWrapper);

    shadowOf(root).addChild(newMsgBtn);
    shadowOf(root).addChild(activeRail);
    shadowOf(root).addChild(inboxList);

    assertFalse(
        MessengerMessageActionsMenu.isMessengerInboxConversationNode(
            AccessibilityNodeInfoCompat.wrap(activeCard)));
    assertTrue(
        MessengerMessageActionsMenu.isMessengerInboxConversationNode(
            AccessibilityNodeInfoCompat.wrap(convRow)));
  }
}
