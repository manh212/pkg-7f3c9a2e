/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.accessibility.talkback.compositor;

import static com.google.android.accessibility.talkback.compositor.CompositorConfigs.shouldEarlyAnnounceForLiftToType;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.compositor.rule.EventTypeViewAccessibilityFocusedFeedbackRule;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.AsyncTraversalOrder;
import com.google.android.accessibility.talkback.focusmanagement.ColumnWrap;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget.TargetType;
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.calls.CallAnnouncements;
import com.google.android.accessibility.talkback.notifications.NotificationReader;
import com.google.android.accessibility.talkback.notifications.Outcome;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Performance.PreparedSpeechWork;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.TelegramChatCell;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.input.TextEventInterpreter;
import com.google.android.accessibility.utils.monitor.TouchMonitor;
import com.google.android.accessibility.utils.monitor.VoiceActionDelegate;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SearchDirection;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Determines whether events should be passed on to the compositor. Also interprets events into more
 * specific event types, and extracts data from events.
 */
public class EventFilter {

  private static final String TAG = "EventFilter";

  ///////////////////////////////////////////////////////////////////////////////////
  // Member variables
  private final Context context;
  private final Compositor compositor;
  private VoiceActionDelegate voiceActionDelegate;

  private AccessibilityFocusEventInterpreter accessibilityFocusEventInterpreter;
  private final GlobalVariables globalVariables;

  private final @NonNull TouchMonitor touchMonitor;
  private AccessibilityNodeInfoCompat lastHoverEnteredNode = null;
  private final EarlyFocusSpeech earlyFocusSpeech;

  /** Works out which node a swipe from the focused node would most likely reach. */
  public interface TargetPredictor {
    @Nullable AccessibilityNodeInfoCompat predictTarget(
        AccessibilityNodeInfoCompat pivot, @SearchDirection int searchDirection);

    /**
     * Works out which node a swipe from the focused node at the reading control now in effect
     * would most likely reach.
     */
    @Nullable AccessibilityNodeInfoCompat predictTarget(
        AccessibilityNodeInfoCompat pivot,
        @SearchDirection int searchDirection,
        @TargetType int targetType);

    /** The target type of the reading control now in effect. */
    @TargetType int currentTargetType();
  }

  private @Nullable TargetPredictor targetPredictor;
  // Whether a finger is on the screen now.
  private BooleanSupplier fingerDown = () -> false;
  // The node that accessibility focus last moved to, as far as this filter knows.
  private @Nullable AccessibilityNodeInfoCompat lastFocusedNode;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable prepareSwipeTargets = this::prepareSwipeTargets;
  private final Runnable prepareNextSwipeTarget = this::prepareNextSwipeTarget;
  private @Nullable PreparationPass preparationPass;
  private boolean waitingForTouchEnd;
  /** A pass step is waiting out the input-quiet delay before it runs (Q11, round 3). */
  private boolean waitingForIdle;
  /** An order-build wait is already outstanding for this preparation round (Q5). */
  private boolean retriedMissingOrder;
  private int preferredDirectionIndex;
  private long preparationGeneration;

  /**
   * How long after focus moves the announcements of the next and previous nodes are prepared: after
   * the app's focus event has been handled, and usually before the next swipe starts.
   */
  private static final long PREPARE_DELAY_MS = 55;
  /** Yield between target preparations so a swipe is not queued behind a whole prefetch pass. */
  private static final long PREPARE_STEP_DELAY_MS = 8;
  private static final long PREPARE_ORDER_RETRY_MS = 48;

  /**
   * Whether a swipe's target that is only partly on screen is spoken before the swipe has scrolled
   * it into view, rather than from the app's focus event after it has, as TalkBack does.
   */
  private static volatile boolean speakItemsBeforeScroll = false;

  private static volatile boolean latencyExtrasEnabled = true;
  private static volatile boolean prepareAdjacentSpeechEnabled;
  private static volatile boolean prepareSecondAheadSpeechEnabled;

  /** The directions of the swipes whose targets are prepared: to the next and previous node. */
  private static final int[] SWIPE_DIRECTIONS = {
    TraversalStrategy.SEARCH_FOCUS_FORWARD, TraversalStrategy.SEARCH_FOCUS_BACKWARD
  };

  /** Incremental, main-thread cursor for a cooperative speech-preparation pass. */
  private static final class PreparationPass {
    final AccessibilityNodeInfoCompat focusedNode;
    final int maximumDistance;
    final int controlTargetType;
    final int preferredDirectionIndex;
    int targetTypeIndex;
    int directionIndex;
    int distance = 1;
    @Nullable AccessibilityNodeInfoCompat firstAdjacent;
    @Nullable AccessibilityNodeInfoCompat secondAdjacent;
    /**
     * Q10 (round 3): predictions that already failed in this pass. Cache failures for the
     * same pivot, direction and target type, without suppressing other reading controls.
     */
    private final Set<FailedPrediction> failedPredictions = new HashSet<>();

    boolean failedBefore(
        AccessibilityNodeInfoCompat pivot,
        @SearchDirection int direction,
        @TargetType int targetType) {
      return failedPredictions.contains(new FailedPrediction(pivot, direction, targetType));
    }

    void noteFailed(
        AccessibilityNodeInfoCompat pivot,
        @SearchDirection int direction,
        @TargetType int targetType) {
      failedPredictions.add(new FailedPrediction(pivot, direction, targetType));
    }

    PreparationPass(
        AccessibilityNodeInfoCompat focusedNode, int maximumDistance, int controlTargetType,
        int preferredDirectionIndex) {
      this.focusedNode = focusedNode;
      this.maximumDistance = maximumDistance;
      this.controlTargetType = controlTargetType;
      this.preferredDirectionIndex = preferredDirectionIndex;
    }

    @TargetType int targetType() {
      return targetTypeIndex == 0 ? controlTargetType : NavigationTarget.TARGET_DEFAULT;
    }

    @SearchDirection int direction() {
      return SWIPE_DIRECTIONS[(preferredDirectionIndex + directionIndex) % SWIPE_DIRECTIONS.length];
    }

    @Nullable AccessibilityNodeInfoCompat pivot() {
      if (distance == 1) {
        return focusedNode;
      }
      return directionIndex == 0 ? firstAdjacent : secondAdjacent;
    }

    boolean advance(@Nullable AccessibilityNodeInfoCompat target) {
      if (distance == 1) {
        if (directionIndex == 0) {
          firstAdjacent = target;
        } else {
          secondAdjacent = target;
        }
      }
      if (++directionIndex < SWIPE_DIRECTIONS.length) {
        return true;
      }
      directionIndex = 0;
      if (++distance <= maximumDistance) {
        return true;
      }
      firstAdjacent = null;
      secondAdjacent = null;
      distance = 1;
      return targetTypeIndex++ == 0 && controlTargetType != NavigationTarget.TARGET_DEFAULT;
    }
  }

  /**
   * A prediction that already failed in a {@link PreparationPass}, keyed by pivot, direction
   * and target type. Equality is the node's own: two wrappers for the same node in the same
   * window are the same pivot, so a re-obtained node still hits the cache.
   */
  private static final class FailedPrediction {
    final AccessibilityNodeInfoCompat pivot;
    final int direction;
    final int targetType;

    FailedPrediction(AccessibilityNodeInfoCompat pivot, int direction, int targetType) {
      this.pivot = pivot;
      this.direction = direction;
      this.targetType = targetType;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof FailedPrediction)) {
        return false;
      }
      FailedPrediction that = (FailedPrediction) other;
      return pivot.equals(that.pivot)
          && direction == that.direction
          && targetType == that.targetType;
    }

    @Override
    public int hashCode() {
      int result = pivot.hashCode();
      result = result * 31 + direction;
      result = result * 31 + targetType;
      return result;
    }
  }

  // /////////////////////////////////////////////////////////////////////////////////
  // Construction

  public EventFilter(
      Context context,
      Compositor compositor,
      @NonNull TouchMonitor touchMonitor,
      GlobalVariables globalVariables,
      EarlyFocusSpeech earlyFocusSpeech) {
    this.context = context;
    this.compositor = compositor;
    this.touchMonitor = touchMonitor;
    this.globalVariables = globalVariables;
    this.earlyFocusSpeech = earlyFocusSpeech;
  }

  ///////////////////////////////////////////////////////////////////////////////////
  // Methods

  public void setVoiceActionDelegate(VoiceActionDelegate delegate) {
    voiceActionDelegate = delegate;
  }

  public void setTargetPredictor(@Nullable TargetPredictor predictor) {
    targetPredictor = predictor;
  }

  /** Sets how to tell whether a finger is on the screen, so that a swipe never waits for this. */
  public void setFingerDownSupplier(BooleanSupplier supplier) {
    fingerDown = supplier;
  }

  public void setAccessibilityFocusEventInterpreter(
      AccessibilityFocusEventInterpreter interpreter) {
    accessibilityFocusEventInterpreter = interpreter;
  }

  public void sendEvent(AccessibilityEvent event, @Nullable EventId eventId) {
    EarlyFocusMatch earlyFocus = earlyFocusSpeech.consume(event);
    if (earlyFocus == EarlyFocusMatch.SPOKEN) {
      // Spoken as soon as the focus was set, so this event only goes to the interpreter, which
      // passes it on for other uses, such as image captions.
      lastHoverEnteredNode = null;
      if (accessibilityFocusEventInterpreter != null) {
        accessibilityFocusEventInterpreter.interpret(event);
      }
      return;
    }
    if (earlyFocus == EarlyFocusMatch.STALE) {
      // Focus moved on, and was spoken, after this event was sent. Speaking it now would cut off
      // the newer focus, and its node's captions and state no longer matter.
      LogUtils.d(TAG, "Drop focus event older than the focus spoken early: %s", event);
      return;
    }

    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      // The focus moved without being spoken early, so the prepared announcements are of no use.
      PreparedFocusSpeech.clear();
      @Nullable AccessibilityNodeInfoCompat focused = AccessibilityEventUtils.sourceCompat(event);
      if (focused != null) {
        onFocusMoved(focused);
      }
    }

    // Update persistent state, unless the focus spoken early already did, but failed to speak.
    if (earlyFocus != EarlyFocusMatch.STATE_UPDATED) {
      globalVariables.updateStateFromEvent(event);
    }

    // Interpret event more specifically, and extract data from event.
    EventInterpretation eventInterpreted =
        new EventInterpretation(Compositor.toCompositorEvent(event));
    AccessibilityFocusEventInterpretation a11yFocusEventInterpreted =
        (accessibilityFocusEventInterpreter == null)
            ? null
            : accessibilityFocusEventInterpreter.interpret(event);
    if (a11yFocusEventInterpreted != null) {
      eventInterpreted.setEvent(a11yFocusEventInterpreted.getEvent());
      a11yFocusEventInterpreted.setIsEqualsToLastHoverEnterKeyboardEventNode(
          isEqualsToLastHoverEnteredKeyboardNode(event));
      eventInterpreted.setAccessibilityFocusInterpretation(a11yFocusEventInterpreted);
    }

    eventInterpreted.setReadOnly();

    int eventType = eventInterpreted.getEvent();
    if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      // Drop accessibility-focus events based on EventState.
      // TODO: Remove this when focus management is done.
      {
        lastHoverEnteredNode = null;
        if (globalVariables.resettingSkipFocusProcessing()) {
          return;
        }
        if ((a11yFocusEventInterpreted != null)
            && a11yFocusEventInterpreted.getShouldMuteFeedback()) {
          return;
        }
      }
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
      if (globalVariables.isTapToType()
          && AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)) {
        // The key is typed without being spoken.
        return;
      }
      AccessibilityNodeInfoCompat node = AccessibilityEventUtils.sourceCompat(event);
      if (shouldEarlyAnnounceForLiftToType(context)
          && AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)
          && !Objects.equals(node, lastHoverEnteredNode)) {
        lastHoverEnteredNode = node;
      } else if (node != null) {
        lastHoverEnteredNode = node;
        // For focus fallback events, drop events with a source node.
        return;
      }
    } else if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      // Event notification
      // REFERTO. If the user is touching on screen, skip event.
      // For toast events, the notification parcel is null. (Use event text instead.)
      Notification notification = AccessibilityEventUtils.extractNotification(event);
      // While the device is locked, a private notification's event has only its public version,
      // or none, and the notification listener reads the whole notification instead.
      if (notification == null
          && Notification.class.getName().contentEquals(String.valueOf(event.getClassName()))
          && NotificationReader.readsFromListener()) {
        LogUtils.d(TAG, "Notification without content while locked: read from the listener");
        return;
      }
      boolean isCall =
          (notification != null) && Notification.CATEGORY_CALL.equals(notification.category);
      // Read now: the state kept in GlobalVariables changes only when a setting does.
      boolean dnd = SettingsUtils.getDoNotDisturbState(context) > 0;
      boolean touching = touchMonitor.isUserTouchingScreen();
      if (isCall) {
        CallAnnouncements.onCallNotification(String.valueOf(event.getPackageName()), notification);
        return;
      }
      if ((notification != null) && !globalVariables.getSpeakNotifications()) {
        LogUtils.d(TAG, "Do not announce notification: disabled in settings");
        NotificationReader.noteUnread(event, notification, Outcome.SPEECH_OFF);
        return;
      }
      // Notifications are read as the notification settings say, Do Not Disturb included, and
      // those that arrive while the screen is touched are read after the touch.
      if ((notification != null) && NotificationReader.handle(event, notification, touching, dnd)) {
        return;
      }
      if ((notification != null) && (touching || dnd)) {
        LogUtils.d(TAG, "Do not announce notification: DND is enabled or the screen is touched");
        return;
      }
      if ((voiceActionDelegate != null)
          && voiceActionDelegate.isVoiceRecognitionActive()
          && (Role.getSourceRole(event) == Role.ROLE_TOAST)) {
        LogUtils.d(TAG, "Do not announce the toast: Voice recognition is active.");
        return;
      }
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
      return; // Text-events only travel through TextEventInterpreter.
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
      LogUtils.d(
          TAG,
          "Do not handle TYPE_VIEW_SELECTED by ProcessEventQueue. "
              + "Handled in SelectionEventInterpreter.");
      return;
    } else {
      // Let event go to compositor.
    }

    compositor.handleEvent(event, eventId, eventInterpreted);
  }

  /**
   * Told when Backtalk has set accessibility focus on {@code node}, before the app's focus event
   * for it arrives. Speaks the focus at once if it can, see {@link #speakFocusEarly}; otherwise
   * makes sure the app's event for it is spoken.
   *
   * @param actionTime when the focus action started, in {@link SystemClock#uptimeMillis()} time
   * @param continuousReading whether continuous reading moved the focus. Its focus is not spoken
   *     early: continuous reading moves on when the speech for a node ends, and gains little from
   *     speaking sooner, so it keeps waiting for the app's event.
   */
  public void onAccessibilityFocusSet(
      AccessibilityNodeInfoCompat node,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime,
      boolean continuousReading) {
    if (continuousReading || !speakFocusEarly(node, info, eventId, actionTime)) {
      // A node spoken early before, whose event never came here, must not hide this focus.
      earlyFocusSpeech.forget(node);
    }
  }

  /**
   * Speaks the accessibility focus that a swipe or other user navigation just set on {@code node},
   * without waiting for the app's focus event, which takes a round trip to the app and every event
   * listener before it would be spoken. The focus event, when it arrives, is then not spoken again.
   *
   * <p>Only focus that stays in the same window is spoken early, so that window changes are still
   * announced from the event. Nodes whose descriptions need details only the event has, such as
   * keyboard keys, sliders, pages and web content, wait for their event.
   *
   * @return whether its node is now remembered, so that the app's focus event for it is handled
   *     by what was done here
   */
  private boolean speakFocusEarly(
      AccessibilityNodeInfoCompat focusedNode,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime) {
    // The focus is moving, so the prepared announcements are used now or not at all.
    NavigationAction action = info.navigationAction;
    if (action == null || !shouldSpeakFocusEarly(info)) {
      return false;
    }
    if (action.actionType == NavigationAction.DIRECTIONAL_NAVIGATION) {
      if (action.searchDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD) {
        preferredDirectionIndex = 0;
      } else if (action.searchDirection == TraversalStrategy.SEARCH_FOCUS_BACKWARD) {
        preferredDirectionIndex = 1;
      }
    }
    PreparedFocusSpeech.@Nullable Prepared prepared = info.columnWrap == ColumnWrap.NONE
        ? PreparedFocusSpeech.take(focusedNode, action.targetType) : null;
    AccessibilityNodeInfoCompat node;
    @Nullable EventFeedback preparedFeedback;
    @Nullable CharSequence preparedContainerTitle = null;
    if (prepared != null) {
      // Prepared from the node read again from the app, and thrown away had anything in its
      // window changed since, even its text or state. So it needs no second read, which would
      // wait for the app to finish drawing the focus it was just given.
      node = prepared.getNode();
      preparedFeedback = prepared.getFeedback();
      preparedContainerTitle = prepared.getContainerTitle();
    } else if (TraversalTreeCache.holdsCurrent(focusedNode)) {
      // Read from the app with the saved order, and nothing in its window has changed since, so a
      // second read would only wait for the app, which on a watch is often busy scrolling.
      node = focusedNode;
      preparedFeedback = null;
    } else {
      // The node may come from the saved reading order, which keeps nodes for a while after their
      // text or state changes, such as a progress label or a switch the app turned on. Read it
      // again from the app, as its focus event would. If it is gone, leave it to the event.
      @SuppressWarnings("deprecation") // obtain(): copy, so that the saved node is left as it was.
      AccessibilityNodeInfoCompat copy = AccessibilityNodeInfoCompat.obtain(focusedNode);
      if (!copy.refresh()) {
        return false;
      }
      node = copy;
      preparedFeedback = null;
    }
    if (!shouldSpeakFocusEarly(node)) {
      Performance.getInstance().onNavigationDetail(eventId, "early_focus", 0);
      return false;
    }
    Performance.getInstance().onNavigationDetail(eventId, "early_focus", 1);
    AccessibilityEvent event = focusEventFor(node);
    EventInterpretation eventInterpreted = focusInterpretation(info);

    // The speech is composed from the state that follows focus, so it is updated first, and the
    // app's event must then not update it a second time, whether or not the speech succeeds:
    // moving the collection state twice would lose "list, N items" or "in table".
    globalVariables.updateStateFromFocusedNode(node);
    globalVariables.updateCollectionStateFromFocusedNode(node, event);
    lastHoverEnteredNode = null;
    try {
      EventFeedback feedback;
      if (preparedFeedback != null) {
        feedback = preparedFeedback;
        Performance.getInstance().onPreparedSpeechHit();
        Performance.getInstance().onNavigationDetail(eventId, "prepared_text_hit", 1);
        LogUtils.v(TAG, "Prepared speech hit: %s", node);
        // Working the feedback out would have moved the container title to the node's, as
        // preparing it did before putting it back.
        EventTypeViewAccessibilityFocusedFeedbackRule.currentContainerTitle =
            preparedContainerTitle;
      } else {
        Performance.getInstance().onPreparedSpeechMiss();
        Performance.getInstance().onNavigationDetail(eventId, "prepared_text_hit", 0);
        // No node in this line: formatting it walks the node's properties on the swipe path.
        LogUtils.v(TAG, "Prepared speech miss");
        feedback = getSwipeTargetFeedback(event, node, eventInterpreted);
      }
      if (!hasSpeech(feedback)) {
        // Some nodes only show their content once focused, such as the second notification of a
        // collapsed group, whose text is hidden until then. Nothing to say yet means the app's
        // event, which comes after it shows the content, should say it.
        earlyFocusSpeech.addStateUpdated(node, actionTime);
        onFocusMoved(node);
        return true;
      }
      compositor.handleEvent(event, node, eventId, eventInterpreted, feedback);
    } catch (RuntimeException e) {
      // Something on the way to speech needed more than a Backtalk-made event has. Leave the
      // focus to be spoken from the app's event rather than stop Backtalk.
      LogUtils.e(TAG, "Cannot speak focus early: %s", e);
      earlyFocusSpeech.addStateUpdated(node, actionTime);
      onFocusMoved(node);
      return true;
    }
    earlyFocusSpeech.addSpoken(node, actionTime);
    onFocusMoved(node);
    return true;
  }

  /** Sets whether items are spoken before a swipe scrolls them into view; see the field. */
  public static void setSpeakItemsBeforeScroll(boolean speak) {
    speakItemsBeforeScroll = speak;
  }

  public static void setLatencyExtrasEnabled(boolean enabled) {
    latencyExtrasEnabled = enabled;
    if (!enabled) {
      PreparedFocusSpeech.clear();
    }
  }

  /** Enables independently selectable preparation of the adjacent and second-ahead targets. */
  public static void setPreparedSpeechOptions(boolean adjacent, boolean secondAhead) {
    prepareAdjacentSpeechEnabled = adjacent;
    prepareSecondAheadSpeechEnabled = secondAhead;
    if (!adjacent && !secondAhead) {
      PreparedFocusSpeech.clear();
    }
  }

  /**
   * Works out what focusing {@code node} by a swipe says. This comes before the swipe has scrolled
   * the node into view, so its children still off screen are described as they will be once it has.
   */
  private EventFeedback getSwipeTargetFeedback(
      AccessibilityEvent event, AccessibilityNodeInfoCompat node, EventInterpretation interpreted) {
    globalVariables.setDescribingSwipeTarget(speakItemsBeforeScroll);
    try {
      return compositor.getFeedback(event, node, interpreted);
    } finally {
      globalVariables.setDescribingSwipeTarget(false);
    }
  }

  /** The interpretation of the focus event for focus that user navigation set with {@code info}. */
  private static EventInterpretation focusInterpretation(FocusActionInfo info) {
    AccessibilityFocusEventInterpretation focusInterpretation =
        new AccessibilityFocusEventInterpretation(
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    focusInterpretation.setForceFeedbackEvenIfAudioPlaybackActive(
        info.forceFeedbackEvenIfAudioPlaybackActive());
    focusInterpretation.setForceFeedbackEvenIfMicrophoneActive(
        info.forceFeedbackEvenIfMicrophoneActive());
    focusInterpretation.setForceFeedbackEvenIfSsbActive(info.forceFeedbackEvenIfSsbActive());
    focusInterpretation.setIsNavigateByUser(true);
    focusInterpretation.setColumnWrap(info.columnWrap);
    EventInterpretation eventInterpreted =
        new EventInterpretation(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    eventInterpreted.setAccessibilityFocusInterpretation(focusInterpretation);
    eventInterpreted.setReadOnly();
    return eventInterpreted;
  }

  /** Whether focus set with {@code info} may be spoken early, before looking at its node. */
  private boolean shouldSpeakFocusEarly(FocusActionInfo info) {
    return info.sourceAction == FocusActionInfo.LOGICAL_NAVIGATION
        && info.navigationAction != null
        && !info.forceMuteFeedback
        && !globalVariables.hasSkipFocusProcessing();
  }

  /** Whether focus on {@code node} may be spoken early. */
  private boolean shouldSpeakFocusEarly(AccessibilityNodeInfoCompat node) {
    if (node.getWindowId() != globalVariables.getCurrentWindowId()) {
      return false;
    }
    int role = Role.getRole(node);
    boolean preparedControl = LatencyExperiments.Feature.EARLY_CONTROLS.enabled()
        && (!TextUtils.isEmpty(node.getText()) || !TextUtils.isEmpty(node.getContentDescription())
            || node.getRangeInfo() != null);
    if (role == Role.ROLE_TEXT_ENTRY_KEY
        || role == Role.ROLE_KEYBOARD_KEY
        || (!preparedControl && (role == Role.ROLE_SEEK_CONTROL
            || role == Role.ROLE_PROGRESS_BAR || AccessibilityNodeInfoUtils.isPage(node)))
        || WebInterfaceUtils.supportsWebActions(node)) {
      return false;
    }
    // A Telegram chat list row may still describe the conversation it showed before it was reused,
    // until the app fills in its focus event.
    if (TelegramChatCell.isDescribedOnFocus(node)) {
      return false;
    }
    AccessibilityWindowInfo window = AccessibilityNodeInfoUtils.getWindow(node.unwrap());
    if (AccessibilityWindowInfoUtils.getType(window)
        == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
      return false;
    }
    // An item only partly on screen, which the swipe is about to scroll into view, can have all
    // its text in children still off screen, which descriptions leave out unless they are spoken
    // as they will be after the scroll. Otherwise the app's event, after the scroll, says it.
    return speakItemsBeforeScroll || !hasChildOffScreen(node);
  }

  /** Whether a child of {@code node} is off screen. */
  private static boolean hasChildOffScreen(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && !child.isVisibleToUser()) {
        return true;
      }
    }
    return false;
  }

  /** Prepares the announcements of the nodes next to {@code node}, which focus just moved to. */
  private void onFocusMoved(AccessibilityNodeInfoCompat node) {
    lastFocusedNode = node;
    schedulePreparation();
  }

  private boolean preparationEnabled() {
    return latencyExtrasEnabled && (prepareAdjacentSpeechEnabled || prepareSecondAheadSpeechEnabled
        || LatencyExperiments.preparePcm());
  }

  private void schedulePreparation() {
    preparationGeneration++;
    // Q11 (round 3): a deferred preparation that dies here — focus moved again while it was
    // waiting for touch to end, or for input to go quiet — is counted, so the next round can
    // tell a delayed preparation from a lost one.
    if (waitingForTouchEnd) {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.TOUCH_DEFERRED_DIED);
    }
    if (waitingForIdle) {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.IDLE_DEFERRED_DIED);
    }
    preparationPass = null;
    waitingForTouchEnd = false;
    waitingForIdle = false;
    retriedMissingOrder = false;
    handler.removeCallbacks(prepareSwipeTargets);
    handler.removeCallbacks(prepareNextSwipeTarget);
    if (preparationEnabled() && lastFocusedNode != null
        && targetPredictor != null) {
      handler.postDelayed(prepareSwipeTargets, PREPARE_DELAY_MS);
    }
  }

  public void onTouchInteractionEnded() {
    if (waitingForTouchEnd) {
      waitingForTouchEnd = false;
      handler.post(preparationPass == null ? prepareSwipeTargets : prepareNextSwipeTarget);
    }
  }

  private boolean waitForTouchEnd() {
    if (!LatencyExperiments.preparationTouchActive(fingerDown.getAsBoolean())) {
      return false;
    }
    waitingForTouchEnd = true;
    Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.WAITING_FOR_TOUCH);
    return true;
  }

  /**
   * Q3: prepares the announcement of the focused item itself, not only the nodes a swipe
   * would reach. The prepared audio is exactly what the user is about to hear from the
   * focused item, and a later return to it can use it. The focused item needs no traversal
   * order, so this runs before the order check in {@link #prepareSwipeTargets}.
   */
  private void prepareFocusedItem(AccessibilityNodeInfoCompat pivot) {
    TargetPredictor predictor = targetPredictor;
    if (predictor == null) {
      return;
    }
    int targetType = predictor.currentTargetType();
    boolean prepareAudio = LatencyExperiments.Feature.PCM_ADJACENT.enabled();
    boolean storePreparedSpeech =
        (prepareAdjacentSpeechEnabled || prepareSecondAheadSpeechEnabled)
            && PreparedFocusSpeech.shouldPrepare(pivot);
    if (!prepareAudio && !storePreparedSpeech) {
      return;
    }
    PreparedFocusSpeech.Prepared cached =
        storePreparedSpeech ? PreparedFocusSpeech.peek(pivot, targetType) : null;
    if (cached == null) {
      // Q1 (round 3): the pivot just arrived in a live focus event, so it is current by
      // construction — it is never vetoed as stale, whatever the saved order says.
      prepareFocusSpeech(
          pivot, targetType, prepareAudio, /* isFocusedItem= */ true, storePreparedSpeech);
    } else if (prepareAudio) {
      compositor.prepareNavigationFeedback(cached.getFeedback(), preparationGeneration);
    }
  }

  /**
   * Works out the announcements of the nodes that a swipe forward or back from the focused node
   * would most likely reach, while the user listens to the focused node, so that the swipe can
   * speak one at once. Finding them also fetches them from the app, so that the swipe's own search
   * finds them already fetched.
   */
  private void prepareSwipeTargets() {
    if (!preparationEnabled()) {
      return;
    }
    @Nullable AccessibilityNodeInfoCompat pivot = lastFocusedNode;
    if (pivot == null || targetPredictor == null || waitForTouchEnd()) {
      // A swipe may be starting, and must not wait for this.
      return;
    }
    if (!LatencyExperiments.preparePcm() && !PreparedFocusSpeech.shouldPrepare(pivot)) {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.SUPPRESSED_WINDOW);
      // This window keeps throwing preparations away unused, so preparation is paused for it.
      return;
    }
    // Q3: also prepare the announcement of the focused item itself, not only the nodes a
    // swipe would reach. It needs no traversal order — it is the pivot — so it is prepared
    // here, before the order check below, and is ready even while the order is still building.
    prepareFocusedItem(pivot);
    if (TraversalTreeCache.orderForPrediction(pivot) == null) {
      TraversalTreeCache.rememberPivot(pivot);
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.MISSING_ORDER);
      if (!retriedMissingOrder) {
        retriedMissingOrder = true;
        // Q5: prepare again when the order build completes, instead of after a fixed 48 ms.
        // Registering on the in-flight build also starts one when none is running yet: the
        // build is the same one warm() would have started, and warm() is a no-op while a
        // build is active. Only when no window root can be found at all, or the order cache
        // is off, is there nothing to register on, and the single 48 ms retry is kept for
        // that case.
        @Nullable AccessibilityNodeInfoCompat root =
            TraversalTreeCache.getEnabled() ? AccessibilityNodeInfoUtils.getRoot(pivot) : null;
        if (root != null) {
          long generation = preparationGeneration;
          AsyncTraversalOrder.request(root, null, result -> {
            if (result == AsyncTraversalOrder.Result.READY
                && generation == preparationGeneration
                && Objects.equals(lastFocusedNode, pivot)) {
              // Drop any steps of the pass that ran without an order (see below) before
              // starting the order-based pass, as schedulePreparation() does.
              handler.removeCallbacks(prepareNextSwipeTarget);
              handler.post(prepareSwipeTargets);
            }
          });
        } else {
          handler.postDelayed(prepareSwipeTargets, PREPARE_ORDER_RETRY_MS);
        }
      }
      // Q7 wiring: no return here. The pass below still runs, so predictTarget is reached
      // with no order and its local-neighbour fallback can find the swipe targets at once
      // instead of waiting for the build. Without that fallback predictTarget returns null
      // and the pass prepares nothing; the Q5 re-run above then prepares the order-based
      // targets when the build lands, correcting any local guess (a wrong guess only wastes
      // its prepared speech — the real swipe re-derives its target and never reads it).
    }
    int maximumDistance = prepareSecondAheadSpeechEnabled
        || LatencyExperiments.Feature.PCM_SECOND.enabled() ? 2 : 1;
    preparationPass =
        new PreparationPass(pivot, maximumDistance, targetPredictor.currentTargetType(),
            preferredDirectionIndex);
    prepareNextSwipeTarget.run();
  }

  /** Prepares one predicted node at a time, yielding to input between synchronous app queries. */
  private void prepareNextSwipeTarget() {
    // Q11 (round 3): the idle retry ran — clear the flag first, on every path, so
    // schedulePreparation() only counts attempts whose re-post never ran.
    waitingForIdle = false;
    PreparationPass pass = preparationPass;
    TargetPredictor predictor = targetPredictor;
    if (pass == null
        || !preparationEnabled()
        || predictor == null
        || !Objects.equals(pass.focusedNode, lastFocusedNode)
        || (!LatencyExperiments.preparePcm()
            && !PreparedFocusSpeech.shouldPrepare(pass.focusedNode))) {
      preparationPass = null;
      return;
    }
    if (waitForTouchEnd()) {
      return;
    }
    long waitMs = LatencyExperiments.Feature.INPUT_PRIORITY.enabled()
        ? LatencyExperiments.inputQuietDelay(65) : 0;
    if (waitMs > 0) {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.WAITING_FOR_IDLE);
      waitingForIdle = true;
      handler.postDelayed(prepareNextSwipeTarget, waitMs);
      return;
    }
    int targetType = pass.targetType();
    int direction = pass.direction();
    @Nullable AccessibilityNodeInfoCompat currentPivot = pass.pivot();
    @Nullable AccessibilityNodeInfoCompat target = null;
    try {
      if (currentPivot != null) {
        // Repeated failures for the same target type do not rerun the search or count NO_TARGET
        // twice. A different reading control can still have a valid target.
        if (pass.failedBefore(currentPivot, direction, targetType)) {
          Performance.getInstance()
              .onPreparedSpeechWork(PreparedSpeechWork.NULL_PREDICTION_CACHED);
        } else {
          target = targetType == NavigationTarget.TARGET_DEFAULT
                ? predictor.predictTarget(currentPivot, direction)
                : predictor.predictTarget(currentPivot, direction, targetType);
          if (target == null) {
            pass.noteFailed(currentPivot, direction, targetType);
          }
        }
      }
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot predict swipe target: %s", e);
      preparationPass = null;
      return;
    }

    if (target != null) {
      boolean shouldPrepareSpeech =
          (pass.distance == 1 && (prepareAdjacentSpeechEnabled
              || LatencyExperiments.Feature.PCM_ADJACENT.enabled()))
              || (pass.distance == 2 && (prepareSecondAheadSpeechEnabled
                  || LatencyExperiments.Feature.PCM_SECOND.enabled()));
      if (shouldPrepareSpeech) {
        boolean prepareAudio = pass.distance == 1
            ? LatencyExperiments.Feature.PCM_ADJACENT.enabled()
            : LatencyExperiments.Feature.PCM_SECOND.enabled();
        boolean storePreparedSpeech =
            (pass.distance == 1 ? prepareAdjacentSpeechEnabled : prepareSecondAheadSpeechEnabled)
                && !WebInterfaceUtils.supportsWebActions(target)
                && PreparedFocusSpeech.shouldPrepare(target);
        PreparedFocusSpeech.Prepared cached =
            storePreparedSpeech ? PreparedFocusSpeech.peek(target, targetType) : null;
        if (cached == null) {
          prepareFocusSpeech(
              target, targetType, prepareAudio, /* isFocusedItem= */ false, storePreparedSpeech);
        } else if (prepareAudio) {
          compositor.prepareNavigationFeedback(cached.getFeedback(), preparationGeneration);
        }
      }
    }

    if (!pass.advance(target)) {
      preparationPass = null;
      return;
    }
    handler.postDelayed(prepareNextSwipeTarget, PREPARE_STEP_DELAY_MS);
  }

  /**
   * The reading control changed while the focus stayed where it was, so the targets the next
   * swipe would reach changed with it: prepare them around the focused node again.
   */
  public void onGranularityChanged() {
    schedulePreparation();
  }

  /**
   * Prepares the announcement of focusing {@code focusedNode}. When {@code isFocusedItem} the
   * node is the pivot itself, which just arrived in a live focus event and is current by
   * construction, so it is never vetoed as stale. When {@code storePreparedSpeech} is false
   * (PCM-only mode or suppressed window), speech is synthesized to NavigationPcmCache without
   * populating PreparedFocusSpeech so unused entries do not trigger window suppression.
   */
  private void prepareFocusSpeech(
      AccessibilityNodeInfoCompat focusedNode,
      @TargetType int targetType,
      boolean prepareAudio,
      boolean isFocusedItem,
      boolean storePreparedSpeech) {
    // Read the node again from the app, as a swipe to it would, rather than prepare from a copy
    // that the saved reading order may have kept since before its text or state changed.
    @SuppressWarnings("deprecation")
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain(focusedNode);
    if (!isFocusedItem) {
      if (LatencyExperiments.Feature.CACHED_TEXT.enabled()
          && TraversalTreeCache.hasSavedOrder()) {
        // The saved order is the fast path: when it holds the node current, no second read is
        // needed. When no order is saved at all — the order is still building, or the async
        // order feature is off — there is nothing for the node to be stale against, so fall
        // back to re-reading the node, as without the cached-text feature.
        // Q1 (round 3): when an order is saved but does not hold the node current, the node
        // is usually fine and the order is the stale one — a churning window clears and
        // rebuilds the order constantly, and one bounds-less background change vetoes every
        // node through the grace flag. So re-read the node instead of vetoing it, exactly
        // as the no-order path already does; only a failed re-read still vetoes. The cause
        // is counted (round-3 Q11) whether the re-read saves the preparation or not.
        if (!TraversalTreeCache.holdsCurrent(node)) {
          if (!TraversalTreeCache.holds(node)) {
            Performance.getInstance()
                .onPreparedSpeechWork(PreparedSpeechWork.STALE_NODE_NOT_HELD);
          } else {
            // Held but not current: with region grace on, a graced change touches the node;
            // with grace off, a text change arrived since the order was saved.
            Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.STALE_NODE_GRACED);
          }
          if (!node.refresh()) {
            Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.STALE_NODE);
            Performance.getInstance()
                .onPreparedSpeechWork(PreparedSpeechWork.STALE_NODE_REFRESH_FAILED);
            return;
          }
        }
      } else if (!node.refresh()) {
        Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.STALE_NODE);
        return;
      }
    }
    if (globalVariables.hasSkipFocusProcessing() || !shouldSpeakFocusEarly(node)) {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.INELIGIBLE_NODE);
      return;
    }
    AccessibilityEvent event = focusEventFor(node);
    FocusActionInfo info =
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build();
    // Working it out moves the focus state to the node, as a swipe would, so put it back after.
    GlobalVariables.SavedFocusState saved = globalVariables.saveFocusState();
    EventFeedback feedback;
    CharSequence containerTitle;
    try {
      globalVariables.updateStateFromFocusedNode(node);
      globalVariables.updateCollectionStateFromFocusedNode(node, event);
      feedback = getSwipeTargetFeedback(event, node, focusInterpretation(info));
      containerTitle = EventTypeViewAccessibilityFocusedFeedbackRule.currentContainerTitle;
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot prepare focus speech: %s", e);
      return;
    } finally {
      globalVariables.restoreFocusState(saved);
    }
    if (hasSpeech(feedback)) {
      if (storePreparedSpeech) {
        PreparedFocusSpeech.put(node, feedback, containerTitle, targetType);
      }
      if (prepareAudio) compositor.prepareNavigationFeedback(feedback, preparationGeneration);
    } else {
      Performance.getInstance().onPreparedSpeechWork(PreparedSpeechWork.EMPTY_SPEECH);
    }
  }

  /** Whether {@code feedback} says anything. */
  private static boolean hasSpeech(EventFeedback feedback) {
    return !TextUtils.isEmpty(feedback.ttsOutput().orElse(null));
  }

  /** Makes the focus event the app would send for {@code node}, without its source. */
  @SuppressWarnings("deprecation") // AccessibilityEvent(int) needs Android 11.
  private static AccessibilityEvent focusEventFor(AccessibilityNodeInfoCompat node) {
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    event.setEventTime(SystemClock.uptimeMillis());
    event.setClassName(node.getClassName());
    event.setPackageName(node.getPackageName());
    event.setContentDescription(node.getContentDescription());
    if (node.getText() != null) {
      event.getText().add(node.getText());
    }
    event.setEnabled(node.isEnabled());
    event.setChecked(node.isChecked());
    event.setPassword(node.isPassword());
    event.setScrollable(node.isScrollable());
    return event;
  }

  /** Passes text-event-interpretation to compositor. */
  public void accept(TextEventInterpreter.Interpretation textEventInterpreted) {
    AccessibilityEvent event = textEventInterpreted.event;
    globalVariables.setLastTextEditIsPassword(event.isPassword());

    int eventType =
        (textEventInterpreted.interpretation == null)
            ? Compositor.toCompositorEvent(event)
            : Compositor.toCompositorEvent(textEventInterpreted.interpretation.getEvent());
    EventInterpretation eventInterpreted = new EventInterpretation(eventType);
    eventInterpreted.setTextEventInterpretation(textEventInterpreted.interpretation);
    eventInterpreted.setPackageName(event.getPackageName());
    eventInterpreted.setReadOnly();

    compositor.handleEvent(event, textEventInterpreted.eventId, eventInterpreted);
  }

  private boolean isEqualsToLastHoverEnteredKeyboardNode(@Nullable AccessibilityEvent event) {
    if (!FeatureFlagReader.enableEarlyAnnounceForLiftToType(context)
        || !AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)) {
      return false;
    }
    return Objects.equals(lastHoverEnteredNode, AccessibilityEventUtils.sourceCompat(event));
  }
}
