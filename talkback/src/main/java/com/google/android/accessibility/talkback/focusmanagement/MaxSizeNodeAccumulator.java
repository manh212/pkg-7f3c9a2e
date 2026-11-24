package com.google.android.accessibility.talkback.focusmanagement;

import android.graphics.Rect;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Filter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Filters nodes which are smaller than the temporary scrollable node. The accumulator will update
 * the temporary scrollable node once it finds a bigger scrollable node. Finally it can get the
 * node with maximum area in the node tree.
 */
class MaxSizeNodeAccumulator extends Filter<AccessibilityNodeInfoCompat> {
  final Filter<AccessibilityNodeInfoCompat> scrollableFilter;
  AccessibilityNodeInfoCompat maximumScrollableNode;
  int maximumSize;

  /**
   * @param node Initial node of the max size check.
   */
  MaxSizeNodeAccumulator(
      @Nullable AccessibilityNodeInfoCompat node,
      Filter<AccessibilityNodeInfoCompat> scrollableFilter) {
    this.scrollableFilter = scrollableFilter;
    if (node == null) {
      maximumSize = 0;
    } else {
      maximumScrollableNode = node;
      Rect nodeBounds = new Rect();
      maximumScrollableNode.getBoundsInScreen(nodeBounds);
      maximumSize = nodeBounds.width() * nodeBounds.height();
    }
  }

  @Override
  public boolean accept(AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return true;
    }

    Rect nodeBounds = new Rect();
    node.getBoundsInScreen(nodeBounds);
    int nodeSize = nodeBounds.width() * nodeBounds.height();
    if (nodeSize <= maximumSize) {
      return true;
    } else {
      // Update maximum scrollable node if the node is scrollable.
      if (scrollableFilter.accept(node)) {
        maximumScrollableNode = node;
        maximumSize = nodeSize;
      }
    }

    return false;
  }
}
