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
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Báo Mới ({@code com.epi}): collapses feed articles, sponsored cards, vertical videos, and the
 * Lịch Việt utility card into single focus stops while routing their secondary buttons into the
 * Actions reading control (swipe up/down).
 */
final class BaoMoiCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = BaoMoiAccessibility.PACKAGE_NAME;

  private static final String FIELD_TITLE = "title";
  private static final String FIELD_SPONSORED = "sponsored";
  private static final String FIELD_PUBLISHER = "publisher";
  private static final String FIELD_TIME = "time";
  private static final String FIELD_LIKES = "likes";
  private static final String FIELD_LUNAR = "lunar";
  private static final String FIELD_EVENT = "event";
  private static final String FIELD_ACTIVATION = "activation";

  BaoMoiCollapseScript() {
    super(PACKAGE_NAME, Pattern.COMPOSITE_ROOT, /* maxActions= */ 12);
  }

  @Override
  public int minCompositeClickables() {
    return 1;
  }

  @Override
  public boolean allowsUnflaggedRepresentative() {
    return true;
  }

  @Override
  public boolean allowsSeekControlDescendant() {
    return true;
  }

  @Override
  public @Nullable String activationAnchorFieldId() {
    return FIELD_ACTIVATION;
  }

  @Override
  public @Nullable String selectionFieldId() {
    return FIELD_TITLE;
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    String rootId = scan.itemRoot().getViewIdResourceName();
    if (BaoMoiAccessibility.ID_CONTENT_ROOT.equals(rootId)) {
      return resolveArticleFields(scan);
    }
    if (BaoMoiAccessibility.ID_CONTENT_ADS.equals(rootId)) {
      return resolveSponsoredFields(scan);
    }
    if (BaoMoiAccessibility.ID_CALENDAR_ROOT.equals(rootId)) {
      return resolveCalendarFields(scan);
    }
    if (findById(scan, BaoMoiAccessibility.ID_VIDEO_ROOT) != null) {
      return resolveVideoFields(scan);
    }
    return null;
  }

  private static @Nullable List<Field> resolveArticleFields(ItemScan scan) {
    AccessibilityNodeInfoCompat titleNode = findById(scan, BaoMoiAccessibility.ID_CONTENT_TITLE);
    String title = titleNode == null ? "" : BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(titleNode));
    if (TextUtils.isEmpty(title)) {
      return null;
    }
    List<Field> fields = new ArrayList<>(3);
    fields.add(new Field(FIELD_TITLE, titleNode, title));

    AccessibilityNodeInfoCompat pubNode = findById(scan, BaoMoiAccessibility.ID_CONTENT_PUBLISHER);
    if (pubNode != null) {
      String pub = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(pubNode));
      if (!BaoMoiAccessibility.isNoisePublisher(pub)) {
        fields.add(new Field(FIELD_PUBLISHER, pubNode, pub));
      }
    }

    AccessibilityNodeInfoCompat timeNode = findById(scan, BaoMoiAccessibility.ID_CONTENT_TIME);
    if (timeNode != null) {
      String time = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(timeNode));
      if (!TextUtils.isEmpty(time)) {
        fields.add(new Field(FIELD_TIME, timeNode, time));
      }
    }
    return fields;
  }

  private static @Nullable List<Field> resolveSponsoredFields(ItemScan scan) {
    AccessibilityNodeInfoCompat titleNode = findById(scan, BaoMoiAccessibility.ID_CONTENT_TITLE);
    String title = titleNode == null ? "" : BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(titleNode));
    if (TextUtils.isEmpty(title)) {
      return null;
    }
    List<Field> fields = new ArrayList<>(3);
    AccessibilityNodeInfoCompat sponsoredNode =
        findById(scan, BaoMoiAccessibility.ID_CONTENT_SPONSORED);
    if (sponsoredNode != null) {
      String sponsored = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(sponsoredNode));
      if (!TextUtils.isEmpty(sponsored)) {
        fields.add(new Field(FIELD_SPONSORED, sponsoredNode, sponsored));
      }
    }
    fields.add(new Field(FIELD_TITLE, titleNode, title));

    AccessibilityNodeInfoCompat pubNode = findById(scan, BaoMoiAccessibility.ID_CONTENT_PUBLISHER);
    if (pubNode != null) {
      String pub = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(pubNode));
      if (!BaoMoiAccessibility.isNoisePublisher(pub)) {
        fields.add(new Field(FIELD_PUBLISHER, pubNode, pub));
      }
    }
    return fields;
  }

  private static @Nullable List<Field> resolveVideoFields(ItemScan scan) {
    AccessibilityNodeInfoCompat titleNode = findById(scan, BaoMoiAccessibility.ID_VIDEO_TITLE);
    String title = titleNode == null ? "" : BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(titleNode));
    if (TextUtils.isEmpty(title)) {
      return null;
    }
    AccessibilityNodeInfoCompat activationNode =
        findById(scan, BaoMoiAccessibility.ID_VIDEO_SURFACE);
    if (activationNode == null) {
      activationNode = findById(scan, BaoMoiAccessibility.ID_VIDEO_ROOT);
    }
    if (activationNode == null) {
      return null;
    }

    List<Field> fields = new ArrayList<>(4);
    fields.add(new Field(FIELD_TITLE, titleNode, title));

    AccessibilityNodeInfoCompat pubNode = findById(scan, BaoMoiAccessibility.ID_VIDEO_PUBLIC_LOGO);
    if (pubNode != null) {
      String pub = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(pubNode));
      if (!TextUtils.isEmpty(pub)) {
        fields.add(new Field(FIELD_PUBLISHER, pubNode, pub));
      }
    }

    AccessibilityNodeInfoCompat likesNode =
        findById(scan, BaoMoiAccessibility.ID_VIDEO_UP_VOTE_COUNT);
    if (likesNode != null) {
      String likes = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(likesNode));
      if (!TextUtils.isEmpty(likes)) {
        fields.add(new Field(FIELD_LIKES, likesNode, likes + " lượt thích"));
      }
    }

    fields.add(new Field(FIELD_ACTIVATION, activationNode, ""));
    return fields;
  }

  private static @Nullable List<Field> resolveCalendarFields(ItemScan scan) {
    AccessibilityNodeInfoCompat thumbNode = findById(scan, BaoMoiAccessibility.ID_CALENDAR_THUMB);
    if (thumbNode == null) {
      return null;
    }
    AccessibilityNodeInfoCompat dowNode = findById(scan, BaoMoiAccessibility.ID_CALENDAR_DOW);
    AccessibilityNodeInfoCompat dayNode = findById(scan, BaoMoiAccessibility.ID_CALENDAR_DAY);
    AccessibilityNodeInfoCompat monthYearNode =
        findById(scan, BaoMoiAccessibility.ID_CALENDAR_MONTH_YEAR);

    StringBuilder solar = new StringBuilder();
    appendPart(solar, dowNode);
    appendPart(solar, dayNode);
    appendPart(solar, monthYearNode);
    if (solar.length() == 0) {
      return null;
    }

    List<Field> fields = new ArrayList<>(4);
    AccessibilityNodeInfoCompat titleTarget = dowNode != null ? dowNode : dayNode;
    fields.add(new Field(FIELD_TITLE, titleTarget != null ? titleTarget : thumbNode, solar.toString()));

    AccessibilityNodeInfoCompat lunarNode = findById(scan, BaoMoiAccessibility.ID_CALENDAR_LUNAR);
    if (lunarNode != null) {
      String lunar = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(lunarNode));
      if (!TextUtils.isEmpty(lunar)) {
        fields.add(new Field(FIELD_LUNAR, lunarNode, lunar));
      }
    }

    AccessibilityNodeInfoCompat eventNode = findById(scan, BaoMoiAccessibility.ID_CALENDAR_EVENT);
    if (eventNode != null) {
      String event = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(eventNode));
      if (!TextUtils.isEmpty(event)) {
        fields.add(new Field(FIELD_EVENT, eventNode, event));
      }
    }

    fields.add(new Field(FIELD_ACTIVATION, thumbNode, ""));
    return fields;
  }

  @Override
  protected CharSequence actionLabel(
      AccessibilityNodeInfoCompat node, CharSequence spokenLabel) {
    String id = node.getViewIdResourceName();
    if (id != null) {
      switch (id) {
        case BaoMoiAccessibility.ID_CONTENT_REMOVE:
          return "Tùy chọn tin";
        case BaoMoiAccessibility.ID_VIDEO_ROOT:
        case BaoMoiAccessibility.ID_VIDEO_SURFACE:
        case BaoMoiAccessibility.ID_VIDEO_TITLE:
        case BaoMoiAccessibility.ID_VIDEO_PUBLIC_ICON:
        case BaoMoiAccessibility.ID_VIDEO_AD_WRAPPER:
        case BaoMoiAccessibility.ID_CALENDAR_LUNAR:
        case BaoMoiAccessibility.ID_CALENDAR_EVENT:
          return "";
        case BaoMoiAccessibility.ID_VIDEO_UP_VOTE:
          return BaoMoiAccessibility.voteLabel(
              BaoMoiAccessibility.siblingText(node, BaoMoiAccessibility.ID_VIDEO_UP_VOTE_COUNT));
        case BaoMoiAccessibility.ID_VIDEO_DOWN_VOTE:
          return "Không thích";
        case BaoMoiAccessibility.ID_VIDEO_PUBLIC_LOGO:
          return BaoMoiAccessibility.publisherPageLabel(spokenLabel);
        case BaoMoiAccessibility.ID_VIDEO_FOLLOW:
          return BaoMoiAccessibility.cleanText(spokenLabel);
        case BaoMoiAccessibility.ID_VIDEO_SHARE:
          return "Chia sẻ";
        case BaoMoiAccessibility.ID_VIDEO_MENU:
          return "Tùy chọn khác";
        case BaoMoiAccessibility.ID_VIDEO_AD_CLOSE:
          return "Đóng quảng cáo";
        case BaoMoiAccessibility.ID_CALENDAR_THUMB:
          return "Mở Lịch Việt";
        default:
          break;
      }
    }
    String own = BaoMoiAccessibility.cleanText(spokenLabel);
    if (!TextUtils.isEmpty(own)) {
      return own;
    }
    return childActionButtonText(node);
  }

  private static String childActionButtonText(AccessibilityNodeInfoCompat container) {
    for (int i = 0; i < container.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = container.getChild(i);
      if (child != null
          && BaoMoiAccessibility.ID_BTN_ACTION_TV.equals(child.getViewIdResourceName())) {
        String text = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(child));
        if (!TextUtils.isEmpty(text)) {
          return text;
        }
      }
    }
    return "";
  }

  private static void appendPart(StringBuilder sb, @Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return;
    }
    String text = BaoMoiAccessibility.cleanText(ItemScan.spokenLabel(node));
    if (TextUtils.isEmpty(text)) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(", ");
    }
    sb.append(text);
  }

  private static @Nullable AccessibilityNodeInfoCompat findById(ItemScan scan, String viewId) {
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (viewId.equals(node.getViewIdResourceName())) {
        return node;
      }
    }
    return null;
  }
}
