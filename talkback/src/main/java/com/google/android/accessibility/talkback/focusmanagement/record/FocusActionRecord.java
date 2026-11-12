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

package com.google.android.accessibility.talkback.focusmanagement.record;

import static com.google.android.accessibility.utils.AccessibilityNodeInfoUtils.toStringShort;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.record.NodePathDescription.Deadline;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A record of TalkBack performing {@link AccessibilityNodeInfoCompat#ACTION_ACCESSIBILITY_FOCUS}
 * action.
 */
public class FocusActionRecord {
  private static final String TAG = "FocusActionRecord";

  /**
   * Time when the accessibility focus action is performed. Initialized with
   * SystemClock.uptimeMillis().
   */
  private final long actionTime;
  /** Node being accessibility focused. */
  private final @NonNull AccessibilityNodeInfoCompat focusedNode;
  private final @NonNull NodePathDescription nodePathDescription;
  private final @Nullable PathWithSiblingContext siblingContext;
  /** Extra information about source focus action. */
  private final @NonNull FocusActionInfo extraInfo;
  /** Unique id for the node */
  private final String uniqueId;
  private final @Nullable EventId originEventId;
  private final boolean collectionItem;

  public @Nullable EventId getOriginEventId() {
    return originEventId;
  }

  public FocusActionRecord(
      @NonNull AccessibilityNodeInfoCompat focusedNode,
      @NonNull FocusActionInfo extraInfo,
      long actionTime,
      @Nullable EventId originEventId) {
    this.focusedNode = focusedNode;
    boolean isWeb = WebInterfaceUtils.supportsWebActions(focusedNode);
    if (isWeb) {
      nodePathDescription = NodePathDescription.obtainWithoutSiblingContext(focusedNode);
      siblingContext = new PathWithSiblingContext(nodePathDescription);
    } else {
      nodePathDescription = NodePathDescription.obtain(focusedNode);
      siblingContext = null;
    }
    collectionItem = !isWeb && nodePathDescription.hasCollectionItemAncestor();
    this.extraInfo = extraInfo;
    this.actionTime = actionTime;
    this.uniqueId = compoundPackageNameAndUniqueId(focusedNode);
    this.originEventId = originEventId;
  }

  private static @Nullable String compoundPackageNameAndUniqueId(
      @Nullable AccessibilityNodeInfoCompat nodeInfo) {
    if (nodeInfo == null) {
      return null;
    }
    String compoundId = nodeInfo.getUniqueId();
    if (compoundId != null) {
      compoundId = String.valueOf(nodeInfo.getPackageName()) + ':' + compoundId;
    }
    return compoundId;
  }

  /**
   * Constructs a FocusActionRecord.
   *
   * @param focusedNode Node being accessibility focused.
   * @param extraInfo Extra information defined by action performer.
   * @param actionTime Time when focus action happens. Got by {@link SystemClock#uptimeMillis()}.
   */
  public FocusActionRecord(
      @NonNull AccessibilityNodeInfoCompat focusedNode,
      @NonNull FocusActionInfo extraInfo,
      long actionTime) {
    this(focusedNode, extraInfo, actionTime, null);
  }

  /** Constructs FocusActionRecord. Used internally by {@link #copy(FocusActionRecord)}. */
  private FocusActionRecord(
      @NonNull AccessibilityNodeInfoCompat focusedNode,
      @NonNull NodePathDescription nodePathDescription,
      @Nullable PathWithSiblingContext siblingContext,
      @NonNull FocusActionInfo extraInfo,
      long actionTime,
      String uniqueId,
      @Nullable EventId originEventId,
      boolean collectionItem) {
    this.focusedNode = focusedNode;
    this.nodePathDescription = new NodePathDescription(nodePathDescription);
    this.siblingContext = siblingContext;
    this.extraInfo = extraInfo;
    this.actionTime = actionTime;
    this.uniqueId = uniqueId;
    this.originEventId = originEventId;
    this.collectionItem = collectionItem;
  }

  /** Returns an instance of the focused node. */
  public @NonNull AccessibilityNodeInfoCompat getFocusedNode() {
    return focusedNode;
  }

  public @NonNull NodePathDescription getNodePathDescription() {
    return getNodePathDescription(Deadline.afterSearchTimeLimit());
  }

  private @NonNull NodePathDescription getNodePathDescription(@NonNull Deadline deadline) {
    return (siblingContext == null) ? nodePathDescription : siblingContext.get(deadline);
  }

  public @NonNull NodePathDescription getAncestorIdentityPath() {
    return nodePathDescription;
  }

  /** Returns extra information of the focus action. */
  public @NonNull FocusActionInfo getExtraInfo() {
    return extraInfo;
  }

  /**
   * Returns the time when the accessibility focus action happens, which is initialized with {@link
   * SystemClock#uptimeMillis()}.
   */
  public long getActionTime() {
    return actionTime;
  }

  /** Returns the stored unique id which is created in the FocusActionRecord constructor. */
  public @Nullable String getUniqueId() {
    return uniqueId;
  }

  /** Returns a copied instance of another FocusActionRecord. */
  public static @Nullable FocusActionRecord copy(FocusActionRecord record) {
    if (record == null) {
      return null;
    }
    return new FocusActionRecord(
        record.focusedNode,
        record.nodePathDescription,
        record.siblingContext,
        record.extraInfo,
        record.actionTime,
        record.uniqueId,
        record.originEventId,
        record.collectionItem);
  }

  public @Nullable AccessibilityNodeInfoCompat findMatchingCollectionItem(
      AccessibilityNodeInfoCompat root) {
    return collectionItem
        ? nodePathDescription.findUniqueCollectionItem(
            root, focusedNode.getPackageName(), Deadline.afterSearchTimeLimit())
        : null;
  }

  /**
   * Returns the focusable node from the focus record with the following priority:
   *
   * <ol>
   *   <li>Unique id match
   *   <li>Focused node match
   *   <li>Node path score match
   * </ol>
   *
   * @param root The root node of the current tree.
   * @param focusFinder The focus finder to find the focusable node.
   * @param focusActionRecord The focus action record to get the focusable node from.
   * @return The focusable node from the focus record.
   */
  public static @Nullable AccessibilityNodeInfoCompat getFocusableNodeFromFocusRecord(
      @Nullable AccessibilityNodeInfoCompat root,
      @NonNull FocusFinder focusFinder,
      @NonNull FocusActionRecord focusActionRecord) {
    AccessibilityNodeInfoCompat lastFocusedNode = focusActionRecord.getFocusedNode();

    // 1. Unique id match
    @Nullable String uniqueId = focusActionRecord.getUniqueId();
    if (uniqueId != null) {
      AccessibilityNodeInfoCompat uniqueIdNode = null;
      if (lastFocusedNode.refresh()
          && uniqueId.equals(compoundPackageNameAndUniqueId(lastFocusedNode))) {
        uniqueIdNode = lastFocusedNode;
      } else {
        uniqueIdNode =
            AccessibilityNodeInfoUtils.searchFromBfs(
                root,
                new Filter<AccessibilityNodeInfoCompat>() {
                  @Override
                  public boolean accept(AccessibilityNodeInfoCompat node) {
                    return uniqueId.equals(compoundPackageNameAndUniqueId(node));
                  }
                });
      }
      if (uniqueIdNode != null && AccessibilityNodeInfoUtils.shouldFocusNode(uniqueIdNode)) {
        LogUtils.d(TAG, "getFocusableNodeFromFocusRecord from uniqueId=%s", uniqueId);
        return uniqueIdNode;
      }
    }
    // 2. Focused node match. Don't use cached node if it's in collection because collection reuse
    // views.
    if (lastFocusedNode.refresh()
        && AccessibilityNodeInfoUtils.shouldFocusNode(lastFocusedNode)
        && !focusActionRecord.collectionItem
        && !AccessibilityNodeInfoUtils.isInCollection(lastFocusedNode)) {
      LogUtils.d(
          TAG,
          "getFocusableNodeFromFocusRecord from focused node in focusActionRecord=%s",
          focusActionRecord);
      return lastFocusedNode;
    }
    // 3. Node path score match
    if (root == null) {
      return null;
    }
    Deadline deadline = Deadline.afterSearchTimeLimit();
    @Nullable NodePathDescription nodePath =
        focusActionRecord.getNodePathDescription(deadline); // Not owner
    if (nodePath == null) {
      return null;
    }
    @Nullable AccessibilityNodeInfoCompat nodeAtSamePosition =
        nodePath.findNodeToRefocus(root, focusFinder, deadline);
    if (focusActionRecord.collectionItem
        && (nodeAtSamePosition == null || !nodePath.matchesLeafText(nodeAtSamePosition))) {
      AccessibilityNodeInfoCompat matchingItem =
          nodePath.findUniqueCollectionItem(root, lastFocusedNode.getPackageName(), deadline);
      if (matchingItem != null) return matchingItem;
    }
    if ((nodeAtSamePosition != null)
        && AccessibilityNodeInfoUtils.shouldFocusNode(nodeAtSamePosition)) {
      LogUtils.d(
          TAG, "getFocusableNodeFromFocusRecord from nodeAtSamePosition in nodePath=%s", nodePath);
      return nodeAtSamePosition;
    }
    return null;
  }

  private static final class PathWithSiblingContext {
    private final @NonNull NodePathDescription withoutSiblingContext;
    private @Nullable NodePathDescription completed;

    PathWithSiblingContext(@NonNull NodePathDescription withoutSiblingContext) {
      this.withoutSiblingContext = withoutSiblingContext;
    }

    synchronized @NonNull NodePathDescription get(@NonNull Deadline deadline) {
      if (completed == null) {
        try {
          completed = withoutSiblingContext.withSiblingContext(deadline);
        } catch (RuntimeException e) {
          LogUtils.w(TAG, "Cannot read the siblings of the focus path: %s", e);
          completed = withoutSiblingContext;
        }
      }
      return completed;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(actionTime, focusedNode, nodePathDescription, extraInfo);
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof FocusActionRecord)) {
      return false;
    }
    FocusActionRecord otherRecord = (FocusActionRecord) other;
    return (focusedNode.equals(otherRecord.focusedNode))
        && (nodePathDescription.equals(otherRecord.nodePathDescription))
        && (extraInfo.equals(otherRecord.extraInfo))
        && (actionTime == otherRecord.actionTime)
        && Objects.equals(uniqueId, otherRecord.uniqueId);
  }

  public boolean focusedNodeEquals(AccessibilityNodeInfoCompat targetNode) {
    if (focusedNode == null || targetNode == null) {
      return false;
    }

    // Unique id (if it exists) dominates the focus record comparison.
    String targetUniqueId = compoundPackageNameAndUniqueId(targetNode);
    if (!Objects.equals(uniqueId, targetUniqueId)) {
      return false;
    } else if (uniqueId != null) {
      return true;
    }

    return (focusedNode == targetNode) || focusedNode.equals(targetNode);
  }

  @Override
  public String toString() {
    return "FocusActionRecord: \n    "
        + "node="
        + toStringShort(focusedNode)
        + "\n    "
        + "time="
        + actionTime
        + "\n    "
        + "extraInfo="
        + extraInfo.toString()
        + "\n    "
        + "uniqueId="
        + uniqueId;
  }
}
