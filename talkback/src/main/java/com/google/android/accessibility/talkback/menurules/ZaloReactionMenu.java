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

  private final Pipeline.FeedbackReturner pipeline;
  private final Handler handler = new Handler(Looper.getMainLooper());

  public ZaloReactionMenu(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    return ZaloAccessibilityHelper.isReactionBarNode(node);
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> items = new ArrayList<>(REACTION_LABELS.length);
    if (!ZaloAccessibilityHelper.isReactionBarNode(node)) {
      return items;
    }
    Rect capturedBounds = new Rect();
    node.getBoundsInScreen(capturedBounds);
    for (int i = 0; i < REACTION_LABELS.length; i++) {
      final int slotIndex = i;
      ContextMenuItem item =
          ContextMenu.createMenuItem(
              context,
              Menu.NONE,
              ACTION_ID_BASE + slotIndex,
              Menu.NONE,
              REACTION_LABELS[slotIndex]);
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
            int x = computeSlotCenterX(bounds, slotIndex, REACTION_LABELS.length);
            int y = bounds.centerY();
            LogUtils.d(
                TAG,
                "Tapping Zalo reaction slot %d (%s) at (%d, %d) in %s",
                slotIndex,
                REACTION_LABELS[slotIndex],
                x,
                y,
                bounds);
            return tap(x, y);
          });
      items.add(item);
    }
    return items;
  }

  static int computeSlotCenterX(Rect bounds, int slotIndex, int slotCount) {
    return Math.round(bounds.left + bounds.width() * ((slotIndex + 0.5f) / slotCount));
  }

  private boolean tap(int x, int y) {
    @Nullable AccessibilityService service = TalkBackService.getInstance();
    if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return false;
    }
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(
                new GestureDescription.StrokeDescription(
                    path, /* startTime= */ 0, ViewConfiguration.getTapTimeout()))
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      DisplayMetrics metrics = service.getResources().getDisplayMetrics();
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.passThroughMode(
              LOCK_PASS_THROUGH, new Region(0, 0, metrics.widthPixels, metrics.heightPixels)));
    }
    AccessibilityService.GestureResultCallback ended =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription description) {
            release();
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
