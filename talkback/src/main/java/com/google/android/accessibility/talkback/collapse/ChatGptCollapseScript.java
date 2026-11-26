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
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

final class ChatGptCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.openai.chatgpt";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_STATUS = "status";
  private static final String TEXT_VIEW_CLASS = "android.widget.TextView";

  ChatGptCollapseScript() {
    super(PACKAGE_NAME, Pattern.COMPOSITE_ROOT, 24);
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
  public String selectionFieldId() {
    return FIELD_BODY;
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    List<AccessibilityNodeInfoCompat> clickables = scan.clickables();
    if (clickables.size() < 2) {
      return null;
    }

    List<AccessibilityNodeInfoCompat> bodyTexts = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!node.isVisibleToUser() || !isTextView(node)) {
        continue;
      }
      if (isInsideAnyClickable(node, clickables)) {
        continue;
      }
      String cleaned = cleanLabel(node.getText());
      if (!TextUtils.isEmpty(cleaned)) {
        bodyTexts.add(node);
      }
    }

    if (bodyTexts.isEmpty()) {

      List<Field> stepFields = new ArrayList<>();
      for (AccessibilityNodeInfoCompat clickable : clickables) {
        String label = cleanLabel(descendantLabel(clickable));
        if (!TextUtils.isEmpty(label)) {
          stepFields.add(
              new Field(stepFields.isEmpty() ? FIELD_BODY : FIELD_STATUS, clickable, label));
        }
      }
      if (stepFields.isEmpty()) {
        return null;
      }
      if (stepFields.size() <= 2) {
        return stepFields;
      }
      List<Field> compact = new ArrayList<>(2);
      compact.add(stepFields.get(0));
      compact.add(stepFields.get(stepFields.size() - 1));
      return compact;
    }

    if (bodyTexts.size() > 3 && hasTextualClickableSteps(clickables)) {
      List<Field> summary = new ArrayList<>(2);
      AccessibilityNodeInfoCompat intro = bodyTexts.get(0);
      summary.add(new Field(FIELD_BODY, intro, cleanLabel(intro.getText())));
      AccessibilityNodeInfoCompat latest = bodyTexts.get(bodyTexts.size() - 1);
      if (!latest.equals(intro)) {
        summary.add(new Field(FIELD_STATUS, latest, cleanLabel(latest.getText())));
      }
      return summary;
    }

    List<Field> fields = new ArrayList<>(bodyTexts.size());
    for (int i = 0; i < bodyTexts.size(); i++) {
      AccessibilityNodeInfoCompat textNode = bodyTexts.get(i);
      String id = (i == 0) ? FIELD_BODY : (FIELD_BODY + "_" + i);
      fields.add(new Field(id, textNode, cleanLabel(textNode.getText())));
    }
    return fields;
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
