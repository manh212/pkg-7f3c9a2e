package com.google.android.accessibility.talkback.focusmanagement;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A data class to represent the navigation result of logical navigation. */
class NavigationResult {
  /** The type of the navigation result. */
  public enum Type {
    EMPTY, // No element found.
    NATIVE_ELEMENT, // A native element found, calls #getNode to return the native element node.
    WEB_ELEMENT, // A web element found, it should skip the navigation and return success.
    EXISTING_FOCUS, // An existing focus found, it should skip the navigation and return success.
    REACH_EDGE, // An edge found, it should skip the navigation and return failure.
    EXCEPTION, // General exception cases, it should skip the navigation and return failure.
  }

  private final Type type;
  private final @Nullable AccessibilityNodeInfoCompat node;
  private int linkIndex;

  private NavigationResult(Type type, @Nullable AccessibilityNodeInfoCompat node) {
    this.type = type;
    this.node = node;
    this.linkIndex = -1;
  }

  /** Convenient method to create an instance for the certain type without the node. */
  public static NavigationResult create(Type type) {
    if (type == Type.NATIVE_ELEMENT) {
      throw new IllegalArgumentException("create native type without node");
    }
    return new NavigationResult(type, null);
  }

  /**
   * Convenient method to create an instance for the native element, return empty if null node.
   */
  public static NavigationResult create(@Nullable AccessibilityNodeInfoCompat node) {
    return new NavigationResult((node == null ? Type.EMPTY : Type.NATIVE_ELEMENT), node);
  }

  /** Gets the native element node. */
  public @Nullable AccessibilityNodeInfoCompat getNode() {
    if (shouldSkipNavigation()) {
      throw new IllegalStateException("getNode for skipped type: " + type);
    }
    return node;
  }

  public boolean isEmpty() {
    return Type.EMPTY.equals(type);
  }

  public boolean isWebElement() {
    return Type.WEB_ELEMENT.equals(type);
  }

  public boolean isFocusAvailable() {
    return Type.NATIVE_ELEMENT.equals(type) || isWebElement() || Type.EXISTING_FOCUS.equals(type);
  }

  /** Returns true if it should skip the navigation immediately. */
  public boolean shouldSkipNavigation() {
    return Type.EXISTING_FOCUS.equals(type)
        || isWebElement()
        || Type.REACH_EDGE.equals(type)
        || Type.EXCEPTION.equals(type);
  }

  public void setLinkIndex(int linkIndex) {
    this.linkIndex = linkIndex;
  }

  public int getLinkIndex() {
    return linkIndex;
  }
}
