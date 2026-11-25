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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.MessengerAccessibilityHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Direct Actions-menu rule for messages and call cards inside a Facebook Messenger
 * ({@code com.facebook.orca}) conversation.
 *
 * <p>Uses the same proven long-press + bottom-sheet polling approach as
 * {@link ZaloMessageActionsMenu}, avoiding list-item collapsing constraints (nested message rows,
 * selectable text, grouped messages) and supporting raw-tap fallback for Messenger's focusable-only
 * reaction buttons ({@code ❤}, {@code 😆}, {@code 😮}, {@code 😢}, {@code 😡}, {@code 👍},
 * {@code Biểu tượng cảm xúc khác}).
 */
public final class MessengerMessageActionsMenu implements NodeMenu {

  private static final int ACTION_ID_BASE = 0x5f310000;
  private static final int REACTION_ID_BASE = 0x5f320000;

  private static final long INITIAL_POLL_DELAY_MS = 180L;
  private static final long POLL_INTERVAL_MS = 120L;
  private static final int MAX_POLL_ATTEMPTS = 15;
  private static final int MAX_SCAN_NODES = 600;

  private static final class ActionSpec {
    final String displayLabel;
    final String[] matchLabels;
    final boolean isReaction;

    ActionSpec(String displayLabel, String[] matchLabels, boolean isReaction) {
      this.displayLabel = displayLabel;
      this.matchLabels = matchLabels;
      this.isReaction = isReaction;
    }
  }

  private static final ActionSpec[] MESSAGE_ACTIONS = {
    new ActionSpec("Trả lời", new String[] {"Trả lời", "Reply"}, false),
    new ActionSpec("Sao chép", new String[] {"Sao chép", "Copy"}, false),
    new ActionSpec("Xóa", new String[] {"Xóa", "Delete"}, false),
    new ActionSpec(
        "Đặt lời nhắc", new String[] {"Đặt lời nhắc", "Lời nhắc", "Set reminder", "Remind"}, false),
    new ActionSpec("Khác", new String[] {"Khác", "More"}, false),
  };

  private static final ActionSpec[] MESSAGE_REACTIONS = {
    new ActionSpec("Thả cảm xúc: Thả tim", new String[] {"❤", "❤️"}, true),
    new ActionSpec("Thả cảm xúc: Haha", new String[] {"😆"}, true),
    new ActionSpec("Thả cảm xúc: Wow", new String[] {"😮"}, true),
    new ActionSpec("Thả cảm xúc: Buồn", new String[] {"😢"}, true),
    new ActionSpec("Thả cảm xúc: Phẫn nộ", new String[] {"😡"}, true),
    new ActionSpec("Thả cảm xúc: Thích", new String[] {"👍"}, true),
    new ActionSpec(
        "Thả cảm xúc: Biểu tượng cảm xúc khác",
        new String[] {
          "Biểu tượng cảm xúc khác",
          "Thêm biểu tượng cảm xúc",
          "More reactions",
          "Add reaction"
        },
        true),
  };

  private static final ActionSpec[] CALL_CARD_ACTIONS = {
    new ActionSpec("Xóa", new String[] {"Xóa", "Delete"}, false),
    new ActionSpec(
        "Đặt lời nhắc", new String[] {"Đặt lời nhắc", "Lời nhắc", "Set reminder", "Remind"}, false),
  };

  private final Pipeline.FeedbackReturner pipeline;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private @Nullable Runnable pendingPoll;

  public MessengerMessageActionsMenu(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    return isMessengerChatItemNode(node);
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> items = new ArrayList<>();
    if (!isMessengerChatItemNode(node)) {
      return items;
    }
    boolean callCard = isCallCardNode(node);
    ActionSpec[] actions = callCard ? CALL_CARD_ACTIONS : MESSAGE_ACTIONS;
    for (int i = 0; i < actions.length; i++) {
      final ActionSpec spec = actions[i];
      ContextMenuItem item =
          ContextMenu.createMenuItem(
              context, Menu.NONE, ACTION_ID_BASE + i, Menu.NONE, spec.displayLabel);
      item.setOnMenuItemClickListener(
          menuItem -> {
            triggerLongPressAndSelect(spec);
            return true;
          });
      items.add(item);
    }
    if (!callCard) {
      for (int i = 0; i < MESSAGE_REACTIONS.length; i++) {
        final ActionSpec spec = MESSAGE_REACTIONS[i];
        ContextMenuItem item =
            ContextMenu.createMenuItem(
                context, Menu.NONE, REACTION_ID_BASE + i, Menu.NONE, spec.displayLabel);
        item.setOnMenuItemClickListener(
            menuItem -> {
              triggerLongPressAndSelect(spec);
              return true;
            });
        items.add(item);
      }
    }
    return items;
  }

  private void triggerLongPressAndSelect(ActionSpec spec) {
    cancelPendingPoll();
    pipeline.returnFeedback(
        EVENT_ID_UNTRACKED, Feedback.focus(Feedback.Focus.Action.DIRECT_LONG_PRESS_CURRENT));
    scheduleSheetSearch(spec, /* attempt= */ 0, INITIAL_POLL_DELAY_MS);
  }

  private void scheduleSheetSearch(ActionSpec spec, int attempt, long delayMs) {
    Runnable task =
        () -> {
          pendingPoll = null;
          @Nullable AccessibilityService service = TalkBackService.getInstance();
          if (service == null) {
            return;
          }
          if (clickSheetOption(service, spec)) {
            return;
          }
          if (attempt + 1 < MAX_POLL_ATTEMPTS) {
            scheduleSheetSearch(spec, attempt + 1, POLL_INTERVAL_MS);
          } else {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED, Feedback.speech("Không tìm thấy " + spec.displayLabel));
          }
        };
    pendingPoll = task;
    handler.postDelayed(task, delayMs);
  }

  private void cancelPendingPoll() {
    if (pendingPoll != null) {
      handler.removeCallbacks(pendingPoll);
      pendingPoll = null;
    }
  }

  private boolean clickSheetOption(AccessibilityService service, ActionSpec spec) {
    List<AccessibilityNodeInfo> roots = collectMessengerRoots(service);
    for (int i = 0; i < roots.size(); i++) {
      AccessibilityNodeInfo match = findMatchingButtonInSheet(roots.get(i), spec);
      if (match != null) {
        if (performClickOrRawTap(service, match, spec.isReaction)) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<AccessibilityNodeInfo> collectMessengerRoots(AccessibilityService service) {
    List<AccessibilityNodeInfo> roots = new ArrayList<>();
    List<AccessibilityWindowInfo> windows = service.getWindows();
    if (windows != null) {
      for (int i = 0; i < windows.size(); i++) {
        AccessibilityWindowInfo win = windows.get(i);
        if (win == null) {
          continue;
        }
        AccessibilityNodeInfo root = win.getRoot();
        if (root != null && MessengerAccessibilityHelper.isMessengerPackage(root.getPackageName())) {
          roots.add(root);
        }
      }
    }
    AccessibilityNodeInfo active = service.getRootInActiveWindow();
    if (active != null
        && MessengerAccessibilityHelper.isMessengerPackage(active.getPackageName())
        && !roots.contains(active)) {
      roots.add(active);
    }
    return roots;
  }

  static @Nullable AccessibilityNodeInfo findMatchingButtonInSheet(
      @Nullable AccessibilityNodeInfo root, ActionSpec spec) {
    if (root == null) {
      return null;
    }
    // Verify the window looks like an open Messenger action/reaction sheet so we never click
    // unrelated buttons in the underlying chat window.
    if (!looksLikeActionSheet(root)) {
      return null;
    }
    ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
    queue.add(root);
    int scanned = 0;
    while (!queue.isEmpty() && scanned < MAX_SCAN_NODES) {
      AccessibilityNodeInfo cur = queue.poll();
      scanned++;
      if (cur == null || !cur.isVisibleToUser()) {
        continue;
      }
      CharSequence cls = cur.getClassName();
      if (cls != null && "android.widget.Button".contentEquals(cls)) {
        if (matchesAnyLabel(cur.getText(), spec.matchLabels)
            || matchesAnyLabel(cur.getContentDescription(), spec.matchLabels)) {
          return cur;
        }
      }
      int count = cur.getChildCount();
      for (int i = 0; i < count && scanned + queue.size() < MAX_SCAN_NODES; i++) {
        AccessibilityNodeInfo child = cur.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return null;
  }

  private static boolean looksLikeActionSheet(AccessibilityNodeInfo root) {
    ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
    queue.add(root);
    int scanned = 0;
    while (!queue.isEmpty() && scanned < MAX_SCAN_NODES) {
      AccessibilityNodeInfo cur = queue.poll();
      scanned++;
      if (cur == null) {
        continue;
      }
      CharSequence cls = cur.getClassName();
      if (cls != null && cls.toString().endsWith("AbsListView")) {
        return true;
      }
      CharSequence desc = cur.getContentDescription();
      if (desc != null) {
        String d = desc.toString().toLowerCase(Locale.ROOT);
        if (d.contains("bảng điều khiến")
            || d.contains("bảng điều khiển")
            || d.contains("danh sách các cảm xúc")
            || d.contains("chọn cảm xúc")
            || d.contains("reactions")) {
          return true;
        }
      }
      int count = cur.getChildCount();
      for (int i = 0; i < count && scanned + queue.size() < MAX_SCAN_NODES; i++) {
        AccessibilityNodeInfo child = cur.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return false;
  }

  private static boolean matchesAnyLabel(@Nullable CharSequence raw, String[] targets) {
    if (TextUtils.isEmpty(raw)) {
      return false;
    }
    String normalized = raw.toString().trim().toLowerCase(Locale.ROOT);
    for (String target : targets) {
      String t = target.trim().toLowerCase(Locale.ROOT);
      if (normalized.equals(t) || normalized.startsWith(t + ",")) {
        return true;
      }
    }
    return false;
  }

  private static boolean performClickOrRawTap(
      AccessibilityService service, AccessibilityNodeInfo target, boolean preferTapFallback) {
    if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      return true;
    }
    if (!preferTapFallback) {
      AccessibilityNodeInfo parent = target.getParent();
      for (int depth = 0; depth < 3 && parent != null; depth++) {
        if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          return true;
        }
        parent = parent.getParent();
      }
    }
    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      return true;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return false;
    }
    Rect bounds = new Rect();
    target.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      return false;
    }
    Path path = new Path();
    path.moveTo(bounds.centerX(), bounds.centerY());
    GestureDescription.StrokeDescription stroke =
        new GestureDescription.StrokeDescription(path, /* startTime= */ 0L, /* duration= */ 40L);
    return service.dispatchGesture(
        new GestureDescription.Builder().addStroke(stroke).build(), null, null);
  }

  /**
   * Returns true when {@code node} is a message or call card inside a Messenger chat thread (not
   * the Inbox list, not an open bottom-sheet, and not the top header or bottom composer).
   */
  static boolean isMessengerChatItemNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null
        || !MessengerAccessibilityHelper.isMessengerPackage(node.getPackageName())
        || !node.isVisibleToUser()
        || node.isEditable()) {
      return false;
    }
    // Reject buttons/controls inside an already-open bottom sheet or composer/header.
    if (isInsideActionSheetOrExcludedControl(node)) {
      return false;
    }
    // Fast path: Messenger attaches the explicit message-reaction hint to chat message nodes.
    if (hasMessageHintInNodeOrNeighbors(node)) {
      return true;
    }
    // General path for in-chat call cards and media/voice messages without the text hint:
    // must be inside a chat thread window (not Inbox) and inside a scrollable message list.
    if (!hasScrollableListAncestor(node)) {
      return false;
    }
    if (!isInMessengerChatScreen(node)) {
      return false;
    }
    return isLongClickableOrHasLongClickableNeighbor(node);
  }

  static boolean isCallCardNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || hasMessageHintInNodeOrNeighbors(node)) {
      return false;
    }
    String combined = collectNodeAndChildrenText(node).toLowerCase(Locale.ROOT);
    return combined.contains("cuộc gọi")
        || combined.contains("gọi nhỡ")
        || combined.contains("gọi thoại")
        || combined.contains("gọi video")
        || combined.contains("audio call")
        || combined.contains("video call")
        || combined.contains("missed call");
  }

  private static boolean hasMessageHintInNodeOrNeighbors(AccessibilityNodeInfoCompat node) {
    if (hasMessageHintText(node.getContentDescription()) || hasMessageHintText(node.getText())) {
      return true;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null
        && (hasMessageHintText(parent.getContentDescription())
            || hasMessageHintText(parent.getText()))) {
      return true;
    }
    int count = Math.min(node.getChildCount(), 6);
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null
          && (hasMessageHintText(child.getContentDescription())
              || hasMessageHintText(child.getText()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasMessageHintText(@Nullable CharSequence cs) {
    if (TextUtils.isEmpty(cs)) {
      return false;
    }
    String lower = cs.toString().toLowerCase(Locale.ROOT);
    return lower.contains("bày tỏ cảm xúc về tin nhắn")
        || lower.contains("xem ngày giờ gửi/nhận")
        || lower.contains("react to this message");
  }

  private static boolean isInsideActionSheetOrExcludedControl(AccessibilityNodeInfoCompat node) {
    CharSequence desc = node.getContentDescription();
    if (desc != null) {
      String d = desc.toString().trim();
      if (d.startsWith("Mở trang cá nhân của ")
          || d.equals("Quay lại")
          || d.contains("Chi tiết chuỗi bài")
          || d.equals("Gọi thoại")
          || d.equals("Gọi video")
          || d.equals("Tin nhắn mới")
          || d.equals("Gửi")
          || d.startsWith("Tab ")) {
        return true;
      }
    }
    AccessibilityNodeInfoCompat cur = node;
    for (int depth = 0; depth < 8 && cur != null; depth++) {
      CharSequence cls = cur.getClassName();
      if (cls != null && cls.toString().endsWith("AbsListView")) {
        return true;
      }
      CharSequence curDesc = cur.getContentDescription();
      if (curDesc != null) {
        String lower = curDesc.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("bảng điều khiến") || lower.contains("danh sách các cảm xúc")) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean hasScrollableListAncestor(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 12 && cur != null; depth++) {
      CharSequence cls = cur.getClassName();
      if (cls != null) {
        String s = cls.toString();
        if (s.contains("RecyclerView") || s.contains("ListView")) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean isLongClickableOrHasLongClickableNeighbor(
      AccessibilityNodeInfoCompat node) {
    if (node.isLongClickable()) {
      return true;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent != null && parent.isLongClickable()) {
      return true;
    }
    int count = Math.min(node.getChildCount(), 6);
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && child.isLongClickable()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Distinguishes an open Messenger chat thread from the Inbox screen by checking the window root
   * for chat-only chrome ("Chi tiết chuỗi bài", "Quay lại", or an EditText composer) while
   * rejecting Inbox chrome ("Tin nhắn mới").
   */
  private static boolean isInMessengerChatScreen(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat root = node;
    while (root.getParent() != null) {
      root = root.getParent();
    }
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(root);
    int scanned = 0;
    boolean hasChatChrome = false;
    while (!queue.isEmpty() && scanned < 180) {
      AccessibilityNodeInfoCompat cur = queue.poll();
      scanned++;
      if (cur == null) {
        continue;
      }
      if (cur.isEditable()) {
        hasChatChrome = true;
      }
      CharSequence desc = cur.getContentDescription();
      if (desc != null) {
        String d = desc.toString();
        if (d.equals("Tin nhắn mới") || d.equals("New message")) {
          return false;
        }
        if (d.contains("Chi tiết chuỗi bài") || d.equals("Quay lại") || d.equals("Back")) {
          hasChatChrome = true;
        }
      }
      int count = cur.getChildCount();
      for (int i = 0; i < count && scanned + queue.size() < 180; i++) {
        AccessibilityNodeInfoCompat child = cur.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return hasChatChrome;
  }

  private static String collectNodeAndChildrenText(AccessibilityNodeInfoCompat node) {
    StringBuilder sb = new StringBuilder();
    if (!TextUtils.isEmpty(node.getText())) {
      sb.append(node.getText()).append(' ');
    }
    if (!TextUtils.isEmpty(node.getContentDescription())) {
      sb.append(node.getContentDescription()).append(' ');
    }
    int count = Math.min(node.getChildCount(), 8);
    for (int i = 0; i < count; i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      if (!TextUtils.isEmpty(child.getText())) {
        sb.append(child.getText()).append(' ');
      }
      if (!TextUtils.isEmpty(child.getContentDescription())) {
        sb.append(child.getContentDescription()).append(' ');
      }
    }
    return sb.toString();
  }
}
