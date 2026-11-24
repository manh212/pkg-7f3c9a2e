/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.accessibility.talkback.actor;

import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK;
import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK;
import static com.google.android.accessibility.talkback.Feedback.EditText.Action.SELECT_SEGMENT;
import static com.google.android.accessibility.talkback.Feedback.PassThroughMode.Action.LOCK_PASS_THROUGH;
import static com.google.android.accessibility.talkback.contextmenu.ListMenuManager.MenuId.CUSTOM_ACTION;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.text.style.URLSpan;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.Braille.BrailleImeInputPolicy;
import com.google.android.accessibility.talkback.ActorStateWritable;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.talkback.UserInterface.UserInputEventListener;
import com.google.android.accessibility.talkback.WebActor;
import com.google.android.accessibility.talkback.actor.helper.FocusActorHelper;
import com.google.android.accessibility.talkback.collapse.CollapseEngine;
import com.google.android.accessibility.talkback.compositor.GlobalVariables;
import com.google.android.accessibility.talkback.contextmenu.ListMenuManager;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionRecord;
import com.google.android.accessibility.talkback.gesture.SyntheticTouches;
import com.google.android.accessibility.talkback.scripting.Scripts;
import com.google.android.accessibility.talkback.utils.LinkUtils;
import com.google.android.accessibility.talkback.utils.LinkUtils.LinkSpan;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.ZaloAccessibilityHelper;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** FocusActor executes focus-feedback, using FocusManagerInternal. */
// TODO: Merge FocusActor with FocusManagerInternal.
public class FocusActor implements UserInputEventListener {

  private static final String TAG = "FocusActor";
  private static final int STROKE_TIME_GAP_MS = 40;
  private static final int FAST_TAP_STROKE_MS = 40;
  private static final long DIRECT_LONG_PRESS_DELAY_MS = 120L;
  private static final long MIN_DIRECT_LONG_PRESS_MS = 700L;
  private static final long LONG_PRESS_EXTRA_MARGIN_MS = 250L;
  private static final int DIRECT_LONG_PRESS_PASS_THROUGH_RADIUS = 48;

  /** The only class in TalkBack which has direct access to accessibility focus from framework. */
  private final FocusManagerInternal focusManagerInternal;

  private final AccessibilityService service;
  private final AccessibilityFocusActionHistory history;
  private final AccessibilityFocusMonitor accessibilityFocusMonitor;

  /** Actor-state passed in from pipeline, which encapsulates {@code history}. */
  private ActorStateWritable actorState;

  private final WebActor webActor;
  private Pipeline.FeedbackReturner pipeline;
  private ListMenuManager menuManager;
  private GlobalVariables globalVariables;

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Construction

  public FocusActor(
      AccessibilityService service,
      FocusFinder focusFinder,
      ScreenStateMonitor.State screenState,
      AccessibilityFocusActionHistory accessibilityFocusActionHistory,
      AccessibilityFocusMonitor accessibilityFocusMonitor,
      GlobalVariables globalVariables) {
    this.service = service;
    this.history = accessibilityFocusActionHistory;
    this.accessibilityFocusMonitor = accessibilityFocusMonitor;
    this.globalVariables = globalVariables;
    focusManagerInternal =
        new FocusManagerInternal(
            service, focusFinder, screenState, history, accessibilityFocusMonitor);
    webActor = new WebActor(service, this::updateFocusHistory);
  }

  public void setActorState(ActorStateWritable actorState) {
    this.actorState = actorState;
    focusManagerInternal.setActorState(actorState);
  }

  public void setPipeline(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
    focusManagerInternal.setPipeline(pipeline);
    webActor.setPipeline(pipeline);
  }

  public void setFocusSetListener(FocusManagerInternal.@Nullable FocusSetListener listener) {
    focusManagerInternal.setFocusSetListener(listener);
  }

  public void setMenuManager(ListMenuManager menuManager) {
    this.menuManager = menuManager;
  }

  public AccessibilityFocusActionHistory.Reader getHistory() {
    return history.reader;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Methods

  public boolean clickCurrentFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);

    if (currentFocus != null && pipeline != null) {
      pipeline.returnFeedback(eventId, Feedback.part().setInterruptGentle(true));
    }
    if (Scripts.touchesToActivate(currentFocus) && touchFocus(currentFocus, eventId)) return true;
    return clickNode(currentFocus, eventId);
  }

  public boolean touchClickCurrentFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus = accessibilityFocusMonitor.getAccessibilityFocus(false);
    if (currentFocus == null || pipeline == null) return false;
    pipeline.returnFeedback(eventId, Feedback.part().setInterruptGentle(true));
    return touchFocus(currentFocus, eventId) || clickNode(currentFocus, eventId);
  }

  private boolean touchFocus(AccessibilityNodeInfoCompat focus, EventId eventId) {
    if (focus == null || pipeline == null || !(service instanceof TalkBackService meridian)) return false;
    allowBrailleImeForUserActivatedEditor(focus);
    pipeline.returnFeedback(eventId, Feedback.interrupt(Feedback.HINT, 2));
    return meridian.performTouchClick(AccessibilityWindowInfoUtils.getDisplayId(focus.getWindow()));
  }

  private void allowBrailleImeForUserActivatedEditor(AccessibilityNodeInfoCompat node) {
    BrailleImeInputPolicy.allowUserReopenForActivation(service, node);
  }

  public boolean clickNode(AccessibilityNodeInfoCompat node, EventId eventId) {
    if (node == null || pipeline == null) {
      return false;
    }
    allowBrailleImeForUserActivatedEditor(node);

    // Grab exposes "Đặt xe" beside the real button. Redirect only an explicit
    // activation of that exact booking control; never initiate a booking on focus.
    AccessibilityNodeInfoCompat grabBookingButton = GrabBookingClickTarget.resolve(node);
    if (grabBookingButton != null) {
      pipeline.returnFeedback(eventId, Feedback.interrupt(Feedback.HINT, /* level= */ 2));
      return pipeline.returnFeedback(
          eventId, Feedback.nodeAction(grabBookingButton, ACTION_CLICK.getId()));
    }

    node = Scripts.actionTarget(node, false);
    AccessibilityNodeInfoCompat collapseTarget = CollapseEngine.activationTarget(service, node);
    if (collapseTarget != null) {
      node = collapseTarget;
    }

    // Drop the focused item's usage hint, which would otherwise be spoken after the item is
    // activated, ahead of the next window's name.
    pipeline.returnFeedback(eventId, Feedback.interrupt(Feedback.HINT, /* level= */ 2));

    if (globalVariables.supportClickableLinks() && tryClickLinks(node, eventId)) {
      return true;
    }

    boolean fastDoubleTap = LatencyExperiments.Feature.FAST_DOUBLE_TAP.enabled()
        && fromDoubleTap(eventId);
    if (PerformActionUtils.isNodeSupportAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK)
        && pipeline.returnFeedback(eventId, Feedback.nodeAction(node, ACTION_CLICK.getId()))) {
      if (fastDoubleTap) logDoubleTapRoute(eventId, "accessibility click");
      return true;
    }
    AccessibilityNodeInfoCompat item = itemOfTextInside(node, ACTION_CLICK.getId());
    if (item != null
        && pipeline.returnFeedback(eventId, Feedback.nodeAction(item, ACTION_CLICK.getId()))) {
      return true;
    }
    if (fastDoubleTap && node.isAccessibilityFocused() && service instanceof TalkBackService meridian
        && meridian.performTouchClick(AccessibilityWindowInfoUtils.getDisplayId(node.getWindow()))) {
      logDoubleTapRoute(eventId, "Android touch controller");
      return true;
    }
    if (fastDoubleTap) logDoubleTapRoute(eventId, "simulated fallback");
    // When none of the preceding conditions are met, the last resort is simulating click event.
    return simulateClickOnNode(service, node, eventId);
  }

  private static void logDoubleTapRoute(EventId eventId, String route) {
    if (LatencyExperiments.Feature.TRACE.enabled()) {
      LogUtils.i(TAG, "Double tap: %s at %dms after recognition",
          route, SystemClock.uptimeMillis() - eventId.getEventTimeMs());
    }
  }

  public boolean doubleClickCurrentFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    return doubleClickNode(currentFocus, eventId);
  }

  public boolean doubleClickNode(AccessibilityNodeInfoCompat node, EventId eventId) {
    return pipeline.returnFeedback(eventId, Feedback.edit(node, SELECT_SEGMENT))
        || simulateDoubleClickOnNode(service, node);
  }

  public boolean longClickCurrentFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    return longClickNode(currentFocus, eventId);
  }

  public boolean longClickNode(AccessibilityNodeInfoCompat node, EventId eventId) {
    if (node == null || pipeline == null) {
      return false;
    }
    node = Scripts.actionTarget(node, true);
    AccessibilityNodeInfoCompat item = itemOfTextInside(node, ACTION_LONG_CLICK.getId());
    AccessibilityNodeInfoCompat target = item != null ? item : node;
    boolean inZalo = ZaloAccessibilityHelper.isZaloPackage(target.getPackageName());
    if (inZalo && !PerformActionUtils.isNodeSupportAction(target, ACTION_LONG_CLICK.getId())) {
      return directLongPressNode(target, eventId);
    }
    boolean performed =
        pipeline.returnFeedback(eventId, Feedback.nodeAction(target, ACTION_LONG_CLICK.getId()));
    if (!performed && inZalo) {
      return directLongPressNode(target, eventId);
    }
    return performed;
  }

  public boolean directLongPressCurrentFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    return directLongPressNode(currentFocus, eventId);
  }

  /**
   * Sends a raw physical long-press directly to the screen center of {@code node}, bypassing
   * Android's {@code ACTION_LONG_CLICK} and touch exploration so apps that handle long-press via
   * raw touch listeners (such as Zalo chat messages) still respond.
   */
  public boolean directLongPressNode(
      @Nullable AccessibilityNodeInfoCompat node, EventId eventId) {
    if (node == null || pipeline == null || service == null) {
      return false;
    }
    node.refresh();
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    DisplayMetrics metrics = service.getResources().getDisplayMetrics();
    if (bounds.isEmpty()) {
      return false;
    }
    pipeline.returnFeedback(eventId, Feedback.part().setInterruptGentle(true));

    // Wide full-row containers (such as Zalo chat message canvases where incoming bubbles sit on
    // the left and outgoing bubbles sit on the right) have empty background at bounds.centerX().
    // Sample the rendered pixels inside the row to land directly on the visible bubble.
    if (bounds.width() > metrics.widthPixels * 0.65f
        && FeatureSupport.canTakeScreenShotByAccessibilityService()) {
      int fallbackX = resolveFallbackBubbleX(node, bounds);
      int fallbackY = bounds.centerY();
      ScreenshotCapture.takeScreenshot(
          service,
          (screenCapture, isFormatSupported) -> {
            int[] point =
                findVisibleBubblePoint(screenCapture, bounds, fallbackX, fallbackY);
            if (screenCapture != null && !screenCapture.isRecycled()) {
              screenCapture.recycle();
            }
            dispatchRawLongPressAt(point[0], point[1], metrics, eventId);
          });
      return true;
    }

    int targetX = resolveFallbackBubbleX(node, bounds);
    return dispatchRawLongPressAt(targetX, bounds.centerY(), metrics, eventId);
  }

  private boolean dispatchRawLongPressAt(
      int rawX, int rawY, DisplayMetrics metrics, EventId eventId) {
    int maxX = Math.max(1, metrics.widthPixels - 2);
    int maxY = Math.max(1, metrics.heightPixels - 2);
    int x = Math.max(1, Math.min(rawX, maxX));
    int y = Math.max(1, Math.min(rawY, maxY));

    long durationMs =
        Math.max(
            ViewConfiguration.getLongPressTimeout() + LONG_PRESS_EXTRA_MARGIN_MS,
            MIN_DIRECT_LONG_PRESS_MS);
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new StrokeDescription(path, /* startTime= */ 0, durationMs))
            .build();

    Handler mainHandler = new Handler(Looper.getMainLooper());
    if (SyntheticTouches.isAvailable()) {
      mainHandler.postDelayed(
          () -> SyntheticTouches.dispatch(service, gesture, durationMs, /* onEnd= */ null),
          DIRECT_LONG_PRESS_DELAY_MS);
      return true;
    }

    Region passThroughRegion =
        new Region(
            Math.max(0, x - DIRECT_LONG_PRESS_PASS_THROUGH_RADIUS),
            Math.max(0, y - DIRECT_LONG_PRESS_PASS_THROUGH_RADIUS),
            Math.min(metrics.widthPixels, x + DIRECT_LONG_PRESS_PASS_THROUGH_RADIUS),
            Math.min(metrics.heightPixels, y + DIRECT_LONG_PRESS_PASS_THROUGH_RADIUS));
    pipeline.returnFeedback(
        eventId, Feedback.passThroughMode(LOCK_PASS_THROUGH, passThroughRegion));
    Runnable unlockPassThrough =
        () ->
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED,
                Feedback.passThroughMode(LOCK_PASS_THROUGH, /* region= */ null));
    AccessibilityService.GestureResultCallback callback =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription description) {
            unlockPassThrough.run();
          }

          @Override
          public void onCancelled(GestureDescription description) {
            unlockPassThrough.run();
          }
        };
    mainHandler.postDelayed(
        () -> {
          if (!service.dispatchGesture(gesture, callback, mainHandler)) {
            unlockPassThrough.run();
          }
        },
        DIRECT_LONG_PRESS_DELAY_MS);
    return true;
  }


  /**
   * Scans horizontal lines inside {@code bounds} on {@code bitmap} to find the widest non-background
   * visual bubble (ignoring the far-left avatar margin), returning {@code [x, y]} in screen
   * coordinates. Falls back to {@code [fallbackX, fallbackY]} when no clear bubble is detected.
   */
  private static int[] findVisibleBubblePoint(
      @Nullable Bitmap bitmap, Rect bounds, int fallbackX, int fallbackY) {
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
    if (width < 40 || height < 20) {
      return new int[] {fallbackX, fallbackY};
    }

    int edgeInset = Math.min(12, Math.max(4, width / 40));
    int sampleTopY = Math.min(bottom - 1, top + Math.min(10, Math.max(4, height / 6)));
    int midY = top + height / 2;
    int[] bgSamples =
        new int[] {
          bitmap.getPixel(left + edgeInset, midY),
          bitmap.getPixel(right - 1 - edgeInset, midY),
          bitmap.getPixel(left + edgeInset, sampleTopY),
          bitmap.getPixel(right - 1 - edgeInset, sampleTopY),
        };
    int bgR = median4(Color.red(bgSamples[0]), Color.red(bgSamples[1]), Color.red(bgSamples[2]), Color.red(bgSamples[3]));
    int bgG = median4(Color.green(bgSamples[0]), Color.green(bgSamples[1]), Color.green(bgSamples[2]), Color.green(bgSamples[3]));
    int bgB = median4(Color.blue(bgSamples[0]), Color.blue(bgSamples[1]), Color.blue(bgSamples[2]), Color.blue(bgSamples[3]));

    // Skip far-left avatar zone (first ~9% of row width) and focus border inset on right.
    int scanStartX = left + Math.max(edgeInset, (int) (width * 0.09f));
    int scanEndX = right - edgeInset;
    int[] scanYs =
        new int[] {
          top + (int) (height * 0.35f),
          midY,
          top + (int) (height * 0.65f),
        };

    int bestLen = 0;
    int bestX = fallbackX;
    int bestY = fallbackY;
    int minRunPx = Math.max(24, width / 18);

    for (int y : scanYs) {
      if (y < top || y >= bottom) {
        continue;
      }
      int curStart = -1;
      for (int x = scanStartX; x < scanEndX; x++) {
        int px = bitmap.getPixel(x, y);
        int diff =
            Math.abs(Color.red(px) - bgR)
                + Math.abs(Color.green(px) - bgG)
                + Math.abs(Color.blue(px) - bgB);
        if (diff > 20) {
          if (curStart < 0) {
            curStart = x;
          }
        } else if (curStart >= 0) {
          int runLen = x - curStart;
          if (runLen > bestLen) {
            bestLen = runLen;
            bestX = (curStart + x) / 2;
            bestY = y;
          }
          curStart = -1;
        }
      }
      if (curStart >= 0) {
        int runLen = scanEndX - curStart;
        if (runLen > bestLen) {
          bestLen = runLen;
          bestX = (curStart + scanEndX) / 2;
          bestY = y;
        }
      }
    }
    return bestLen >= minRunPx ? new int[] {bestX, bestY} : new int[] {fallbackX, fallbackY};
  }

  private static int median4(int a, int b, int c, int d) {
    int[] v = new int[] {a, b, c, d};
    java.util.Arrays.sort(v);
    return (v[1] + v[2]) / 2;
  }

  private static int resolveFallbackBubbleX(
      AccessibilityNodeInfoCompat node, Rect bounds) {
    if (!ZaloAccessibilityHelper.isZaloPackage(node.getPackageName())) {
      return bounds.centerX();
    }
    StringBuilder sb = new StringBuilder();
    if (node.getText() != null) {
      sb.append(node.getText()).append(' ');
    }
    if (node.getContentDescription() != null) {
      sb.append(node.getContentDescription());
    }
    String lower = sb.toString().toLowerCase(Locale.ROOT);
    if (lower.contains("đã gửi")
        || lower.contains("đã nhận")
        || lower.contains("đã xem")
        || lower.contains("đang gửi")
        || lower.contains("cuộc gọi thoại đi")
        || lower.contains("cuộc gọi video đi")
        || lower.contains("bạn đã hủy")) {
      return bounds.left + (int) (bounds.width() * 0.82f);
    }
    if (lower.contains("cuộc gọi thoại đến")
        || lower.contains("cuộc gọi video đến")
        || lower.contains("bạn đã từ chối")) {
      return bounds.left + (int) (bounds.width() * 0.28f);
    }
    return bounds.centerX();
  }
  /**
   * With Focus text inside items separately on, returns the nearest ancestor of focused text that
   * supports {@code action} when the text itself isn't actionable, so that activating the text
   * activates its item. Returns null otherwise.
   */
  private static @Nullable AccessibilityNodeInfoCompat itemOfTextInside(
      AccessibilityNodeInfoCompat node, int action) {
    if (!AccessibilityNodeInfoUtils.focusesTextInsideItems()
        || AccessibilityNodeInfoUtils.isActionableForAccessibility(node)
        || PerformActionUtils.isNodeSupportAction(node, action)) {
      return null;
    }
    return AccessibilityNodeInfoUtils.getMatchingAncestor(
        node,
        new Filter<AccessibilityNodeInfoCompat>() {
          @Override
          public boolean accept(AccessibilityNodeInfoCompat ancestor) {
            return AccessibilityNodeInfoUtils.isVisible(ancestor)
                && PerformActionUtils.isNodeSupportAction(ancestor, action);
          }
        });
  }

  public boolean clickCurrentHierarchical(@Nullable EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    if (currentFocus == null) {
      return false;
    }
    AccessibilityNodeInfoCompat grabBookingButton = GrabBookingClickTarget.resolve(currentFocus);
    if (grabBookingButton != null) {
      return pipeline.returnFeedback(
          eventId, Feedback.nodeAction(grabBookingButton, ACTION_CLICK.getId()));
    }
    AccessibilityNodeInfoCompat nodeToClick =
        AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(
            currentFocus, AccessibilityNodeInfoUtils.FILTER_CLICKABLE);
    return pipeline.returnFeedback(eventId, Feedback.nodeAction(nodeToClick, ACTION_CLICK.getId()));
  }

  public void clearAccessibilityFocus(EventId eventId) {
    focusManagerInternal.clearAccessibilityFocus(eventId);
  }

  /** Allows menus to prevent announcing content-focus event after edit-menu dismissed. */
  public void setMuteNextFocus() {
    focusManagerInternal.setMuteNextFocus();
  }

  public void renewEnsureFocus() {
    focusManagerInternal.renewEnsureFocus();
  }

  /** Passes through to FocusManagerInternal.setAccessibilityFocus() */
  public boolean setAccessibilityFocus(
      @NonNull AccessibilityNodeInfoCompat node,
      boolean forceRefocusIfAlreadyFocused,
      @NonNull FocusActionInfo focusActionInfo,
      EventId eventId) {
    return focusManagerInternal.setAccessibilityFocus(
        node, forceRefocusIfAlreadyFocused, focusActionInfo, eventId);
  }

  public WebActor getWebActor() {
    return webActor;
  }

  /** Passes through to FocusManagerInternal.updateFocusHistory() */
  private void updateFocusHistory(
      @NonNull AccessibilityNodeInfoCompat pivot, @NonNull FocusActionInfo focusActionInfo,
      @Nullable EventId eventId) {
    focusManagerInternal.updateFocusHistory(pivot, focusActionInfo, eventId);
  }

  /**
   * Caches the current focused node especially for context menu and dialogs, which is used to
   * restore focus when context menu or dialog closes. It can work by calling {@code
   * overrideNextFocusRestorationForWindowTransition} before next window transition to invoke {@code
   * restoreFocus} when screen state changes to restore the cached focus.
   *
   * <p>If the cached focused node is null, the current focused node will be the target node for
   * restore focus.
   *
   * <p>This is a workaround to restore focus when returning from special windows or web containers,
   * other cases will fallback to standard flow to assign focus. And it is used for below cases:
   * <li>non-active window: REFERTO, restore focus on non-active window after popup
   *     window close.
   * <li>system window: REFERTO, restore focus on system window after popup window
   *     close.
   * <li>WebView: REFERTO, restore focus on a view in a web container after popup
   *     window close.
   *
   * @return true if cached node successfully, otherwise false
   */
  public boolean cacheNodeToRestoreFocus(@Nullable AccessibilityNodeInfoCompat targetNode) {
    if (targetNode != null) {
      history.cacheNodeToRestoreFocus(targetNode);
      return true;
    } else {
      targetNode =
          accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    }
    if (targetNode == null) {
      return false;
    }

    if (WebInterfaceUtils.isWebContainer(targetNode)) {
      history.cacheNodeToRestoreFocus(targetNode);
      return true;
    }

    AccessibilityWindowInfoCompat windowInfoCompat =
        AccessibilityNodeInfoUtils.getWindow(targetNode);

    if (windowInfoCompat != null
        && (!windowInfoCompat.isActive()
            || windowInfoCompat.getType() == AccessibilityWindowInfo.TYPE_SYSTEM)) {
      history.cacheNodeToRestoreFocus(targetNode);
      return true;
    }

    return false;
  }

  /**
   * Clears node to restore focus after context menu/dialog closes, and returns whether node
   * existed.
   */
  public boolean popCachedNodeToRestoreFocus() {
    return (history.popCachedNodeToRestoreFocus() != null);
  }

  /** Restore focus with the node cached before context menu or dialog appeared. */
  public boolean restoreFocus(@Nullable EventId eventId) {
    AccessibilityNodeInfoCompat nodeToRestoreFocus = history.popCachedNodeToRestoreFocus();
    if (nodeToRestoreFocus == null) {
      return false;
    }

    if (!nodeToRestoreFocus.refresh() || !nodeToRestoreFocus.isVisibleToUser()) {
      return false;
    }
    // Checks if the node is in the window for pane changes.
    // b/365017291: Only check native nodes because sometimes the WebView is gone in the node tree.
    if (!WebInterfaceUtils.isWebContainer(nodeToRestoreFocus)
        && !AccessibilityNodeInfoUtils.isInWindow(
            nodeToRestoreFocus, AccessibilityNodeInfoUtils.getWindow(nodeToRestoreFocus))) {
      LogUtils.e(TAG, "Do not restore focus from the invalid node " + nodeToRestoreFocus);
      return false;
    }
    return focusManagerInternal.setAccessibilityFocus(
        nodeToRestoreFocus,
        /* forceRefocusIfAlreadyFocused= */ false,
        FocusActionInfo.builder()
            .setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE)
            .setInitialFocusType(FocusActionInfo.RESTORED_LAST_FOCUS)
            .build(),
        eventId);
  }

  /** Restores focus precisely at the next {@link ScreenState} change. */
  public void overrideNextFocusRestorationForWindowTransition() {
    actorState.setOverrideFocusRestore();
  }

  /**
   * Checks the accessibility focused node on the current screen, and requests an initial focus if
   * no focused node is found.
   */
  public boolean ensureAccessibilityFocusOnScreen(EventId eventId) {
    return focusManagerInternal.ensureAccessibilityFocusOnScreen(eventId);
  }

  /**
   * Attempts to announce the URL of the focused content or notify if content contains any URLs. If
   * no URL is found, returns false.
   */
  public boolean readFocusedContentLinkUrl(EventId eventId) {
    String warningMessage = service.getString(R.string.read_link_url_no_url_found);
    AccessibilityNodeInfoCompat focusedNode =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    if (focusedNode == null) {
      pipeline.returnFeedback(eventId, Feedback.speech(warningMessage));
      return false;
    }
    if (!readUrlFromExtraBundle(focusedNode, eventId)
        && !readUrlFromLinkSpan(focusedNode, eventId)) {
      pipeline.returnFeedback(eventId, Feedback.speech(warningMessage));
      return false;
    }
    return true;
  }

  /**
   * Reads the URL from the node's extras bundle. Returns false if extras bundle does not contain
   * key or value is empty string.
   */
  private boolean readUrlFromExtraBundle(
      @NonNull AccessibilityNodeInfoCompat focusedNode, EventId eventId) {
    // Target URL string comes from AccessibilityNodeInfoBuilder#EXTRAS_KEY_TARGET_URL defined at
    // go/a11y_node_info_builder_extra_target_url_key.
    final String urlKey = "AccessibilityNodeInfo.targetUrl";
    Object urlValue = focusedNode.getExtras().get(urlKey);
    if (urlValue != null && urlValue.toString().length() > 0) {
      return pipeline.returnFeedback(
          eventId, Feedback.speech(service.getString(R.string.read_link_url_url_found, urlValue)));
    }
    return false;
  }

  /**
   * Reads the URL from the node's LinkSpan or notifies if node contains more than one LinkSpan.
   * Returns false if node does not contain LinkSpans or focused index is out-of-range.
   */
  private boolean readUrlFromLinkSpan(
      @NonNull AccessibilityNodeInfoCompat focusedNode, EventId eventId) {
    final List<LinkSpan> linkSpans = LinkUtils.getLinkSpansInNodeGroup(focusedNode);
    if (linkSpans.isEmpty()) {
      return false;
    }
    int index = getFocusedLinkIndex(focusedNode);
    // Node contains a single link then read available link.
    if (linkSpans.size() == 1) {
      index = 0;
    }
    // Index is within valid range then read URL.
    if (0 <= index && index < linkSpans.size()) {
      // Nodes that provide the URL in the span matching the focused link index.
      LinkSpan linkSpan = linkSpans.get(index);
      if (linkSpan.span instanceof URLSpan span) {
        return pipeline.returnFeedback(
            eventId,
            Feedback.speech(service.getString(R.string.read_link_url_url_found, span.getURL())));
      } else {
        return pipeline.returnFeedback(
            eventId,
            Feedback.speech(service.getString(R.string.read_link_url_url_found, linkSpan.label)));
      }
    }
    // Node contains multiple links but no focused link.
    return pipeline.returnFeedback(
        eventId, Feedback.speech(service.getString(R.string.read_link_url_multiple_links_found)));
  }

  /** Simulates a click on the center of a view. */
  private boolean simulateClickOnNode(
      AccessibilityService accessibilityService, AccessibilityNodeInfoCompat node, EventId eventId) {
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    return accessibilityService.dispatchGesture(
        buildFallbackClickGesture(rect, eventId), /* callback= */ null, /* handler= */ null);
  }

  @VisibleForTesting
  static GestureDescription buildFallbackClickGesture(Rect rect, @Nullable EventId eventId) {
    Path path = new Path();
    path.moveTo(rect.centerX(), rect.centerY());
    int durationMs = ViewConfiguration.getTapTimeout();
    if (LatencyExperiments.Feature.FAST_DOUBLE_TAP.enabled() && fromDoubleTap(eventId)) {
      durationMs = Math.min(durationMs, FAST_TAP_STROKE_MS);
    }
    return new GestureDescription.Builder()
        .addStroke(new StrokeDescription(path, 0, durationMs))
        .addStroke(new StrokeDescription(path, durationMs + STROKE_TIME_GAP_MS, durationMs))
        .build();
  }

  private static boolean fromDoubleTap(@Nullable EventId eventId) {
    return eventId != null && eventId.getEventType() == Performance.EVENT_TYPE_GESTURE
        && eventId.getEventSubtype() == AccessibilityService.GESTURE_DOUBLE_TAP;
  }

  /** Simulates a double-click on the center of a view. */
  private boolean simulateDoubleClickOnNode(
      AccessibilityService accessibilityService, AccessibilityNodeInfoCompat node) {
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    Path path = new Path();
    path.moveTo(rect.centerX(), rect.centerY());
    int durationMs = ViewConfiguration.getTapTimeout();
    GestureDescription gestureDescription =
        new GestureDescription.Builder()
            .addStroke(new StrokeDescription(path, /* startTime= */ 0, durationMs))
            .addStroke(new StrokeDescription(path, durationMs + STROKE_TIME_GAP_MS, durationMs))
            .addStroke(
                new StrokeDescription(path, 2L * (durationMs + STROKE_TIME_GAP_MS), durationMs))
            .addStroke(
                new StrokeDescription(path, 3L * (durationMs + STROKE_TIME_GAP_MS), durationMs))
            .build();
    return accessibilityService.dispatchGesture(
        gestureDescription, /* callback= */ null, /* handler= */ null);
  }

  /**
   * Clicks the native links in the node by priorities:
   * <li>If a link inside the node is focused, click the current focused link.
   * <li>If the node is clickable, return false to continue to click the node.
   * <li>If the node has single link, click the single link.
   * <li>If the node has multiple links, show the context menu.
   *
   * @return true if any link is clicked, otherwise false.
   */
  private boolean tryClickLinks(AccessibilityNodeInfoCompat node, EventId eventId) {
    int focusedLinkIndex = getFocusedLinkIndex(node);

    if (focusedLinkIndex == FocusActionInfo.LINK_INDEX_NOT_SET
        && AccessibilityNodeInfoUtils.isClickable(node)) {
      return false;
    }

    final List<LinkSpan> linkSpans = LinkUtils.getLinkSpansInNodeGroup(node);
    if (linkSpans.isEmpty()) {
      return false;
    }

    int index = -1;
    if (linkSpans.size() == 1) {
      index = 0;
    } else if (focusedLinkIndex >= 0 && focusedLinkIndex < linkSpans.size()) {
      index = focusedLinkIndex;
    }

    LogUtils.d(TAG, "tryClickLinks with index=%s", index);
    if (index >= 0) {
      LinkSpan linkSpan = linkSpans.get(index);
      return LinkUtils.activateLinkSpan(service, linkSpan);
    } else {
      return menuManager.showMenu(CUSTOM_ACTION, eventId);
    }
  }

  private int getFocusedLinkIndex(AccessibilityNodeInfoCompat node) {
    FocusActionRecord focusActionRecord = history.getLastFocusActionRecord();
    if (focusActionRecord == null) {
      return FocusActionInfo.LINK_INDEX_NOT_SET;
    }
    int curLinkIndex = focusActionRecord.getExtraInfo().getFocusedLinkIndex();
    if (node.equals(focusActionRecord.getFocusedNode()) && (curLinkIndex >= 0)) {
      return curLinkIndex;
    }
    return FocusActionInfo.LINK_INDEX_NOT_SET;
  }

  @Override
  public void newItemFocused(
      AccessibilityNodeInfo nodeInfo, Interpretation.@NonNull AccessibilityFocused axFocused) {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoUtils.toCompat(nodeInfo);
    if (node == null || axFocused.focusActionInfo() == null) {
      return;
    }
    if (FocusActorHelper.shouldSyncInputFocusToAccessibilityFocus(
        service, node, axFocused.focusActionInfo())) {
      focusManagerInternal.setFocus(EVENT_ID_UNTRACKED, node);
    }
    if (axFocused.focusActionInfo().sourceAction != FocusActionInfo.LOGICAL_NAVIGATION
        && axFocused.focusActionInfo().sourceAction != FocusActionInfo.TOUCH_EXPLORATION) {
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED, Feedback.speech(Feedback.Speech.Action.RESET_FORMATTING_HISTORY));
    }
  }
}
