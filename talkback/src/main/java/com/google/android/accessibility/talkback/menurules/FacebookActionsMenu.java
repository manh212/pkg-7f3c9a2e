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

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.FacebookAccessibilityHelper;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class FacebookActionsMenu implements NodeMenu {

  private static final int FEED_TRAY_ID_BASE = 0x5f410000;
  private static final int CARD_ACTION_ID_BASE = 0x5f420000;

  private static final long INITIAL_POLL_DELAY_MS = 180L;
  private static final long POLL_INTERVAL_MS = 120L;
  private static final int MAX_POLL_ATTEMPTS = 15;
  private static final int MAX_SCAN_NODES = 500;

  private static final String FEED_TRAY_SIGNATURE = "Xem thêm lựa chọn cho bài viết này";

  private static final class TrayActionSpec {
    final String displayLabel;
    final String[] matchLabels;

    TrayActionSpec(String displayLabel, String[] matchLabels) {
      this.displayLabel = displayLabel;
      this.matchLabels = matchLabels;
    }
  }

  private static final TrayActionSpec[] FEED_TRAY_ACTIONS = {
    new TrayActionSpec("Thích", new String[] {"Thích", "Bỏ thích", "Like", "Unlike"}),
    new TrayActionSpec("Bày tỏ cảm xúc", new String[] {"Bày tỏ cảm xúc", "React"}),
    new TrayActionSpec("Bình luận", new String[] {"Bình luận", "Comment"}),
    new TrayActionSpec("Chia sẻ", new String[] {"Chia sẻ", "Share"}),
    new TrayActionSpec(
        FEED_TRAY_SIGNATURE, new String[] {FEED_TRAY_SIGNATURE, "More post options"}),
    new TrayActionSpec(
        "Xem phần mô tả chi tiết về hình ảnh",
        new String[] {"Xem phần mô tả chi tiết về hình ảnh", "View image description"}),
  };

  private final Pipeline.FeedbackReturner pipeline;

  public FacebookActionsMenu(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    if (node == null || !FacebookAccessibilityHelper.isFacebookPackage(node.getPackageName())) {
      return false;
    }
    return FacebookAccessibilityHelper.isLeafFeedPost(node)
        || FacebookAccessibilityHelper.isPymkCard(node)
        || FacebookAccessibilityHelper.isFriendRequestCard(node)
        || FacebookAccessibilityHelper.isNotificationCard(node);
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> items = new ArrayList<>();
    if (node == null || !FacebookAccessibilityHelper.isFacebookPackage(node.getPackageName())) {
      return items;
    }

    if (FacebookAccessibilityHelper.isLeafFeedPost(node)) {
      addFeedTrayActions(context, node, items);
      return items;
    }
    if (FacebookAccessibilityHelper.isPymkCard(node)) {
      addPymkActions(context, node, items);
      return items;
    }
    if (FacebookAccessibilityHelper.isFriendRequestCard(node)) {
      addFriendRequestActions(context, node, items);
      return items;
    }
    if (FacebookAccessibilityHelper.isNotificationCard(node)) {
      addNotificationActions(context, node, items);
      return items;
    }
    return items;
  }

  private void addFeedTrayActions(
      Context context, AccessibilityNodeInfoCompat postNode, List<ContextMenuItem> items) {
    for (int i = 0; i < FEED_TRAY_ACTIONS.length; i++) {
      TrayActionSpec spec = FEED_TRAY_ACTIONS[i];
      ContextMenuItem item =
          ContextMenu.createMenuItem(
              context, Menu.NONE, FEED_TRAY_ID_BASE + i, Menu.NONE, spec.displayLabel);
      item.setOnMenuItemClickListener(
          menuItem -> {
            triggerFeedTrayAndClick(postNode, spec);
            return true;
          });
      item.setSkipRefocusEvents(true);
      item.setSkipWindowEvents(true);
      items.add(item);
    }
  }

  private void addPymkActions(
      Context context, AccessibilityNodeInfoCompat card, List<ContextMenuItem> items) {
    int idx = 0;
    AccessibilityNodeInfoCompat addFriend =
        FacebookAccessibilityHelper.findDescendantButtonByDesc(card, "Thêm bạn bè", 0);
    if (addFriend != null) {
      items.add(createDirectClickItem(context, CARD_ACTION_ID_BASE + idx++, "Thêm bạn bè", addFriend, false));
    }
    AccessibilityNodeInfoCompat remove =
        FacebookAccessibilityHelper.findDescendantButtonByDesc(card, "Gỡ gợi ý kết bạn", 0);
    if (remove != null) {
      items.add(createDirectClickItem(context, CARD_ACTION_ID_BASE + idx++, "Gỡ gợi ý kết bạn", remove, false));
    }
  }

  private void addFriendRequestActions(
      Context context, AccessibilityNodeInfoCompat card, List<ContextMenuItem> items) {
    int idx = 0;
    AccessibilityNodeInfoCompat accept =
        FacebookAccessibilityHelper.findDescendantButtonByPrefix(card, "Xác nhận lời mời kết bạn", 0);
    if (accept != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Chấp nhận lời mời kết bạn", accept, false));
    }
    AccessibilityNodeInfoCompat delete =
        FacebookAccessibilityHelper.findDescendantButtonByPrefix(card, "Xóa lời mời kết bạn", 0);
    if (delete != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Xóa lời mời kết bạn", delete, false));
    }
  }

  private void addNotificationActions(
      Context context, AccessibilityNodeInfoCompat card, List<ContextMenuItem> items) {
    int idx = 0;
    AccessibilityNodeInfoCompat accept =
        FacebookAccessibilityHelper.findDescendantButtonByDesc(card, "Chấp nhận", 0);
    if (accept != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Chấp nhận lời mời kết bạn", accept, false));
    }
    AccessibilityNodeInfoCompat delete =
        FacebookAccessibilityHelper.findDescendantButtonByDesc(card, "Xóa", 0);
    if (delete != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Xóa lời mời kết bạn", delete, false));
    }
    AccessibilityNodeInfoCompat likeBtn =
        FacebookAccessibilityHelper.findDescendantButtonByPrefix(card, "Nút Thích.", 0);
    if (likeBtn != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Thích bình luận", likeBtn, false));
    }
    AccessibilityNodeInfoCompat reactNode =
        FacebookAccessibilityHelper.findDescendantByDescPrefix(card, "Nút Thích.", true, 0);
    if (reactNode != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Bày tỏ cảm xúc", reactNode, true));
    }
    AccessibilityNodeInfoCompat manage =
        FacebookAccessibilityHelper.findDescendantButtonByDesc(
            card, "Quản lý cài đặt của thông báo", 0);
    if (manage != null) {
      items.add(
          createDirectClickItem(
              context, CARD_ACTION_ID_BASE + idx++, "Quản lý cài đặt thông báo", manage, false));
    }
  }

  private ContextMenuItem createDirectClickItem(
      Context context,
      int itemId,
      String title,
      AccessibilityNodeInfoCompat targetNode,
      boolean longClick) {
    ContextMenuItem item = ContextMenu.createMenuItem(context, Menu.NONE, itemId, Menu.NONE, title);
    item.setOnMenuItemClickListener(
        menuItem -> {
          int action =
              longClick
                  ? AccessibilityNodeInfoCompat.ACTION_LONG_CLICK
                  : AccessibilityNodeInfoCompat.ACTION_CLICK;
          boolean ok = targetNode.performAction(action);
          if (!ok) {
            pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech("Không thực hiện được " + title));
          }
          return true;
        });
    item.setSkipRefocusEvents(true);
    item.setSkipWindowEvents(true);
    return item;
  }

  private void triggerFeedTrayAndClick(
      AccessibilityNodeInfoCompat postNode, TrayActionSpec targetSpec) {
    boolean opened = performCustomActionByLabel(postNode, FacebookAccessibilityHelper.FEED_POST_ACTION_MENU);
    if (!opened) {
      opened = postNode.performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
    }
    if (!opened) {
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED, Feedback.speech("Không mở được menu hành động bài viết"));
      return;
    }
    Handler handler = new Handler(Looper.getMainLooper());
    pollAndClickTrayButton(handler, targetSpec, 0);
  }

  private void pollAndClickTrayButton(Handler handler, TrayActionSpec spec, int attempt) {
    long delay = attempt == 0 ? INITIAL_POLL_DELAY_MS : POLL_INTERVAL_MS;
    handler.postDelayed(
        () -> {
          TalkBackService service = TalkBackService.getInstance();
          if (service == null) {
            return;
          }
          AccessibilityNodeInfoCompat match = findTrayButtonInWindows(service, spec.matchLabels);
          if (match != null) {
            match.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            return;
          }
          if (attempt + 1 < MAX_POLL_ATTEMPTS) {
            pollAndClickTrayButton(handler, spec, attempt + 1);
          } else {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED,
                Feedback.speech("Không tìm thấy nút " + spec.displayLabel + " trong menu bài viết"));
          }
        },
        delay);
  }

  private static boolean performCustomActionByLabel(
      AccessibilityNodeInfoCompat node, String targetLabel) {
    List<AccessibilityActionCompat> actions = node.getActionList();
    if (actions == null) {
      return false;
    }
    for (AccessibilityActionCompat action : actions) {
      CharSequence label = action.getLabel();
      if (label != null && targetLabel.equalsIgnoreCase(label.toString().trim())) {
        return node.performAction(action.getId());
      }
    }
    return false;
  }

  private static @Nullable AccessibilityNodeInfoCompat findTrayButtonInWindows(
      TalkBackService service, String[] targetLabels) {
    List<AccessibilityWindowInfo> windows = service.getWindows();
    if (windows != null) {
      for (int i = windows.size() - 1; i >= 0; i--) {
        AccessibilityWindowInfo window = windows.get(i);
        AccessibilityNodeInfo root = window == null ? null : window.getRoot();
        if (root == null || !FacebookAccessibilityHelper.isFacebookPackage(root.getPackageName())) {
          continue;
        }
        AccessibilityNodeInfoCompat compatRoot = AccessibilityNodeInfoCompat.wrap(root);
        if (!containsLabel(compatRoot, FEED_TRAY_SIGNATURE)) {
          continue;
        }
        AccessibilityNodeInfoCompat button = findMatchingButton(compatRoot, targetLabels);
        if (button != null) {
          return button;
        }
      }
    }
    AccessibilityNodeInfo active = service.getRootInActiveWindow();
    if (active != null && FacebookAccessibilityHelper.isFacebookPackage(active.getPackageName())) {
      AccessibilityNodeInfoCompat compatActive = AccessibilityNodeInfoCompat.wrap(active);
      if (containsLabel(compatActive, FEED_TRAY_SIGNATURE)) {
        return findMatchingButton(compatActive, targetLabels);
      }
    }
    return null;
  }

  private static boolean containsLabel(AccessibilityNodeInfoCompat root, String target) {
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(root);
    int visited = 0;
    while (!queue.isEmpty() && visited < MAX_SCAN_NODES) {
      AccessibilityNodeInfoCompat cur = queue.poll();
      visited++;
      if (cur == null) {
        continue;
      }
      if (matchesAny(cur.getContentDescription(), new String[] {target})
          || matchesAny(cur.getText(), new String[] {target})) {
        return true;
      }
      for (int i = 0; i < cur.getChildCount(); i++) {
        AccessibilityNodeInfoCompat child = cur.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return false;
  }

  private static @Nullable AccessibilityNodeInfoCompat findMatchingButton(
      AccessibilityNodeInfoCompat root, String[] targetLabels) {
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(root);
    int visited = 0;
    while (!queue.isEmpty() && visited < MAX_SCAN_NODES) {
      AccessibilityNodeInfoCompat cur = queue.poll();
      visited++;
      if (cur == null) {
        continue;
      }
      if (Role.getRole(cur) == Role.ROLE_BUTTON
          && cur.isClickable()
          && (matchesAny(cur.getContentDescription(), targetLabels)
              || matchesAny(cur.getText(), targetLabels))) {
        return cur;
      }
      for (int i = 0; i < cur.getChildCount(); i++) {
        AccessibilityNodeInfoCompat child = cur.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return null;
  }

  private static boolean matchesAny(@Nullable CharSequence cs, String[] labels) {
    if (TextUtils.isEmpty(cs)) {
      return false;
    }
    String s = cs.toString().trim();
    for (String label : labels) {
      if (s.equalsIgnoreCase(label)) {
        return true;
      }
    }
    return false;
  }
}
