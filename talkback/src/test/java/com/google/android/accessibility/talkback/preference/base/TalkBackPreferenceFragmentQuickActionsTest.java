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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.quickactions.QuickActionSections;
import com.google.android.accessibility.talkback.quickactions.SettingsQuickActions;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Quick actions on the main screen follow the accessibility-focused row: focusing a
 * section-entry row publishes that section, focusing a non-section row clears it. Inside a
 * sub-screen no quick actions are offered, so no fragment publishes a section on resume.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TalkBackPreferenceFragmentQuickActionsTest {

  private ActivityController<FragmentActivity> controller;
  private FragmentActivity activity;
  private TalkBackPreferenceFragment fragment;
  private RecyclerView listView;

  @Before
  public void setUp() {
    controller = Robolectric.buildActivity(FragmentActivity.class);
    activity = controller.get();
    activity.setTheme(R.style.TalkBackSettings);
    FormFactorUtils.initialize(activity);
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(activity);
    prefs.edit().clear().commit();
    controller.setup();

    fragment = new TalkBackPreferenceFragment();
    activity
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(android.R.id.content, fragment)
        .commitNow();

    listView = fragment.getListView();
    assertNotNull(listView);
    listView.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(10000, View.MeasureSpec.EXACTLY));
    listView.layout(0, 0, 1080, 10000);
  }

  @After
  public void tearDown() {
    SettingsQuickActions.setCurrentSection(null);
    controller.pause().stop().destroy();
  }

  private int positionOf(String key) {
    PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) listView.getAdapter();
    assertNotNull(adapter);
    for (int i = 0; i < adapter.getItemCount(); i++) {
      if (key.equals(adapter.getItem(i).getKey())) {
        return i;
      }
    }
    return RecyclerView.NO_POSITION;
  }

  private View rowView(int position) {
    RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
    assertNotNull(holder);
    return holder.itemView;
  }

  private void sendFocusEvent(View rowView, int eventType) {
    View.AccessibilityDelegate delegate = listView.getAccessibilityDelegate();
    assertNotNull(delegate);
    AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
    assertTrue(delegate.onRequestSendAccessibilityEvent(listView, rowView, event));
  }

  @Test
  public void focusOnVoiceRow_publishesVoiceSection() {
    String voiceKey = activity.getString(R.string.pref_tts_settings_key);
    View voiceRow = rowView(positionOf(voiceKey));

    sendFocusEvent(voiceRow, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);

    assertEquals(QuickActionSections.Section.VOICE, SettingsQuickActions.currentSection());
  }

  @Test
  public void focusOnUpdatesRow_clearsSection() {
    String voiceKey = activity.getString(R.string.pref_tts_settings_key);
    View voiceRow = rowView(positionOf(voiceKey));
    sendFocusEvent(voiceRow, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    assertEquals(QuickActionSections.Section.VOICE, SettingsQuickActions.currentSection());

    View updatesRow = rowView(positionOf("pref_updates_screen"));
    sendFocusEvent(updatesRow, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);

    assertNull(SettingsQuickActions.currentSection());
  }

  @Test
  public void focusCleared_clearsSection() {
    String voiceKey = activity.getString(R.string.pref_tts_settings_key);
    View voiceRow = rowView(positionOf(voiceKey));
    sendFocusEvent(voiceRow, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    assertEquals(QuickActionSections.Section.VOICE, SettingsQuickActions.currentSection());

    sendFocusEvent(voiceRow, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);

    assertNull(SettingsQuickActions.currentSection());
  }
}
