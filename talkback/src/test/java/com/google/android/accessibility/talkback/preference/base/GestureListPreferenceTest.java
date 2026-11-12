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

package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.FormFactorUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Guards the paired title/value arrays behind the gesture-assignment list. A length mismatch
 * makes ActionItem.createItemsFromArray return empty and silently wipes a whole group: this
 * happened to the text-editing group when the text-editor value was added without its title,
 * hiding Dictate and Copy/Cut/Paste from gesture assignment.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
public class GestureListPreferenceTest {
  private static final int[][] ARRAY_PAIRS = {
    {R.array.shortcut_basic_navigation, R.array.shortcut_value_basic_navigation},
    {R.array.shortcut_system_actions, R.array.shortcut_value_system_actions},
    {R.array.shortcut_reading_control, R.array.shortcut_value_reading_control},
    {R.array.shortcut_menu_control, R.array.shortcut_value_menu_control},
    {R.array.shortcut_text_editing, R.array.shortcut_value_text_editing},
  };

  private Context context;
  private GestureListPreference.ActionItem[] actions;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    actions = new GestureListPreference(context, null).getActionItems();
  }

  @Test
  public void pairedShortcutArraysHaveEqualLengths() {
    for (int[] pair : ARRAY_PAIRS) {
      String[] texts = context.getResources().getStringArray(pair[0]);
      String[] values = context.getResources().getStringArray(pair[1]);
      assertTrue("title array must not be empty", texts.length > 0);
      assertEquals(
          "title/value array length mismatch wipes the whole group",
          texts.length,
          values.length);
    }
  }

  @Test
  public void textEditingGroupOffersEveryArrayValue() {
    String[] values =
        context.getResources().getStringArray(R.array.shortcut_value_text_editing);
    assertEquals(12, values.length);
    for (String value : values) {
      assertTrue("missing from gesture assignment: " + value, hasActionWithValue(value));
    }
  }

  @Test
  public void textEditingGroupOffersDictateAndTextEditor() {
    assertTrue(
        hasAction(
            context.getString(R.string.shortcut_value_dictate),
            context.getString(R.string.shortcut_dictate)));
    assertTrue(
        hasAction(
            context.getString(R.string.shortcut_value_text_editor),
            context.getString(R.string.text_editor_title)));
  }

  private boolean hasAction(String value, String text) {
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_ACTION_ITEM
          && value.equals(action.value)
          && text.equals(action.text)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasActionWithValue(String value) {
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_ACTION_ITEM
          && value.equals(action.value)) {
        return true;
      }
    }
    return false;
  }
}
