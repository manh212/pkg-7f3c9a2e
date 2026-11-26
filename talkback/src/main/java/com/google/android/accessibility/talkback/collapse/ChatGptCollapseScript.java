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
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * ChatGPT ({@code com.openai.chatgpt}): groups assistant replies by heading section and
 * collapses bullet content while keeping reply actions available in the Actions control.
 */
final class ChatGptCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.openai.chatgpt";
  private static final String[] THINKING_PREFIXES = {
    "đã xử lý trong",
    "đã suy nghĩ",
    "đang suy nghĩ",
    "thought for",
    "reasoned for",
    "thinking"
  };
  private static final String TEXT_VIEW_CLASS = "android.widget.TextView";

  ChatGptCollapseScript() {
    super(PACKAGE_NAME, Pattern.SECTIONED_ROOT, /* maxActions= */ 24);
  }

  @Override
  public boolean allowsUnflaggedRepresentative() {
    return true;
  }

  @Override
  public boolean requiresActivationAnchor() {
    return false;
  }


  @Override
  public boolean requiresActionsForSectionedRoot() {
    return false;
  }

  @Override
  protected @Nullable List<Section> resolveSections(ItemScan scan) {
    List<AccessibilityNodeInfoCompat> clickables = scan.clickables();
    List<AccessibilityNodeInfoCompat> descendants = scan.descendants();

    // Classify visible non-clickable content nodes in pre-order: headings, body TextViews, and
    // standalone bullet markers ("•").
    List<AccessibilityNodeInfoCompat> headings = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> bodyTexts = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> meaningfulTexts = new ArrayList<>();

    AccessibilityNodeInfoCompat activeHeadingContainer = null;
    for (AccessibilityNodeInfoCompat node : descendants) {
      if (!node.isVisibleToUser()) {
        continue;
      }
      if (isInsideAnyClickable(node, clickables)) {
        continue;
      }
      if (node.isHeading()) {
        if (activeHeadingContainer == null
            || !AccessibilityNodeInfoUtils.hasAncestor(node, activeHeadingContainer)) {
          headings.add(node);
          activeHeadingContainer = node;
        }
      }
      if (isTextView(node)) {
        String cleaned = cleanLabel(node.getText());
        if (!TextUtils.isEmpty(cleaned)) {
          bodyTexts.add(node);
          if (!isStandaloneBullet(cleaned)) {
            meaningfulTexts.add(node);
          }
        }
      }
    }

    // Fallback: pure clickable activity/thinking block (all visible text lives inside clickable
    // step rows). Summarize from the first and latest clickable step labels.
    if (meaningfulTexts.isEmpty()) {
      if (clickables.size() < 2) {
        return null;
      }
      List<String> stepLabels = new ArrayList<>();
      for (AccessibilityNodeInfoCompat clickable : clickables) {
        String label = cleanLabel(descendantLabel(clickable));
        if (!TextUtils.isEmpty(label)) {
          stepLabels.add(label);
        }
      }
      if (stepLabels.isEmpty()) {
        return null;
      }
      StringBuilder summary = new StringBuilder(stepLabels.get(0));
      if (stepLabels.size() > 1) {
        String latest = stepLabels.get(stepLabels.size() - 1);
        if (!latest.equals(stepLabels.get(0))) {
          summary.append(CompositorUtils.getSeparator()).append(latest);
        }
      }
      List<Section> result = new ArrayList<>(1);
      result.add(
          new Section(
              scan.itemRoot(),
              summary.toString(),
              clickables.get(0),
              descendants));
      return result;
    }

    // Decide whether this item is an assistant reply (even when partially scrolled so that the
    // bottom action bar or top reasoning button is temporarily off-screen). Single-text user
    // prompts, timestamps, and single attachment bubbles stay untouched.
    boolean hasHeading = !headings.isEmpty();
    boolean hasThinkingButton = hasThinkingClickable(clickables);
    boolean qualifies =
        clickables.size() >= 2
            || hasHeading
            || hasThinkingButton
            || meaningfulTexts.size() >= 2;
    if (!qualifies) {
      return null;
    }

    // Multi-step activity/thinking block without headings: summarize intro + latest status line.
    if (!hasHeading && meaningfulTexts.size() > 3 && hasTextualClickableSteps(clickables)) {
      AccessibilityNodeInfoCompat intro = meaningfulTexts.get(0);
      AccessibilityNodeInfoCompat latest = meaningfulTexts.get(meaningfulTexts.size() - 1);
      StringBuilder summary = new StringBuilder(cleanLabel(intro.getText()));
      if (!latest.equals(intro)) {
        summary.append(CompositorUtils.getSeparator()).append(cleanLabel(latest.getText()));
      }
      List<Section> result = new ArrayList<>(1);
      result.add(new Section(scan.itemRoot(), summary.toString(), intro, bodyTexts));
      return result;
    }

    // Reply WITHOUT headings: keep as a single clean focus stop with standalone bullets stripped.
    if (!hasHeading) {
      CharSequence label = joinTextNodes(meaningfulTexts);
      if (TextUtils.isEmpty(label)) {
        return null;
      }
      List<Section> result = new ArrayList<>(1);
      result.add(new Section(scan.itemRoot(), label, meaningfulTexts.get(0), bodyTexts));
      return result;
    }

    // Reply WITH headings: split into one focus stop per heading (plus an optional leading
    // preamble section for any body text appearing before the first heading).
    List<Section> sections = new ArrayList<>();
    AccessibilityNodeInfoCompat currentRep = null;
    AccessibilityNodeInfoCompat currentSelectionTarget = null;
    List<AccessibilityNodeInfoCompat> currentTextNodes = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> currentMembers = new ArrayList<>();
    AccessibilityNodeInfoCompat currentHeadingRoot = null;

    for (AccessibilityNodeInfoCompat node : descendants) {
      if (!node.isVisibleToUser() || isInsideAnyClickable(node, clickables)) {
        continue;
      }

      boolean isNewHeading =
          node.isHeading()
              && (currentHeadingRoot == null
                  || !AccessibilityNodeInfoUtils.hasAncestor(node, currentHeadingRoot));

      if (isNewHeading) {
        flushSection(
            sections, currentRep, currentSelectionTarget, currentTextNodes, currentMembers);
        currentRep = node;
        currentHeadingRoot = node;
        currentSelectionTarget = null;
        currentTextNodes = new ArrayList<>();
        currentMembers = new ArrayList<>();
        currentMembers.add(node);

        if (isTextView(node)) {
          String cleaned = cleanLabel(node.getText());
          if (!TextUtils.isEmpty(cleaned) && !isStandaloneBullet(cleaned)) {
            currentTextNodes.add(node);
            currentSelectionTarget = node;
          }
        }
        continue;
      }

      if (isTextView(node)) {
        String cleaned = cleanLabel(node.getText());
        if (TextUtils.isEmpty(cleaned)) {
          continue;
        }
        boolean bullet = isStandaloneBullet(cleaned);
        if (currentRep == null) {
          if (bullet) {
            // Defer preamble representative until we see the first real text node, while still
            // capturing the bullet so it stays suppressed.
            currentMembers.add(node);
            continue;
          }
          currentRep = node;
        }
        currentMembers.add(node);
        if (!bullet) {
          currentTextNodes.add(node);
          if (currentSelectionTarget == null) {
            currentSelectionTarget = node;
          }
        }
      }
    }

    flushSection(sections, currentRep, currentSelectionTarget, currentTextNodes, currentMembers);
    return sections.isEmpty() ? null : sections;
  }

  private static void flushSection(
      List<Section> out,
      @Nullable AccessibilityNodeInfoCompat rep,
      @Nullable AccessibilityNodeInfoCompat selectionTarget,
      List<AccessibilityNodeInfoCompat> textNodes,
      List<AccessibilityNodeInfoCompat> members) {
    if (rep == null || textNodes.isEmpty()) {
      return;
    }
    CharSequence label = joinTextNodes(textNodes);
    if (TextUtils.isEmpty(label)) {
      return;
    }
    out.add(
        new Section(
            rep,
            label,
            selectionTarget != null ? selectionTarget : textNodes.get(0),
            members));
  }

  private static CharSequence joinTextNodes(List<AccessibilityNodeInfoCompat> textNodes) {
    StringBuilder label = new StringBuilder();
    for (AccessibilityNodeInfoCompat textNode : textNodes) {
      String cleaned = cleanLabel(textNode.getText());
      if (TextUtils.isEmpty(cleaned) || isStandaloneBullet(cleaned)) {
        continue;
      }
      if (label.length() > 0) {
        label.append(CompositorUtils.getSeparator());
      }
      label.append(cleaned);
    }
    return label.toString();
  }

  @Override
  protected CharSequence actionLabel(
      AccessibilityNodeInfoCompat node, CharSequence spokenLabel) {
    String own = cleanLabel(spokenLabel);
    if (!TextUtils.isEmpty(own)) {
      return own;
    }
    return cleanLabel(descendantLabel(node));
  }

  private static boolean isTextView(AccessibilityNodeInfoCompat node) {
    CharSequence className = node.getClassName();
    return className != null && TEXT_VIEW_CLASS.contentEquals(className);
  }

  static boolean isStandaloneBullet(String cleaned) {
    if (cleaned.length() != 1) {
      return false;
    }
    char c = cleaned.charAt(0);
    return c == '•' || c == '·' || c == '◦' || c == '▪' || c == '–' || c == '-';
  }

  private boolean hasThinkingClickable(List<AccessibilityNodeInfoCompat> clickables) {
    for (AccessibilityNodeInfoCompat clickable : clickables) {
      CharSequence raw = actionLabel(clickable, ItemScan.spokenLabel(clickable));
      if (TextUtils.isEmpty(raw)) {
        continue;
      }
      String lower = raw.toString().toLowerCase(Locale.ROOT);
      for (String prefix : THINKING_PREFIXES) {
        if (lower.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isInsideAnyClickable(
      AccessibilityNodeInfoCompat node, List<AccessibilityNodeInfoCompat> clickables) {
    for (AccessibilityNodeInfoCompat clickable : clickables) {
      if (node.equals(clickable) || AccessibilityNodeInfoUtils.hasAncestor(node, clickable)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasTextualClickableSteps(List<AccessibilityNodeInfoCompat> clickables) {
    int textSteps = 0;
    for (AccessibilityNodeInfoCompat clickable : clickables) {
      if (hasVisibleTextViewDescendant(clickable)) {
        textSteps++;
      }
    }
    return textSteps >= 2;
  }

  private static boolean hasVisibleTextViewDescendant(AccessibilityNodeInfoCompat root) {
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null) {
        continue;
      }
      if (child.isVisibleToUser()
          && isTextView(child)
          && !TextUtils.isEmpty(cleanLabel(child.getText()))) {
        return true;
      }
      if (hasVisibleTextViewDescendant(child)) {
        return true;
      }
    }
    return false;
  }

  private static CharSequence descendantLabel(AccessibilityNodeInfoCompat root) {
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null || !child.isVisibleToUser()) {
        continue;
      }
      CharSequence own = ItemScan.spokenLabel(child);
      if (!TextUtils.isEmpty(own)) {
        return own;
      }
      CharSequence nested = descendantLabel(child);
      if (!TextUtils.isEmpty(nested)) {
        return nested;
      }
    }
    return "";
  }

  static String cleanLabel(@Nullable CharSequence raw) {
    if (TextUtils.isEmpty(raw)) {
      return "";
    }

    String cleaned = raw.toString().replace("\uFFFD", "");
    return cleaned.replaceAll("\\s{2,}", " ").trim();
  }
}
