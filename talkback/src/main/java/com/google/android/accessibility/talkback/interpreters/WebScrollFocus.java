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
import android.text.TextUtils;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import java.util.ArrayDeque;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

final class WebScrollFocus {

  @VisibleForTesting static final int MAX_NODES_READ = 400;

  @VisibleForTesting static final int MAX_INDEX_READS = 100;

  @VisibleForTesting static final int MIN_CHILDREN_FOR_BINARY_SEARCH = 32;

  @VisibleForTesting static final long FLING_MS = 3000;

  private WebScrollFocus() {}

  static boolean isWebContent(@Nullable AccessibilityNodeInfoCompat node) {
    return WebInterfaceUtils.supportsWebActions(node) || Role.getRole(node) == Role.ROLE_WEB_VIEW;
  }

  static boolean scrollsOnlySideways(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return false;
    }
    boolean sideways = false;
    for (AccessibilityActionCompat action : node.getActionList()) {
      int id = action.getId();
      if (id == AccessibilityActionCompat.ACTION_SCROLL_UP.getId()
          || id == AccessibilityActionCompat.ACTION_SCROLL_DOWN.getId()) {
        return false;
      }
      sideways |=
          id == AccessibilityActionCompat.ACTION_SCROLL_LEFT.getId()
              || id == AccessibilityActionCompat.ACTION_SCROLL_RIGHT.getId();
    }
    return sideways;
  }

  static int outermostSidewaysStrip(
      List<AccessibilityNodeInfoCompat> focusAndAncestors, AccessibilityNodeInfoCompat root) {
    for (int i = focusAndAncestors.indexOf(root) - 1; i >= 0; i--) {
      if (scrollsOnlySideways(focusAndAncestors.get(i))) {
        return i;
      }
    }
    return -1;
  }

  static boolean isUserScroll(
      long scrollTime,
      long touchStartTime,
      long touchEndTime,
      boolean touching,
      long previousTouchStartTime,
      long previousTouchEndTime,
      long focusTime) {
    if (touchStartTime > scrollTime) {
      return isUserScroll(
          scrollTime, previousTouchStartTime, previousTouchEndTime, /* touching= */ false, focusTime);
    }
    return isUserScroll(scrollTime, touchStartTime, touchEndTime, touching, focusTime);
  }

  static boolean isUserScroll(
      long scrollTime, long touchStartTime, long touchEndTime, boolean touching, long focusTime) {
    if (touchStartTime < 0 || scrollTime < touchStartTime || focusTime >= touchStartTime) {
      return false;
    }
    return touching || (touchEndTime >= touchStartTime && scrollTime - touchEndTime < FLING_MS);
  }

  static boolean isOutside(Rect bounds, Rect viewport) {
    if (bounds.left == 0 && bounds.top == 0 && bounds.right == 0 && bounds.bottom == 0) {
      return false;
    }
    return !(Math.min(bounds.left, bounds.right) < viewport.right
        && Math.max(bounds.left, bounds.right) > viewport.left
        && Math.min(bounds.top, bounds.bottom) < viewport.bottom
        && Math.max(bounds.top, bounds.bottom) > viewport.top);
  }

  static Rect belowFixedStrip(Rect viewport, Rect strip) {
    Rect result = new Rect(viewport);
    Rect bounds = new Rect(strip);
    bounds.sort();
    int height = viewport.height();
    if (bounds.isEmpty()
        || height <= 0
        || bounds.top >= viewport.top + height / 3
        || bounds.bottom <= viewport.top
        || bounds.bottom > viewport.top + height / 2
        || bounds.left >= viewport.right
        || bounds.right <= viewport.left
        || bounds.width() * 2 < viewport.width()) {
      return result;
    }
    result.top = bounds.bottom;
    return result;
  }

  static boolean isUnderFixedStrip(Rect bounds, Rect viewport, Rect strip) {
    Rect below = belowFixedStrip(viewport, strip);
    if (below.top <= viewport.top) {
      return false;
    }
    Rect sortedStrip = new Rect(strip);
    sortedStrip.sort();
    if (Math.min(bounds.top, bounds.bottom) >= sortedStrip.top
        && Math.max(bounds.top, bounds.bottom) <= sortedStrip.bottom) {
      return false;
    }
    return isOutside(bounds, below);
  }

  static @Nullable AccessibilityNodeInfoCompat findItemInView(
      AccessibilityNodeInfoCompat root,
      @Nullable List<AccessibilityNodeInfoCompat> focusAndAncestors,
      Rect viewport,
      boolean forward) {
    Search search = new Search(root, focusAndAncestors, viewport, forward);
    while (!search.step()) {}
    return search.result();
  }

  static final class Search {
    private final AccessibilityNodeInfoCompat root;
    private final @Nullable List<AccessibilityNodeInfoCompat> focusAndAncestors;
    private final Rect viewport;
    private final boolean forward;
    private final Rect bounds = new Rect();

    private int nodesLeft = MAX_NODES_READ;
    private int indexReadsLeft = MAX_INDEX_READS;

    private @Nullable AccessibilityNodeInfoCompat partial;

    private @Nullable AccessibilityNodeInfoCompat result;
    private boolean done;

    private int level = 0;

    private final int rootIndex;

    private boolean findingIndex;

    private int indexScan;
    private int indexReadsAtLevelStart;

    private boolean rootSearched;

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    private static final class Frame {
      final AccessibilityNodeInfoCompat node;
      final int count;

      int next;

      int low;

      int high;

      Frame(AccessibilityNodeInfoCompat node, int count, int next) {
        this.node = node;
        this.count = count;
        this.next = next;
      }
    }

    Search(
        AccessibilityNodeInfoCompat root,
        @Nullable List<AccessibilityNodeInfoCompat> focusAndAncestors,
        Rect viewport,
        boolean forward) {
      this.root = root;
      this.focusAndAncestors = focusAndAncestors;
      this.viewport = new Rect(viewport);
      this.forward = forward;
      rootIndex = (focusAndAncestors == null) ? -1 : focusAndAncestors.indexOf(root);
      startLevel();
    }

    @Nullable AccessibilityNodeInfoCompat result() {
      return result;
    }

    void stop() {
      finish(partial);
    }

    boolean step() {
      while (!done) {
        if (findingIndex) {
          stepIndex();
          return done;
        }
        if (!frames.isEmpty()) {
          if (stepFrames()) {
            return done;
          }
          continue;
        }
        if (nodesLeft <= 0) {
          finish(partial);
        } else if (level < rootIndex) {
          level++;
          startLevel();
        } else if (!rootSearched) {
          if (partial != null) {
            finish(partial);
          } else {
            rootSearched = true;
            pushStart(root);
          }
        } else {
          finish(partial);
        }
      }
      return true;
    }

    private void finish(@Nullable AccessibilityNodeInfoCompat item) {
      result = item;
      done = true;
      frames.clear();
      findingIndex = false;
    }

    private void startLevel() {
      if (level < rootIndex) {
        findingIndex = true;
        indexScan = 0;
        indexReadsAtLevelStart = indexReadsLeft;
      } else {
        level = Math.max(level, rootIndex);
      }
    }

    private void stepIndex() {
      AccessibilityNodeInfoCompat node = focusAndAncestors.get(level);
      AccessibilityNodeInfoCompat parent = focusAndAncestors.get(level + 1);
      int count = parent.getChildCount();
      if (indexScan < count && indexReadsLeft > 0) {
        indexReadsLeft--;
        int i = indexScan++;
        if (node.equals(parent.getChild(i))) {
          findingIndex = false;
          frames.push(new Frame(parent, count, forward ? i + 1 : i - 1));
        }
        return;
      }
      findingIndex = false;
      int read = indexReadsAtLevelStart - indexReadsLeft;
      if (read >= count) {
        pushStart(parent);
        return;
      }
      Frame frame = new Frame(parent, count, 0);
      frame.low = forward ? read : 0;
      frame.high = count;
      frames.push(frame);
    }

    private void pushStart(AccessibilityNodeInfoCompat node) {
      int count = node.getChildCount();
      Frame frame = new Frame(node, count, forward ? 0 : count - 1);
      if (count > MIN_CHILDREN_FOR_BINARY_SEARCH) {
        frame.low = 0;
        frame.high = count;
      }
      frames.push(frame);
    }

    private boolean stepFrames() {
      Frame frame = frames.peek();
      if (frame.low < frame.high) {
        stepBinarySearch(frame);
        return true;
      }
      int i = frame.next;
      if (i < 0 || i >= frame.count) {
        frames.pop();
        return false;
      }
      if (nodesLeft-- <= 0) {
        finish(partial);
        return true;
      }
      frame.next += forward ? 1 : -1;
      AccessibilityNodeInfoCompat child = frame.node.getChild(i);
      if (child == null) {
        return true;
      }
      child.getBoundsInScreen(bounds);
      if (isOutside(bounds, viewport)) {
        return true;
      }
      if (isItem(child, bounds, viewport)) {
        if (isWhollyInside(bounds, viewport)) {
          finish(child);
          return true;
        }
        if (partial == null) {
          partial = child;
        }
      }
      pushStart(child);
      return true;
    }

    private void stepBinarySearch(Frame frame) {
      if (nodesLeft <= 0) {
        finish(partial);
        return;
      }
      int middle = (frame.low + frame.high) >>> 1;
      nodesLeft--;
      AccessibilityNodeInfoCompat child = frame.node.getChild(middle);
      boolean before = false;
      if (child != null) {
        child.getBoundsInScreen(bounds);
        before = forward ? isAbove(bounds, viewport) : !isBelow(bounds, viewport);
      }
      if (before) {
        frame.low = middle + 1;
      } else {
        frame.high = middle;
      }
      if (frame.low >= frame.high) {
        frame.next = forward ? frame.low : frame.high - 1;
      }
    }
  }

  private static boolean isAbove(Rect bounds, Rect viewport) {
    return isOutside(bounds, viewport) && Math.max(bounds.top, bounds.bottom) <= viewport.top;
  }

  private static boolean isBelow(Rect bounds, Rect viewport) {
    return isOutside(bounds, viewport) && Math.min(bounds.top, bounds.bottom) >= viewport.bottom;
  }

  @VisibleForTesting
  static boolean isWhollyInside(Rect bounds, Rect viewport) {
    return bounds.top > viewport.top
        && bounds.bottom < viewport.bottom
        && bounds.left >= viewport.left
        && bounds.right <= viewport.right;
  }

  private static boolean isItem(AccessibilityNodeInfoCompat node, Rect bounds, Rect viewport) {
    if (Role.getRole(node) == Role.ROLE_WEB_VIEW) {
      return false;
    }
    Rect visible = new Rect(bounds);
    visible.sort();
    if (!visible.intersect(viewport)) {
      return false;
    }
    if (node.getChildCount() > 0 && visible.height() * 4 > viewport.height() * 3) {
      return false;
    }
    return node.isScreenReaderFocusable()
        || AccessibilityNodeInfoUtils.isActionableForAccessibility(node)
        || !TextUtils.isEmpty(AccessibilityNodeInfoUtils.getNodeText(node));
  }
}
