package com.google.android.accessibility.talkback.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityNode;
import com.google.android.accessibility.utils.DiagnosticOverlayController;
import com.google.android.accessibility.utils.DiagnosticOverlayUtils;
import com.google.android.accessibility.utils.DiagnosticOverlayUtils.DiagnosticType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

// TODO: (b/191913512) - add tests for this implementation
/** Implementation for log overlay tool */
public class DiagnosticOverlayControllerImpl implements DiagnosticOverlayController {

  private static final String TAG = "DiagnosticOverlayController";

  private boolean enabled;
  private @NonNull Context context;
  private @Nullable DiagnosticOverlay diagnosticOverlay;
  private @Nullable HighlightOverlay highlightOverlay;

  private @NonNull HashSet<AccessibilityNodeInfoCompat> traversedNodes = new HashSet<>();

  private @NonNull HashMap<Integer, ArrayList<AccessibilityNodeInfoCompat>>
      unfocusedIdToNode = new HashMap<>();

  private @NonNull HashSet<AccessibilityNode> refocusNodePath = new HashSet<>();

  private boolean collectNodes;

  public DiagnosticOverlayControllerImpl(Context context) {
    this.context = context;
    DiagnosticOverlayUtils.setDiagnosticOverlayController(this);
  }

  @VisibleForTesting
  public DiagnosticOverlayControllerImpl(
      Context context, DiagnosticOverlay diagnosticOverlay, HighlightOverlay highlightOverlay) {
    this.context = context;
    // instantiate overlay in case preference is toggled on when TalkBack starts
    this.diagnosticOverlay = diagnosticOverlay;
    this.highlightOverlay = highlightOverlay;
  }

  /** Receives and appends the category of {@link DiagnosticType} and related unfocusable nodes */
  @Override
  public void appendLog(@DiagnosticType Integer diagnosticInfo, AccessibilityNodeInfoCompat node) {
    if (!this.enabled || !collectNodes || (node == null)) {
      return;
    }

    /**
     * If node is appended from {@link TraversalStrategyUtils#searchFocus} then simply add to a
     * hashmap
     */
    if (diagnosticInfo == DiagnosticOverlayUtils.SEARCH_FOCUS_FAIL) {
      traversedNodes.add(AccessibilityNodeInfoCompat.obtain(node));
      return;
    }

    /**
     * If node is appended from {@link AccessibilityNodeInfoUtils#shouldFocusNode} then check to
     * see if node is also in {@code traversedNodes}. If so, add the node to collection to be
     * highlighted. If not, ignore the node since it wasn't a node affected by gesture swipe.
     */
    if (!traversedNodes.contains(node)) {
      return;
    }
    if (unfocusedIdToNode.get(diagnosticInfo) == null) {
      ArrayList<AccessibilityNodeInfoCompat> currentNodes = new ArrayList<>();
      currentNodes.add(AccessibilityNodeInfoCompat.obtain(node));
      unfocusedIdToNode.put(diagnosticInfo, currentNodes);
    } else {
      unfocusedIdToNode.get(diagnosticInfo).add(AccessibilityNodeInfoCompat.obtain(node));
    }
  }

  @Override
  public void appendLog(@DiagnosticType Integer diagnosticInfo, AccessibilityNode node) {
    if (!this.enabled || (node == null)) {
      return;
    }

    if (diagnosticInfo == DiagnosticOverlayUtils.REFOCUS_PATH) {
      refocusNodePath.add(node);
    }
  }

  /** Clears the highlight focused View after a focus-action. */
  public void clearHighlight() {
    if (!isHighlightOverlayEnabled()) {
      return;
    }
    highlightOverlay.clearHighlight();
  }

  /**
   * Checks if highlight node overlay is enabled.
   *
   * @return true if enabled.
   */
  public boolean isHighlightOverlayEnabled() {
    if (!enabled || highlightOverlay == null || diagnosticOverlay == null) {
      return false;
    }
    return true;
  }

  /**
   * Highlights focused View after a focus-action.
   *
   * @param focusedNode The view has the focused node.
   */
  public void highlightNodesOnScreen(AccessibilityNodeInfoCompat focusedNode) {
    if (!isHighlightOverlayEnabled() || (focusedNode == null)) {
      return;
    }
    /**
     * {@link TraversalStrategyUtils#searchFocus} will append focusedNode to list of unfocused nodes
     * since it is the last node to get traversed, so we must remove
     */
    traversedNodes.remove(focusedNode);
    for (ArrayList<AccessibilityNodeInfoCompat> nodes : unfocusedIdToNode.values()) {
      nodes.remove(focusedNode);
    }
    highlightOverlay.highlightNodesOnScreen(focusedNode, unfocusedIdToNode, refocusNodePath);
    clearCollectionNodes();
  }

  @SuppressLint("SwitchIntDef") // Only some event-types are filtered out.
  public void displayEvent(AccessibilityEvent event) {
    switch (event.getEventType()) {
      case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
          AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
          AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
          AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
          AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
          AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
          AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
        return;
      }
      default -> {
        if (diagnosticOverlay != null) {
          CharSequence text = AccessibilityEventUtils.toStringShort(event);
          diagnosticOverlay.displayText(text);
        }
      }
    }
  }

  public void setLogOverlayEnabled(boolean enabled) {
    /** Only enable if enabled state has changed to true and diagnostic overlay */
    if (enabled == this.enabled) {
      return;
    }
    if (enabled) {
      if (diagnosticOverlay == null) {
        diagnosticOverlay = new DiagnosticOverlay(context);
      }
      if (highlightOverlay == null) {
        highlightOverlay = new HighlightOverlay(context);
      }
    } else {
      if (diagnosticOverlay != null) {
        diagnosticOverlay.hide();
        diagnosticOverlay = null;
      }
      if (highlightOverlay != null) {
        highlightOverlay.clearHighlight();
        highlightOverlay = null;
      }
    }
    this.enabled = enabled;
  }

  public void setNodeCollectionEnabled(boolean collect) {
    if (collect) {
      clearCollectionNodes();
    }
    collectNodes = collect;
  }

  /** Clear nodes in collections. */
  private void clearCollectionNodes() {
    for (AccessibilityNodeInfoCompat node : traversedNodes) {
      node.recycle();
    }
    traversedNodes.clear();
    for (ArrayList<AccessibilityNodeInfoCompat> nodes : unfocusedIdToNode.values()) {
      for (AccessibilityNodeInfoCompat node : nodes) {
        node.recycle();
      }
    }
    unfocusedIdToNode.clear();
    refocusNodePath.clear();
  }

  @VisibleForTesting
  boolean isLogOverlayEnabled() {
    return this.enabled && diagnosticOverlay != null;
  }
}
