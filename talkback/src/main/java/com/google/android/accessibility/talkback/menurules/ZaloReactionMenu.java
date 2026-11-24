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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.ViewConfiguration;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.gesture.SyntheticTouches;
import com.google.android.accessibility.utils.ZaloAccessibilityHelper;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Cung cấp 7 hành động chọn trực tiếp từng biểu tượng cảm xúc trên thanh {@code
 * com.zing.zalo:id/reaction_container_view} của Zalo (vốn được vẽ trực tiếp lên 1 khung duy nhất
 * không có view con).
 *
 * <p>Thứ tự 7 ô chia đều từ trái sang phải trên Zalo: Thả tim, Thích, Haha, Ngạc nhiên, Buồn, Tức
 * giận, Thêm biểu tượng cảm xúc.
 */
public final class ZaloReactionMenu implements NodeMenu {

  private static final String TAG = "ZaloReactionMenu";
  private static final int ACTION_ID_BASE = 0x5e000000;
  private static final long PASS_THROUGH_WAIT_MS = 180;

  private static final String[] REACTION_LABELS =
      new String[] {
        "Thả tim",
        "Thích",
        "Haha",
        "Ngạc nhiên",
        "Buồn",
        "Tức giận",
        "Thêm biểu tượng cảm xúc",
      };

  private static final String[] ME_TAB_MENU_LABELS =
      new String[] {
        "zCloud",
        "zStyle – Nổi bật trên Zalo",
        "My Documents",
        "Dữ liệu trên máy",
        "Ví QR",
        "Tài khoản và bảo mật",
        "Quyền riêng tư",
      };

  private static final float[] ME_TAB_MENU_Y_RATIOS =
      new float[] {0.075f, 0.226f, 0.390f, 0.539f, 0.691f, 0.836f, 0.945f};

  private final Pipeline.FeedbackReturner pipeline;
  private final Handler handler = new Handler(Looper.getMainLooper());

  public ZaloReactionMenu(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    return ZaloAccessibilityHelper.isReactionBarNode(node)
        || ZaloAccessibilityHelper.getMeTabCardIndex(node) == 1
        || ZaloAccessibilityHelper.getChatInfoQuickActionLabels(node) != null;
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    boolean vertical = false;
    String[] labels = null;
    float[] yRatios = null;
    if (ZaloAccessibilityHelper.isReactionBarNode(node)) {
      labels = REACTION_LABELS;
    } else if (ZaloAccessibilityHelper.getMeTabCardIndex(node) == 1) {
      vertical = true;
      labels = ME_TAB_MENU_LABELS;
      yRatios = ME_TAB_MENU_Y_RATIOS;
    } else {
      labels = ZaloAccessibilityHelper.getChatInfoQuickActionLabels(node);
    }
    if (labels == null || labels.length == 0) {
      return new ArrayList<>();
    }
    List<ContextMenuItem> items = new ArrayList<>(labels.length);
    Rect capturedBounds = new Rect();
    node.getBoundsInScreen(capturedBounds);
    final int slotCount = labels.length;
    final boolean isVertical = vertical;
    final float[] ratios = yRatios;
    for (int i = 0; i < slotCount; i++) {
      final int slotIndex = i;
      final String label = labels[i];
      ContextMenuItem item =
          ContextMenu.createMenuItem(
              context,
              Menu.NONE,
              ACTION_ID_BASE + slotIndex,
              Menu.NONE,
              label);
      item.setOnMenuItemClickListener(
          menuItem -> {
            Rect bounds = new Rect(capturedBounds);
            if (node.refresh()) {
              Rect refreshed = new Rect();
              node.getBoundsInScreen(refreshed);
              if (!refreshed.isEmpty()) {
                bounds.set(refreshed);
              }
            }
            if (bounds.isEmpty()) {
              return false;
            }
            int x =
                isVertical
                    ? bounds.centerX()
                    : computeSlotCenterX(bounds, slotIndex, slotCount);
            int y =
                isVertical && ratios != null
                    ? Math.round(bounds.top + bounds.height() * ratios[slotIndex])
                    : bounds.centerY();
            LogUtils.d(
                TAG,
                "Tapping Zalo slot %d/%d (%s) at (%d, %d) in %s",
                slotIndex,
                slotCount,
                label,
                x,
                y,
                bounds);
            return tap(x, y, slotIndex < REACTION_LABELS.length - 1 ? REACTION_LABELS[slotIndex] : null);
          });
      items.add(item);
    }
    return items;
  }

  static int computeSlotCenterX(Rect bounds, int slotIndex, int slotCount) {
    return Math.round(bounds.left + bounds.width() * ((slotIndex + 0.5f) / slotCount));
  }

  private boolean tap(int x, int y, @Nullable String reactionLabel) {
    @Nullable AccessibilityService service = TalkBackService.getInstance();
    if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return false;
    }
    long tapDurationMs = ViewConfiguration.getTapTimeout();
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(
                new GestureDescription.StrokeDescription(
                    path, /* startTime= */ 0, tapDurationMs))
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      DisplayMetrics metrics = service.getResources().getDisplayMetrics();
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.passThroughMode(
              LOCK_PASS_THROUGH, new Region(0, 0, metrics.widthPixels, metrics.heightPixels)));
    }
    Runnable finishSuccess =
        () -> {
          release();
          if (reactionLabel != null) {
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED, Feedback.speech("Đã gửi cảm xúc " + reactionLabel));
          }
        };
    if (SyntheticTouches.isAvailable()) {
      handler.postDelayed(
          () -> {
            if (!SyntheticTouches.dispatch(service, gesture, tapDurationMs, finishSuccess)) {
              release();
            }
          },
          PASS_THROUGH_WAIT_MS);
      return true;
    }
    AccessibilityService.GestureResultCallback ended =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription description) {
            finishSuccess.run();
          }

          @Override
          public void onCancelled(GestureDescription description) {
            release();
          }
        };
    handler.postDelayed(
        () -> {
          if (!service.dispatchGesture(gesture, ended, handler)) {
            release();
          }
        },
        PASS_THROUGH_WAIT_MS);
    return true;
  }

  private void release() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED, Feedback.passThroughMode(LOCK_PASS_THROUGH, null));
    }
  }
}
