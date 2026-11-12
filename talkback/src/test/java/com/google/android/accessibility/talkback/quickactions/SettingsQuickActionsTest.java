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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.calls.CallSettingsFragment;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.preference.base.SoundFeedbackFragment;
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The settings quick actions menu: when it offers actions, and what it offers per section. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsQuickActionsTest {

  private Context context;
  private SharedPreferences prefs;
  private SettingsQuickActions actions;
  private AccessibilityNodeInfoCompat node;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    prefs
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), true)
        .commit();
    actions = new SettingsQuickActions(feedback -> true, /* analytics= */ null);
    node =
        AccessibilityNodeInfoCompat.wrap(
            android.view.accessibility.AccessibilityNodeInfo.obtain());
    node.setPackageName(context.getPackageName());
  }

  @After
  public void tearDown() {
    SettingsQuickActions.setCurrentSection(null);
  }

  @Test
  public void accept_falseWhenFeatureOff() {
    prefs
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), false)
        .commit();
    SettingsQuickActions.setCurrentSection(QuickActionSections.Section.VOICE);

    assertFalse(actions.accept(context, node));
  }

  @Test
  public void accept_falseWhenNoSection() {
    SettingsQuickActions.setCurrentSection(null);

    assertFalse(actions.accept(context, node));
  }

  @Test
  public void accept_falseForOtherPackage() {
    SettingsQuickActions.setCurrentSection(QuickActionSections.Section.VOICE);
    node.setPackageName("com.example.other");

    assertFalse(actions.accept(context, node));
  }

  @Test
  public void accept_trueForVoiceSection() {
    SettingsQuickActions.setCurrentSection(QuickActionSections.Section.VOICE);

    assertTrue(actions.accept(context, node));
  }

  @Test
  public void voiceActions_fourInOrderWithCurrentStateInTitles() {
    SettingsQuickActions.setCurrentSection(QuickActionSections.Section.VOICE);

    List<ContextMenuItem> items = actions.getMenuItemsForNode(context, node, false);

    assertEquals(4, items.size());
    String[] entries = context.getResources().getStringArray(R.array.pref_speech_stream_entries);
    String[] values = context.getResources().getStringArray(R.array.pref_speech_stream_values);
    assertEquals(
        context.getString(R.string.title_pref_talkback_speech_rate),
        items.get(0).getTitle().toString());
    assertEquals(
        context.getString(R.string.title_pref_speech_volume),
        items.get(1).getTitle().toString());
    assertEquals(
        context.getString(R.string.title_pref_speech_stream) + ", " + entries[0],
        items.get(2).getTitle().toString());
    assertEquals(
        context.getString(R.string.title_pref_voice_profiles)
            + ", "
            + VoiceProfileNames.nameOf(context, prefs, VoiceProfiles.activeId(prefs)),
        items.get(3).getTitle().toString());
    for (int i = 0; i < items.size(); i++) {
      assertEquals(SettingsQuickActions.ACTION_ID_BASE + i, items.get(i).getItemId());
      assertTrue(SettingsQuickActions.isQuickActionItem(items.get(i)));
    }
  }

  @Test
  public void toggleTitle_rebuiltAfterToggle() {
    SettingsQuickActions.setCurrentSection(QuickActionSections.Section.SOUND);

    List<ContextMenuItem> before = actions.getMenuItemsForNode(context, node, false);
    assertEquals(
        context.getString(R.string.title_pref_soundback)
            + ", "
            + context.getString(R.string.quick_action_state_on),
        before.get(0).getTitle().toString());
    assertTrue(before.get(0).onClickPerformed());

    assertFalse(prefs.getBoolean("pref_soundback", true));
    List<ContextMenuItem> after = actions.getMenuItemsForNode(context, node, false);
    assertEquals(
        context.getString(R.string.title_pref_soundback)
            + ", "
            + context.getString(R.string.quick_action_state_off),
        after.get(0).getTitle().toString());
  }

  @Test
  public void isQuickActionItem_onlyInsideReservedRange() {
    ContextMenuItem inside =
        com.google.android.accessibility.talkback.contextmenu.ContextMenu.createMenuItem(
            context, 1, SettingsQuickActions.ACTION_ID_BASE + 5, Menu.NONE, "Inside");
    ContextMenuItem below =
        com.google.android.accessibility.talkback.contextmenu.ContextMenu.createMenuItem(
            context, 1, SettingsQuickActions.ACTION_ID_BASE - 1, Menu.NONE, "Below");
    ContextMenuItem above =
        com.google.android.accessibility.talkback.contextmenu.ContextMenu.createMenuItem(
            context, 1, SettingsQuickActions.ACTION_ID_BASE + 0x100000, Menu.NONE, "Above");

    assertTrue(SettingsQuickActions.isQuickActionItem(inside));
    assertFalse(SettingsQuickActions.isQuickActionItem(below));
    assertFalse(SettingsQuickActions.isQuickActionItem(above));
  }

  @Test
  public void sections_coverAllTwentyNineActions() {
    int total = 0;
    for (QuickActionSections.Section section : QuickActionSections.Section.values()) {
      List<QuickAction> sectionActions = QuickActionSections.actionsFor(section);
      assertFalse("empty section: " + section, sectionActions.isEmpty());
      total += sectionActions.size();
    }

    assertEquals(29, total);
  }

  @Test
  public void forFragment_mapsSectionsAndLeavesMainScreenUnmapped() {
    assertEquals(
        QuickActionSections.Section.VOICE,
        QuickActionSections.forFragment(VoiceProfileFragment.class));
    assertEquals(
        QuickActionSections.Section.SOUND,
        QuickActionSections.forFragment(SoundFeedbackFragment.class));
    assertEquals(
        QuickActionSections.Section.CALLS,
        QuickActionSections.forFragment(CallSettingsFragment.class));
    assertEquals(null, QuickActionSections.forFragment(TalkBackPreferenceFragment.class));
  }
}
