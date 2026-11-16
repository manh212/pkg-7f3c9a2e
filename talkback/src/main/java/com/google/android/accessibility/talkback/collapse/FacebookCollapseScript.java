/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.collapse;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Facebook comments: the comment's outer frame is clickable and focusable but has no label of
 * its own; the content is spread over child nodes (avatar, author, time, body) and every action
 * (reply, reactions, like, demote) is a child button. The frame stays the single stop, the label
 * is composed as author, time, body, and the child buttons become actions.
 *
 * <p>The body text and the node that can be tapped for it are two different nodes in the real
 * tree: the text sits on a plain child with no flags at all, wrapped by a clickable shell with
 * no text. Fields are therefore resolved by content, not by clickability: the body field is the
 * node carrying the text (also the text-selection target), while the shell is the node the
 * synthesized body action lands on.
 *
 * <p>The action blocks (reply, reactions, like, demote) are not always siblings of the header:
 * in one recorded shape they sit one level higher, as siblings of the header group inside a
 * shared container, where the Like block's long instruction label would win a whole-item
 * longest-label search and be spoken as the body. The body search is therefore anchored to the
 * lowest common ancestor of the avatar, author and timestamp — a subtree that only ever holds
 * header content — and prefers the subtree of the header's clickable, long-clickable shell
 * that wraps the body text.
 *
 * <p>Some comment frames carry no clickable or focusable flags at all — the recorded replies
 * with an attached photo are shaped like that. This script opts in to those frames; the engine
 * then only accepts them as direct children of the scrollable comment list, and a double-tap
 * lands on the body shell instead of the frame. A comment that is only a photo still does not
 * match: it has no author, time or body text to compose a label from.
 *
 * <p>The action cap covers the heaviest recorded combination: a photo comment with a follow
 * button and a reactions block reaches eleven actions after de-duplication.
 */
final class FacebookCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.facebook.katana";
  private static final String FIELD_AUTHOR = "author";
  private static final String FIELD_TIME = "time";
  private static final String FIELD_BODY = "body";
  private static final int MAX_LABEL_DEPTH = 12;
  private static final CollapseScript FEED_SCRIPT = new FacebookFeedCollapseScript();

  FacebookCollapseScript() {
    super(PACKAGE_NAME, Pattern.COMPOSITE_ROOT, /* maxActions= */ 12);
  }

  @Override
  public CollapseScript fallbackScript() {
    return FEED_SCRIPT;
  }

  /**
   * The full contents of Facebook's comment tray, in the tray's own order, as recorded from
   * the live app: the seven reactions, then the action row. The Vietnamese labels are the
   * recorded ground truth; the English ones are the tray's standard English wording. The
   * scanned Like and Reply actions are absorbed into the set; everything in it activates
   * through the tray (see {@code CollapsedItemActions}).
   */
  private static final TrayCatalog TRAY_CATALOG =
      new TrayCatalog(
          new TrayCatalog.Entry[] {
            new TrayCatalog.Entry("Thích", "Like", true, true),
            new TrayCatalog.Entry("Yêu thích", "Love", true, false),
            new TrayCatalog.Entry("Thương thương", "Care", true, false),
            new TrayCatalog.Entry("Haha", "Haha", true, false),
            new TrayCatalog.Entry("Wow", "Wow", true, false),
            new TrayCatalog.Entry("Buồn", "Sad", true, false),
            new TrayCatalog.Entry("Phẫn nộ", "Angry", true, false),
            new TrayCatalog.Entry("Chia sẻ bình luận", "Share comment", false, false),
            new TrayCatalog.Entry("Báo cáo bình luận", "Report comment", false, false),
            new TrayCatalog.Entry("Trả lời", "Reply", false, true),
            new TrayCatalog.Entry("Sao chép", "Copy", true, false),
            new TrayCatalog.Entry("Ẩn bình luận", "Hide comment", false, false),
          });

  @Override
  public @Nullable TrayCatalog trayCatalog() {
    return TRAY_CATALOG;
  }

  /** The item's content fields, resolved once and shared by every hook of one item match. */
  private static final class ResolvedFields {
    final @Nullable AccessibilityNodeInfoCompat avatar;
    final @Nullable AccessibilityNodeInfoCompat author;
    final @Nullable AccessibilityNodeInfoCompat time;
    final @Nullable AccessibilityNodeInfoCompat body;

    ResolvedFields(ItemScan scan) {
      avatar = avatarNode(scan);
      author = authorNode(scan, avatar);
      time = timeNode(scan, avatar, author);
      body = bodyNode(scan, avatar, author, time);
    }
  }

  private @Nullable ItemScan resolvedScan;
  private @Nullable ResolvedFields resolvedFields;

  private ResolvedFields fieldsFor(ItemScan scan) {
    if (resolvedFields == null || resolvedScan != scan) {
      resolvedScan = scan;
      resolvedFields = new ResolvedFields(scan);
    }
    return resolvedFields;
  }

  @Override
  public @Nullable String selectionFieldId() {
    return FIELD_BODY;
  }

  @Override
  public boolean allowsUnflaggedRepresentative() {
    return true;
  }

  @Override
  public @Nullable String activationAnchorFieldId() {
    return FIELD_BODY;
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    if (FacebookFeedCollapseScript.isFeedItem(scan)) {
      return null;
    }
    ResolvedFields resolved = fieldsFor(scan);
    AccessibilityNodeInfoCompat author = resolved.author;
    AccessibilityNodeInfoCompat time = resolved.time;
    AccessibilityNodeInfoCompat body = resolved.body;
    if (body == null || time == null || author == null) {
      return null;
    }
    if (author.equals(time) || author.equals(body) || time.equals(body)) {
      // One node filling two fields means a field is really absent — with no timestamp, the
      // body itself is the first focusable, labelled, non-clickable node the timestamp
      // selector can find. Fail safe instead of composing a label with a repeated field.
      return null;
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field(FIELD_AUTHOR, author, ItemScan.spokenLabel(author)));
    fields.add(new Field(FIELD_TIME, time, ItemScan.spokenLabel(time)));
    fields.add(new Field(FIELD_BODY, body, ItemScan.spokenLabel(body)));
    return fields;
  }

  @Override
  protected CharSequence actionLabel(
      AccessibilityNodeInfoCompat node, CharSequence spokenLabel) {
    // Some buttons carry a long instruction-style description ("Like button. Double-tap and
    // hold...") next to a short text ("Like"); the short text is the usable action name.
    CharSequence text = node.getText();
    CharSequence description = node.getContentDescription();
    if (!TextUtils.isEmpty(text)
        && !TextUtils.isEmpty(description)
        && text.length() < description.length()) {
      return text;
    }
    if (TextUtils.isEmpty(spokenLabel)) {
      // A clickable node with no label of its own can still be the tap target wrapping the
      // body text (the real tree splits the two); name its action after the text it wraps.
      CharSequence wrapped = longestDescendantLabel(node);
      if (!TextUtils.isEmpty(wrapped)) {
        return wrapped;
      }
    }
    return spokenLabel;
  }

  @Override
  protected boolean longClickTwin(AccessibilityNodeInfoCompat node, ItemScan scan) {
    // Long-pressing a bottom-row button (Like) opens the reaction picker; the body itself is
    // excluded so its own long-press behaviour is not shadowed. The body content node sits
    // inside its clickable shell, so the shell precedes it in reading order and is excluded
    // by the same comparison.
    if (!node.isLongClickable()) {
      return false;
    }
    // The attached-photo block is content, not a bottom-row action: its wrapper and the
    // image inside are both long-clickable and sit after the body in reading order, but
    // long-pressing them opens no picker, so they never earn a twin.
    if (isOrWrapsImage(node, scan)) {
      return false;
    }
    AccessibilityNodeInfoCompat body = fieldsFor(scan).body;
    return body != null && !node.equals(body) && scan.indexOf(node) > scan.indexOf(body);
  }

  @Override
  protected AccessibilityNodeInfoCompat actionTargetNode(
      AccessibilityNodeInfoCompat node, ItemScan scan) {
    // The bottom-row blocks (reply, like) wrap their button in a focusable frame that
    // carries the block's instruction label. The button inside is a Litho node: it exposes
    // no click action, and a simulated tap at its centre is accepted without doing
    // anything. The frame is the stop Facebook designed the press to land on, so the
    // action targets it instead — under strict conditions, so nothing else in the comment
    // changes target.
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return node;
    }
    ResolvedFields resolved = fieldsFor(scan);
    boolean retarget =
        !parent.equals(scan.itemRoot())
            && !parent.isClickable()
            && parent.isFocusable()
            && !TextUtils.isEmpty(ItemScan.spokenLabel(parent))
            && wrappedClickables(scan, parent) == 1
            && !parent.equals(resolved.author)
            && !parent.equals(resolved.time)
            && !parent.equals(resolved.body);
    if (!retarget) {
      parent.recycle();
      return node;
    }
    return parent;
  }

  /** The number of scanned clickables inside a node's subtree. */
  private static int wrappedClickables(
      ItemScan scan, AccessibilityNodeInfoCompat parent) {
    int wrappedClickables = 0;
    for (AccessibilityNodeInfoCompat clickable : scan.clickables()) {
      if (isWithin(clickable, parent)) {
        wrappedClickables++;
      }
    }
    return wrappedClickables;
  }

  /** Whether a node is an image or wraps one — the shape of an attached-photo block. */
  private static boolean isOrWrapsImage(AccessibilityNodeInfoCompat node, ItemScan scan) {
    if (isImage(node)) {
      return true;
    }
    for (AccessibilityNodeInfoCompat descendant : scan.descendants()) {
      if (isWithin(descendant, node) && isImage(descendant)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isImage(AccessibilityNodeInfoCompat node) {
    int role = Role.getRole(node);
    return role == Role.ROLE_IMAGE || role == Role.ROLE_IMAGE_BUTTON;
  }

  /** The avatar: the first clickable image button in the item. */
  private static @Nullable AccessibilityNodeInfoCompat avatarNode(ItemScan scan) {
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (Role.getRole(node) == Role.ROLE_IMAGE_BUTTON) {
        return node;
      }
    }
    return null;
  }

  /** The author name: the first labelled clickable after the avatar, skipping images. */
  private static @Nullable AccessibilityNodeInfoCompat authorNode(
      ItemScan scan, @Nullable AccessibilityNodeInfoCompat avatar) {
    if (avatar == null) {
      return null;
    }
    int avatarIndex = scan.indexOf(avatar);
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (scan.indexOf(node) <= avatarIndex) {
        continue;
      }
      if (Role.getRole(node) != Role.ROLE_IMAGE_BUTTON
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))) {
        return node;
      }
    }
    return null;
  }

  /**
   * The timestamp: a focusable, non-clickable, labelled node in the header cluster (the
   * subtree of the avatar/author common ancestor), so a look-alike wrapper from an action
   * block can never stand in for it.
   */
  private static @Nullable AccessibilityNodeInfoCompat timeNode(
      ItemScan scan,
      @Nullable AccessibilityNodeInfoCompat avatar,
      @Nullable AccessibilityNodeInfoCompat author) {
    if (avatar == null || author == null) {
      return null;
    }
    AccessibilityNodeInfoCompat cluster = lowestCommonAncestor(avatar, author);
    if (cluster == null) {
      return null;
    }
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!isWithin(node, cluster) || node.equals(author)) {
        continue;
      }
      if (node.isFocusable()
          && !node.isClickable()
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))) {
        recycleIfForeign(scan, cluster);
        return node;
      }
    }
    recycleIfForeign(scan, cluster);
    return null;
  }

  /**
   * The comment body: a content node inside the header region — the subtree of the lowest
   * common ancestor of avatar, author and timestamp, which never contains an action block in
   * either recorded nesting shape. The header's one clickable, long-clickable node is the
   * shell wrapping the body text, so the body is the longest spoken label in the shell's
   * subtree; without such a shell it is the longest spoken label in the region, excluding
   * the avatar, author and timestamp and anything above or below them. The tap target for
   * the body (its clickable shell) already appears among the item's clickables on its own.
   */
  private static @Nullable AccessibilityNodeInfoCompat bodyNode(
      ItemScan scan,
      @Nullable AccessibilityNodeInfoCompat avatar,
      @Nullable AccessibilityNodeInfoCompat author,
      @Nullable AccessibilityNodeInfoCompat time) {
    if (avatar == null || author == null || time == null) {
      return null;
    }
    AccessibilityNodeInfoCompat cluster = lowestCommonAncestor(avatar, author);
    AccessibilityNodeInfoCompat region =
        cluster == null ? null : lowestCommonAncestor(cluster, time);
    if (region == null) {
      recycleIfForeign(scan, cluster);
      return null;
    }
    AccessibilityNodeInfoCompat shell = null;
    int shells = 0;
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (isWithin(node, region) && node.isClickable() && node.isLongClickable()) {
        shell = node;
        shells++;
      }
    }
    AccessibilityNodeInfoCompat body = null;
    if (shells == 1) {
      body = longestLabelIn(scan, shell);
    } else {
      int longest = 0;
      for (AccessibilityNodeInfoCompat node : scan.descendants()) {
        if (!isWithin(node, region) || isFieldOrKin(node, avatar, author, time)) {
          continue;
        }
        CharSequence label = ItemScan.spokenLabel(node);
        if (!TextUtils.isEmpty(label) && label.length() > longest) {
          longest = label.length();
          body = node;
        }
      }
    }
    recycleIfForeign(scan, region);
    recycleIfForeign(scan, cluster);
    return body;
  }

  /** The node with the longest spoken label in a subtree, or null if it carries none. */
  private static @Nullable AccessibilityNodeInfoCompat longestLabelIn(
      ItemScan scan, AccessibilityNodeInfoCompat subtreeRoot) {
    AccessibilityNodeInfoCompat best = null;
    int longest = 0;
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!isWithin(node, subtreeRoot)) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(node);
      if (!TextUtils.isEmpty(label) && label.length() > longest) {
        longest = label.length();
        best = node;
      }
    }
    return best;
  }

  /** Whether a node is one of the resolved fields, or an ancestor or descendant of one. */
  private static boolean isFieldOrKin(
      AccessibilityNodeInfoCompat node,
      AccessibilityNodeInfoCompat avatar,
      AccessibilityNodeInfoCompat author,
      AccessibilityNodeInfoCompat time) {
    AccessibilityNodeInfoCompat[] fields = {avatar, author, time};
    for (AccessibilityNodeInfoCompat field : fields) {
      if (isWithin(node, field) || isWithin(field, node)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a node is the subtree root itself or one of its descendants. */
  static boolean isWithin(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat subtreeRoot) {
    AccessibilityNodeInfoCompat current = node;
    while (current != null) {
      if (current.equals(subtreeRoot)) {
        if (current != node) {
          current.recycle();
        }
        return true;
      }
      AccessibilityNodeInfoCompat parent = current.getParent();
      if (current != node) {
        current.recycle();
      }
      current = parent;
    }
    return false;
  }

  /** The lowest common ancestor of two nodes in the same tree, or null if there is none. */
  private static @Nullable AccessibilityNodeInfoCompat lowestCommonAncestor(
      AccessibilityNodeInfoCompat first, AccessibilityNodeInfoCompat second) {
    List<AccessibilityNodeInfoCompat> firstChain = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node = first; node != null; node = node.getParent()) {
      firstChain.add(node);
    }
    List<AccessibilityNodeInfoCompat> secondChain = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node = second; node != null; node = node.getParent()) {
      secondChain.add(node);
      if (firstChain.contains(node)) {
        recycleAllExcept(firstChain, first);
        recycleAllExcept(secondChain, second, node);
        return node;
      }
    }
    recycleAllExcept(firstChain, first);
    recycleAllExcept(secondChain, second);
    return null;
  }

  /** Recycles every node in {@code nodes} except those equal to one of {@code keep}. */
  private static void recycleAllExcept(
      List<AccessibilityNodeInfoCompat> nodes, AccessibilityNodeInfoCompat... keep) {
    for (AccessibilityNodeInfoCompat node : nodes) {
      boolean keepNode = false;
      for (AccessibilityNodeInfoCompat kept : keep) {
        if (node.equals(kept)) {
          keepNode = true;
          break;
        }
      }
      if (!keepNode) {
        node.recycle();
      }
    }
  }

  /**
   * Recycles a walked parent the scan does not own. A node the scan itself holds (the item
   * root or one of its descendants) is left alone.
   */
  private static void recycleIfForeign(
      ItemScan scan, @Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || node == scan.itemRoot()) {
      return;
    }
    for (AccessibilityNodeInfoCompat descendant : scan.descendants()) {
      if (node == descendant) {
        return;
      }
    }
    node.recycle();
  }

  /** The longest spoken label in a node's subtree, or empty if it carries none. */
  private static CharSequence longestDescendantLabel(AccessibilityNodeInfoCompat node) {
    return longestDescendantLabel(node, /* depth= */ 0);
  }

  private static CharSequence longestDescendantLabel(AccessibilityNodeInfoCompat node, int depth) {
    if (depth > MAX_LABEL_DEPTH) {
      return "";
    }
    CharSequence best = "";
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(child);
      if (label.length() > best.length()) {
        best = label;
      }
      CharSequence nested = longestDescendantLabel(child, depth + 1);
      if (nested.length() > best.length()) {
        best = nested;
      }
      child.recycle();
    }
    return best;
  }
}
