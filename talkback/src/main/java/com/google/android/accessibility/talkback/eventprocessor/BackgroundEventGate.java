package com.google.android.accessibility.talkback.eventprocessor;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Predicate;
import java.util.function.Consumer;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.input.ScrollEventInterpreter;
import com.google.android.accessibility.utils.WindowCachePolicy;
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache;

public final class BackgroundEventGate {
  public interface Receiver {
    void accept(AccessibilityEvent event, long receivedAt);
  }
  private static final long INTERVAL_MS = 500;
  private static final long INPUT_GRACE_MS = 150;
  private static final long RECENT_INPUT_MS = 500;
  private static final long FLING_CHAIN_GAP_MS = 250;
  private static final long FLING_MAX_EXTENSION_MS = 1800;
  private static final int PANE_CONTENT_CHANGES =
      AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
          | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
          | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED;
  private static final int STRICT_EVENTS =
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          | AccessibilityEvent.TYPE_WINDOWS_CHANGED
          | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          | AccessibilityEvent.TYPE_VIEW_SCROLLED
          | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
          | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
          | AccessibilityEvent.TYPE_VIEW_FOCUSED
          | AccessibilityEvent.TYPE_VIEW_SELECTED
          | AccessibilityEvent.TYPE_VIEW_CLICKED
          | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
          | AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
          | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
          | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
          | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_START
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_END
          | AccessibilityEvent.TYPE_ANNOUNCEMENT
          | AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE
          | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
  private static final int WINDOW_TRANSITIONS = AccessibilityEvent.WINDOWS_CHANGE_ADDED
      | AccessibilityEvent.WINDOWS_CHANGE_REMOVED
      | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
      | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
      | AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;
  private static final int UPDATE_TYPES = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      | AccessibilityEvent.TYPE_VIEW_SELECTED | AccessibilityEvent.TYPE_VIEW_SCROLLED
      | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Receiver receiver;
  private final Predicate<AccessibilityEvent> inputResponse;
  private final Consumer<AccessibilityEvent> deferredInvalidation;
  private final Runnable releasePending = this::releasePending;
  private boolean strict;
  private boolean interactionPriorityEnabled;
  private boolean touching;
  private boolean releaseScheduled;
  private long lastInput;
  private long lastInteractiveScrollAt;
  private long lastDispatch = SystemClock.uptimeMillis();
  private long pendingForegroundSince;
  private long pendingBackgroundSince;
  private int interactionWindowId = -1;
  private String interactionPackage;
  private AccessibilityEvent pendingForeground;
  private AccessibilityEvent pendingBackground;

  public BackgroundEventGate(Receiver receiver) {
    this(receiver, event -> false);
  }

  public BackgroundEventGate(Receiver receiver, Predicate<AccessibilityEvent> inputResponse) {
    this(receiver, inputResponse, event -> {});
  }

  public BackgroundEventGate(Receiver receiver, Predicate<AccessibilityEvent> inputResponse,
      Consumer<AccessibilityEvent> deferredInvalidation) {
    this.receiver = receiver;
    this.inputResponse = inputResponse;
    this.deferredInvalidation = deferredInvalidation;
  }

  public void setProcessingLevel(String level) {
    boolean nextStrict = "very_low".equals(level);
    if (strict != nextStrict) {
      boolean wasTouching = touching;
      clear();
      touching = wasTouching;
      strict = nextStrict;
      lastDispatch = SystemClock.uptimeMillis();
    }
  }

  public boolean isStrict() {
    return strict;
  }

  public int subscribedEventTypes() {
    return strict ? STRICT_EVENTS : AccessibilityEvent.TYPES_ALL_MASK;
  }

  // 6A: retained for compatibility (TalkBackService still calls it); the interaction-window
  // deferral it used to enable no longer exists, so this is now a no-op.
  public void setInteractionPriorityEnabled(boolean enabled) {
    interactionPriorityEnabled = enabled;
  }

  private static final long FOREGROUND_STORM_WINDOW_MS = 250L;
  private static final int FOREGROUND_STORM_THRESHOLD = 4;
  private static final long FOREGROUND_SCROLL_STORM_WINDOW_MS = 120L;
  private static final int FOREGROUND_SCROLL_STORM_THRESHOLD = 3;
  private static final int CHURNING_SUBTREE_STORM_THRESHOLD = 3;
  private long fgContentBurstWindowStartMs;
  private int fgContentBurstCount;
  private long fgScrollBurstWindowStartMs;
  private int fgScrollBurstCount;
  private long fgSubtreeBurstWindowStartMs;
  private int fgSubtreeBurstCount;

  public void onUserInput() {
    lastInput = SystemClock.uptimeMillis();
  }

  public boolean onAccessibilityEvent(AccessibilityEvent event) {
    return onAccessibilityEvent(event, SystemClock.uptimeMillis());
  }

  public boolean onAccessibilityEvent(AccessibilityEvent event, long receivedAt) {
    // Must observe the raw event before deciding to drop/defer it. An active feed can
    // otherwise suppress its own churn detector and keep invalidating the reading order.
    TraversalTreeCache.observeIncomingEvent(event);
    EventProcessingStats.received(event);
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
      touching = true;
      onUserInput();
      handler.removeCallbacks(releasePending);
      releaseScheduled = false;
    } else if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
      touching = false;
      onUserInput();
      schedulePending();
    }
    if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_CLICKED
        || (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event.getPackageName() != null
            && !"com.android.systemui".contentEquals(event.getPackageName())
            && !isPurePaneContentChange(event)
            && (!LatencyExperiments.Feature.SCOPED_CACHE.enabled()
                || interactionWindowId < 0 || interactionWindowId == event.getWindowId()))) {
      if (interactionWindowId != event.getWindowId()) {
        fgContentBurstWindowStartMs = 0;
        fgContentBurstCount = 0;
        fgScrollBurstWindowStartMs = 0;
        fgScrollBurstCount = 0;
        fgSubtreeBurstWindowStartMs = 0;
        fgSubtreeBurstCount = 0;
        lastInteractiveScrollAt = 0;
      }
      interactionWindowId = event.getWindowId();
      interactionPackage = event.getPackageName() == null ? null : event.getPackageName().toString();
    }
    if (strict) {
      return handleStrictEvent(event);
    }
    // 6A: updates from the window the user is interacting with are no longer held by the
    // gate at all — no 200 ms active-update deferral while touching. They are admitted
    // below, like focus and clicks, so list content changing during a swipe is read at
    // once. Background windows keep the 500 ms slot (see isBackgroundUpdate).
    if (!LatencyExperiments.Feature.INPUT_PRIORITY.enabled()) {
      EventProcessingStats.admitted(event, 0);
      return true;
    }
    boolean background = isBackgroundUpdate(event);
    if (!background && isPassiveSeekBarStateTick(event)) {
      return cacheOnly(event);
    }
    if (!background && !isForegroundStormOrPassiveScroll(event, receivedAt)) {
      EventProcessingStats.admitted(event, 0);
      return true;
    }
    deferredInvalidation.accept(event);
    if (background) {
      if (pendingBackground != null) {
        EventProcessingStats.coalesced(pendingBackground);
        pendingBackground.recycle();
      }
      pendingBackground = AccessibilityEvent.obtain(event);
      pendingBackgroundSince = receivedAt;
    } else {
      if (pendingForeground != null) {
        EventProcessingStats.coalesced(pendingForeground);
        pendingForeground.recycle();
      }
      pendingForeground = AccessibilityEvent.obtain(event);
      pendingForegroundSince = receivedAt;
    }
    EventProcessingStats.deferred(event);
    schedulePending();
    return false;
  }

  /**
   * Coalesces foreground live-refresh content storms (e.g. countdown timers or heavy voucher lists)
   * and unsolicited passive auto-scrolls while still admitting normal foreground updates at once.
   */
  private static boolean hasMeaningfulScrollPosition(AccessibilityEvent event) {
    if (!ScrollEventInterpreter.hasScrollPosition(event)) {
      return false;
    }
    if (event.getFromIndex() >= 0 && event.getToIndex() >= 0 && event.getItemCount() > 0) {
      return true;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        && (event.getScrollDeltaX() != -1 || event.getScrollDeltaY() != -1)) {
      return true;
    }
    return event.isScrollable() && (event.getScrollX() > 0 || event.getScrollY() > 0);
  }

  private boolean isPassiveSeekBarStateTick(AccessibilityEvent event) {
    if (interactionWindowId < 0 || event.getWindowId() != interactionWindowId) {
      return false;
    }
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        || event.getContentChangeTypes() != AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) {
      return false;
    }
    CharSequence className = event.getClassName();
    return className != null
        && className.toString().contains("SeekBar")
        && !isRecentInteraction(event);
  }

  private boolean isForegroundStormOrPassiveScroll(AccessibilityEvent event, long receivedAt) {
    if (interactionWindowId < 0 || event.getWindowId() != interactionWindowId) {
      return false;
    }
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
      if (!isRecentInteraction(event) && !inputResponse.test(event)) {
        TraversalTreeCache.notePassiveScroll(event.getWindowId());
        return true;
      }
      if (!inputResponse.test(event)) {
        // Gộp bão sự kiện cuộn liên tiếp (ví dụ Gemini bắn 15-20 Manual scrolling trong ~25 ms)
        // để giữ hàng đợi thoáng cho cử chỉ vuốt mà không ảnh hưởng các cuộn có kỳ vọng.
        if (receivedAt < fgScrollBurstWindowStartMs
            || receivedAt - fgScrollBurstWindowStartMs > FOREGROUND_SCROLL_STORM_WINDOW_MS) {
          fgScrollBurstWindowStartMs = receivedAt;
          fgScrollBurstCount = 1;
          return false;
        }
        fgScrollBurstCount++;
        return fgScrollBurstCount >= FOREGROUND_SCROLL_STORM_THRESHOLD;
      }
      return false;
    }
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      // Preserve structural subtree updates needed during active accessibility navigation.
      // Only repeated non-structural text and state changes enter this throttling lane.
      if (!WindowCachePolicy.isNonStructuralContent(event)) {
        // Trên các ứng dụng nặng tự làm mới cây liên tục (TikTok, Shopee Voucher, Gemini đang đổ
        // chữ) đã vào trạng thái churning, gộp bớt bão SUBTREE khi người dùng không chạm tay để
        // không làm tắc hàng đợi và giữ độ trễ vuốt đồng đều như ứng dụng nhẹ.
        if (!touching && TraversalTreeCache.shouldUseLiveTraversal(event.getWindowId())) {
          if (!hasMeaningfulScrollPosition(event)
              && lastInput > 0
              && receivedAt - lastInput < INPUT_GRACE_MS) {
            return true;
          }
          if (receivedAt < fgSubtreeBurstWindowStartMs
              || receivedAt - fgSubtreeBurstWindowStartMs > FOREGROUND_STORM_WINDOW_MS) {
            fgSubtreeBurstWindowStartMs = receivedAt;
            fgSubtreeBurstCount = 1;
            return false;
          }
          fgSubtreeBurstCount++;
          return fgSubtreeBurstCount >= CHURNING_SUBTREE_STORM_THRESHOLD;
        }
        return false;
      }
      if (touching || (lastInput > 0 && receivedAt - lastInput < INPUT_GRACE_MS)) {
        return true;
      }
      if (receivedAt < fgContentBurstWindowStartMs
          || receivedAt - fgContentBurstWindowStartMs > FOREGROUND_STORM_WINDOW_MS) {
        fgContentBurstWindowStartMs = receivedAt;
        fgContentBurstCount = 1;
        return false;
      }
      fgContentBurstCount++;
      return fgContentBurstCount >= FOREGROUND_STORM_THRESHOLD;
    }
    return false;
  }

  private boolean handleStrictEvent(AccessibilityEvent event) {
    int type = event.getEventType();
    if ((type & STRICT_EVENTS) == 0) {
      EventProcessingStats.dropped(event);
      return false;
    }
    switch (type) {
      case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
        if (!isWindowTransition(event)) {
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
        if ((event.getContentChangeTypes() & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) == 0
            || !isRecentInteraction(event) || !hasMeaningfulScrollPosition(event)) {
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_VIEW_SCROLLED:
        if (!isRecentInteraction(event) && !inputResponse.test(event)) {
          if (interactionWindowId == event.getWindowId()) {
            TraversalTreeCache.notePassiveScroll(event.getWindowId());
          }
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_VIEW_SELECTED:
      case AccessibilityEvent.TYPE_ANNOUNCEMENT:
        if (!isRecentInteraction(event)) {
          EventProcessingStats.dropped(event);
          return false;
        }
        break;
      default:
        break;
    }
    EventProcessingStats.admitted(event, 0);
    return true;
  }

  private boolean cacheOnly(AccessibilityEvent event) {
    deferredInvalidation.accept(event);
    EventProcessingStats.cacheOnly(event);
    return false;
  }

  private boolean isRecentInteraction(AccessibilityEvent event) {
    if (interactionWindowId >= 0
        && event.getWindowId() >= 0
        && event.getWindowId() != interactionWindowId) {
      return false;
    }
    long now = SystemClock.uptimeMillis();
    if (touching || (lastInput > 0 && now - lastInput < RECENT_INPUT_MS)) {
      if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
        lastInteractiveScrollAt = now;
      }
      return true;
    }
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
        && lastInput > 0
        && lastInteractiveScrollAt >= lastInput
        && now - lastInteractiveScrollAt <= FLING_CHAIN_GAP_MS
        && now - lastInput <= FLING_MAX_EXTENSION_MS) {
      lastInteractiveScrollAt = now;
      return true;
    }
    return false;
  }

  private static boolean isPurePaneContentChange(AccessibilityEvent event) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return false;
    }
    int changes = event.getContentChangeTypes();
    return changes != 0 && (changes & ~PANE_CONTENT_CHANGES) == 0;
  }

  private static boolean isWindowTransition(AccessibilityEvent event) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return true;
    }
    int changes = event.getWindowChanges();
    return changes == 0
        || (changes & (WINDOW_TRANSITIONS | AccessibilityEvent.WINDOWS_CHANGE_PIP)) != 0;
  }

  private void schedulePending() {
    if ((pendingForeground != null || pendingBackground != null)
        && !touching
        && !releaseScheduled) {
      releaseScheduled = true;
      handler.postDelayed(releasePending, nextDelay());
    }
  }

  private boolean isBackgroundUpdate(AccessibilityEvent event) {
    int type = event.getEventType();
    if ((type & UPDATE_TYPES) == 0) {
      return false;
    }
    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            || (event.getWindowChanges() & WINDOW_TRANSITIONS) != 0)) {
      return false;
    }
    // 6A: updates from the window the user is interacting with are never held by the gate.
    // Without this carve-out, a foreground text/state/description change while touching
    // would still wait in the background slot below (non-structural content), even though
    // the 200 ms active deferral above is gone.
    if (interactionWindowId >= 0 && event.getWindowId() == interactionWindowId) return false;
    if (WindowCachePolicy.isNonStructuralContent(event)
        && (touching || SystemClock.uptimeMillis() - lastInput < INPUT_GRACE_MS)) return true;
    CharSequence sourcePackage = event.getPackageName();
    boolean background;
    if (sourcePackage != null
        && ("com.android.systemui".contentEquals(sourcePackage)
            || "miui.systemui.plugin".contentEquals(sourcePackage))
        && !"com.android.systemui".equals(interactionPackage)) {
      background = true;
    } else if (interactionWindowId >= 0 && event.getWindowId() >= 0) {
      background = event.getWindowId() != interactionWindowId;
    } else {
      background = interactionPackage != null && sourcePackage != null
          && !interactionPackage.contentEquals(sourcePackage);
    }
    return background
        && !(type == AccessibilityEvent.TYPE_VIEW_SCROLLED && inputResponse.test(event));
  }

  private long nextDelay() {
    long now = SystemClock.uptimeMillis();
    return Math.max(touching ? INPUT_GRACE_MS : 0,
        Math.max(lastDispatch + INTERVAL_MS - now, lastInput + INPUT_GRACE_MS - now));
  }

  private void releasePending() {
    releaseScheduled = false;
    if ((pendingForeground == null && pendingBackground == null) || touching) {
      return;
    }
    long delay = nextDelay();
    if (delay > 0) {
      releaseScheduled = true;
      handler.postDelayed(releasePending, delay);
      return;
    }
    AccessibilityEvent event;
    long since;
    if (pendingForeground != null) {
      event = pendingForeground;
      since = pendingForegroundSince;
      pendingForeground = null;
    } else {
      event = pendingBackground;
      since = pendingBackgroundSince;
      pendingBackground = null;
    }
    lastDispatch = SystemClock.uptimeMillis();
    try {
      if (interactionWindowId >= 0 && event.getWindowId() == interactionWindowId
          && !LatencyExperiments.Feature.INPUT_PRIORITY.enabled()) {
        EventProcessingStats.dropped(event);
        return;
      }
      EventProcessingStats.admitted(event, lastDispatch - since);
      receiver.accept(event, lastDispatch);
    } finally {
      event.recycle();
      if (pendingForeground != null || pendingBackground != null) {
        schedulePending();
      }
    }
  }

  public void clear() {
    handler.removeCallbacks(releasePending);
    releaseScheduled = false;
    TraversalTreeCache.resetChurnTracking();
    if (pendingForeground != null) {
      EventProcessingStats.dropped(pendingForeground);
      pendingForeground.recycle();
      pendingForeground = null;
    }
    if (pendingBackground != null) {
      EventProcessingStats.dropped(pendingBackground);
      pendingBackground.recycle();
      pendingBackground = null;
    }
    lastInteractiveScrollAt = 0;
    touching = false;
  }
}
