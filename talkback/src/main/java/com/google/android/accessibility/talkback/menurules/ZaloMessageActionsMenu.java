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

import static com.google.android.accessibility.talkback.Feedback.PassThroughMode.Action.LOCK_PASS_THROUGH;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.gesture.SyntheticTouches;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.ZaloAccessibilityHelper;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Cung cấp danh sách Hành động theo ngữ cảnh (vuốt lên/xuống ở chế độ Hành động) cho từng loại tin
 * nhắn trong phòng chat Zalo: Thẻ cuộc gọi, Tin nhắn hình ảnh và Tin nhắn chữ — bao gồm cả 6 thao
 * tác thả cảm xúc nhanh trực tiếp ngay từ tin nhắn.
 *
 * <p>Khi người dùng chạm đúp vào một thao tác (ví dụ "Trả lời", "Lưu ảnh", "Xóa" hoặc "Thả cảm xúc:
 * Thả tim"), Meridian tự động nhấn giữ trực tiếp lên bong bóng tin nhắn để mở bảng menu Zalo, sau
 * đó tự tìm và nhấn đúng nút thao tác / biểu tượng cảm xúc tương ứng rồi đọc xác nhận.
 */
public final class ZaloMessageActionsMenu implements NodeMenu {

  private static final int ACTION_ID_BASE = 0x5f000000;
  private static final int REACTION_ID_BASE = 0x5f100000;
  private static final int SEE_MORE_ACTION_ID = 0x5f200000;
  private static final long INITIAL_POLL_DELAY_MS = 1050L;
  private static final long POLL_INTERVAL_MS = 180L;
  private static final long REACTION_TAP_WAIT_MS = 140L;
  private static final int MAX_POLL_ATTEMPTS = 8;
  private static final int MAX_WINDOW_SCAN_NODES = 250;
  private static final int REACTION_SLOT_COUNT = 7;

  private static final String[] REACTION_LABELS =
      new String[] {
        "Thả tim",
        "Thích",
        "Haha",
        "Ngạc nhiên",
        "Buồn",
        "Tức giận",
      };

  private static final String[] CALL_ACTIONS =
      new String[] {
        "Chọn nhiều",
        "Chi tiết",
        "Xóa",
      };

  private static final String[] IMAGE_ACTIONS =
      new String[] {
        "Trả lời",
        "Chuyển tiếp",
        "Lưu ảnh",
        "Sửa ảnh",
        "Sao chép",
        "Xóa",
        "Ghim",
        "Lưu My Documents",
        "Chọn nhiều",
        "Tạo tin nhắn nhanh",
        "Chi tiết",
      };

  private static final String[] IMAGE_OUTGOING_ACTIONS =
      new String[] {
        "Trả lời",
        "Chuyển tiếp",
        "Lưu ảnh",
        "Sửa ảnh",
        "Sao chép",
        "Thu hồi",
        "Xóa",
        "Ghim",
        "Lưu My Documents",
        "Chọn nhiều",
        "Tạo tin nhắn nhanh",
        "Chi tiết",
      };

  private static final String[] TEXT_ACTIONS =
      new String[] {
        "Trả lời",
        "Chuyển tiếp",
        "Sao chép",
        "Thu hồi",
        "Xóa",
        "Ghim",
        "Nhắc hẹn",
        "Lưu My Documents",
        "Chọn nhiều",
        "Tạo tin nhắn nhanh",
        "Dịch",
        "Đọc văn bản",
        "Chi tiết",
      };

  private final Pipeline.FeedbackReturner pipeline;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private @Nullable Runnable pendingPoll;

  public ZaloMessageActionsMenu(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    return isZaloChatMessageNode(node) || isZaloTimelinePostNode(node);
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> items = new ArrayList<>();
    boolean isChatMessage = isZaloChatMessageNode(node);
    boolean isTimelinePost = !isChatMessage && isZaloTimelinePostNode(node);
    if (!isChatMessage && !isTimelinePost) {
      return items;
    }
    String lower = collectLowerText(node);
    if (hasSeeMoreTruncation(lower)) {
      Rect capturedBounds = new Rect();
      node.getBoundsInScreen(capturedBounds);
      final boolean timelineMode = isTimelinePost;
      ContextMenuItem seeMoreItem =
          ContextMenu.createMenuItem(
              context, Menu.NONE, SEE_MORE_ACTION_ID, Menu.NONE, "Xem thêm");
      seeMoreItem.setOnMenuItemClickListener(
          menuItem -> {
            triggerSeeMoreTap(node, capturedBounds, lower, timelineMode);
            return true;
          });
      items.add(seeMoreItem);
    }
    if (isTimelinePost) {
      return items;
    }
    boolean callCard = isCallCard(lower);
    String[] labels = resolveContextualActions(node, lower, callCard);
    for (int i = 0; i < labels.length; i++) {
      final String targetLabel = labels[i];
      ContextMenuItem item =
          ContextMenu.createMenuItem(
              context, Menu.NONE, ACTION_ID_BASE + i, Menu.NONE, targetLabel);
      item.setOnMenuItemClickListener(
          menuItem -> {
            triggerLongPressAndSelect(targetLabel);
            return true;
          });
      items.add(item);
    }

    // Tin nhắn chữ và hình ảnh đều hỗ trợ thanh cảm xúc; thẻ cuộc gọi thì không.
    if (!callCard) {
      for (int i = 0; i < REACTION_LABELS.length; i++) {
        final int slotIndex = i;
        final String reactionName = REACTION_LABELS[i];
        ContextMenuItem item =
            ContextMenu.createMenuItem(
                context,
                Menu.NONE,
                REACTION_ID_BASE + i,
                Menu.NONE,
                "Thả cảm xúc: " + reactionName);
        item.setOnMenuItemClickListener(
            menuItem -> {
              triggerLongPressAndReact(slotIndex, reactionName);
              return true;
            });
        items.add(item);
      }
    }
    return items;
  }

  static boolean hasSeeMoreTruncation(String lower) {
    String trimmed = lower.trim();
    return trimmed.endsWith("xem thêm")
        || trimmed.contains("… xem thêm")
        || trimmed.contains("... xem thêm")
        || trimmed.contains("…xem thêm")
        || trimmed.contains("...xem thêm")
        || trimmed.contains("\nxem thêm");
  }

  private void triggerSeeMoreTap(
      AccessibilityNodeInfoCompat node,
      Rect capturedBounds,
      String lowerText,
      boolean isTimelinePost) {
    cancelPendingPoll();
    @Nullable AccessibilityService service = TalkBackService.getInstance();
    if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return;
    }
    Rect bounds = new Rect(capturedBounds);
    if (node.refresh()) {
      Rect refreshed = new Rect();
      node.getBoundsInScreen(refreshed);
      if (!refreshed.isEmpty()) {
        bounds.set(refreshed);
      }
    }
    if (bounds.isEmpty()) {
      return;
    }
    DisplayMetrics metrics = service.getResources().getDisplayMetrics();
    int fallbackX;
    int fallbackY;
    if (isTimelinePost) {
      // Trên Nhật ký Zalo, cụm "...Xem thêm" nằm ở góc dưới bên phải khối văn bản bài viết.
      fallbackX = bounds.left + (int) (bounds.width() * 0.78f);
      fallbackY = Math.max(bounds.top + 8, bounds.bottom - Math.round(20f * metrics.density));
    } else {
      fallbackX =
          isOutgoingHint(lowerText)
              ? bounds.left + (int) (bounds.width() * 0.72f)
              : bounds.left + (int) (bounds.width() * 0.30f);
      fallbackY = Math.max(bounds.top + 8, bounds.bottom - Math.round(28f * metrics.density));
    }

    if (FeatureSupport.canTakeScreenShotByAccessibilityService()) {
      ScreenshotCapture.takeScreenshot(
          service,
          (screenCapture, isFormatSupported) -> {
            int[] pt = findSeeMoreLinkPoint(screenCapture, bounds, fallbackX, fallbackY, metrics.density);
            if (screenCapture != null && !screenCapture.isRecycled()) {
              screenCapture.recycle();
            }
            tapRawPoint(service, pt[0], pt[1], /* spokenConfirmation= */ null);
          });
      return;
    }
    tapRawPoint(service, fallbackX, fallbackY, /* spokenConfirmation= */ null);
  }

  /**
   * Tìm chính xác tọa độ cụm chữ xanh "Xem thêm" ở phần đáy của bong bóng tin nhắn dài trên Zalo.
   * Nếu không thấy chữ xanh (ví dụ đổi giao diện), tự động chạm vùng đáy bong bóng.
   */
  private static int[] findSeeMoreLinkPoint(
      @Nullable Bitmap bitmap, Rect bounds, int fallbackX, int fallbackY, float density) {
    if (bitmap == null || bitmap.isRecycled()) {
      return new int[] {fallbackX, fallbackY};
    }
    int bmpW = bitmap.getWidth();
    int bmpH = bitmap.getHeight();
    int left = Math.max(0, Math.min(bounds.left, bmpW - 1));
    int right = Math.max(left + 1, Math.min(bounds.right, bmpW));
    int top = Math.max(0, Math.min(bounds.top, bmpH - 1));
    int bottom = Math.max(top + 1, Math.min(bounds.bottom, bmpH));
    int width = right - left;
    int height = bottom - top;
    if (width < 40 || height < 40) {
      return new int[] {fallbackX, fallbackY};
    }

    // Quét 30% phía dưới của khung tin nhắn để tìm cụm chữ xanh "Xem thêm" (xanh dương Zalo)
    int scanTop = top + (int) (height * 0.70f);
    int scanBottom = Math.max(scanTop + 1, bottom - Math.round(6f * density));
    int scanLeft = left + (int) (width * 0.10f);
    int scanRight = right - Math.round(8f * density);

    long sumX = 0;
    long sumY = 0;
    int blueCount = 0;
    int step = Math.max(1, Math.round(density));
    for (int y = scanTop; y < scanBottom; y += step) {
      for (int x = scanLeft; x < scanRight; x += step) {
        int px = bitmap.getPixel(x, y);
        int r = Color.red(px);
        int g = Color.green(px);
        int b = Color.blue(px);
        // Chữ link xanh Zalo: kênh Blue nổi trội rõ so với Red (tránh nền xanh nhạt của tin nhắn đi)
        if (b >= 145 && (b - r) >= 55 && (b - g) >= 15) {
          sumX += x;
          sumY += y;
          blueCount++;
        }
      }
    }
    if (blueCount >= 8) {
      return new int[] {(int) (sumX / blueCount), (int) (sumY / blueCount)};
    }
    return new int[] {fallbackX, fallbackY};
  }

  private void triggerLongPressAndSelect(String targetLabel) {
    cancelPendingPoll();
    pipeline.returnFeedback(
        EVENT_ID_UNTRACKED, Feedback.focus(Feedback.Focus.Action.DIRECT_LONG_PRESS_CURRENT));
    scheduleMenuSearch(targetLabel, /* attempt= */ 0, INITIAL_POLL_DELAY_MS);
  }

  private void triggerLongPressAndReact(int slotIndex, String reactionName) {
    cancelPendingPoll();
    pipeline.returnFeedback(
        EVENT_ID_UNTRACKED, Feedback.focus(Feedback.Focus.Action.DIRECT_LONG_PRESS_CURRENT));
    scheduleReactionSearch(slotIndex, reactionName, /* attempt= */ 0, INITIAL_POLL_DELAY_MS);
  }

  private void scheduleMenuSearch(String targetLabel, int attempt, long delayMs) {
    Runnable task =
        () -> {
          pendingPoll = null;
          @Nullable AccessibilityService service = TalkBackService.getInstance();
          if (service == null) {
            return;
          }
          if (clickMenuOptionByLabel(service, targetLabel)) {
            return;
          }
          if (attempt + 1 < MAX_POLL_ATTEMPTS) {
            scheduleMenuSearch(targetLabel, attempt + 1, POLL_INTERVAL_MS);
          } else {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED, Feedback.speech("Không tìm thấy " + targetLabel));
          }
        };
    pendingPoll = task;
    handler.postDelayed(task, delayMs);
  }

  private void scheduleReactionSearch(
      int slotIndex, String reactionName, int attempt, long delayMs) {
    Runnable task =
        () -> {
          pendingPoll = null;
          @Nullable AccessibilityService service = TalkBackService.getInstance();
          if (service == null) {
            return;
          }
          AccessibilityNodeInfo bar = findReactionBarInWindows(service);
          if (bar != null) {
            Rect bounds = new Rect();
            bar.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
              int x = ZaloReactionMenu.computeSlotCenterX(bounds, slotIndex, REACTION_SLOT_COUNT);
              int y = bounds.centerY();
              tapRawPoint(service, x, y, "Đã gửi cảm xúc " + reactionName);
              return;
            }
          }
          if (attempt + 1 < MAX_POLL_ATTEMPTS) {
            scheduleReactionSearch(slotIndex, reactionName, attempt + 1, POLL_INTERVAL_MS);
          } else {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED, Feedback.speech("Không thể thả cảm xúc cho tin nhắn này"));
          }
        };
    pendingPoll = task;
    handler.postDelayed(task, delayMs);
  }

  private void tapRawPoint(
      AccessibilityService service, int x, int y, @Nullable String spokenConfirmation) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return;
    }
    long tapDurationMs = ViewConfiguration.getTapTimeout();
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, /* startTime= */ 0, tapDurationMs))
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      DisplayMetrics metrics = service.getResources().getDisplayMetrics();
      int radius = 64;
      Region region =
          new Region(
              Math.max(0, x - radius),
              Math.max(0, y - radius),
              Math.max(x + radius, metrics.widthPixels),
              Math.max(y + radius, metrics.heightPixels));
      pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.passThroughMode(LOCK_PASS_THROUGH, region));
    }
    Runnable finishSuccess =
        () -> {
          releasePassThrough();
          if (spokenConfirmation != null) {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED, Feedback.speech(spokenConfirmation));
          }
        };
    Runnable finishCancel = this::releasePassThrough;
    if (SyntheticTouches.isAvailable()) {
      handler.postDelayed(
          () -> {
            if (!SyntheticTouches.dispatch(service, gesture, tapDurationMs, finishSuccess)) {
              finishCancel.run();
            }
          },
          REACTION_TAP_WAIT_MS);
      return;
    }
    AccessibilityService.GestureResultCallback callback =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription description) {
            finishSuccess.run();
          }

          @Override
          public void onCancelled(GestureDescription description) {
            finishCancel.run();
          }
        };
    handler.postDelayed(
        () -> {
          if (!service.dispatchGesture(gesture, callback, handler)) {
            finishCancel.run();
          }
        },
        REACTION_TAP_WAIT_MS);
  }

  private void releasePassThrough() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED, Feedback.passThroughMode(LOCK_PASS_THROUGH, /* region= */ null));
    }
  }

  private void cancelPendingPoll() {
    if (pendingPoll != null) {
      handler.removeCallbacks(pendingPoll);
      pendingPoll = null;
    }
  }

  private static List<AccessibilityNodeInfo> collectZaloWindowRoots(AccessibilityService service) {
    List<AccessibilityNodeInfo> roots = new ArrayList<>();
    List<AccessibilityWindowInfo> windows = service.getWindows();
    if (windows != null) {
      for (int i = windows.size() - 1; i >= 0; i--) {
        AccessibilityWindowInfo win = windows.get(i);
        if (win == null) {
          continue;
        }
        AccessibilityNodeInfo root = win.getRoot();
        if (root != null && ZaloAccessibilityHelper.isZaloPackage(root.getPackageName())) {
          roots.add(root);
        }
      }
    }
    if (roots.isEmpty()) {
      AccessibilityNodeInfo active = service.getRootInActiveWindow();
      if (active != null && ZaloAccessibilityHelper.isZaloPackage(active.getPackageName())) {
        roots.add(active);
      }
    }
    return roots;
  }

  @Nullable
  private static AccessibilityNodeInfo findReactionBarInWindows(AccessibilityService service) {
    for (AccessibilityNodeInfo root : collectZaloWindowRoots(service)) {
      ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
      queue.add(root);
      int visited = 0;
      while (!queue.isEmpty() && visited < MAX_WINDOW_SCAN_NODES) {
        AccessibilityNodeInfo current = queue.poll();
        visited++;
        if (current == null || !current.isVisibleToUser()) {
          continue;
        }
        if (ZaloAccessibilityHelper.isReactionBarNode(AccessibilityNodeInfoCompat.wrap(current))) {
          return current;
        }
        int childCount = current.getChildCount();
        for (int i = 0; i < childCount; i++) {
          AccessibilityNodeInfo child = current.getChild(i);
          if (child != null) {
            queue.add(child);
          }
        }
      }
    }
    return null;
  }

  private static boolean clickMenuOptionByLabel(
      AccessibilityService service, String targetLabel) {
    String normalizedTarget = normalizeLabel(targetLabel);
    for (AccessibilityNodeInfo root : collectZaloWindowRoots(service)) {
      AccessibilityNodeInfo match = findMatchingNode(root, normalizedTarget);
      if (match != null && performClickOnSelfOrParent(match)) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static AccessibilityNodeInfo findMatchingNode(
      AccessibilityNodeInfo root, String normalizedTarget) {
    ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
    queue.add(root);
    int visited = 0;
    while (!queue.isEmpty() && visited < MAX_WINDOW_SCAN_NODES) {
      AccessibilityNodeInfo current = queue.poll();
      visited++;
      if (current == null || !current.isVisibleToUser()) {
        continue;
      }
      if (matchesLabel(current.getText(), normalizedTarget)
          || matchesLabel(current.getContentDescription(), normalizedTarget)) {
        return current;
      }
      int childCount = current.getChildCount();
      for (int i = 0; i < childCount; i++) {
        AccessibilityNodeInfo child = current.getChild(i);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return null;
  }

  private static boolean performClickOnSelfOrParent(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo current = node;
    for (int depth = 0; depth < 4 && current != null; depth++) {
      if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        return true;
      }
      current = current.getParent();
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
  }

  private static boolean matchesLabel(@Nullable CharSequence candidate, String normalizedTarget) {
    if (candidate == null || candidate.length() == 0) {
      return false;
    }
    return normalizeLabel(candidate.toString()).equals(normalizedTarget);
  }

  private static String normalizeLabel(String raw) {
    return raw.replace('\n', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static String[] resolveContextualActions(
      AccessibilityNodeInfoCompat node, String lower, boolean callCard) {
    if (callCard) {
      return CALL_ACTIONS;
    }
    if (isImageMessage(node, lower)) {
      return isOutgoingHint(lower) ? IMAGE_OUTGOING_ACTIONS : IMAGE_ACTIONS;
    }
    return TEXT_ACTIONS;
  }

  private static boolean isCallCard(String lower) {
    return lower.contains("cuộc gọi thoại")
        || lower.contains("cuộc gọi video")
        || lower.contains("bạn bị nhỡ")
        || lower.contains("cuộc gọi nhỡ")
        || lower.contains("bạn đã hủy")
        || lower.contains("bạn đã từ chối")
        || lower.contains("gọi lại");
  }

  private static boolean isImageMessage(AccessibilityNodeInfoCompat node, String lower) {
    String vid = node.getViewIdResourceName();
    if (vid != null) {
      String lv = vid.toLowerCase(Locale.ROOT);
      if (lv.contains("photo") || lv.contains("image") || lv.contains("picture")) {
        return true;
      }
    }
    if (lower.contains("hình ảnh") || lower.contains("[hình ảnh]") || lower.contains("ảnh,")) {
      return true;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (!bounds.isEmpty() && bounds.height() > bounds.width() * 0.45f) {
      String stripped =
          lower
              .replaceAll("[0-9:\\s/]+", "")
              .replace("đã nhận", "")
              .replace("đã xem", "")
              .replace("đã gửi", "")
              .trim();
      if (stripped.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isOutgoingHint(String lower) {
    return lower.contains("đã gửi")
        || lower.contains("đã nhận")
        || lower.contains("đã xem")
        || lower.contains("đang gửi");
  }

  private static boolean isZaloChatMessageNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null
        || !ZaloAccessibilityHelper.isZaloPackage(node.getPackageName())
        || node.isEditable()
        || ZaloAccessibilityHelper.isReactionBarNode(node)) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls != null) {
      String c = cls.toString();
      if (c.contains("ListView") || c.contains("RecyclerView") || c.contains("EditText")) {
        return false;
      }
    }
    String vid = node.getViewIdResourceName();
    if (vid != null && isExcludedViewId(vid)) {
      return false;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty() || bounds.height() < 40) {
      return false;
    }

    boolean hasListAncestor = false;
    boolean hasChatAncestor = false;
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 10 && cur != null; depth++) {
      String pvid = cur.getViewIdResourceName();
      if (pvid != null) {
        if (isExcludedViewId(pvid)) {
          return false;
        }
        String lp = pvid.toLowerCase(Locale.ROOT);
        if (lp.contains("main_chat_view")
            || lp.contains("chat_drawer_layout")
            || lp.contains("chat_list")
            || lp.contains("chatlinelist")
            || lp.contains("chat_content")) {
          hasChatAncestor = true;
        }
      }
      CharSequence pcls = cur.getClassName();
      if (pcls != null) {
        String pc = pcls.toString();
        if (pc.contains("ListView") || pc.contains("RecyclerView")) {
          hasListAncestor = true;
        }
      }
      cur = cur.getParent();
    }
    return hasListAncestor && hasChatAncestor;
  }

  /**
   * Nhận diện khối bài viết bị rút gọn ("...Xem thêm") trên Nhật ký Zalo ({@code lv_media_store}
   * / {@code layoutSocialFeed}) để chỉ thêm đúng thao tác "Xem thêm" mà không lẫn các thao tác
   * tin nhắn.
   */
  static boolean isZaloTimelinePostNode(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null
        || !ZaloAccessibilityHelper.isZaloPackage(node.getPackageName())
        || !node.isClickable()
        || !node.isFocusable()
        || ZaloAccessibilityHelper.isReactionBarNode(node)) {
      return false;
    }
    CharSequence cls = node.getClassName();
    if (cls == null) {
      return false;
    }
    String className = cls.toString();
    if (!className.endsWith("FrameLayout") && !className.endsWith("ViewGroup")) {
      return false;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty() || bounds.height() < 40) {
      return false;
    }
    String lower = collectLowerText(node);
    if (!hasSeeMoreTruncation(lower)) {
      return false;
    }
    AccessibilityNodeInfoCompat cur = node.getParent();
    for (int depth = 0; depth < 8 && cur != null; depth++) {
      String pvid = cur.getViewIdResourceName();
      if (pvid != null) {
        if (isExcludedViewId(pvid)) {
          return false;
        }
        String lp = pvid.toLowerCase(Locale.ROOT);
        if (lp.contains("lv_media_store") || lp.contains("layoutsocialfeed")) {
          return true;
        }
      }
      cur = cur.getParent();
    }
    return false;
  }

  private static boolean isExcludedViewId(String viewId) {
    String lower = viewId.toLowerCase(Locale.ROOT);
    return lower.contains("actionbar")
        || lower.contains("zds_action_bar")
        || lower.contains("chat_input")
        || lower.contains("btn_send")
        || lower.contains("sticker")
        || lower.contains("emoji")
        || lower.contains("reaction_container")
        || lower.contains("chat_right_drawer");
  }

  private static String collectLowerText(AccessibilityNodeInfoCompat node) {
    StringBuilder sb = new StringBuilder();
    if (node.getText() != null) {
      sb.append(node.getText()).append(' ');
    }
    if (node.getContentDescription() != null) {
      sb.append(node.getContentDescription());
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}
