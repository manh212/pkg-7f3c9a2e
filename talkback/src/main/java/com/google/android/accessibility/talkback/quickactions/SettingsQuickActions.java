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

package com.google.android.accessibility.talkback.quickactions;

import android.content.Context;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.collapse.CollapseEngine;
import com.google.android.accessibility.talkback.contextmenu.AbstractOnContextMenuItemClickListener;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.menurules.CustomActionMenu;
import com.google.android.accessibility.talkback.menurules.NodeMenu;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import androidx.annotation.Nullable;

/**
 * Generates the settings quick actions of the current section for the Actions reading control,
 * following the collapse precedent: each section's most-used settings are offered as actions on
 * the focused row, and a double-tap performs one right there. The items are rebuilt from the
 * current section and the current preference values on every request, so a title always carries
 * the value or state it will act on.
 *
 * <p>These items must only ever surface in the reading control: every other surface (context
 * menu, custom-action surfaces) filters them out via {@link #isQuickActionItem}.
 */
public class SettingsQuickActions implements NodeMenu {
  static final int ACTION_ID_BASE = 0x5e000000;
  private static final int ACTION_ID_RANGE = 0x100000;

  /** The section whose settings screen is open now, published by the settings fragments. */
  private static volatile @Nullable QuickActionSections.Section currentSection;

  /** Records the section whose settings screen is open now, or clears it when leaving. */
  public static void setCurrentSection(@Nullable QuickActionSections.Section section) {
    currentSection = section;
  }

  /** The section whose settings screen is open now, or null when none is. */
  public static @Nullable QuickActionSections.Section currentSection() {
    return currentSection;
  }

  public static boolean isQuickActionItem(ContextMenuItem item) {
    int itemId = item.getItemId();
    return itemId >= ACTION_ID_BASE && itemId < ACTION_ID_BASE + ACTION_ID_RANGE;
  }

  private final Pipeline.FeedbackReturner pipeline;
  private final TalkBackAnalytics analytics;

  public SettingsQuickActions(
      Pipeline.FeedbackReturner pipeline, TalkBackAnalytics analytics) {
    this.pipeline = pipeline;
    this.analytics = analytics;
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    if (node == null || !CollapseEngine.isEnabled(context)) {
      return false;
    }
    QuickActionSections.Section section = currentSection;
    if (section == null) {
      return false;
    }
    if (!TextUtils.equals(node.getPackageName(), context.getPackageName())) {
      return false;
    }
    return !QuickActionSections.actionsFor(section).isEmpty();
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> menuItems = new ArrayList<>();
    QuickActionSections.Section section = currentSection;
    if (section == null) {
      return menuItems;
    }
    List<QuickAction> actions = QuickActionSections.actionsFor(section);
    for (int i = 0; i < actions.size(); i++) {
      QuickAction action = actions.get(i);
      CharSequence title =
          QuickActionPerformer.displayTitle(
              context, action, SharedPreferencesUtils.getSharedPreferences(context));
      ContextMenuItem menuItem =
          ContextMenu.createMenuItem(
              context,
              CustomActionMenu.CUSTOM_ACTION_GROUP_ID,
              ACTION_ID_BASE + i,
              Menu.NONE,
              title);
      menuItem.setOnMenuItemClickListener(
          new QuickActionClickListener(context, node, action, pipeline, analytics));
      menuItem.setCheckable(false);
      menuItems.add(menuItem);
    }
    return menuItems;
  }

  private static class QuickActionClickListener extends AbstractOnContextMenuItemClickListener {
    private final Context context;
    private final QuickAction action;

    QuickActionClickListener(
        Context context,
        AccessibilityNodeInfoCompat node,
        QuickAction action,
        Pipeline.FeedbackReturner pipeline,
        TalkBackAnalytics analytics) {
      super(node, pipeline, analytics);
      this.context = context;
      this.action = action;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
      try {
        return QuickActionPerformer.perform(
            context, action, SharedPreferencesUtils.getSharedPreferences(context), pipeline,
            analytics);
      } finally {
        clear();
      }
    }
  }
}
