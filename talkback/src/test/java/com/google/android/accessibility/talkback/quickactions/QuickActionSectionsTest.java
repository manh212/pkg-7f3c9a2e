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
import static org.junit.Assert.assertNull;

import android.content.Context;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.calls.CallSettingsFragment;
import com.google.android.accessibility.talkback.preference.base.HelpCenterGroupFragment;
import com.google.android.accessibility.talkback.preference.base.TextToSpeechSettingsFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment;
import com.google.android.accessibility.talkback.update.UpdatesFragment;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The main-screen row to section mapping behind the quick actions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QuickActionSectionsTest {

  private Context context;
  private PreferenceScreen mainScreen;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    mainScreen = new PreferenceManager(context).createPreferenceScreen(context);
  }

  private Preference row(String key, Class<?> fragmentClass) {
    Preference preference = new Preference(context);
    preference.setKey(key);
    if (fragmentClass != null) {
      preference.setFragment(fragmentClass.getName());
    }
    mainScreen.addPreference(preference);
    return preference;
  }

  @Test
  public void voiceRow_mapsToVoice() {
    Preference preference = row("pref_tts_settings_key", TextToSpeechSettingsFragment.class);

    assertEquals(
        QuickActionSections.Section.VOICE,
        QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void callsRow_mapsToCalls() {
    Preference preference = row("pref_call_settings", CallSettingsFragment.class);

    assertEquals(
        QuickActionSections.Section.CALLS,
        QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void updatesRow_mapsToNull() {
    Preference preference = row("pref_updates_screen", UpdatesFragment.class);

    assertNull(QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void helpCenterRow_mapsToNull() {
    Preference preference = row("pref_settings_group_help_center_key", HelpCenterGroupFragment.class);

    assertNull(QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void rowNotInScreen_mapsToNull() {
    Preference preference = new Preference(context);
    preference.setKey("pref_tts_settings_key");
    preference.setFragment(TextToSpeechSettingsFragment.class.getName());

    assertNull(QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void rowWithoutFragment_mapsToNull() {
    Preference preference = row("pref_tts_settings_key", null);

    assertNull(QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void rowWithUnknownFragment_mapsToNull() {
    Preference preference = new Preference(context);
    preference.setKey("pref_tts_settings_key");
    preference.setFragment("com.example.NoSuchFragment");
    mainScreen.addPreference(preference);

    assertNull(QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void subScreenFragmentAsRow_stillMapsByFragment() {
    // A Voice sub-screen fragment is in the section map; the main screen never contains
    // such a row, so the membership check above is what keeps sub-screen rows out.
    Preference preference = row("pref_voice_profile_row", VoiceProfileFragment.class);

    assertEquals(
        QuickActionSections.Section.VOICE,
        QuickActionSections.forMainScreenRow(preference, mainScreen));
  }

  @Test
  public void nullInputs_mapToNull() {
    Preference preference = row("pref_tts_settings_key", TextToSpeechSettingsFragment.class);

    assertNull(QuickActionSections.forMainScreenRow(null, mainScreen));
    assertNull(QuickActionSections.forMainScreenRow(preference, null));
    assertNull(QuickActionSections.forMainScreenRow(null, null));
  }
}
