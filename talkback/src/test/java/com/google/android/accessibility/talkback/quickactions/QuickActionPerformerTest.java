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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.dictation.DictationSettings;
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.talkback.preference.base.SettingsSearchNavigation;
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment;
import com.google.android.accessibility.talkback.soundthemes.SoundThemes;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.accessibility.utils.preference.BasePreferencesActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** One press per action: toggles flip and speak, cycles advance and speak, sliders focus. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QuickActionPerformerTest {

  /** Records the last feedback part instead of performing it. */
  private static final class RecordingReturner implements Pipeline.FeedbackReturner {
    Feedback.Part lastPart;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder part) {
      lastPart = part.build();
      return true;
    }
  }

  private Context context;
  private SharedPreferences prefs;
  private RecordingReturner returner;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    returner = new RecordingReturner();
  }

  private String spokenText() {
    return returner.lastPart.speech().text().toString();
  }

  private QuickAction soundFeedbackToggle() {
    return QuickAction.toggle(
        QuickActionSections.Section.SOUND,
        R.string.title_pref_soundback,
        "pref_soundback",
        true);
  }

  private QuickAction speechStreamCycle() {
    return QuickAction.cycleList(
        QuickActionSections.Section.VOICE,
        R.string.title_pref_speech_stream,
        FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY,
        R.array.pref_speech_stream_entries,
        R.array.pref_speech_stream_values,
        FailoverTextToSpeech.STREAM_VALUE_ACCESSIBILITY);
  }

  private QuickAction voiceProfileCycle() {
    return QuickAction.cycleSpecial(
        QuickActionSections.Section.VOICE,
        R.string.title_pref_voice_profiles,
        VoiceProfiles.PREF_ACTIVE,
        QuickActionPerformer.PERFORMER_VOICE_PROFILE);
  }

  @Test
  public void toggle_flipsPrefAndSpeaksNewState() {
    QuickAction action = soundFeedbackToggle();

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertFalse(prefs.getBoolean("pref_soundback", true));
    assertEquals(
        context.getString(R.string.title_pref_soundback)
            + ", "
            + context.getString(R.string.quick_action_state_off),
        spokenText());

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertTrue(prefs.getBoolean("pref_soundback", false));
    assertEquals(
        context.getString(R.string.title_pref_soundback)
            + ", "
            + context.getString(R.string.quick_action_state_on),
        spokenText());
  }

  @Test
  public void cycle_wrapsAndSpeaksNewEntry() {
    String[] values = context.getResources().getStringArray(R.array.pref_speech_stream_values);
    String[] entries =
        context.getResources().getStringArray(R.array.pref_speech_stream_entries);
    prefs
        .edit()
        .putString(FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY, values[values.length - 1])
        .commit();

    assertTrue(
        QuickActionPerformer.perform(context, speechStreamCycle(), prefs, returner, null));

    assertEquals(values[0], prefs.getString(FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY, null));
    assertEquals(
        context.getString(R.string.title_pref_speech_stream) + ", " + entries[0], spokenText());
  }

  @Test
  public void cycle_singleEntryListSpeaksCurrentWithoutWriting() {
    QuickAction themeCycle =
        QuickAction.cycleSpecial(
            QuickActionSections.Section.SOUND,
            R.string.title_pref_sound_themes,
            SoundThemes.PREF_ACTIVE,
            QuickActionPerformer.PERFORMER_SOUND_THEME);

    assertTrue(QuickActionPerformer.perform(context, themeCycle, prefs, returner, null));

    // Only Backtalk's theme is installed, so the cycle stays on it and writes nothing.
    assertFalse(prefs.contains(SoundThemes.PREF_ACTIVE));
    assertEquals(
        context.getString(R.string.title_pref_sound_themes)
            + ", "
            + context.getString(R.string.sound_theme_backtalk),
        spokenText());
  }

  @Test
  public void profileCycle_wrapsThroughProfiles() {
    String first =
        VoiceProfiles.create(prefs, "Work", "", "100", "1", "1", /* phrases= */ false);
    String second =
        VoiceProfiles.create(prefs, "Home", "", "100", "1", "1", /* phrases= */ false);
    assertNotEquals(first, second);
    QuickAction action = voiceProfileCycle();

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals(first, prefs.getString(VoiceProfiles.PREF_ACTIVE, null));
    assertEquals(VoiceProfileNames.nameOf(context, prefs, first), spokenText());

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals(second, prefs.getString(VoiceProfiles.PREF_ACTIVE, null));
    assertEquals(VoiceProfileNames.nameOf(context, prefs, second), spokenText());

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("", prefs.getString(VoiceProfiles.PREF_ACTIVE, null));
    assertEquals(VoiceProfileNames.nameOf(context, prefs, ""), spokenText());
  }

  @Test
  public void toggle_speakNotificationsOn() {
    QuickAction action =
        QuickAction.toggleSpecial(
            QuickActionSections.Section.NOTIFICATIONS,
            R.string.title_pref_speak_notifications,
            "pref_speak_notifications",
            true,
            QuickActionPerformer.PERFORMER_SPEAK_NOTIFICATIONS);
    prefs.edit().putBoolean("pref_speak_notifications", false).commit();

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));

    assertTrue(prefs.getBoolean("pref_speak_notifications", false));
    assertEquals(
        context.getString(R.string.title_pref_speak_notifications)
            + ", "
            + context.getString(R.string.quick_action_state_on),
        spokenText());
  }

  @Test
  public void cycle_typingConfirmationSkipsMissingMethodFour() {
    QuickAction action =
        QuickAction.cycleSpecial(
            QuickActionSections.Section.TYPING,
            R.string.title_pref_typing_confirmation,
            "pref_typing_confirmation",
            QuickActionPerformer.PERFORMER_TYPING_CONFIRMATION);
    prefs.edit().putString("pref_typing_confirmation", "3").commit();

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("5", prefs.getString("pref_typing_confirmation", null));
    assertEquals(
        context.getString(R.string.title_pref_typing_confirmation)
            + ", "
            + context.getString(R.string.value_type_confirmation_single_tap_to_type),
        spokenText());

    prefs.edit().putString("pref_typing_confirmation", "6").commit();
    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("0", prefs.getString("pref_typing_confirmation", null));
    assertEquals(
        context.getString(R.string.title_pref_typing_confirmation)
            + ", "
            + context.getString(R.string.value_type_confirmation_double_tap),
        spokenText());
  }

  @Test
  public void cycle_keyboardEchoAdvancesFromDefault() {
    QuickAction action =
        QuickAction.cycleSpecial(
            QuickActionSections.Section.TYPING,
            R.string.title_pref_echo_virtual_keyboard,
            "pref_echo_on_screen_keyboard",
            QuickActionPerformer.PERFORMER_KEYBOARD_ECHO);

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));

    // Unset reads as the default (characters and words, 0); the choices start at None (2).
    assertEquals("2", prefs.getString("pref_echo_on_screen_keyboard", null));
    assertEquals(
        context.getString(R.string.title_pref_echo_virtual_keyboard)
            + ", "
            + KeyboardEchoSettings.choiceName(context.getResources(), 2),
        spokenText());
  }

  @Test
  public void cycle_dictationRecognitionFollowsDialogOrder() {
    QuickAction action =
        QuickAction.cycleSpecial(
            QuickActionSections.Section.TYPING,
            R.string.title_pref_dictation,
            DictationSettings.PREF_RECOGNITION,
            QuickActionPerformer.PERFORMER_DICTATION_RECOGNITION);

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("zipformer", prefs.getString(DictationSettings.PREF_RECOGNITION, null));
    assertEquals(
        context.getString(R.string.title_pref_dictation)
            + ", "
            + context.getString(R.string.dictation_recognition_zipformer),
        spokenText());

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("rambler", prefs.getString(DictationSettings.PREF_RECOGNITION, null));
    assertEquals(
        context.getString(R.string.title_pref_dictation)
            + ", "
            + context.getString(R.string.dictation_compact_gemini),
        spokenText());

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals("android", prefs.getString(DictationSettings.PREF_RECOGNITION, null));
    assertEquals(
        context.getString(R.string.title_pref_dictation)
            + ", "
            + context.getString(R.string.dictation_compact_android),
        spokenText());
  }

  @Test
  public void focusSlider_opensDefaultProfilePageWithTargetKey() {
    QuickAction action =
        QuickAction.focusSlider(
            QuickActionSections.Section.VOICE,
            R.string.title_pref_talkback_speech_rate,
            QuickActionPerformer.PERFORMER_RATE_SLIDER);

    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));

    Intent intent =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    assertEquals(TalkBackPreferencesActivity.class.getName(), intent.getComponent().getClassName());
    assertEquals(
        VoiceProfileFragment.class.getName(),
        intent.getStringExtra(BasePreferencesActivity.FRAGMENT_NAME));
    assertEquals(
        VoiceProfileFragment.seekBarKey("", VoiceProfiles.RATE),
        intent
            .getBundleExtra(BasePreferencesActivity.FRAGMENT_ARGS)
            .getString(SettingsSearchNavigation.TARGET_KEY));
  }

  @Test
  public void open_unknownPerformerReturnsFalse() {
    QuickAction action =
        QuickAction.open(
            QuickActionSections.Section.MENU, R.string.title_pref_quick_settings_items, "unknown");

    assertFalse(QuickActionPerformer.perform(context, action, prefs, returner, null));
  }

  @Test
  public void open_quickSettingsFallsBackAndSpeaksWhenUnavailable() {
    QuickAction action =
        QuickAction.open(
            QuickActionSections.Section.MENU,
            R.string.title_pref_quick_settings_items,
            QuickActionPerformer.PERFORMER_QUICK_SETTINGS);

    // Meridian's quick settings are off and this context is not the service, so the
    // system panel cannot be opened either: the press speaks that it could not open.
    assertTrue(QuickActionPerformer.perform(context, action, prefs, returner, null));
    assertEquals(
        context.getString(R.string.title_pref_quick_settings_items)
            + ", "
            + context.getString(R.string.quick_action_open_failed),
        spokenText());
  }

  @Test
  public void displayTitle_toggleShowsStateAndCycleShowsEntry() {
    assertEquals(
        context.getString(R.string.title_pref_soundback)
            + ", "
            + context.getString(R.string.quick_action_state_on),
        QuickActionPerformer.displayTitle(context, soundFeedbackToggle(), prefs).toString());
    assertEquals(
        context.getString(R.string.title_pref_speech_stream)
            + ", "
            + context
                .getResources()
                .getStringArray(R.array.pref_speech_stream_entries)[0],
        QuickActionPerformer.displayTitle(context, speechStreamCycle(), prefs).toString());
  }
}
