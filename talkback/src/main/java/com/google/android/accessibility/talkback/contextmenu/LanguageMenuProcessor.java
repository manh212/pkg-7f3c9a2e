/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.accessibility.talkback.contextmenu;

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import com.google.android.accessibility.talkback.ActorState;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.actor.LanguageActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Configure dynamic menu items on the language context menu. */
// TODO: Changes to NodeMenuRule.
public class LanguageMenuProcessor {

  private static @Nullable List<ContextMenuItem> getMenuItems(
      Context context, Pipeline.FeedbackReturner pipeline, ActorState actorState) {

    @Nullable Set<Locale> languagesAvailableRawSet =
        actorState.getLanguageState().getInstalledLanguages();

    if (languagesAvailableRawSet == null) {
      return null;
    }

    List<Locale> languagesAvailable = new ArrayList<>(languagesAvailableRawSet);
    // Sorts the raw set into the new available list. The display string alone does not
    // identify a locale: two installed locales can share one (Serbian Latin and Cyrillic),
    // so the locale tag breaks the tie instead of letting one collapse the other.
    languagesAvailable.sort(
        (o1, o2) -> {
          int byDisplay =
              LanguageActor.getLocaleString(context, o1)
                  .compareTo(LanguageActor.getLocaleString(context, o2));
          return byDisplay != 0
              ? byDisplay
              : o1.toLanguageTag().compareTo(o2.toLanguageTag());
        });

    List<ContextMenuItem> menuItems = new ArrayList<>();

    for (Locale locale : languagesAvailable) {
      ContextMenuItem val =
          ContextMenu.createMenuItem(
              context,
              R.id.group_language,
              Menu.NONE,
              Menu.NONE,
              LanguageActor.getLocaleString(context, locale));
      val.setOnMenuItemClickListener(
          new LanguageMenuItemClickListener(pipeline, locale));
      menuItems.add(val);
    }

    return menuItems;
  }

  /**
   * Populates a {@link Menu} with dynamic items relevant to the current global TalkBack state. This
   * is called when language menu is opened via a gesture or keyboard shortcut.
   *
   * @param context Global information about an application environment.
   * @param pipeline Uses for {@link LanguageMenuItemClickListener}.
   * @param actorState Uses to get installed languages.
   * @param menu The menu to populate.
   */
  public static void prepareLanguageMenu(
      Context context,
      Pipeline.FeedbackReturner pipeline,
      ActorState actorState,
      ContextMenu menu) {
    List<ContextMenuItem> menuItems = getMenuItems(context, pipeline, actorState);
    if (menuItems == null) {
      return;
    }
    for (ContextMenuItem menuItem : menuItems) {
      menu.add(menuItem);
    }
  }

  /**
   * Populates a {@link android.view.SubMenu} with dynamic items relevant to the current global
   * TalkBack state. This is called when language menu is opened via global context menu.
   *
   * @param subMenu The subMenu to populate.
   * @return {@code true} if successful, {@code false} otherwise.
   */
  public static boolean prepareLanguageSubMenu(
      Context context,
      Pipeline.FeedbackReturner pipeline,
      ActorState actorState,
      ListSubMenu subMenu) {
    List<ContextMenuItem> menuItems = getMenuItems(context, pipeline, actorState);
    if (menuItems == null) {
      return false;
    }
    for (ContextMenuItem menuItem : menuItems) {
      subMenu.add(menuItem);
    }
    return true;
  }

  private static class LanguageMenuItemClickListener implements OnContextMenuItemClickListener {
    private final Locale locale;
    private final Pipeline.FeedbackReturner pipeline;

    public LanguageMenuItemClickListener(Pipeline.FeedbackReturner pipeline, Locale locale) {
      this.locale = locale;
      this.pipeline = pipeline;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
      if (item == null) {
        return false;
      }
      pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.setLanguage(locale));
      return true;
    }
  }
}
