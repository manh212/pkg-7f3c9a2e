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

import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiFragment;
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandSettingsFragment;
import com.google.android.accessibility.talkback.calls.CallSettings;
import com.google.android.accessibility.talkback.calls.CallSettingsFragment;
import com.google.android.accessibility.talkback.commands.AppGesturesFragment;
import com.google.android.accessibility.talkback.commands.GestureProfilesFragment;
import com.google.android.accessibility.talkback.dictation.DictationFragment;
import com.google.android.accessibility.talkback.dictation.DictationSettings;
import com.google.android.accessibility.talkback.directtouch.DirectTouchFragment;
import com.google.android.accessibility.talkback.directtouch.DirectTouchSettings;
import com.google.android.accessibility.talkback.gesture.extended.ExtendedGestures;
import com.google.android.accessibility.talkback.individualfeedback.IndividualFeedbackFragment;
import com.google.android.accessibility.talkback.monitor.FullScreenMonitorSettingsFragment;
import com.google.android.accessibility.talkback.notifications.NotificationAppFragment;
import com.google.android.accessibility.talkback.notifications.NotificationAppsFragment;
import com.google.android.accessibility.talkback.notifications.NotificationAppsWordsFragment;
import com.google.android.accessibility.talkback.notifications.NotificationAutomationFragment;
import com.google.android.accessibility.talkback.notifications.NotificationAutomationMonitorFragment;
import com.google.android.accessibility.talkback.notifications.NotificationHistoryFragment;
import com.google.android.accessibility.talkback.notifications.NotificationListSettingsFragment;
import com.google.android.accessibility.talkback.notifications.NotificationReadingFragment;
import com.google.android.accessibility.talkback.notifications.NotificationRuleFragment;
import com.google.android.accessibility.talkback.notifications.NotificationRulesFragment;
import com.google.android.accessibility.talkback.notifications.NotificationSchedulesFragment;
import com.google.android.accessibility.talkback.notifications.NotificationSkipFilterFragment;
import com.google.android.accessibility.talkback.notifications.NotificationStopFragment;
import com.google.android.accessibility.talkback.notifications.NotificationSummaryHistoryFragment;
import com.google.android.accessibility.talkback.notifications.NotificationWhenFragment;
import com.google.android.accessibility.talkback.notifications.NotificationWhenStopFragment;
import com.google.android.accessibility.talkback.notifications.NotificationWordsFragment;
import com.google.android.accessibility.talkback.notifications.NotificationsFragment;
import com.google.android.accessibility.talkback.notifications.SpokenSchedules;
import com.google.android.accessibility.talkback.preference.base.AdvancedGroupFragment;
import com.google.android.accessibility.talkback.preference.base.AdvancedSettingFragment;
import com.google.android.accessibility.talkback.preference.base.AiServicesFragment;
import com.google.android.accessibility.talkback.preference.base.AppsGroupFragment;
import com.google.android.accessibility.talkback.preference.base.AutomaticDescriptionsFragment;
import com.google.android.accessibility.talkback.preference.base.BackupResetFragment;
import com.google.android.accessibility.talkback.preference.base.ContextMenuFragment;
import com.google.android.accessibility.talkback.preference.base.CustomMenusFragment;
import com.google.android.accessibility.talkback.preference.base.DeveloperPrefFragment;
import com.google.android.accessibility.talkback.preference.base.ItemDetailsOrderFragment;
import com.google.android.accessibility.talkback.preference.base.MenuControlsGroupFragment;
import com.google.android.accessibility.talkback.preference.base.NavigationFeedbackFragment;
import com.google.android.accessibility.talkback.preference.base.NavigationGroupFragment;
import com.google.android.accessibility.talkback.preference.base.NavigationPrefFragment;
import com.google.android.accessibility.talkback.preference.base.OcrSettingsFragment;
import com.google.android.accessibility.talkback.preference.base.ReadingItemInfoFragment;
import com.google.android.accessibility.talkback.preference.base.ReadingStyleFragment;
import com.google.android.accessibility.talkback.preference.base.ReadingTextFragment;
import com.google.android.accessibility.talkback.preference.base.ScreenAnnouncementsFragment;
import com.google.android.accessibility.talkback.preference.base.ScreenInfoGroupFragment;
import com.google.android.accessibility.talkback.preference.base.SelectorGroupsFragment;
import com.google.android.accessibility.talkback.preference.base.SelectorMenuFragment;
import com.google.android.accessibility.talkback.preference.base.SmartContextGestureSettingsFragment;
import com.google.android.accessibility.talkback.preference.base.SoundAndVibrationFragment;
import com.google.android.accessibility.talkback.preference.base.SoundDuckingFragment;
import com.google.android.accessibility.talkback.preference.base.SoundFeatureSoundsFragment;
import com.google.android.accessibility.talkback.preference.base.SoundFeedbackFragment;
import com.google.android.accessibility.talkback.preference.base.SoundOutputFragment;
import com.google.android.accessibility.talkback.preference.base.SoundVibrationFragment;
import com.google.android.accessibility.talkback.preference.base.SpeechAdjustmentFragment;
import com.google.android.accessibility.talkback.preference.base.SpeechGroupFragment;
import com.google.android.accessibility.talkback.preference.base.TalkBackGestureShortcutPreferenceFragment;
import com.google.android.accessibility.talkback.preference.base.TalkBackKeyboardShortcutPreferenceFragment;
import com.google.android.accessibility.talkback.preference.base.TextFormattingDetailFragment;
import com.google.android.accessibility.talkback.preference.base.TextToSpeechSettingsFragment;
import com.google.android.accessibility.talkback.preference.base.ToolsGroupFragment;
import com.google.android.accessibility.talkback.preference.base.TouchActivationFragment;
import com.google.android.accessibility.talkback.preference.base.TypingEchoFragment;
import com.google.android.accessibility.talkback.preference.base.TypingGroupFragment;
import com.google.android.accessibility.talkback.preference.base.TypingInterruptFragment;
import com.google.android.accessibility.talkback.preference.base.TypingMethodPrefFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceEngineFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceFeatureVoicesFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceLanguageFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceProfilesFragment;
import com.google.android.accessibility.talkback.preference.base.VoicePronunciationFragment;
import com.google.android.accessibility.talkback.quicksettings.QuickSettingsItemsFragment;
import com.google.android.accessibility.talkback.screenrecognition.ScreenRecognitionSettingsFragment;
import com.google.android.accessibility.talkback.soundthemes.SoundThemes;
import com.google.android.accessibility.talkback.soundthemes.SoundThemesFragment;
import com.google.android.accessibility.talkback.speakerphone.SpeakerphoneSettings;
import com.google.android.accessibility.talkback.speechdictionary.SpeechDictionaryFragment;
import com.google.android.accessibility.talkback.status.StatusReadoutFragment;
import com.google.android.accessibility.talkback.subtitles.SubtitleSettings;
import com.google.android.accessibility.talkback.subtitles.SubtitleSettingsFragment;
import com.google.android.accessibility.talkback.taskassistant.TaskAssistantFragment;
import com.google.android.accessibility.talkback.timer.CountdownAlarmSettingsFragment;
import com.google.android.accessibility.talkback.timer.SpokenClockSettingsFragment;
import com.google.android.accessibility.talkback.timer.StopwatchSettingsFragment;
import com.google.android.accessibility.talkback.timer.TimerSettingsFragment;
import com.google.android.accessibility.talkback.translation.TranslationFragment;
import com.google.android.accessibility.talkback.volume.VolumeKeySettingsFragment;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The settings sections and their quick actions. A fragment class maps to the section its screen
 * belongs to; a sub-screen maps to its parent section. The main screen maps to no section. An
 * action list only ever grows: every action reuses the setting it stands for, and nothing here
 * resets or reseeds a current value.
 */
public final class QuickActionSections {

  public enum Section {
    VOICE,
    READING,
    NAVIGATION,
    MENU,
    SOUND,
    NOTIFICATIONS,
    TYPING,
    RECOGNITION,
    CALLS,
    TOOLS,
    ADVANCED,
  }

  private static final Map<Class<?>, Section> FRAGMENT_SECTIONS = new HashMap<>();

  static {
    map(
        Section.VOICE,
        TextToSpeechSettingsFragment.class,
        VoiceEngineFragment.class,
        VoiceProfilesFragment.class,
        VoiceProfileFragment.class,
        VoiceLanguageFragment.class,
        VoicePronunciationFragment.class,
        VoiceFeatureVoicesFragment.class,
        SpeechDictionaryFragment.class,
        SpeechAdjustmentFragment.class);
    map(Section.SOUND,
        SoundAndVibrationFragment.class,
        SoundFeedbackFragment.class,
        SoundOutputFragment.class,
        SoundDuckingFragment.class,
        SoundThemesFragment.class,
        IndividualFeedbackFragment.class,
        SoundVibrationFragment.class,
        SoundFeatureSoundsFragment.class);
    map(Section.CALLS, CallSettingsFragment.class);
    map(
        Section.READING,
        SpeechGroupFragment.class,
        ReadingStyleFragment.class,
        ReadingItemInfoFragment.class,
        ReadingTextFragment.class,
        ScreenAnnouncementsFragment.class,
        StatusReadoutFragment.class,
        NavigationFeedbackFragment.class,
        ItemDetailsOrderFragment.class,
        TextFormattingDetailFragment.class);
    map(
        Section.NAVIGATION,
        NavigationGroupFragment.class,
        NavigationPrefFragment.class,
        TouchActivationFragment.class,
        SmartContextGestureSettingsFragment.class,
        DirectTouchFragment.class,
        TalkBackGestureShortcutPreferenceFragment.class,
        TalkBackKeyboardShortcutPreferenceFragment.class,
        VolumeKeySettingsFragment.class,
        AppGesturesFragment.class,
        GestureProfilesFragment.class);
    map(
        Section.NOTIFICATIONS,
        NotificationsFragment.class,
        NotificationReadingFragment.class,
        NotificationAppsWordsFragment.class,
        NotificationAppsFragment.class,
        NotificationAppFragment.class,
        NotificationWordsFragment.class,
        NotificationSkipFilterFragment.class,
        NotificationRulesFragment.class,
        NotificationRuleFragment.class,
        NotificationWhenStopFragment.class,
        NotificationWhenFragment.class,
        NotificationStopFragment.class,
        NotificationSummaryHistoryFragment.class,
        NotificationSchedulesFragment.class,
        NotificationHistoryFragment.class,
        NotificationListSettingsFragment.class,
        NotificationAutomationMonitorFragment.class,
        NotificationAutomationFragment.class,
        FullScreenMonitorSettingsFragment.class);
    map(
        Section.TYPING,
        TypingGroupFragment.class,
        TypingMethodPrefFragment.class,
        DictationFragment.class,
        TypingEchoFragment.class,
        TypingInterruptFragment.class);
    map(
        Section.RECOGNITION,
        ScreenInfoGroupFragment.class,
        AutomaticDescriptionsFragment.class,
        AiServicesFragment.class,
        OnDeviceAiFragment.class,
        ScreenRecognitionSettingsFragment.class,
        SubtitleSettingsFragment.class,
        OcrSettingsFragment.class);
    map(
        Section.TOOLS,
        ToolsGroupFragment.class,
        TimerSettingsFragment.class,
        SpokenClockSettingsFragment.class,
        StopwatchSettingsFragment.class,
        CountdownAlarmSettingsFragment.class,
        TranslationFragment.class,
        TaskAssistantFragment.class,
        VoiceCommandSettingsFragment.class);
    map(
        Section.ADVANCED,
        AdvancedGroupFragment.class,
        AdvancedSettingFragment.class,
        DeveloperPrefFragment.class,
        BackupResetFragment.class,
        AppsGroupFragment.class);
    map(
        Section.MENU,
        MenuControlsGroupFragment.class,
        ContextMenuFragment.class,
        SelectorMenuFragment.class,
        SelectorGroupsFragment.class,
        CustomMenusFragment.class,
        QuickSettingsItemsFragment.class);
  }

  private static void map(Section section, Class<?>... fragmentClasses) {
    for (Class<?> fragmentClass : fragmentClasses) {
      FRAGMENT_SECTIONS.put(fragmentClass, section);
    }
  }

  /** The section the fragment's screen belongs to, or null when it is none (main screen). */
  public static @Nullable Section forFragment(Class<?> fragmentClass) {
    return FRAGMENT_SECTIONS.get(fragmentClass);
  }

  /** The section's quick actions, in the order they are offered. */
  public static List<QuickAction> actionsFor(Section section) {
    return switch (section) {
      case VOICE -> voiceActions();
      case READING -> readingActions();
      case NAVIGATION -> navigationActions();
      case MENU -> menuActions();
      case SOUND -> soundActions();
      case NOTIFICATIONS -> notificationActions();
      case TYPING -> typingActions();
      case RECOGNITION -> recognitionActions();
      case CALLS -> callActions();
      case TOOLS -> toolsActions();
      case ADVANCED -> advancedActions();
      default -> List.of();
    };
  }

  private static List<QuickAction> voiceActions() {
    return List.of(
        QuickAction.focusSlider(
            Section.VOICE,
            R.string.title_pref_talkback_speech_rate,
            QuickActionPerformer.PERFORMER_RATE_SLIDER),
        QuickAction.focusSlider(
            Section.VOICE,
            R.string.title_pref_speech_volume,
            QuickActionPerformer.PERFORMER_VOLUME_SLIDER),
        QuickAction.cycleList(
            Section.VOICE,
            R.string.title_pref_speech_stream,
            FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY,
            R.array.pref_speech_stream_entries,
            R.array.pref_speech_stream_values,
            FailoverTextToSpeech.STREAM_VALUE_ACCESSIBILITY),
        QuickAction.cycleSpecial(
            Section.VOICE,
            R.string.title_pref_voice_profiles,
            VoiceProfiles.PREF_ACTIVE,
            QuickActionPerformer.PERFORMER_VOICE_PROFILE));
  }

  private static List<QuickAction> readingActions() {
    return List.of(
        // Resolved value of R.string.pref_verbosity_preset_key; no Java constant exists.
        QuickAction.cycleList(
            Section.READING,
            R.string.title_pref_reading_style,
            "pref_verbosity_preset_key",
            R.array.pref_verbosity_preset_entries,
            R.array.pref_verbosity_preset_values,
            // Resolved value of R.string.pref_verbosity_preset_value_default.
            "pref_verbosity_preset_value_custom"),
        QuickAction.toggle(
            Section.READING,
            R.string.title_pref_always_interrupt_speech,
            FailoverTextToSpeech.PREF_ALWAYS_INTERRUPT_SPEECH_KEY,
            false),
        // Resolved value of R.string.pref_punctuation_verbosity; never pref_punctuation,
        // the invisible legacy switch sharing this row's title.
        QuickAction.cycleList(
            Section.READING,
            R.string.title_pref_punctuation,
            "pref_punctuation_verbosity",
            R.array.pref_punctuation_entries,
            R.array.pref_punctuation_values,
            // Resolved value of R.string.pref_punctuation_verbosity_default.
            "2"));
  }

  private static List<QuickAction> navigationActions() {
    return List.of(
        // Resolved value of R.string.pref_touch_stops_speech_key; no Java constant exists.
        QuickAction.toggle(
            Section.NAVIGATION,
            R.string.title_pref_touch_stops_speech,
            "pref_touch_stops_speech_key",
            true),
        QuickAction.toggleSpecial(
            Section.NAVIGATION,
            R.string.pref_direct_touch_master,
            DirectTouchSettings.PREF_MASTER,
            true,
            QuickActionPerformer.PERFORMER_DIRECT_TOUCH),
        QuickAction.toggle(
            Section.NAVIGATION,
            R.string.extended_gestures_title,
            ExtendedGestures.ENABLED,
            false));
  }

  private static List<QuickAction> notificationActions() {
    return List.of(
        QuickAction.toggleSpecial(
            Section.NOTIFICATIONS,
            R.string.title_pref_speak_notifications,
            // Resolved value of R.string.pref_speak_notifications_key.
            "pref_speak_notifications",
            true,
            QuickActionPerformer.PERFORMER_SPEAK_NOTIFICATIONS),
        QuickAction.toggle(
            Section.NOTIFICATIONS,
            R.string.notification_quiet_hours_title,
            "pref_notification_quiet_hours",
            false),
        QuickAction.cycleList(
            Section.NOTIFICATIONS,
            R.string.notification_while_reading_title,
            "pref_notification_while_reading",
            R.array.notification_while_reading_entries,
            R.array.notification_while_reading_values,
            "NOW"));
  }

  private static List<QuickAction> typingActions() {
    return List.of(
        // Resolved value of R.string.pref_typing_confirmation_key; the six radios carry
        // no key of their own, the key lives on the enclosing category.
        QuickAction.cycleSpecial(
            Section.TYPING,
            R.string.title_pref_typing_confirmation,
            "pref_typing_confirmation",
            QuickActionPerformer.PERFORMER_TYPING_CONFIRMATION),
        // Resolved value of R.string.pref_echo_on_screen_keyboard_key.
        QuickAction.cycleSpecial(
            Section.TYPING,
            R.string.title_pref_echo_virtual_keyboard,
            "pref_echo_on_screen_keyboard",
            QuickActionPerformer.PERFORMER_KEYBOARD_ECHO),
        // The row title is just "Dịch vụ"; the screen title names the feature.
        QuickAction.cycleSpecial(
            Section.TYPING,
            R.string.title_pref_dictation,
            DictationSettings.PREF_RECOGNITION,
            QuickActionPerformer.PERFORMER_DICTATION_RECOGNITION));
  }

  private static List<QuickAction> recognitionActions() {
    return List.of(
        QuickAction.toggleSpecial(
            Section.RECOGNITION,
            R.string.title_pref_detailed_image_description,
            // Resolved value of R.string.pref_detailed_image_description_key.
            "pref_detailed_image_description_key",
            false,
            QuickActionPerformer.PERFORMER_AUTO_DESCRIBE),
        QuickAction.cycleList(
            Section.RECOGNITION,
            R.string.screen_recognition_method_title,
            "pref_screen_recognition_method",
            R.array.screen_recognition_method_entries,
            R.array.screen_recognition_method_values,
            "ocr"),
        QuickAction.cycleList(
            Section.RECOGNITION,
            R.string.subtitle_engine_title,
            SubtitleSettings.ENGINE,
            R.array.subtitle_engine_labels,
            R.array.subtitle_engine_values,
            SubtitleSettings.OFFLINE_ENGINE));
  }

  private static List<QuickAction> toolsActions() {
    return List.of(
        QuickAction.toggleSpecial(
            Section.TOOLS,
            R.string.timer_clock_enabled_title,
            SpokenSchedules.KEY_CLOCK_ENABLED,
            false,
            QuickActionPerformer.PERFORMER_SPEAKING_CLOCK),
        QuickAction.open(
            Section.TOOLS,
            R.string.text_editor_title,
            QuickActionPerformer.PERFORMER_TEXT_EDITOR));
  }

  private static List<QuickAction> advancedActions() {
    return List.of(
        QuickAction.cycleList(
            Section.ADVANCED,
            R.string.title_pref_resume_backtalk,
            // Resolved value of R.string.pref_resume_backtalk_key.
            "pref_resume_talkback",
            R.array.pref_resume_backtalk_entries,
            R.array.pref_resume_backtalk_values,
            "KEYGUARD"));
  }

  private static List<QuickAction> menuActions() {
    return List.of(
        QuickAction.open(
            Section.MENU,
            R.string.title_pref_quick_settings_items,
            QuickActionPerformer.PERFORMER_QUICK_SETTINGS));
  }

  private static List<QuickAction> soundActions() {
    return List.of(
        // Resolved value of R.string.pref_soundback_key; no Java constant exists.
        QuickAction.toggle(
            Section.SOUND, R.string.title_pref_soundback, "pref_soundback", true),
        QuickAction.focusSlider(
            Section.SOUND,
            R.string.title_pref_soundback_volume,
            QuickActionPerformer.PERFORMER_SOUNDBACK_VOLUME_SLIDER),
        // Resolved value of R.string.pref_vibration_key; no Java constant exists.
        QuickAction.toggle(
            Section.SOUND, R.string.title_pref_vibration, "pref_vibration", true),
        QuickAction.cycleSpecial(
            Section.SOUND,
            R.string.title_pref_sound_themes,
            SoundThemes.PREF_ACTIVE,
            QuickActionPerformer.PERFORMER_SOUND_THEME));
  }

  private static List<QuickAction> callActions() {
    return List.of(
        QuickAction.cycleSpecial(
            Section.CALLS,
            R.string.call_voice_title,
            CallSettings.VOICE,
            QuickActionPerformer.PERFORMER_CALL_VOICE_PROFILE),
        QuickAction.toggleSpecial(
            Section.CALLS,
            R.string.title_pref_speakerphone_away_from_ear,
            SpeakerphoneSettings.PREF_ENABLED,
            false,
            QuickActionPerformer.PERFORMER_SPEAKERPHONE));
  }

  private QuickActionSections() {}
}
