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

package com.google.android.accessibility.talkback.interpreters;

import android.graphics.Rect;
import android.os.SystemClock;
import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.ConcurrentNodeQueries;
import com.google.android.accessibility.utils.ConcurrentNodeQueries.ParentRead;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

final class WebFocusRecovery {

  @VisibleForTesting static final int MAX_ANCESTORS_READ = 128;

  /** The optional parallel check is limited to a short, already captured path. */
  private static final int MAX_READY_PATH_PARENTS = 16;
  private static final long READY_PATH_WAIT_MS = 80;

  @IntDef({RUNNING, FOUND, IN_VIEW, NOTHING_IN_VIEW, NOT_WEB_SCROLL})
  @Retention(RetentionPolicy.SOURCE)
  @interface Outcome {}

  static final int RUNNING = 0;

  static final int FOUND = 1;

  static final int IN_VIEW = 2;

  static final int NOTHING_IN_VIEW = 3;

  static final int NOT_WEB_SCROLL = 4;

  private final @Nullable AccessibilityNodeInfoCompat focus;
  private final @Nullable AccessibilityNodeInfoCompat lastFocused;
  private AccessibilityNodeInfoCompat scrolled;
  private final boolean leftBehind;
  private final boolean forward;

  private @Nullable AccessibilityNodeInfoCompat strip;

  private boolean stripRead;
  private final Rect stripBounds = new Rect();

  private final boolean focusMoved;
  private final Rect scrolledBounds = new Rect();
  private final Rect focusBounds = new Rect();

  private final List<AccessibilityNodeInfoCompat> focusAndAncestors = new ArrayList<>();

  private static final int WALK_NONE = 0;

  private static final int WALK_FROM_FOCUS = 1;

  private static final int WALK_FROM_SCROLLED = 2;

  private int walk = WALK_NONE;

  private @Nullable AccessibilityNodeInfoCompat walking;

  private int ancestorsRead;
  private @Nullable AccessibilityNodeInfoCompat webView;
  private boolean scrolledHoldsFocus;

  private boolean scrolledInWebView;

  private WebScrollFocus.@Nullable Search search;
  private List<AccessibilityNodeInfoCompat> readyPath = Collections.emptyList();
  private int readySourceIndex;
  private boolean readyPathChecked;
  private @Outcome int outcome = RUNNING;
  private @Nullable AccessibilityNodeInfoCompat item;
  private final AtomicBoolean backgroundCancelled = new AtomicBoolean();
  private long readDeadline = Long.MAX_VALUE;

  /** Only node reads run on a worker; the caller still validates and applies the result. */
  @Nullable Future<Boolean> startInBackground(long deadline) {
    AccessibilityNodeInfoCompat anchor = focus != null ? focus
        : lastFocused != null ? lastFocused : scrolled;
    return startInBackground(deadline,
        read -> ConcurrentNodeQueries.readAlongsidePath(anchor, read));
  }

  @VisibleForTesting
  @Nullable Future<Boolean> startInBackground(
      long deadline, Function<Supplier<Boolean>, Future<Boolean>> submit) {
    Future<Boolean> work = submit.apply(() -> {
      readDeadline = deadline;
      if (backgroundCancelled.get() || Thread.currentThread().isInterrupted()) return false;
      // The strip is also kept by the interpreter. Refresh a worker-owned copy.
      if (strip != null) strip = AccessibilityNodeInfoCompat.obtain(strip);
      while (!backgroundCancelled.get() && !Thread.currentThread().isInterrupted()
          && SystemClock.uptimeMillis() < deadline) {
        if (step()) return !backgroundCancelled.get() && !Thread.currentThread().isInterrupted();
      }
      if (backgroundCancelled.get() || Thread.currentThread().isInterrupted()) return false;
      LogUtils.w("WebFocusRecovery", "Stopped the search for a web item in view at its time limit");
      stop();
      return true;
    });
    // A rejected worker keeps the original one-read-at-a-time fallback. The optional
    // parent batch must not introduce an 80 ms join on the main thread.
    if (work == null) readyPath = Collections.emptyList();
    return work;
  }

  void cancelBackground() {
    backgroundCancelled.set(true);
  }

  WebFocusRecovery(
      @Nullable AccessibilityNodeInfoCompat focus,
      @Nullable AccessibilityNodeInfoCompat lastFocused,
      AccessibilityNodeInfoCompat scrolled,
      boolean leftBehind,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat strip,
      boolean focusMoved) {
    this(focus, lastFocused, scrolled, leftBehind, forward, strip, focusMoved,
        Collections.emptyList());
  }

  WebFocusRecovery(
      @Nullable AccessibilityNodeInfoCompat focus,
      @Nullable AccessibilityNodeInfoCompat lastFocused,
      AccessibilityNodeInfoCompat scrolled,
      boolean leftBehind,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat strip,
      boolean focusMoved,
      List<AccessibilityNodeInfoCompat> readyPath) {
    this.strip = strip;
    this.focusMoved = focusMoved;
    this.focus = focus;
    this.lastFocused = (focus == null) ? lastFocused : null;
    this.scrolled = scrolled;
    this.leftBehind = leftBehind;
    this.forward = forward;
    scrolled.getBoundsInScreen(scrolledBounds);
    if (focus != null) {
      focus.getBoundsInScreen(focusBounds);
      startWalk(WALK_FROM_FOCUS, focus);
      selectReadyPath(readyPath);
    } else if (this.lastFocused != null) {
      startWalk(WALK_FROM_FOCUS, this.lastFocused);
    } else {
      startWalk(WALK_FROM_SCROLLED, scrolled);
    }
  }

  private void selectReadyPath(List<AccessibilityNodeInfoCompat> path) {
    if (path.isEmpty() || focus == null || !sameIdentity(path.get(0), focus)) return;
    int source = -1;
    for (int i = 0; i < path.size() && i <= MAX_READY_PATH_PARENTS; i++) {
      AccessibilityNodeInfoCompat node = path.get(i);
      if (node.getWindowId() != focus.getWindowId()) return;
      if (sameIdentity(node, scrolled)) source = i;
      if (isNativeWebView(node)) {
        if (i > 0 && source >= 0) {
          readyPath = new ArrayList<>(path.subList(0, i + 1));
          readyPath.set(0, focus);
          readySourceIndex = source;
        }
        return;
      }
    }
  }

  private static boolean isNativeWebView(AccessibilityNodeInfoCompat node) {
    return Role.getRole(node) == Role.ROLE_WEB_VIEW
        && !WebInterfaceUtils.supportsWebActions(node);
  }

  static boolean sameIdentity(
      AccessibilityNodeInfoCompat saved, AccessibilityNodeInfoCompat current) {
    if (!saved.equals(current) || saved.getWindowId() != current.getWindowId()) return false;
    String id = saved.getUniqueId();
    return id != null ? id.equals(current.getUniqueId())
        : current.getUniqueId() == null && isNativeWebView(saved) && isNativeWebView(current);
  }

  /** A native WebView and its own root document report the same physical viewport. */
  @VisibleForTesting
  static boolean sameNativeViewport(AccessibilityNodeInfoCompat first,
      AccessibilityNodeInfoCompat second, List<AccessibilityNodeInfoCompat> path) {
    for (int i = 0; i + 1 < path.size(); i++) {
      AccessibilityNodeInfoCompat document = path.get(i);
      AccessibilityNodeInfoCompat host = path.get(i + 1);
      if (isNativeWebView(host) && Role.getRole(document) == Role.ROLE_WEB_VIEW
          && "rootWebArea".contentEquals(String.valueOf(
              AccessibilityNodeInfoUtils.getChromeRole(document)))
          && ((sameIdentity(first, host) && sameIdentity(second, document))
              || (sameIdentity(second, host) && sameIdentity(first, document)))) return true;
    }
    return false;
  }

  @VisibleForTesting
  static List<AccessibilityNodeInfoCompat> verifiedAncestorNodes(
      List<AccessibilityNodeInfoCompat> saved,
      Map<AccessibilityNodeInfoCompat, ParentRead> reads) {
    List<AccessibilityNodeInfoCompat> current = new ArrayList<>();
    for (int i = 0; i + 1 < saved.size(); i++) {
      ParentRead read = reads.get(saved.get(i));
      if (read == null || !sameIdentity(saved.get(i), read.getChild())
          || !sameIdentity(saved.get(i + 1), read.getParent())
          || (i == 0 && !read.getChild().isAccessibilityFocused())) {
        return Collections.emptyList();
      }
      current.add(read.getChild());
      if (i + 2 == saved.size()) current.add(read.getParent());
    }
    return !current.isEmpty() && isNativeWebView(current.get(current.size() - 1))
        ? current : Collections.emptyList();
  }

  private void tryCurrentAncestors() {
    int last = readyPath.size() - 1;
    Map<AccessibilityNodeInfoCompat, ParentRead> reads =
        ConcurrentNodeQueries.refreshParentsForRecovery(
            readyPath.subList(0, last), readyPathWaitMs(readDeadline, SystemClock.uptimeMillis()));
    List<AccessibilityNodeInfoCompat> current = verifiedAncestorNodes(readyPath, reads);
    if (current.isEmpty()) return;
    focusAndAncestors.addAll(current);
    current.get(0).getBoundsInScreen(focusBounds);
    scrolled = current.get(readySourceIndex);
    scrolled.getBoundsInScreen(scrolledBounds);
    scrolledHoldsFocus = true;
    webView = current.get(current.size() - 1);
    walking = null;
  }

  @VisibleForTesting
  static long readyPathWaitMs(long deadline, long now) {
    return Math.min(READY_PATH_WAIT_MS, Math.max(0, deadline - now));
  }

  /** Returns what the recovery found, or {@link #RUNNING}. */
  @Outcome
  int outcome() {
    return outcome;
  }

  @Nullable AccessibilityNodeInfoCompat item() {
    return item;
  }

  void stop() {
    if (outcome != RUNNING) {
      return;
    }
    if (search != null) {
      search.stop();
      end(search.result());
    } else {
      outcome = NOTHING_IN_VIEW;
    }
  }

  boolean step() {
    if (outcome != RUNNING) {
      return true;
    }
    if (!readyPathChecked && !readyPath.isEmpty()) {
      readyPathChecked = true;
      tryCurrentAncestors();
    } else if (search != null) {
      if (search.step()) {
        end(search.result());
      }
    } else if (strip != null && !stripRead) {
      stripRead = true;
      if (strip.refresh() && strip.isVisibleToUser()) {
        strip.getBoundsInScreen(stripBounds);
      } else {
        strip = null;
      }
    } else if (walking != null) {
      stepWalk();
    } else {
      decide();
    }
    return outcome != RUNNING;
  }

  private void end(@Nullable AccessibilityNodeInfoCompat found) {
    item = found;
    outcome = (found != null) ? FOUND : NOTHING_IN_VIEW;
  }

  private void startWalk(int walk, AccessibilityNodeInfoCompat from) {
    this.walk = walk;
    walking = from;
    ancestorsRead = 0;
  }

  private void stepWalk() {
    AccessibilityNodeInfoCompat node = walking;
    if (walk == WALK_FROM_SCROLLED) {
      if (node.equals(webView)) {
        scrolledInWebView = true;
        walking = null;
        return;
      }
    } else {
      if (focusAndAncestors.size() >= MAX_ANCESTORS_READ || focusAndAncestors.contains(node)) {
        walking = null;
        return;
      }
      focusAndAncestors.add(node);
      scrolledHoldsFocus |= node.equals(scrolled);
    }
    if (ancestorsRead >= MAX_ANCESTORS_READ) {
      walking = null;
      return;
    }
    ancestorsRead++;
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (webView == null
        && Role.getRole(node) == Role.ROLE_WEB_VIEW
        && Role.getRole(parent) != Role.ROLE_WEB_VIEW) {
      webView = node;
      if (walk == WALK_FROM_SCROLLED) {
        scrolledInWebView = true;
        walking = null;
        return;
      }
      if (scrolledHoldsFocus
          && WebScrollFocus.isWebContent(scrolled)
          && !WebInterfaceUtils.supportsWebActions(node)) {
        // The web source is already on this path. Native ancestors above its WebView
        // cannot change the search; keep reading them only for a native scroll source.
        walking = null;
        return;
      }
    }
    walking = parent;
  }

  private void decide() {
    if (focus == null && walk == WALK_FROM_FOCUS && !(scrolledHoldsFocus && webView != null)) {
      focusAndAncestors.clear();
      webView = null;
      startWalk(WALK_FROM_SCROLLED, scrolled);
      return;
    }
    if (webView == null) {
      outcome = NOT_WEB_SCROLL;
      return;
    }
    Rect viewport = new Rect();
    webView.getBoundsInScreen(viewport);
    if (viewport.isEmpty()) {
      outcome = NOT_WEB_SCROLL;
      return;
    }
    if (focus == null) {
      if (!scrolledBounds.isEmpty() && !viewport.intersect(scrolledBounds)) {
        outcome = NOT_WEB_SCROLL;
        return;
      }
      startSearch(scrolled, belowStrip(viewport));
      return;
    }
    if (scrolledHoldsFocus) {
      if (!scrolledBounds.isEmpty() && !viewport.intersect(scrolledBounds)) {
        outcome = NOT_WEB_SCROLL;
        return;
      }
    } else if (!WebScrollFocus.isWebContent(scrolled)) {
      outcome = NOT_WEB_SCROLL;
      return;
    } else if (!scrolled.equals(webView) && !scrolledInWebView) {
      if (walk != WALK_FROM_SCROLLED) {
        startWalk(WALK_FROM_SCROLLED, scrolled);
        return;
      }
      outcome = NOT_WEB_SCROLL;
      return;
    }
    boolean underStrip =
        strip != null
            && focusMoved
            && !focusAndAncestors.contains(strip)
            && WebScrollFocus.isUnderFixedStrip(focusBounds, viewport, stripBounds);
    if (!leftBehind && !underStrip && !WebScrollFocus.isOutside(focusBounds, viewport)) {
      outcome = IN_VIEW;
      return;
    }

    AccessibilityNodeInfoCompat searchRoot = scrolled;
    if (!WebScrollFocus.isWebContent(scrolled)) {
      searchRoot = webView;
    } else if (!scrolledHoldsFocus
        && !scrolledBounds.isEmpty()
        && !viewport.intersect(scrolledBounds)) {
      outcome = NOT_WEB_SCROLL;
      return;
    }
    startSearch(searchRoot, belowStrip(viewport));
  }

  private Rect belowStrip(Rect viewport) {
    return (strip == null) ? viewport : WebScrollFocus.belowFixedStrip(viewport, stripBounds);
  }

  private void startSearch(AccessibilityNodeInfoCompat searchRoot, Rect viewport) {
    @Nullable List<AccessibilityNodeInfoCompat> searchFrom =
        focusAndAncestors.isEmpty() ? null : focusAndAncestors;
    int strip = WebScrollFocus.outermostSidewaysStrip(focusAndAncestors, searchRoot);
    if (strip >= 0) {
      searchFrom = forward ? focusAndAncestors.subList(strip, focusAndAncestors.size()) : null;
    }
    search = new WebScrollFocus.Search(searchRoot, searchFrom, viewport, forward);
  }
}
