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
import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

final class WebFocusRecovery {

  @VisibleForTesting static final int MAX_ANCESTORS_READ = 128;

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
  private final AccessibilityNodeInfoCompat scrolled;
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
  private @Outcome int outcome = RUNNING;
  private @Nullable AccessibilityNodeInfoCompat item;

  WebFocusRecovery(
      @Nullable AccessibilityNodeInfoCompat focus,
      @Nullable AccessibilityNodeInfoCompat lastFocused,
      AccessibilityNodeInfoCompat scrolled,
      boolean leftBehind,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat strip,
      boolean focusMoved) {
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
    } else if (this.lastFocused != null) {
      startWalk(WALK_FROM_FOCUS, this.lastFocused);
    } else {
      startWalk(WALK_FROM_SCROLLED, scrolled);
    }
  }

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
    if (search != null) {
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
