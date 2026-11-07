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

package com.google.android.accessibility.talkback.interpreters;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
import static com.google.android.accessibility.talkback.Interpretation.Touch.Action.LIFT;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.ActorState;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Interpretation.ManualScroll;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.actor.ImageCaptioner;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.compositor.AccessibilityFocusEventInterpretation;
import com.google.android.accessibility.talkback.compositor.AccessibilityFocusEventInterpreter;
import com.google.android.accessibility.talkback.compositor.Compositor;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.ColumnWrap;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForScreenStateChange;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.TypingMethod;
import com.google.android.accessibility.talkback.focusmanagement.action.TouchExplorationAction;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor.ScreenStateChangeListener;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.TouchExplorationInterpreter.TouchExplorationActionListener;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionRecord;
import com.google.android.accessibility.talkback.focusmanagement.record.NodePathDescription;
import com.google.android.accessibility.talkback.interpreters.InputFocusInterpreter.TargetViewChangeListener;
import com.google.android.accessibility.talkback.interpreters.ManualScrollInterpreter.ManualScrollInterpretation;
import com.google.android.accessibility.talkback.interpreters.ManualScrollInterpreter.ScrolledViewChangeListener;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.BuildVersionUtils;
import com.google.android.accessibility.utils.DisplayUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.labeling.Label;
import com.google.android.accessibility.utils.monitor.TouchMonitor;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SearchDirection;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Accessibility focus event interpreter that consumes semi-interpreted events from other
 * event-interpreters, then sends completed interpretations to pipeline.
 */
public class AccessibilityFocusInterpreter
    implements AccessibilityFocusEventInterpreter,
        ScreenStateChangeListener,
        ScrolledViewChangeListener,
        TouchExplorationActionListener,
        TargetViewChangeListener {
  public static final String TAG = "A11yFocusInterp";

  // Scroll events arrive many times per second, and finding a new focus target queries the app's
  // node tree over IPC on the main thread. Waiting for scrolling to settle keeps touch responsive.
  // 100 ms matches the framework's own scroll-event grouping interval, so a shorter wait would
  // mostly fire between the grouped events of a scroll that is still moving.
  private static final long MANUAL_SCROLL_SETTLE_MS = 100;

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Member variables

  private final FocusProcessorForTapAndTouchExploration focusProcessorForTapAndTouchExploration;
  private final FocusProcessorForScreenStateChange focusProcessorForScreenStateChange;
  private final AccessibilityFocusMonitor accessibilityFocusMonitor;
  private final ScreenStateMonitor.State screenState;
  private final TouchMonitor touchMonitor;
  private final Context context;

  private Pipeline.InterpretationReceiver pipelineInterpretations;
  private ActorState actorState;

  private final Handler handler = new Handler(Looper.getMainLooper());

  private long webScrollTouchStart = -1;

  private @Nullable AccessibilityNodeInfoCompat webScrolled;

  private boolean webScrolledSideways;

  private @Nullable ManualScrollInterpretation webScroll;

  private boolean pageScrolledFocusStrip;

  private @Nullable AccessibilityNodeInfoCompat webStrip;

  private @Nullable ManualScrollInterpretation pendingManualScroll;
  private final Runnable handlePendingManualScroll =
      new Runnable() {
        @Override
        public void run() {
          ManualScrollInterpretation interpretation = pendingManualScroll;
          if (interpretation != null
              && interpretation == webScroll
              && webScrolledSideways
              && touchMonitor.isUserTouchingScreen()) {
            handler.postDelayed(this, MANUAL_SCROLL_SETTLE_MS);
            return;
          }
          boolean stripScrolled = pageScrolledFocusStrip;
          pendingManualScroll = null;
          pageScrolledFocusStrip = false;
          if (interpretation != null) {
            handleManualScroll(interpretation, stripScrolled);
          }
        }
      };

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Construction methods

  public AccessibilityFocusInterpreter(
      Context context,
      AccessibilityFocusMonitor accessibilityFocusMonitor,
      ScreenStateMonitor.State screenState,
      TouchMonitor touchMonitor,
      TalkBackAnalytics analytics) {
    this.context = context;
    this.accessibilityFocusMonitor = accessibilityFocusMonitor;
    this.screenState = screenState;
    this.touchMonitor = touchMonitor;
    focusProcessorForTapAndTouchExploration =
        new FocusProcessorForTapAndTouchExploration(context, analytics);
    focusProcessorForScreenStateChange =
        new FocusProcessorForScreenStateChange(accessibilityFocusMonitor);
  }

  public void performSplitTap(EventId eventId) {
    if (!focusProcessorForTapAndTouchExploration.performSplitTap(eventId)) {
      // Check whether the FocusProcessorForTapAndTouchExploration#performSplitTap succeeds.
      // TODO: Split-tap should be activated everywhere, not just IME.
      // If FocusProcessorForTapAndTouchExploration#performSplitTap returns false (
      // support the Text-Entry-Key for lift-to-type, we should enable this.
      if (FeatureSupport.supportSplitTapEverywhere()
          && FeatureFlagReader.splitTapEverywhere(context)) {
        performClick(eventId);
      }
    }
  }

  private void performClick(EventId eventId) {
    AccessibilityNodeInfoCompat currentA11yFocusedNode =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    if (currentA11yFocusedNode != null) {
      pipelineInterpretations.input(
          eventId, /* event= */ null, Interpretation.Touch.create(LIFT, currentA11yFocusedNode));
    }
  }

  public void setPipeline(Pipeline.InterpretationReceiver pipeline) {
    this.pipelineInterpretations = pipeline;
    this.focusProcessorForTapAndTouchExploration.setInterpretationReceiver(pipeline);
    focusProcessorForScreenStateChange.setPipeline(pipeline);
  }

  public void setActorState(ActorState actorState) {
    this.actorState = actorState;
    focusProcessorForTapAndTouchExploration.setActorState(actorState);
    focusProcessorForScreenStateChange.setActorState(actorState);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Dispatches user actions

  /** Called by TouchExplorationInterpreter. */
  @Override
  public boolean onTouchExplorationAction(TouchExplorationAction action, EventId eventId) {
    LogUtils.d(TAG, "User action: %s", action);
    return focusProcessorForTapAndTouchExploration.onTouchExplorationAction(action, eventId);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Notifies changes from framework

  /** Called by {@link ScreenStateMonitor}. */
  @Override
  public boolean onScreenStateChanged(ScreenState screenState, EventId eventId) {
    return focusProcessorForScreenStateChange.onScreenStateChanged(screenState, eventId);
  }

  /** Event-interpreter function, called by {@link ManualScrollInterpreter}. */
  @Override
  public void onManualScroll(ManualScrollInterpretation interpretation) {
    if (FormFactorUtils.isAndroidWear()) {
      handleManualScroll(interpretation, /* stripScrolled= */ false);
      return;
    }
    if (onWebScroll(interpretation)) {
      return;
    }
    if (pendingManualScroll == null
        || interpretation.scrolledHoldsFocus()
        || !pendingManualScroll.scrolledHoldsFocus()) {
      pendingManualScroll =
          ManualScrollInterpretation.create(
              interpretation.eventId(),
              AccessibilityEvent.obtain(interpretation.event()),
              interpretation.direction(),
              interpretation.scrolledHoldsFocus());
    }
    schedulePendingManualScroll();
  }

  private void schedulePendingManualScroll() {
    cancelWebFocusRecovery();
    handler.removeCallbacks(handlePendingManualScroll);
    handler.postDelayed(handlePendingManualScroll, MANUAL_SCROLL_SETTLE_MS);
  }

  private boolean onWebScroll(ManualScrollInterpretation interpretation) {
    AccessibilityNodeInfoCompat scrolled =
        AccessibilityEventUtils.sourceCompat(interpretation.event());
    if (scrolled == null || !WebScrollFocus.isWebContent(scrolled)) {
      return false;
    }
    long touchStart = touchMonitor.getTouchStartTime();
    boolean sideways = WebScrollFocus.scrollsOnlySideways(scrolled);
    if (touchStart != webScrollTouchStart
        || webScrolled == null
        || (webScrolledSideways && !sideways && !scrolled.equals(webScrolled))) {
      webScrollTouchStart = touchStart;
      webScrolled = scrolled;
      webScrolledSideways = sideways;
      webScroll = null;
    }
    if (sideways && !webScrolledSideways && !scrolled.equals(webScrolled)) {
      webStrip = scrolled;
    }
    if (scrolled.equals(webScrolled)) {
      webScroll =
          ManualScrollInterpretation.create(
              interpretation.eventId(),
              AccessibilityEvent.obtain(interpretation.event()),
              interpretation.direction(),
              interpretation.scrolledHoldsFocus());
    } else if (webScroll != null && interpretation.scrolledHoldsFocus() && sideways) {
      pageScrolledFocusStrip = true;
    } else {
      return true;
    }
    pendingManualScroll = webScroll;
    schedulePendingManualScroll();
    return true;
  }

  private void handleManualScroll(
      ManualScrollInterpretation interpretation, boolean stripScrolled) {
    if (!screenState.areMainWindowsStable()) {
      LogUtils.w(
          TAG,
          "onScrollEvent return due to windows are not stable and the focus will"
              + " be handled by onScreenStateChanged after main windows are stable.");
      return;
    }

    AccessibilityNodeInfoCompat currentA11yFocusedNode =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    if (!FormFactorUtils.isAndroidWear()
        && startWebFocusRecovery(interpretation, currentA11yFocusedNode, stripScrolled)) {
      return;
    }
    moveFocusFromScrolledOutNode(interpretation, currentA11yFocusedNode);
  }

  private void moveFocusFromScrolledOutNode(
      ManualScrollInterpretation interpretation,
      @Nullable AccessibilityNodeInfoCompat currentA11yFocusedNode) {
    if (!shouldMoveFocus(currentA11yFocusedNode, interpretation.direction())) {
      return;
    }
    if (!FormFactorUtils.isAndroidWear()) {
      AccessibilityNodeInfoCompat scrolled =
          AccessibilityEventUtils.sourceCompat(interpretation.event());
      if (scrolled != null && !BuildVersionUtils.isAtLeastOMR1()) {
        scrolled.refresh();
      }
      NodePathDescription focusPath =
          actorState.getFocusHistory().getLastFocusAncestorIdentityPath();
      if (focusPath == null || !focusPath.containsNodeByHashAndIdentity(scrolled)) {
        return;
      }
    }

    ManualScroll.Builder builder =
        ManualScroll.builder()
            .setDirection(interpretation.direction())
            .setScreenState(screenState.getStableScreenState());
    if (FormFactorUtils.isAndroidWear()) {
      builder.setCurrentFocusedNode(currentA11yFocusedNode);
    }

    pipelineInterpretations.input(interpretation.eventId(), interpretation.event(), builder.build());
  }

  private static final long WEB_FOCUS_RECOVERY_SLICE_MS = 16;

  private static final long WEB_FOCUS_RECOVERY_TIME_LIMIT_MS = 500;

  private @Nullable WebFocusRecovery webFocusRecovery;

  private @Nullable ManualScrollInterpretation webFocusRecoveryScroll;

  private long webFocusRecoveryStartTime;

  private long webFocusRecoveryFocusTime;

  private final Runnable continueWebFocusRecovery =
      () -> runWebFocusRecovery(/* yieldToInput= */ true);

  private boolean startWebFocusRecovery(
      ManualScrollInterpretation interpretation,
      @Nullable AccessibilityNodeInfoCompat focus,
      boolean stripScrolled) {
    boolean forward = interpretation.direction() != TraversalStrategy.SEARCH_FOCUS_BACKWARD;
    boolean focusLost = focus == null || Role.getRole(focus) == Role.ROLE_WEB_VIEW;
    if (focusLost) {
      if (!isUserScroll(interpretation)) {
        return false;
      }
      AccessibilityNodeInfoCompat scrolled =
          AccessibilityEventUtils.sourceCompat(interpretation.event());
      if (scrolled == null || !WebScrollFocus.isWebContent(scrolled)) {
        return false;
      }
      FocusActionRecord record = actorState.getFocusHistory().getLastFocusActionRecord();
      AccessibilityNodeInfoCompat lastFocused =
          (record != null && WebInterfaceUtils.supportsWebActions(record.getFocusedNode()))
              ? record.getFocusedNode()
              : null;
      startWebFocusRecovery(
          interpretation,
          new WebFocusRecovery(
              null,
              lastFocused,
              scrolled,
              /* leftBehind= */ true,
              forward,
              stripIn(scrolled),
              /* focusMoved= */ true));
      return true;
    }
    if (!WebInterfaceUtils.supportsWebActions(focus) || !isUserScroll(interpretation)) {
      return false;
    }
    AccessibilityNodeInfoCompat scrolled =
        AccessibilityEventUtils.sourceCompat(interpretation.event());
    if (scrolled == null) {
      return false;
    }
    Rect focusBounds = new Rect();
    focus.getBoundsInScreen(focusBounds);
    Rect scrolledBounds = new Rect();
    scrolled.getBoundsInScreen(scrolledBounds);
    boolean leftBehind = stripScrolled || !focus.isVisibleToUser();
    AccessibilityNodeInfoCompat strip = stripIn(scrolled);
    boolean focusMoved = focusMovedSinceFocused(focus, focusBounds);
    boolean underStrip = false;
    if (strip != null && focusMoved) {
      Rect stripBounds = new Rect();
      strip.getBoundsInScreen(stripBounds);
      underStrip = WebScrollFocus.isUnderFixedStrip(focusBounds, scrolledBounds, stripBounds);
    }
    if (!leftBehind && !underStrip && !WebScrollFocus.isOutside(focusBounds, scrolledBounds)) {
      return true;
    }
    startWebFocusRecovery(
        interpretation,
        new WebFocusRecovery(focus, null, scrolled, leftBehind, forward, strip, focusMoved));
    return true;
  }

  private @Nullable AccessibilityNodeInfoCompat stripIn(AccessibilityNodeInfoCompat scrolled) {
    return (webStrip != null && webStrip.getWindowId() == scrolled.getWindowId())
        ? webStrip
        : null;
  }

  private boolean focusMovedSinceFocused(AccessibilityNodeInfoCompat focus, Rect focusBounds) {
    FocusActionRecord record = actorState.getFocusHistory().getLastFocusActionRecord();
    if (record == null || !record.getFocusedNode().equals(focus)) {
      return false;
    }
    Rect then = new Rect();
    record.getFocusedNode().getBoundsInScreen(then);
    return !then.equals(focusBounds);
  }

  private void startWebFocusRecovery(
      ManualScrollInterpretation interpretation, WebFocusRecovery recovery) {
    cancelWebFocusRecovery();
    webFocusRecovery = recovery;
    webFocusRecoveryScroll = interpretation;
    webFocusRecoveryStartTime = SystemClock.uptimeMillis();
    webFocusRecoveryFocusTime = lastFocusActionTime();
    runWebFocusRecovery(/* yieldToInput= */ true);
  }

  private void runWebFocusRecovery(boolean yieldToInput) {
    WebFocusRecovery recovery = webFocusRecovery;
    ManualScrollInterpretation interpretation = webFocusRecoveryScroll;
    if (recovery == null || interpretation == null) {
      return;
    }
    handler.removeCallbacks(continueWebFocusRecovery);
    if (lastFocusActionTime() != webFocusRecoveryFocusTime) {
      cancelWebFocusRecovery();
      return;
    }
    long sliceEnd = SystemClock.uptimeMillis() + WEB_FOCUS_RECOVERY_SLICE_MS;
    while (!recovery.step()) {
      long now = SystemClock.uptimeMillis();
      if (now - webFocusRecoveryStartTime >= WEB_FOCUS_RECOVERY_TIME_LIMIT_MS) {
        LogUtils.w(TAG, "Stopped the search for a web item in view at its time limit");
        recovery.stop();
        break;
      }
      if (yieldToInput && now >= sliceEnd) {
        handler.post(continueWebFocusRecovery);
        return;
      }
    }
    webFocusRecovery = null;
    webFocusRecoveryScroll = null;
    if (!screenState.areMainWindowsStable()
        || lastFocusActionTime() != webFocusRecoveryFocusTime) {
      return;
    }
    switch (recovery.outcome()) {
      case WebFocusRecovery.FOUND ->
          pipelineInterpretations.input(
              interpretation.eventId(),
              interpretation.event(),
              ManualScroll.builder()
                  .setDirection(interpretation.direction())
                  .setScreenState(screenState.getStableScreenState())
                  .setWebItemInView(recovery.item())
                  .build());
      case WebFocusRecovery.NOTHING_IN_VIEW -> {
        LogUtils.d(TAG, "Web focus is out of view, and no item is in view");
        AccessibilityNodeInfoCompat focus =
            accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
        if (focus != null) {
          moveFocusFromScrolledOutNode(interpretation, focus);
        }
      }
      case WebFocusRecovery.NOT_WEB_SCROLL ->
          moveFocusFromScrolledOutNode(
              interpretation,
              accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false));
      default -> {}
    }
  }

  private void cancelWebFocusRecovery() {
    handler.removeCallbacks(continueWebFocusRecovery);
    webFocusRecovery = null;
    webFocusRecoveryScroll = null;
  }

  public void finishWebScrollFocus() {
    if (FormFactorUtils.isAndroidWear()) {
      return;
    }
    if (pendingManualScroll != null && pendingManualScroll == webScroll) {
      handler.removeCallbacks(handlePendingManualScroll);
      handlePendingManualScroll.run();
    }
    if (webFocusRecovery != null) {
      runWebFocusRecovery(/* yieldToInput= */ false);
    }
  }

  private long lastFocusActionTime() {
    FocusActionRecord record = actorState.getFocusHistory().getLastFocusActionRecord();
    return (record == null) ? -1 : record.getActionTime();
  }

  private boolean isUserScroll(ManualScrollInterpretation interpretation) {
    return WebScrollFocus.isUserScroll(
        interpretation.event().getEventTime(),
        touchMonitor.getTouchStartTime(),
        touchMonitor.getTouchEndTime(),
        touchMonitor.isUserTouchingScreen(),
        touchMonitor.getPreviousTouchStartTime(),
        touchMonitor.getPreviousTouchEndTime(),
        lastFocusActionTime());
  }

  public void onSuspendInfrastructure() {
    handler.removeCallbacks(handlePendingManualScroll);
    cancelWebFocusRecovery();
    pendingManualScroll = null;
    webScroll = null;
    webScrolled = null;
    webStrip = null;
    pageScrolledFocusStrip = false;
  }

  private boolean shouldMoveFocus(
      AccessibilityNodeInfoCompat currentA11yFocusedNode, @SearchDirection int direction) {
    if (FormFactorUtils.isAndroidWear()) {
      // In a Watch device, especially for a rounded screen, we have to move focus onto the next
      // node before current focused node being invisible to mitigate the fighting between TB and
      // a scrolling list view. To do so, we need to focus onto another node when the current
      // focused node is close to borders and there is enough space for the new focused node.
      return shouldFocusNextNodeForWatch(context, currentA11yFocusedNode, direction);
    } else {
      return !AccessibilityNodeInfoUtils.shouldFocusNode(currentA11yFocusedNode);
    }
  }

  private static final float PARTIAL_INVISIBLE_TOP_RATIO = 0.15f;
  private static final float PARTIAL_INVISIBLE_MID_RATIO = 0.5f;
  private static final float PARTIAL_INVISIBLE_BOTTOM_RATIO = 0.85f;

  private boolean shouldFocusNextNodeForWatch(
      Context context, AccessibilityNodeInfoCompat node, @SearchDirection int direction) {
    if (!FormFactorUtils.isAndroidWear()) {
      return false;
    }

    if (node == null) {
      return true;
    }

    Rect nodeRect = new Rect();
    node.getBoundsInScreen(nodeRect);

    Point screenPxSize = DisplayUtils.getScreenPixelSizeWithoutWindowDecor(context);
    // When we scroll up the list, the screen will go down and the top content will be disappeared,
    // and vice versa for scrolling down. Therefore, when we scroll up the list by gesture, we need
    // to search focus forward.
    return closeToBorder(direction, nodeRect, screenPxSize)
        && hasEnoughSpaceForNextNode(direction, nodeRect, screenPxSize);
  }

  private boolean closeToBorder(@SearchDirection int direction, Rect nodeRect, Point screenPxSize) {
    if (direction == TraversalStrategy.SEARCH_FOCUS_FORWARD) {
      return (float) nodeRect.top < (float) screenPxSize.y * PARTIAL_INVISIBLE_TOP_RATIO;
    } else if (direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) {
      return (float) nodeRect.bottom > (float) screenPxSize.y * PARTIAL_INVISIBLE_BOTTOM_RATIO;
    } else {
      return false;
    }
  }

  private boolean hasEnoughSpaceForNextNode(
      @SearchDirection int direction, Rect nodeRect, Point screenPxSize) {
    if (direction == TraversalStrategy.SEARCH_FOCUS_FORWARD) {
      return (float) nodeRect.bottom < (float) screenPxSize.y * PARTIAL_INVISIBLE_MID_RATIO;
    } else if (direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) {
      return (float) nodeRect.top > (float) screenPxSize.y * PARTIAL_INVISIBLE_MID_RATIO;
    } else {
      return false;
    }
  }

  /** Event-interpreter function, called by {@link InputFocusInterpreter}. */
  @Override
  public void onViewTargeted(
      @Nullable EventId eventId,
      AccessibilityEvent event,
      AccessibilityNodeInfoCompat targetedNode) {
    if (!screenState.areMainWindowsStable()) {
      LogUtils.w(
          TAG,
          "onViewTargeted return due to windows are not stable and the focus "
              + "will be handled by onScreenStateChanged after main windows are stable.");
      return;
    }
    pipelineInterpretations.input(eventId, event, new Interpretation.InputFocus(targetedNode));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // APIs used by other TalkBack components

  /** Called by EventFilter. */
  @Override
  public @Nullable AccessibilityFocusEventInterpretation interpret(AccessibilityEvent event) {
    FocusActionInfo info = actorState.getFocusHistory().getFocusActionInfoFromEvent(event);
    if (context instanceof com.google.android.accessibility.talkback.TalkBackService service
        && service.shouldKeepRecordingFocus(
            event.getWindowId(), info == null ? FocusActionInfo.UNKNOWN : info.sourceAction)) {
      AccessibilityFocusEventInterpretation muted =
          new AccessibilityFocusEventInterpretation(Compositor.toCompositorEvent(event));
      muted.setShouldMuteFeedback(true);
      return muted;
    }
    boolean forceMuteFeedback = info != null && info.forceMuteFeedback;

    // For user interface interaction (such as quick menu to handle slider/number-picker) and image
    // caption.
    if (event.getEventType() == TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      AccessibilityNodeInfoCompat node = AccessibilityNodeInfoUtils.toCompat(event.getSource());
      // Skips caption if the view has already been labeled.
      boolean needsCaption =
          !forceMuteFeedback
              && ImageCaptioner.supportsImageCaption(context)
              && ImageCaptioner.needAutomaticCaptioning(
                  context, node, actorState.getGeminiState().hasAiCore())
              && actorState.getLabelManagerState().getLabelIdForNode(node) == Label.NO_ID;
      pipelineInterpretations.input(
          Performance.getInstance().onEventReceivedIfAbsent(event),
          event,
          Interpretation.AccessibilityFocused.create(info, needsCaption),
          node);
    }

    // Focus that a web page moved, when asked to go to the next or previous item, is compared
    // with where it was here.
    int webColumnWrap =
        event.getEventType() == TYPE_VIEW_ACCESSIBILITY_FOCUSED
            ? ColumnWrap.takeWebFocus(event)
            : ColumnWrap.NONE;

    if (info == null) {
      if (webColumnWrap == ColumnWrap.NONE) {
        return null;
      }
      AccessibilityFocusEventInterpretation interpretation =
          new AccessibilityFocusEventInterpretation(Compositor.toCompositorEvent(event));
      interpretation.setColumnWrap(webColumnWrap);
      return interpretation;
    }

    AccessibilityFocusEventInterpretation interpretation =
        new AccessibilityFocusEventInterpretation(Compositor.toCompositorEvent(event));
    interpretation.setColumnWrap(
        info.columnWrap != ColumnWrap.NONE ? info.columnWrap : webColumnWrap);
    interpretation.setForceFeedbackEvenIfAudioPlaybackActive(
        info.forceFeedbackEvenIfAudioPlaybackActive());
    interpretation.setForceFeedbackEvenIfMicrophoneActive(
        info.forceFeedbackEvenIfMicrophoneActive());
    interpretation.setForceFeedbackEvenIfSsbActive(info.forceFeedbackEvenIfSsbActive());
    interpretation.setShouldMuteFeedback(info.forceMuteFeedback);
    interpretation.setIsInitialFocusAfterScreenStateChange(
        info.sourceAction == FocusActionInfo.SCREEN_STATE_CHANGE);
    interpretation.setIsNavigateByUser(
        info.sourceAction == FocusActionInfo.TOUCH_EXPLORATION
            || info.sourceAction == FocusActionInfo.LOGICAL_NAVIGATION);
    return interpretation;
  }

  /**
   * Sets whether single-tap activation is enabled.
   *
   * @param enabled Whether single-tap activation is enabled.
   */
  public void setSingleTapEnabled(boolean enabled) {
    focusProcessorForTapAndTouchExploration.setSingleTapEnabled(enabled);
  }

  /**
   * Gets whether single-tap activation is enabled.
   *
   * @return Whether single-tap activation is enabled.
   */
  public boolean getSingleTapEnabled() {
    return focusProcessorForTapAndTouchExploration.getSingleTapEnabled();
  }

  /**
   * Sets type confirmation method
   *
   * @param type keyboard confirmation type
   */
  public void setTypingMethod(@TypingMethod int type) {
    focusProcessorForTapAndTouchExploration.setTypingMethod(type);
  }

  /**
   * Sets long press duration. It's only applicable when the typing method is not double-tap.
   *
   * @param duration in milliseconds
   */
  public void setTypingLongPressDurationMs(int duration) {
    focusProcessorForTapAndTouchExploration.setTypingLongPressDurationMs(duration);
  }

  /**
   * Gets whether single-tap activation is enabled.
   *
   * @return Whether single-tap activation is enabled.
   */
  @TypingMethod
  public int getTypingMethod() {
    return focusProcessorForTapAndTouchExploration.getTypingMethod();
  }

}
