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

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.calls.CallSettings;
import com.google.android.accessibility.talkback.dictation.DictationSettings;
import com.google.android.accessibility.talkback.directtouch.DirectTouchSettings;
import com.google.android.accessibility.talkback.imagecaption.FeatureSwitchDialog;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants;
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings;
import com.google.android.accessibility.talkback.notifications.NotificationReader;
import com.google.android.accessibility.talkback.notifications.SpokenSchedules;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.talkback.preference.base.SettingsSearchNavigation;
import com.google.android.accessibility.talkback.preference.base.SoundFeedbackFragment;
import com.google.android.accessibility.talkback.preference.base.TypingMethodPrefFragment;
import com.google.android.accessibility.talkback.preference.base.VoiceProfileFragment;
import com.google.android.accessibility.talkback.quicksettings.QuickSettingsActivity;
import com.google.android.accessibility.talkback.shizuku.ShizukuGrants;
import com.google.android.accessibility.talkback.soundthemes.SoundTheme;
import com.google.android.accessibility.talkback.soundthemes.SoundThemes;
import com.google.android.accessibility.talkback.soundthemes.SoundThemesFragment;
import com.google.android.accessibility.talkback.speakerphone.SpeakerphoneSettings;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.talkback.texteditor.TextEditor;
import com.google.android.accessibility.utils.input.TextEventFilter;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.accessibility.utils.preference.BasePreferencesActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Executes one settings quick action. Every path is one press: a toggle flips in place, a list
 * cycles to its next value, a slider lands focus on its row, an opener opens its feature. Cycles
 * start from the current value and toggles flip the current value; nothing here ever resets or
 * reseeds a setting.
 */
public final class QuickActionPerformer {

  /** Cycle the profile in use, wrapping through Backtalk default and every profile. */
  public static final String PERFORMER_VOICE_PROFILE = "voice_profile";

  /** Focus the rate slider on Backtalk default's profile page. */
  public static final String PERFORMER_RATE_SLIDER = "rate_slider";

  /** Focus the volume slider on Backtalk default's profile page. */
  public static final String PERFORMER_VOLUME_SLIDER = "volume_slider";

  /** Focus the sound feedback volume slider on the Sound feedback screen. */
  public static final String PERFORMER_SOUNDBACK_VOLUME_SLIDER = "soundback_volume_slider";

  /** Cycle the sound theme in use, wrapping through every installed theme. */
  public static final String PERFORMER_SOUND_THEME = "sound_theme";

  /** Cycle the voice profile calls are announced with. */
  public static final String PERFORMER_CALL_VOICE_PROFILE = "call_voice_profile";

  /**
   * Flip the speakerphone-away-from-ear switch, asking for its permission first when turning
   * it on.
   */
  public static final String PERFORMER_SPEAKERPHONE = "speakerphone";

  /** Flip the direct-touch master switch through DirectTouchSettings. */
  public static final String PERFORMER_DIRECT_TOUCH = "direct_touch";

  /** Flip notification speech through NotificationReader, which stops speech in progress. */
  public static final String PERFORMER_SPEAK_NOTIFICATIONS = "speak_notifications";

  /** Cycle the typing confirmation method (0, 1, 2, 3, 5, 6 — there is no method 4). */
  public static final String PERFORMER_TYPING_CONFIRMATION = "typing_confirmation";

  /** Cycle the on-screen keyboard echo through KeyboardEchoSettings. */
  public static final String PERFORMER_KEYBOARD_ECHO = "keyboard_echo";

  /** Cycle the dictation service: Zipformer, Rambler, Android. */
  public static final String PERFORMER_DICTATION_RECOGNITION = "dictation_recognition";

  /** Flip auto-describe, showing the Gemini opt-in dialog when turning it on. */
  public static final String PERFORMER_AUTO_DESCRIBE = "auto_describe";

  /** Flip the speaking clock, whose default is computed from the chosen hours. */
  public static final String PERFORMER_SPEAKING_CLOCK = "speaking_clock";

  /** Open the text editor. */
  public static final String PERFORMER_TEXT_EDITOR = "text_editor";

  /** Open the quick settings panel. */
  public static final String PERFORMER_QUICK_SETTINGS = "quick_settings";

  private QuickActionPerformer() {}

  /**
   * Performs the action and speaks the result. Returns false only when the action cannot run
   * (an unknown special case, or an empty list to cycle).
   */
  public static boolean perform(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    return switch (action.kind) {
      case TOGGLE -> toggleAction(context, action, prefs, returner, analytics);
      case CYCLE_LIST -> cycle(context, action, prefs, returner, analytics);
      case FOCUS_SLIDER -> focusSlider(context, action);
      case OPEN -> open(context, action, returner);
    };
  }

  /** The title spoken while swiping through the Actions control: title plus current value. */
  public static CharSequence displayTitle(
      Context context, QuickAction action, SharedPreferences prefs) {
    String title = context.getString(action.titleResId);
    return switch (action.kind) {
      case TOGGLE -> title
          + ", "
          + context.getString(
              toggleState(action, prefs)
                  ? R.string.quick_action_state_on
                  : R.string.quick_action_state_off);
      case CYCLE_LIST -> title + ", " + currentEntryLabel(context, action, prefs);
      case FOCUS_SLIDER, OPEN -> title;
    };
  }

  /** Whether a toggle is on. The speaking clock's default is computed, not a constant. */
  private static boolean toggleState(QuickAction action, SharedPreferences prefs) {
    if (PERFORMER_SPEAKING_CLOCK.equals(action.performerId)) {
      return SpokenSchedules.Companion.clockEnabled(prefs);
    }
    return prefs.getBoolean(action.prefKey, action.toggleDefault);
  }

  private static boolean toggleAction(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    if (PERFORMER_SPEAKERPHONE.equals(action.performerId)) {
      return toggleSpeakerphone(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_DIRECT_TOUCH.equals(action.performerId)) {
      return toggleDirectTouch(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_SPEAK_NOTIFICATIONS.equals(action.performerId)) {
      return toggleSpeakNotifications(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_AUTO_DESCRIBE.equals(action.performerId)) {
      return toggleAutoDescribe(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_SPEAKING_CLOCK.equals(action.performerId)) {
      return toggleSpeakingClock(context, action, prefs, returner, analytics);
    }
    return toggle(context, action, prefs, returner, analytics);
  }

  private static boolean toggle(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean next = !prefs.getBoolean(action.prefKey, action.toggleDefault);
    prefs.edit().putBoolean(action.prefKey, next).apply();
    logChange(analytics, action.prefKey);
    return speakToggleState(context, action, returner, next);
  }

  /**
   * Flips the direct-touch master switch through {@link DirectTouchSettings#setMasterEnabled},
   * the same call the Quick Settings tile uses. The write itself is one boolean; the method
   * is the single source of truth for it.
   */
  private static boolean toggleDirectTouch(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean next = !prefs.getBoolean(action.prefKey, action.toggleDefault);
    DirectTouchSettings.INSTANCE.setMasterEnabled(prefs, next);
    logChange(analytics, action.prefKey);
    return speakToggleState(context, action, returner, next);
  }

  /**
   * Flips notification speech through {@link NotificationReader#setSpeechOn}: a raw write
   * would leave a notification already being read aloud until it finishes, while this stops
   * it when turning off. Announcing is left to the performer, so the spoken state matches
   * every other toggle.
   */
  private static boolean toggleSpeakNotifications(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean next = !prefs.getBoolean(action.prefKey, action.toggleDefault);
    NotificationReader.setSpeechOn(context, next, false);
    logChange(analytics, action.prefKey);
    return speakToggleState(context, action, returner, next);
  }

  /**
   * Flips auto-describe. Turning it off is a direct write, exactly like the fragment's
   * listener allows. Turning it on must show the Gemini opt-in dialog first: a silent
   * write would enable Gemini image description without consent, so the dialog is
   * replicated here from the fragment's own construction, and the pref is written only
   * when the user consents.
   */
  private static boolean toggleAutoDescribe(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean current = prefs.getBoolean(action.prefKey, action.toggleDefault);
    if (current) {
      return toggle(context, action, prefs, returner, analytics);
    }
    new FeatureSwitchDialog(
        context,
        ImageCaptionConstants.FeatureSwitchDialogResources.DETAILED_IMAGE_DESCRIPTION,
        /* isDeletable= */ false,
        R.string.enable_gemini) {
      @Override
      public void handleDialogClick(int buttonClicked) {
        super.handleDialogClick(buttonClicked);
        switch (buttonClicked) {
          case DialogInterface.BUTTON_POSITIVE -> {
            // The base handler writes the switch key from the radio the user picked
            // (on, or on for unlabelled images only); this only logs the consent.
            if (analytics != null) {
              analytics.onGeminiOptInFromSettings(
                  TalkBackAnalytics.GEMINI_OPT_IN_CONSENT, /* serverSide= */ true);
            }
          }
          case DialogInterface.BUTTON_NEGATIVE -> {
            if (analytics != null) {
              analytics.onGeminiOptInFromSettings(
                  TalkBackAnalytics.GEMINI_OPT_IN_DISSENT, /* serverSide= */ true);
            }
          }
          default -> {}
        }
      }
    }.showDialog();
    if (analytics != null) {
      analytics.onGeminiOptInFromSettings(
          TalkBackAnalytics.GEMINI_OPT_IN_SHOW_DIALOG, /* serverSide= */ true);
    }
    return true;
  }

  /**
   * Flips the speaking clock. There is no constant default: before the switch existed the
   * clock was on exactly when hours were in effect, so the current state is computed
   * from the chosen hours until the switch is touched.
   */
  private static boolean toggleSpeakingClock(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean next = !SpokenSchedules.Companion.clockEnabled(prefs);
    prefs.edit().putBoolean(action.prefKey, next).apply();
    logChange(analytics, action.prefKey);
    return speakToggleState(context, action, returner, next);
  }

  private static boolean speakToggleState(
      Context context,
      QuickAction action,
      Pipeline.FeedbackReturner returner,
      boolean next) {
    return speak(
        returner,
        context.getString(action.titleResId)
            + ", "
            + context.getString(
                next ? R.string.quick_action_state_on : R.string.quick_action_state_off));
  }

  /**
   * Advances the typing confirmation method through 0, 1, 2, 3, 5, 6 in that order — there
   * is no method 4, so no resource array can hold the cycle. The value is stored as a
   * string; an unknown stored value starts from the default (lift to type) and advances.
   */
  private static boolean cycleTypingConfirmation(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    int[] methods = {0, 1, 2, 3, 5, 6};
    int current = typingConfirmation(prefs, action.prefKey);
    // Default method (lift to type) when the stored value is unknown: advance from it.
    int currentIndex = 1;
    for (int i = 0; i < methods.length; i++) {
      if (methods[i] == current) {
        currentIndex = i;
        break;
      }
    }
    int next = methods[(currentIndex + 1) % methods.length];
    prefs.edit().putString(action.prefKey, String.valueOf(next)).apply();
    logChange(analytics, action.prefKey);
    return speak(
        returner,
        context.getString(action.titleResId)
            + ", "
            + context.getString(TypingMethodPrefFragment.titleForMethod(next)));
  }

  private static int typingConfirmation(SharedPreferences prefs, @Nullable String prefKey) {
    try {
      return Integer.parseInt(prefs.getString(prefKey, "1"));
    } catch (NumberFormatException | NullPointerException e) {
      return 1;
    }
  }

  /** Advances the on-screen keyboard echo through {@link KeyboardEchoSettings}. */
  private static boolean cycleKeyboardEcho(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    int echo =
        KeyboardEchoSettings.cycle(
            prefs,
            context.getResources(),
            TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD,
            /* next= */ true);
    logChange(
        analytics,
        KeyboardEchoSettings.key(
            context.getResources(), TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD));
    return speak(
        returner,
        context.getString(action.titleResId)
            + ", "
            + KeyboardEchoSettings.choiceName(context.getResources(), echo));
  }

  /**
   * Advances the dictation service in the dialog's order: Zipformer, Rambler, Android. The
   * labels mirror {@code DictationFragment.recognitionLabel()}, which is private.
   */
  private static boolean cycleDictationRecognition(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    DictationSettings.Recognition[] order = {
      DictationSettings.Recognition.ZIPFORMER,
      DictationSettings.Recognition.RAMBLER,
      DictationSettings.Recognition.ANDROID,
    };
    DictationSettings.Recognition current = DictationSettings.INSTANCE.recognition(prefs);
    // Android is the fallback when the stored id is unknown: advance from it.
    int currentIndex = 2;
    for (int i = 0; i < order.length; i++) {
      if (order[i] == current) {
        currentIndex = i;
        break;
      }
    }
    DictationSettings.Recognition next = order[(currentIndex + 1) % order.length];
    DictationSettings.INSTANCE.setRecognition(prefs, next);
    logChange(analytics, DictationSettings.PREF_RECOGNITION);
    return speak(
        returner,
        context.getString(action.titleResId)
            + ", "
            + context.getString(dictationRecognitionLabel(next)));
  }

  private static int dictationRecognitionLabel(DictationSettings.Recognition recognition) {
    return switch (recognition) {
      case ZIPFORMER -> R.string.dictation_recognition_zipformer;
      case RAMBLER -> R.string.dictation_compact_gemini;
      case ANDROID -> R.string.dictation_compact_android;
    };
  }

  private static boolean cycle(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    if (PERFORMER_VOICE_PROFILE.equals(action.performerId)) {
      return cycleVoiceProfile(context, prefs, returner, analytics);
    }
    if (PERFORMER_SOUND_THEME.equals(action.performerId)) {
      return cycleSoundTheme(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_CALL_VOICE_PROFILE.equals(action.performerId)) {
      return cycleCallVoiceProfile(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_TYPING_CONFIRMATION.equals(action.performerId)) {
      return cycleTypingConfirmation(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_KEYBOARD_ECHO.equals(action.performerId)) {
      return cycleKeyboardEcho(context, action, prefs, returner, analytics);
    }
    if (PERFORMER_DICTATION_RECOGNITION.equals(action.performerId)) {
      return cycleDictationRecognition(context, action, prefs, returner, analytics);
    }
    String[] values = context.getResources().getStringArray(action.valuesResId);
    String[] entries = context.getResources().getStringArray(action.entriesResId);
    if (values.length == 0 || entries.length == 0) {
      return false;
    }
    int currentIndex = indexOf(values, currentStoredValue(action, prefs));
    if (currentIndex < 0) {
      currentIndex = indexOf(values, action.cycleDefault);
    }
    if (currentIndex < 0) {
      currentIndex = 0;
    }
    int nextIndex = (currentIndex + 1) % values.length;
    prefs.edit().putString(action.prefKey, values[nextIndex]).apply();
    logChange(analytics, action.prefKey);
    return speak(
        returner,
        context.getString(action.titleResId) + ", " + entries[nextIndex]);
  }

  private static boolean cycleVoiceProfile(
      Context context,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    if (VoiceProfiles.ids(prefs).isEmpty()) {
      return speak(returner, context.getString(R.string.no_voice_profiles));
    }
    String nextId = VoiceProfiles.adjacentId(prefs, /* isNext= */ true);
    prefs.edit().putString(VoiceProfiles.PREF_ACTIVE, nextId).apply();
    logChange(analytics, VoiceProfiles.PREF_ACTIVE);
    return speak(returner, VoiceProfileNames.nameOf(context, prefs, nextId));
  }

  /**
   * Advances to the next installed theme, wrapping. A raw key write would skip saving the
   * outgoing theme's settings and the new theme's control-sounds/3D values, so the switch
   * goes through {@link SoundThemes#activate} like tapping a theme row does.
   */
  private static boolean cycleSoundTheme(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    List<SoundTheme> installed = SoundThemes.installed(context);
    if (installed.isEmpty()) {
      return false;
    }
    String currentId = SoundThemes.activeId(prefs);
    int currentIndex = -1;
    for (int i = 0; i < installed.size(); i++) {
      if (installed.get(i).getId().equals(currentId)) {
        currentIndex = i;
        break;
      }
    }
    SoundTheme next = installed.get((currentIndex + 1) % installed.size());
    if (!next.getId().equals(currentId)) {
      SoundThemes.activate(context, prefs, next.getId());
      logChange(analytics, SoundThemes.PREF_ACTIVE);
    }
    return speak(
        returner,
        context.getString(action.titleResId) + ", " + SoundThemesFragment.nameOf(context, next));
  }

  /**
   * Advances to the next call voice, wrapping through the current Meridian voice and every
   * profile in creation order. Only a value from the live entry list is ever written, so a
   * profile deleted since the last visit can never leave a stale id behind.
   */
  private static boolean cycleCallVoiceProfile(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    Map<String, String> names = callVoiceProfileNames(context, prefs);
    List<String> values = new ArrayList<>();
    values.add("");
    values.addAll(names.keySet());
    String current = prefs.getString(CallSettings.VOICE, "");
    if (current == null) {
      current = "";
    }
    String next = values.get((values.indexOf(current) + 1) % values.size());
    if (!next.equals(current)) {
      prefs.edit().putString(CallSettings.VOICE, next).apply();
      logChange(analytics, CallSettings.VOICE);
    }
    String name = next.isEmpty() ? null : names.get(next);
    CharSequence label =
        (name == null || name.isEmpty())
            ? context.getString(R.string.call_voice_default)
            : name;
    return speak(returner, context.getString(action.titleResId) + ", " + label);
  }

  /** The call voice entries in creation order: "" is the current Meridian voice. */
  private static Map<String, String> callVoiceProfileNames(
      Context context, SharedPreferences prefs) {
    return VoiceProfiles.names(
        prefs,
        context.getString(R.string.voice_profile_default),
        number -> context.getString(R.string.voice_profile_new_name, number));
  }

  /**
   * Flips the speakerphone-away-from-ear switch. Turning it off needs nothing; turning it on
   * needs the MANAGE_ONGOING_CALLS app-op, so like tapping the row the grant is asked for
   * first through Shizuku, and the switch only turns on if it lands. Without the grant the
   * state stays off and the speech says so.
   */
  private static boolean toggleSpeakerphone(
      Context context,
      QuickAction action,
      SharedPreferences prefs,
      Pipeline.FeedbackReturner returner,
      @Nullable TalkBackAnalytics analytics) {
    boolean current = prefs.getBoolean(action.prefKey, action.toggleDefault);
    if (current || SpeakerphoneSettings.INSTANCE.canControlCalls(context)) {
      return toggle(context, action, prefs, returner, analytics);
    }
    ShizukuGrants.INSTANCE.request(
        context,
        ShizukuGrants.Grant.MANAGE_ONGOING_CALLS,
        granted -> {
          if (granted) {
            toggle(context, action, prefs, returner, analytics);
          } else {
            speakToggleState(context, action, returner, false);
          }
          return Unit.INSTANCE;
        });
    return true;
  }

  private static boolean focusSlider(Context context, QuickAction action) {
    if (PERFORMER_SOUNDBACK_VOLUME_SLIDER.equals(action.performerId)) {
      Bundle args = new Bundle();
      args.putString(
          SettingsSearchNavigation.TARGET_KEY,
          context.getString(R.string.pref_soundback_volume_seekbar_key_int));
      Intent intent = new Intent(context, TalkBackPreferencesActivity.class);
      intent.putExtra(
          BasePreferencesActivity.FRAGMENT_NAME, SoundFeedbackFragment.class.getName());
      intent.putExtra(BasePreferencesActivity.FRAGMENT_ARGS, args);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
      return true;
    }
    String field;
    if (PERFORMER_RATE_SLIDER.equals(action.performerId)) {
      field = VoiceProfiles.RATE;
    } else if (PERFORMER_VOLUME_SLIDER.equals(action.performerId)) {
      field = VoiceProfiles.VOLUME;
    } else {
      return false;
    }
    Bundle args = new Bundle();
    args.putString(VoiceProfileFragment.ARG_PROFILE_ID, "");
    args.putString(
        SettingsSearchNavigation.TARGET_KEY, VoiceProfileFragment.seekBarKey("", field));
    Intent intent = new Intent(context, TalkBackPreferencesActivity.class);
    intent.putExtra(BasePreferencesActivity.FRAGMENT_NAME, VoiceProfileFragment.class.getName());
    intent.putExtra(BasePreferencesActivity.FRAGMENT_ARGS, args);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
    return true;
  }

  private static boolean open(
      Context context, QuickAction action, Pipeline.FeedbackReturner returner) {
    if (PERFORMER_TEXT_EDITOR.equals(action.performerId)) {
      if (TextEditor.open(context)) {
        return true;
      }
      return speak(
          returner,
          context.getString(action.titleResId)
              + ", "
              + context.getString(R.string.quick_action_open_failed));
    }
    if (PERFORMER_QUICK_SETTINGS.equals(action.performerId)) {
      if (QuickSettingsActivity.openInsteadOfSystem(context)) {
        return true;
      }
      // Meridian's panel is off, or the phone is locked: fall back to the system panel,
      // like the assignable quick-settings action does.
      if (context instanceof AccessibilityService
          && ((AccessibilityService) context)
              .performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
        return true;
      }
      return speak(
          returner,
          context.getString(action.titleResId)
              + ", "
              + context.getString(R.string.quick_action_open_failed));
    }
    return false;
  }

  private static CharSequence currentEntryLabel(
      Context context, QuickAction action, SharedPreferences prefs) {
    if (PERFORMER_VOICE_PROFILE.equals(action.performerId)) {
      return VoiceProfileNames.nameOf(context, prefs, VoiceProfiles.activeId(prefs));
    }
    if (PERFORMER_SOUND_THEME.equals(action.performerId)) {
      return SoundThemesFragment.nameOf(context, SoundThemes.active(context, prefs));
    }
    if (PERFORMER_CALL_VOICE_PROFILE.equals(action.performerId)) {
      String current = prefs.getString(CallSettings.VOICE, "");
      if (current == null || current.isEmpty()) {
        return context.getString(R.string.call_voice_default);
      }
      String name = callVoiceProfileNames(context, prefs).get(current);
      return (name == null || name.isEmpty())
          ? context.getString(R.string.call_voice_default)
          : name;
    }
    if (PERFORMER_TYPING_CONFIRMATION.equals(action.performerId)) {
      return context.getString(
          TypingMethodPrefFragment.titleForMethod(
              typingConfirmation(prefs, action.prefKey)));
    }
    if (PERFORMER_KEYBOARD_ECHO.equals(action.performerId)) {
      return KeyboardEchoSettings.choiceName(
          context.getResources(),
          KeyboardEchoSettings.read(
              prefs, context.getResources(), TextEventFilter.TypingInput.ON_SCREEN_KEYBOARD));
    }
    if (PERFORMER_DICTATION_RECOGNITION.equals(action.performerId)) {
      return context.getString(
          dictationRecognitionLabel(DictationSettings.INSTANCE.recognition(prefs)));
    }
    String[] values = context.getResources().getStringArray(action.valuesResId);
    String[] entries = context.getResources().getStringArray(action.entriesResId);
    if (values.length == 0 || entries.length == 0) {
      return "";
    }
    int currentIndex = indexOf(values, currentStoredValue(action, prefs));
    if (currentIndex < 0) {
      currentIndex = indexOf(values, action.cycleDefault);
    }
    if (currentIndex < 0) {
      currentIndex = 0;
    }
    return entries[currentIndex];
  }

  /**
   * The value a cycle starts from. The speech stream may never have been saved: before the list,
   * a switch chose between the accessibility and media volumes, so the stream in use is the
   * effective one, not the stored one.
   */
  private static @Nullable String currentStoredValue(
      QuickAction action, SharedPreferences prefs) {
    if (FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY.equals(action.prefKey)) {
      return FailoverTextToSpeech.chosenSpeechStream(prefs);
    }
    return prefs.getString(action.prefKey, null);
  }

  private static int indexOf(String[] values, @Nullable String value) {
    if (value == null) {
      return -1;
    }
    for (int i = 0; i < values.length; i++) {
      if (values[i].equals(value)) {
        return i;
      }
    }
    return -1;
  }

  private static void logChange(@Nullable TalkBackAnalytics analytics, @Nullable String prefKey) {
    if (analytics != null && prefKey != null) {
      analytics.onManuallyChangeSetting(prefKey, TalkBackAnalytics.TYPE_SELECTOR);
    }
  }

  private static boolean speak(Pipeline.FeedbackReturner returner, CharSequence text) {
    return returner.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text));
  }
}
