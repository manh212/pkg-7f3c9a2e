/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.accessibility.talkback.actor.voicecommands;

import static com.google.android.accessibility.talkback.Feedback.UniversalSearch.Action.TOGGLE_SEARCH;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_ALL_APPS;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_BACK;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_BRIGHTEN_SCREEN;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_COPY;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_COPY_LAST_SPOKEN_UTTERANCE;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_CUT;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_DELETE;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_DIM_SCREEN;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_END_SELECT;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_FIND;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_FIRST;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_HOME;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_INSERT;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_LABEL;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_LAST;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_NEXT_GRANULARITY;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_NOTIFICATIONS;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_PASTE;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_QUICK_SETTINGS;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_RECENT;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_REPEAT_SEARCH;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_SELECT_ALL;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_SHOW_COMMAND_LIST;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_START_AT_CURSOR;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_START_AT_TOP;
import static com.google.android.accessibility.talkback.Interpretation.VoiceCommand.Action.VOICE_COMMAND_START_SELECT;
import static com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor.RECOGNITION_SPEECH_DELAY_MS;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_RECOGNIZED;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_ACTION;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_ALL_APPS;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_ASSISTANT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_BACK;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_COPY;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_COPY_LAST_UTTERANCE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_CUSTOM_ACTION;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_CUT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_DELETE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_DIAL;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_END_SELECT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_FIND;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_FIRST;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_GEMINI;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_GRANULARITY;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_HELP;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_ITEM_NUMBER;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_HIDE_SCREEN;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_HOME;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_INSERT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_LABEL;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_LANGUAGE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_LAST;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_NEXT_CONTROL;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_NEXT_HEADING;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_NEXT_LANDMARK;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_NEXT_LINK;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_NOTIFICATION;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_PASTE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_QUICK_SETTING;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_QUIT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_READ_FROM_CURSOR;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_READ_FROM_TOP;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_RECENT_APPS;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_SCREEN_SEARCH;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_SELECT_ALL;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_SHOW_SCREEN;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_SPEECH_RATE_DECREASE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_SPEECH_RATE_INCREASE;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_START_SELECT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_TALKBACK_SETTING;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TYPE_VERBOSITY;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_UNRECOGNIZED;
import static com.google.android.accessibility.talkback.contextmenu.ListMenuManager.MenuId.CUSTOM_ACTION;
import static com.google.android.accessibility.talkback.contextmenu.ListMenuManager.MenuId.LANGUAGE;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.talkback.ActorState;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.actor.DimScreenActor;
import com.google.android.accessibility.talkback.actor.SpeechRateAndPitchActor;
import com.google.android.accessibility.talkback.actor.gemini.GeminiConfiguration;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VoiceCommandTypeId;
import com.google.android.accessibility.talkback.collapse.CollapsedItemActions;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.contextmenu.ListMenuManager;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.granularediting.GranularEditing;
import com.google.android.accessibility.talkback.menurules.RuleAction;
import com.google.android.accessibility.talkback.selector.SelectorController;
import com.google.android.accessibility.talkback.selector.SelectorController.Setting;
import com.google.android.accessibility.talkback.selector.SpeechVolumeControl;
import com.google.android.accessibility.talkback.timer.Stopwatch;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.LocaleUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.SpannableUtils;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.input.CursorGranularity;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

// TODO: Refactoring this class as a feedback-mapper class
/** Class to handle voice commands and calls from {@link VoiceCommandActor}. */
public class VoiceCommandProcessor {

  private static final String LOG_TAG = "VoiceCommandProcessor";

  private final TalkBackService service;
  private Pipeline.FeedbackReturner pipeline;
  private Pipeline.InterpretationReceiver interpretationReceiver;
  private final AccessibilityFocusMonitor accessibilityFocusMonitor;
  private ListMenuManager menuManager;
  private SelectorController selectorController;
  private ActorState actorState;
  private final TalkBackAnalytics analytics;
  private final SpeechRateAndPitchActor speechRateAndPitchActor;

  private boolean echoNotRecognizedTextEnabled;

  ////////////////////////////////////////////////////////////////////////////////////////////////
  private List<String> verbosityCommandList;
  private List<String> granularityCommandList;
  private List<VoiceCommandMatcher.Entry> catalogEntries;
  private VoiceCommandPhrases.Keywords phraseKeywords;
  private VoiceCommandChain.Keywords chainKeywords;

  /* Set while the fixed legacy commands run their second, scored-matching pass. */
  private boolean fuzzyLegacyPass;
  private int chainGeneration;
  private ConfirmationSession confirmationSession;
  private Runnable pendingConfirmationAction;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable confirmationTimeout = this::onConfirmationTimeout;
  private final Runnable phraseDialogueTimeout = this::onPhraseDialogueTimeout;
  private final Runnable dialStageTimeout = this::onDialStageTimeout;
  private int phraseStage;
  private int dialStage;
  private String phraseDraft;
  private final List<String> phraseDraftValues = new ArrayList<>();
  private final List<String> phraseDraftLabels = new ArrayList<>();
  private int aiRequestId;
  private String aiCommand;
  private final Runnable aiFallbackTimeout = this::onAiFallbackTimeout;

  private static final int PHRASE_STAGE_NONE = 0;
  private static final int PHRASE_STAGE_PHRASE = 1;
  private static final int PHRASE_STAGE_ACTION = 2;

  private static final int DIAL_STAGE_NONE = 0;
  private static final int DIAL_STAGE_NUMBER = 1;

  private static final long CONFIRMATION_TIMEOUT_MS = 10000;
  private static final long AI_FALLBACK_TIMEOUT_MS = 12000;
  private static final long REPEAT_INTERVAL_MS = 350;
  private static final long MACRO_STEP_INTERVAL_MS = 500;
  private static final int MAX_PHRASE_ACTIONS = 10;

  /** Asks the owner of the listening session to listen once more for a spoken answer. */
  public interface ConfirmationSession {
    void requestListenAgain();

    /** Switches this session to continuous listening, at the user's spoken request. */
    void enableContinuousListening();

    /** Notes that a command just ran, for the session's continuous-listening idle clock. */
    void noteCommandRan();
  }

  public void setConfirmationSession(ConfirmationSession confirmationSession) {
    this.confirmationSession = confirmationSession;
  }

  private static final int[] typeCommandResArray = {
    R.string.voice_commands_type,
    R.string.voice_commands_input,
    R.string.voice_commands_dictate,
    R.string.voice_commands_write,
    R.string.voice_commands_Spell
  };

  private static final int[] talkbackSettingCommandResArray = {
    R.string.voice_commands_talkback_settings, R.string.voice_commands_talkback_setting
  };

  private static final int[] finishSelectCommandResArray = {
    R.string.voice_commands_finish_select,
    R.string.voice_commands_finish_selection_mode,
    R.string.voice_commands_finish_selection,
    R.string.voice_commands_end_select
  };

  private static final int[] selectCommandResArray = {
    R.string.voice_commands_select,
    R.string.voice_commands_start_select,
    R.string.voice_commands_start_selection,
    R.string.voice_commands_start_selection_mode
  };

  private static final int[] languageCommandResArray = {
    R.string.voice_commands_language, R.string.voice_commands_languages
  };

  private static final int[] actionsCommandResArray = {
    R.string.title_custom_action,
    R.string.voice_commands_custom_actions,
    R.string.voice_commands_action
  };

  private static final int[] quickSettingCommandResArray = {
    R.string.voice_commands_quick_settings, R.string.voice_commands_quick_setting
  };

  private static final int[] showScreenCommandResArray = {
    R.string.shortcut_disable_dimming,
    R.string.voice_commands_brighten_screen,
    R.string.voice_commands_restore_screen,
    R.string.voice_commands_cancel_hide_screen
  };

  private static final int[] notificationsCommandResArray = {
    R.string.voice_commands_notification, R.string.voice_commands_notifications
  };

  private static final int[] hideScreenCommandResArray = {
    R.string.voice_commands_dim, R.string.voice_commands_darken
  };

  private static final int[] readFromNextCommandResArray = {
    R.string.shortcut_read_from_current, R.string.voice_commands_read_from_next
  };

  private static final int[] overviewCommandResArray = {
    R.string.voice_commands_overview, R.string.voice_commands_recent_apps,
    R.string.voice_commands_recent, R.string.voice_commands_recents
  };

  private static final int[] verbosityCommandArray = {
    R.string.voice_commands_verbosity_parameter,
    R.string.voice_commands_parameter_verbosity,
    R.string.voice_commands_change_verbosity_to_parameter
  };

  private static final int[] verbosityParameters = {
    R.string.pref_verbosity_preset_entry_high,
    R.string.pref_verbosity_preset_entry_custom,
    R.string.pref_verbosity_preset_entry_low,
    R.string.voice_commands_homophone_high_and_hi
  };

  private static final int[] granularityCommandArray = {
    R.string.voice_commands_navigation_by_parameter,
    R.string.voice_commands_parameter_navigation,
    R.string.voice_commands_parameter_granularity,
    R.string.voice_commands_read_by_parameter
  };

  // This sequence of granularity mode should always aligns with
  // SelectorController.SELECTOR_SETTINGS
  private static final int[] granularityModeArray = {
    R.string.granularity_character,
    R.string.granularity_word,
    R.string.granularity_line,
    R.string.granularity_paragraph,
    R.string.granularity_native_heading, // headings
    R.string.granularity_native_control, // controls
    R.string.granularity_web_landmark, // landmarks
    R.string.granularity_window,
    R.string.granularity_default
  };

  private static final int[] findCommandResArray = {
    R.string.voice_commands_find, R.string.voice_commands_search_for, R.string.voice_commands_search
  };

  public VoiceCommandProcessor(
      TalkBackService service,
      AccessibilityFocusMonitor accessibilityFocusMonitor,
      SelectorController selectorController,
      TalkBackAnalytics analytics,
      SpeechRateAndPitchActor speechRateAndPitchActor) {
    this.service = service;
    this.accessibilityFocusMonitor = accessibilityFocusMonitor;
    this.selectorController = selectorController;
    this.analytics = analytics;
    this.speechRateAndPitchActor = speechRateAndPitchActor;
  }

  public void setPipeline(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  public void setActorState(ActorState actorState) {
    this.actorState = actorState;
  }

  public void setPipelineInterpretationReceiver(
      Pipeline.InterpretationReceiver interpretationReceiver) {
    this.interpretationReceiver = interpretationReceiver;
  }

  public void setListMenuManager(ListMenuManager menuManager) {
    this.menuManager = menuManager;
  }

  public void setEchoRecognizedTextEnabled(boolean enable) {
    echoNotRecognizedTextEnabled = enable;
  }

  public void setSelectorController(SelectorController selectorController) {
    this.selectorController = selectorController;
  }

  private void dimScreenVoiceCommand(EventId eventId) {
    if (DimScreenActor.isSupported(service)) {
      sendInterpretation(VOICE_COMMAND_DIM_SCREEN, eventId);
    }
  }

  private void performGeminiCommand(EventId eventId, String prompt) {
    // TODO: Verify takeScreenshot by faking ScreenshotCapture.
    ScreenshotCapture.takeScreenshot(
        service,
        (screenCapture, isFormatSupported) -> {
          pipeline.returnFeedback(
              eventId, Feedback.geminiRequest(/* requestId= */ -1, prompt, screenCapture));
        });
  }

  /* Returns true if the recognized string is one of the voice commands. */
  @CanIgnoreReturnValue
  public boolean handleSpeechCommand(String command, boolean isPartialResult) {
    return handleSpeechCommand(command, isPartialResult, /* chainedPart= */ false);
  }

  private boolean handleSpeechCommand(
      String command, boolean isPartialResult, boolean chainedPart) {
    if (TextUtils.isEmpty(command)) {
      return false;
    }
    LogUtils.i(LOG_TAG, "handleSpeechCommand() command=\"%s\"", command);
    EventId eventId = EVENT_ID_UNTRACKED;

    // A new final result supersedes an AI answer still on its way for an earlier sentence.
    if (!isPartialResult) {
      invalidateAiFallback();
    }

    // A dangerous command is waiting for its spoken confirmation: this result is the answer,
    // never a new command. Partial results do not answer it.
    if (pendingConfirmationAction != null) {
      if (isPartialResult) {
        return false;
      }
      Runnable action = pendingConfirmationAction;
      pendingConfirmationAction = null;
      mainHandler.removeCallbacks(confirmationTimeout);
      if (VoiceCommandParameters.isYesAnswer(command, parameterKeywords())) {
        action.run();
      } else {
        speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
      }
      return true;
    }

    // A phrase is being taught: this result is the dialogue's next answer, never a command.
    if (phraseStage != PHRASE_STAGE_NONE) {
      if (isPartialResult) {
        return false;
      }
      mainHandler.removeCallbacks(phraseDialogueTimeout);
      handlePhraseDialogueAnswer(command);
      return true;
    }

    // A call command asked for its number: this result is the number, never a command.
    if (dialStage != DIAL_STAGE_NONE) {
      if (isPartialResult) {
        return false;
      }
      mainHandler.removeCallbacks(dialStageTimeout);
      handleDialAnswer(command);
      return true;
    }

    // Taught phrases are managed, and a taught phrase said in full is used, before any
    // built-in command, so a taught phrase wins over a built-in name. Partial results are
    // not matched: the phrase may still be growing into another command.
    if (!isPartialResult && handlePhraseCommand(command, eventId)) {
      return true;
    }

    // Asking for continuous listening switches this session to listen again after every
    // command, until a stop word or an idle stretch ends it.
    if (!isPartialResult && isContinuousStartCommand(command)) {
      if (confirmationSession != null) {
        confirmationSession.enableContinuousListening();
      }
      speakDelayed(service.getString(R.string.voice_continuous_on));
      return true;
    }

    // While the text editor is on screen, its own spoken commands drive the editing.
    // They are heard before the fixed commands, whose Find and Delete would otherwise
    // take the same words for the screen as a whole. With no editor on screen, or a
    // sentence that is none of its commands, the sentence falls through unchanged.
    if (!isPartialResult && GranularEditing.handleVoiceCommand(service, command)) {
      return true;
    }

    // Gemini voice command
    // command format: Gemini
    // TODO: When the code structure allows to test taking screenshot, add unit tests
    // of Gemini voice command.
    if (GeminiConfiguration.isGeminiVoiceCommandEnabled(service)
        && startsWith(command, R.string.voice_commands_gemini)) {
      performGeminiCommand(
          eventId, command.substring(service.getString(R.string.voice_commands_gemini).length()));
      pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_GEMINI);
      return true;
    }

    if (handleLegacyCommands(command, eventId)) {
      return true;
    }
    if (!isPartialResult) {
      // A fixed command said slightly differently — a word off, a diacritic missing —
      // gets one more pass with scored matching before the parameterized commands
      // see the sentence.
      fuzzyLegacyPass = true;
      try {
        if (handleLegacyCommands(command, eventId)) {
          return true;
        }
      } finally {
        fuzzyLegacyPass = false;
      }
    }

    // Commands carrying a spoken number come next: a percentage or a countdown duration.
    // Any other action that a gesture or a shortcut can run can be spoken by its name, with
    // an optional repeat count. Partial results are not matched: the phrase may still be
    // growing into another command.
    if (!isPartialResult && handleParameterCommand(command, eventId)) {
      return true;
    }
    if (!isPartialResult && handleCatalogCommand(command, eventId)) {
      return true;
    }

    if (!isPartialResult && !chainedPart) {
      // A sentence that matched nothing may still be a chain of commands joined by a
      // connector: each part runs in order, and a part nothing recognizes is skipped.
      if (tryChainedCommands(command, eventId)) {
        return true;
      }
      // Nothing matched. When the user turned the AI fallback on, the sentence is given
      // to the AI before it is called unrecognized; the fallback speaks for itself.
      if (startAiFallback(command, eventId)) {
        return true;
      }
    }

    if (chainedPart) {
      // One part of a chain that nothing recognized is skipped without a word.
      return false;
    }
    speakUnrecognized(command);
    if (!isPartialResult) {
      // To expect further partial/final result from speech recognizer could match the supported
      // commands, we don't log the unrecognized metrics for partial result. On the other hand, for
      // final result, we should return true even the command match failed so that the entire
      // session will be terminated.
      analytics.onVoiceCommandEvent(VOICE_COMMAND_UNRECOGNIZED);
      return true;
    }

    return false;
  }

  /*
   * The fixed legacy commands, in their long-standing order: returns true when one of
   * them took the sentence. Matching goes through the equals and containsWord
   * helpers, so a second pass with fuzzyLegacyPass set runs the very same commands
   * with scored, diacritic-tolerant matching.
   */
  private boolean handleLegacyCommands(String command, EventId eventId) {
    @Nullable AccessibilityNodeInfoCompat node = null;
    // select all voice command
    // command format: Select all
    if (equals(command, android.R.string.selectAll)) {
      node = getEditTextFocus();
      if (node != null) {
        sendInterpretation(VOICE_COMMAND_SELECT_ALL, node, eventId);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SELECT_ALL);
      return true;
    }

    // hide screen command
    // command format: Hide screen,
    if (equals(command, R.string.shortcut_enable_dimming)) {
      dimScreenVoiceCommand(eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_HIDE_SCREEN);
      return true;
    }

    // finish selection mode voice command
    // command format: Finish select, Finish selection, Finish selection mode, End select
    int finishSelectCommand = equals(command, finishSelectCommandResArray);
    if (finishSelectCommand >= 0) {
      node = getEditTextFocus();
      if (node != null) {
        // TODO Separate VoiceCommandProcessor as feedback mapper and
        // command-pattern-matching.
        sendInterpretation(VOICE_COMMAND_END_SELECT, node, eventId);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_END_SELECT);
      return true;
    }

    // screen search voice command
    // command format: screen search, search on screen
    if (!FormFactorUtils.isAndroidWear()
        && (equals(command, R.string.voice_commands_screen_search)
            || equals(command, R.string.voice_commands_search_on_screen))) {
      pipeline.returnFeedback(eventId, Feedback.universalSearch(TOGGLE_SEARCH));
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SCREEN_SEARCH);
      return true;
    }

    // select voice command
    // command format: select, start select, start selection mode, start selection
    int selectCommand = equals(command, selectCommandResArray);
    if (selectCommand >= 0) {
      node = getEditTextFocus();
      if (node != null) {
        sendInterpretation(VOICE_COMMAND_START_SELECT, node, eventId);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_START_SELECT);
      return true;
    }

    // actions voice command
    // command format: actions, custom actions
    int actionCommand = equals(command, actionsCommandResArray);
    if (actionCommand >= 0) {
      node = accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true);
      RuleAction ruleAction =
          new RuleAction(pipeline, actorState, accessibilityFocusMonitor, analytics);
      List<ContextMenuItem> menuItems =
          ruleAction.getMenuItemsForNode(service, node, /* includeAncestors= */ true);
      menuItems.removeIf(CollapsedItemActions::isCollapsedActionItem);
      if (node == null || menuItems.isEmpty()) {
        menuManager.showMenu(CUSTOM_ACTION, eventId, R.string.voice_commands_no_actions_feedback);
      } else {
        menuManager.showMenu(CUSTOM_ACTION, eventId);
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_CUSTOM_ACTION);
      return true;
    }

    // next heading voice command
    // command format: next heading
    if (equals(command, R.string.voice_commands_next_heading)) {
      boolean result;
      node = accessibilityFocusMonitor.getAccessibilityFocus(false);
      result =
          sendInterpretation(VOICE_COMMAND_NEXT_GRANULARITY, CursorGranularity.HEADING, eventId);

      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
        speakDelayed(service.getString(R.string.voice_commands_no_next_heading_feedback));
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_NEXT_HEADING);
      return true;
    }

    // next control voice command
    // command format: next control
    if (equals(command, R.string.voice_commands_next_control)) {
      node = accessibilityFocusMonitor.getAccessibilityFocus(false);

      if (!sendInterpretation(VOICE_COMMAND_NEXT_GRANULARITY, CursorGranularity.CONTROL, eventId)) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
        speakDelayed(service.getString(R.string.voice_commands_no_next_control_feedback));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_NEXT_CONTROL);
      return true;
    }

    // next link voice command
    // command format: next link
    if (equals(command, R.string.voice_commands_next_link)) {
      node = accessibilityFocusMonitor.getAccessibilityFocus(false);

      if (!sendInterpretation(VOICE_COMMAND_NEXT_GRANULARITY, CursorGranularity.LINK, eventId)) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
        speakDelayed(service.getString(R.string.voice_commands_no_next_link_feedback));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_NEXT_LINK);
      return true;
    }

    // verbosity command
    // support command format:
    //             low/high/custom verbosity
    //             verbosity low/high/custom
    //             change verbosity to low/high/custom
    if (verbosityCommandList == null) {
      verbosityCommandList = getCommandList(verbosityCommandArray, verbosityParameters);
    }
    String verbosityCommand = equals(command, verbosityCommandList);
    if (verbosityCommand != null) {
      int verbosityCommandIndex = contains(verbosityCommand, verbosityParameters);
      // TODO workaround for the homophones high and hi.
      if (LocaleUtils.isDefaultLocale(LocaleUtils.LANGUAGE_EN)
          && containsWord(verbosityCommand, R.string.voice_commands_homophone_high_and_hi)) {
        verbosityCommandIndex = 0;
      }
      if (verbosityCommandIndex >= 0) {
        selectorController.changeVerbosity(eventId, verbosityCommandIndex);
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_VERBOSITY);
      return true;
    }

    // word/characters/line/etc navigation set granularity command
    // command format: navigation by %s, %s granularity, read by %s
    if (granularityCommandList == null) {
      granularityCommandList = getCommandList(granularityCommandArray, granularityModeArray);
    }
    String granularityCommand = equals(command, granularityCommandList);
    if (granularityCommand != null) {
      int index = contains(granularityCommand, granularityModeArray);
      if (index >= 0) {
        Setting setting = SelectorController.getSettingByGranularityId(granularityModeArray[index]);
        // TODO Apply selector-changes to pipeline on VoiceCommandProcessor.
        selectorController.selectSetting(setting, /* showOverlay= */ false);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_GRANULARITY);
      return true;
    }

    // next landmark voice command
    // command format: next landmark
    if (equals(command, R.string.voice_commands_next_landmark)) {
      node = accessibilityFocusMonitor.getAccessibilityFocus(false);

      if (!WebInterfaceUtils.supportsWebActions(node)
          || !sendInterpretation(
              VOICE_COMMAND_NEXT_GRANULARITY, CursorGranularity.WEB_LANDMARK, eventId)) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
        speakDelayed(service.getString(R.string.voice_commands_no_next_landmark_feedback));
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_NEXT_LANDMARK);
      return true;
    }

    // show screen command
    // command format:show screen, brighten screen, restore screen, cancel hide screen
    int showScreenCommand = equals(command, showScreenCommandResArray);
    if (showScreenCommand >= 0) {
      if (DimScreenActor.isSupported(service)) {
        sendInterpretation(VOICE_COMMAND_BRIGHTEN_SCREEN, eventId);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SHOW_SCREEN);
      return true;
    }

    // back voice command
    // command format: back, go back
    if (equals(command, R.string.voice_commands_back)
        || equals(command, R.string.voice_commands_go_back)) {
      sendInterpretation(VOICE_COMMAND_BACK, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_BACK);
      return true;
    }

    // talk faster voice command
    // command format: increase speech rate
    if (equals(command, R.string.voice_commands_increase_speech_rate)) {
      selectorController.changeSpeechRate(eventId, /* isIncrease= */ true);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SPEECH_RATE_INCREASE);
      return true;
    }
    // talk slower voice command
    // command format: decrease speech rate
    if (equals(command, R.string.voice_commands_decrease_speech_rate)) {
      selectorController.changeSpeechRate(eventId, /* isIncrease= */ false);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SPEECH_RATE_DECREASE);
      return true;
    }

    // find voice command
    // command format: find *, search for *, search *, find
    int findCommand = startsWith(command, findCommandResArray);
    if (findCommand >= 0) {
      // "Find X": Find argument, starting from root (not focused node), so user need not navigate
      // to root before searching.
      boolean found = false;
      final CharSequence text = remainder(command, findCommand);
      if (TextUtils.isEmpty(text)) {
        found = sendInterpretation(VOICE_COMMAND_REPEAT_SEARCH, eventId);
      } else {
        found = sendInterpretation(VOICE_COMMAND_FIND, text, eventId);
      }
      if (!found) {
        speakDelayed(service.getString(R.string.msg_no_matches));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_FIND);
      return true;
    }

    // type voice command
    // command format: Type *, Input *, Dictate *, Write *, Spell *
    int inputCommand = startsWith(command, typeCommandResArray);
    if (inputCommand >= 0) {
      node = getEditTextFocus();
      if (node != null) {
        CharSequence inputText = remainder(command, inputCommand);
        if (!TextUtils.isEmpty(inputText)) {
          sendInterpretation(VOICE_COMMAND_INSERT, node, inputText, eventId);
        }
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_INSERT);
      return true;
    }

    // label voice command
    // command format: Label *
    if (startsWith(command, R.string.voice_commands_label)) {
      CharSequence label = remainder(command, R.string.voice_commands_label);
      label = SpannableUtils.trimText(label);
      node = accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
      if (node != null && !TextUtils.isEmpty(label)) {
        boolean success = sendInterpretation(VOICE_COMMAND_LABEL, node, label, eventId);
        if (success) {
          String successFeedback = service.getString(R.string.voice_commands_label_saved);
          pipeline.returnFeedback(
              eventId, Feedback.speech(successFeedback, SpeakOptions.create()).setDelayMs(500));
        } else {
          speakDelayed(service.getString(R.string.voice_commands_cannot_label_feedback));
        }
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_LABEL);
      return true;
    }

    // read from cursor voice command
    // command format: Read from cursor position, Read from cursor
    int readFromNextCommand = startsWith(command, readFromNextCommandResArray);
    if (readFromNextCommand >= 0) {
      sendInterpretation(VOICE_COMMAND_START_AT_CURSOR, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_READ_FROM_CURSOR);
      return true;
    }

    // read from top voice command
    // command format: Read from top
    if (startsWith(command, R.string.shortcut_read_from_top)) {
      sendInterpretation(VOICE_COMMAND_START_AT_TOP, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_READ_FROM_TOP);
      return true;
    }

    // Copy last spoken phrase
    // command format: Copy last spoken phrase
    if (startsWith(command, R.string.title_copy_last_spoken_phrase)) {
      sendInterpretation(VOICE_COMMAND_COPY_LAST_SPOKEN_UTTERANCE, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_COPY_LAST_UTTERANCE);
      return true;
    }

    // quick setting voice command
    // command format: * quick setting *, * quick settings *
    int quickSettingCommand = contains(command, quickSettingCommandResArray);
    if (quickSettingCommand >= 0) {
      sendInterpretation(VOICE_COMMAND_QUICK_SETTINGS, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_QUICK_SETTING);
      return true;
    }

    // start talkback setting voice command
    // command format: * talkback setting *, * talkback settings *
    int talkbackSettingCommand = contains(command, talkbackSettingCommandResArray);
    if (talkbackSettingCommand >= 0 && SettingsUtils.allowLinksOutOfSettings(service)) {
      Intent intent = new Intent(service, TalkBackPreferencesActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      service.startActivity(intent);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_TALKBACK_SETTING);
      return true;
    }

    // hide screen command
    // command format: * dim *, * darken *
    if (contains(command, hideScreenCommandResArray) >= 0) {
      dimScreenVoiceCommand(eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_HIDE_SCREEN);
      return true;
    }

    // copy voice command
    // command format: * copy *
    if (containsWord(command, R.string.voice_commands_copy)) {
      node = getEditTextFocus();
      if (node != null) {
        sendInterpretation(VOICE_COMMAND_COPY, node, eventId);
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_COPY);
      return true;
    }

    // paste voice command
    // command format: * paste *
    if (containsWord(command, R.string.voice_commands_paste)) {
      node = getEditTextFocus();
      if (node != null) {
        sendInterpretation(VOICE_COMMAND_PASTE, node, eventId);
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_PASTE);
      return true;
    }

    // delete voice command
    // command format: * delete *
    if (containsWord(command, R.string.voice_commands_delete)) {
      node = getEditTextFocus();
      if (node != null) {
        // Deleted text is gone for good, so the command runs only after a spoken confirmation.
        requestConfirmation(
            service.getString(R.string.voice_commands_delete),
            () -> {
              AccessibilityNodeInfoCompat freshNode = getEditTextFocus();
              if (freshNode != null) {
                sendInterpretation(VOICE_COMMAND_DELETE, freshNode, EVENT_ID_UNTRACKED);
                handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_DELETE);
              }
            });
        return true;
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_DELETE);
      return true;
    }

    // first voice command
    // command format: * first *, * top *
    if (containsWord(command, R.string.voice_commands_first)
        || (containsWord(command, R.string.voice_commands_top))) {
      boolean result = sendInterpretation(VOICE_COMMAND_FIRST, eventId);
      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_FIRST);
      return true;
    }

    // last voice command
    // command format: * last *, * bottom *
    if (containsWord(command, R.string.voice_commands_last)
        || containsWord(command, R.string.voice_commands_bottom)) {
      boolean result = sendInterpretation(VOICE_COMMAND_LAST, eventId);
      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_LAST);
      return true;
    }

    // language voice command
    // command format: * language *, * languages *
    int languageCommand = contains(command, languageCommandResArray);
    if (languageCommand >= 0) {
      menuManager.showMenu(LANGUAGE, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_LANGUAGE);
      return true;
    }

    // notifications command
    // command format: * notification *, * notifications *
    int notificationCommand = contains(command, notificationsCommandResArray);
    if (notificationCommand >= 0) {
      boolean result = sendInterpretation(VOICE_COMMAND_NOTIFICATIONS, eventId);
      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_NOTIFICATION);
      return true;
    }

    // all apps command
    // command format: * apps *
    if (containsWord(command, R.string.voice_commands_apps)
        && FeatureSupport.supportGetSystemActions(service)
        && !containsWord(command, R.string.voice_commands_recent)
        && !containsWord(command, R.string.voice_commands_recents)) {
      boolean result = sendInterpretation(VOICE_COMMAND_ALL_APPS, eventId);
      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ALL_APPS);
      return true;
    }

    // Overview command
    // command format: " recent apps *, * recents *, * recent *, * overview *
    int overviewCommand = contains(command, overviewCommandResArray);
    if (overviewCommand >= 0) {
      boolean result = sendInterpretation(VOICE_COMMAND_RECENT, eventId);
      if (!result) {
        pipeline.returnFeedback(eventId, Feedback.sound(R.raw.complete));
      }
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_RECENT_APPS);
      return true;
    }

    // assistant voice command
    // command format: * assistant *
    if (containsWord(command, R.string.voice_commands_assistant)) {
      service.startActivity(
          new Intent(Intent.ACTION_VOICE_COMMAND).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ASSISTANT);
      return true;
    }

    // home voice command
    // command format: * home *, * desktop *
    if (containsWord(command, R.string.voice_commands_home)
        || containsWord(command, R.string.voice_commands_desktop)) {
      sendInterpretation(VOICE_COMMAND_HOME, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_HOME);
      return true;
    }

    // stop voice command
    // command format: * stop *, * quit *, * quite *, " pause *, nevermind, shut up
    if (containsWord(command, R.string.voice_commands_stop)
        || containsWord(command, R.string.voice_commands_quit)
        || containsWord(command, R.string.voice_commands_quiet)
        || containsWord(command, R.string.voice_commands_pause)
        || equals(command, R.string.voice_commands_nevermind)
        || equals(command, R.string.voice_commands_shut_up)) {
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_QUIT);
      return true;
    }

    // talk faster voice command
    // command format: * faster *, increase speech rate
    if (containsWord(command, R.string.voice_commands_faster)) {
      selectorController.changeSpeechRate(eventId, /* isIncrease= */ true);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SPEECH_RATE_INCREASE);
      return true;
    }

    // talk slower voice command
    // command format: * slower *, decrease speech rate
    if (containsWord(command, R.string.voice_commands_slower)) {
      selectorController.changeSpeechRate(eventId, /* isIncrease= */ false);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_SPEECH_RATE_DECREASE);
      return true;
    }

    // cut voice command
    // command format: * cut *
    if (containsWord(command, R.string.voice_commands_cut)) {
      node = getEditTextFocus();
      if (node != null) {
        sendInterpretation(VOICE_COMMAND_CUT, node, eventId);
      }

      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_CUT);
      return true;
    }

    // help voice command
    // command format: * help *, * what * && * say *
    if (containsAll(command, R.string.voice_commands_what, R.string.voice_commands_say)
        || containsWord(command, R.string.title_pref_help)) {
      sendInterpretation(VOICE_COMMAND_SHOW_COMMAND_LIST, eventId);
      handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_HELP);
      return true;
    }
    return false;
  }

  private VoiceCommandParameters.Keywords parameterKeywords() {
    // Read fresh every time, so phrasings the user replaced in Settings apply at once.
    return VoiceCommandParameters.Keywords.from(service);
  }

  /* Asks about a dangerous command by name and holds it until the spoken answer arrives. */
  private void requestConfirmation(String label, Runnable action) {
    requestConfirmationText(
        service.getString(R.string.voice_commands_confirm_question, label), action);
  }

  /* Asks a yes-or-no question by voice and holds [action] until the spoken answer arrives. */
  private void requestConfirmationText(String question, Runnable action) {
    pendingConfirmationAction = action;
    mainHandler.removeCallbacks(confirmationTimeout);
    mainHandler.postDelayed(confirmationTimeout, CONFIRMATION_TIMEOUT_MS);
    speakDelayed(question);
    if (confirmationSession != null) {
      confirmationSession.requestListenAgain();
    }
  }

  private VoiceCommandPhrases.Keywords phraseKeywords() {
    if (phraseKeywords == null) {
      phraseKeywords = VoiceCommandPhrases.Keywords.from(service);
    }
    return phraseKeywords;
  }

  /* Asks one step of the phrase dialogue by voice and listens for its answer. */
  private void askPhraseQuestion(int questionRes) {
    mainHandler.removeCallbacks(phraseDialogueTimeout);
    mainHandler.postDelayed(phraseDialogueTimeout, CONFIRMATION_TIMEOUT_MS);
    speakDelayed(service.getString(questionRes));
    if (confirmationSession != null) {
      confirmationSession.requestListenAgain();
    }
  }

  /* Drops the phrase dialogue in progress, with every action it had gathered. */
  private void clearPhraseDialogue() {
    phraseStage = PHRASE_STAGE_NONE;
    phraseDraft = null;
    phraseDraftValues.clear();
    phraseDraftLabels.clear();
    mainHandler.removeCallbacks(phraseDialogueTimeout);
  }

  /* Runs when a phrase dialogue answer never arrives: the teaching is abandoned. */
  private void onPhraseDialogueTimeout() {
    if (phraseStage != PHRASE_STAGE_NONE) {
      clearPhraseDialogue();
      speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
    }
  }

  /* Takes one spoken answer of the guided phrase dialogue. */
  private void handlePhraseDialogueAnswer(String command) {
    if (VoiceCommandPhrases.isCancel(command, phraseKeywords())) {
      clearPhraseDialogue();
      speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
      return;
    }
    if (phraseStage == PHRASE_STAGE_PHRASE) {
      phraseDraft = command.trim();
      phraseDraftValues.clear();
      phraseDraftLabels.clear();
      phraseStage = PHRASE_STAGE_ACTION;
      askPhraseQuestion(R.string.voice_phrase_ask_action);
      return;
    }
    // The action step gathers one action per answer until the finish word ends the
    // list: the phrase is then read back with every action before it is saved.
    if (VoiceCommandPhrases.isFinish(command, phraseKeywords())
        && !phraseDraftValues.isEmpty()) {
      finishPhraseTeaching();
      return;
    }
    if (catalogEntries == null) {
      catalogEntries = VoiceCommandCatalog.entries(service);
    }
    VoiceCommandMatcher.Match match =
        VoiceCommandMatcher.INSTANCE.match(command, catalogEntries);
    if (match == null) {
      askPhraseQuestion(R.string.voice_phrase_action_not_found);
      return;
    }
    phraseDraftValues.add(match.getValue());
    phraseDraftLabels.add(match.getPhrase());
    if (phraseDraftValues.size() >= MAX_PHRASE_ACTIONS) {
      finishPhraseTeaching();
      return;
    }
    askPhraseQuestion(R.string.voice_phrase_more_question);
  }

  /* Ends the action gathering of the phrase dialogue and reads the phrase back. */
  private void finishPhraseTeaching() {
    String phraseText = phraseDraft;
    List<String> values = new ArrayList<>(phraseDraftValues);
    List<String> labels = new ArrayList<>(phraseDraftLabels);
    clearPhraseDialogue();
    confirmPhraseSave(phraseText, values, labels);
  }

  /*
   * Reads a taught phrase and its actions back, warning when the phrase collides with a
   * built-in name or an earlier phrase, and saves only on a spoken yes. More than one
   * action makes the phrase a macro.
   */
  private void confirmPhraseSave(
      String phraseText, List<String> values, List<String> labels) {
    if (catalogEntries == null) {
      catalogEntries = VoiceCommandCatalog.entries(service);
    }
    String key = VoiceCommandPhrases.identity(phraseText);
    boolean collision = false;
    for (VoiceCommandMatcher.Entry entry : catalogEntries) {
      if (VoiceCommandPhrases.identity(entry.getPhrase()).equals(key)) {
        collision = true;
        break;
      }
    }
    if (!collision) {
      for (VoiceCommandPhrases.Phrase phrase : VoiceCommandPhrases.load(service)) {
        if (VoiceCommandPhrases.identity(phrase.getPhrase()).equals(key)) {
          collision = true;
          break;
        }
      }
    }
    String question;
    if (values.size() == 1) {
      question =
          service.getString(
              collision
                  ? R.string.voice_phrase_confirm_assign_collision
                  : R.string.voice_phrase_confirm_assign,
              phraseText,
              labels.get(0));
    } else {
      question =
          service.getString(
              collision
                  ? R.string.voice_phrase_confirm_macro_collision
                  : R.string.voice_phrase_confirm_macro_question,
              phraseText,
              String.join(", ", labels));
    }
    requestConfirmationText(
        question,
        () -> {
          VoiceCommandPhrases.add(service, phraseText, values);
          speakDelayed(service.getString(R.string.voice_phrase_saved));
        });
  }

  /*
   * Runs the commands that manage taught phrases, and a taught phrase said in full.
   * Returns true when the command was one of them.
   */
  private boolean handlePhraseCommand(String command, EventId eventId) {
    VoiceCommandPhrases.Keywords keywords = phraseKeywords();
    if (VoiceCommandPhrases.isCreateOpener(command, keywords)) {
      phraseStage = PHRASE_STAGE_PHRASE;
      phraseDraft = null;
      askPhraseQuestion(R.string.voice_phrase_ask_phrase);
      return true;
    }
    List<kotlin.Pair<String, String>> assignment =
        VoiceCommandPhrases.splitAssignment(command, keywords);
    if (assignment != null) {
      if (catalogEntries == null) {
        catalogEntries = VoiceCommandCatalog.entries(service);
      }
      for (kotlin.Pair<String, String> split : assignment) {
        VoiceCommandMatcher.Match match =
            VoiceCommandMatcher.INSTANCE.match(split.getSecond(), catalogEntries);
        if (match != null) {
          confirmPhraseSave(
              split.getFirst(), List.of(match.getValue()), List.of(match.getPhrase()));
          return true;
        }
      }
      speakDelayed(service.getString(R.string.voice_phrase_action_not_found));
      return true;
    }
    if (VoiceCommandPhrases.isListCommand(command, keywords)) {
      speakPhraseList();
      return true;
    }
    String deleteTarget = VoiceCommandPhrases.deleteTarget(command, keywords);
    if (deleteTarget != null) {
      startPhraseDelete(deleteTarget);
      return true;
    }
    // A taught phrase said in full wins over the built-in command of the same words.
    VoiceCommandParameters.Repeat repeat =
        VoiceCommandParameters.parseRepeat(command, parameterKeywords());
    String text = repeat != null ? repeat.getText() : command;
    String key = VoiceCommandPhrases.identity(text);
    for (VoiceCommandPhrases.Phrase phrase : VoiceCommandPhrases.load(service)) {
      if (VoiceCommandPhrases.identity(phrase.getPhrase()).equals(key)) {
        runPhraseValues(phrase.getValues(), repeat, eventId);
        return true;
      }
    }
    return false;
  }

  /* Asks whether the taught phrase [target] names should be deleted, and deletes on a yes. */
  private void startPhraseDelete(String target) {
    List<VoiceCommandPhrases.Phrase> phrases = VoiceCommandPhrases.load(service);
    VoiceCommandMatcher.Match match =
        VoiceCommandMatcher.INSTANCE.match(target, VoiceCommandPhrases.entries(phrases));
    if (match == null) {
      speakDelayed(service.getString(R.string.voice_phrase_not_found));
      return;
    }
    String phraseText = match.getPhrase();
    requestConfirmationText(
        service.getString(R.string.voice_phrase_confirm_delete, phraseText),
        () -> {
          VoiceCommandPhrases.remove(service, phraseText);
          speakDelayed(service.getString(R.string.voice_phrase_deleted));
        });
  }

  /* Speaks every taught phrase with the name of its action. */
  private void speakPhraseList() {
    List<VoiceCommandPhrases.Phrase> phrases = VoiceCommandPhrases.load(service);
    if (phrases.isEmpty()) {
      speakDelayed(service.getString(R.string.voice_phrase_list_empty));
      return;
    }
    StringBuilder spoken = new StringBuilder();
    for (VoiceCommandPhrases.Phrase phrase : phrases) {
      if (spoken.length() > 0) {
        spoken.append("; ");
      }
      List<String> labels = new ArrayList<>();
      for (String value : phrase.getValues()) {
        labels.add(catalogLabel(value));
      }
      spoken.append(phrase.getPhrase()).append(": ").append(String.join(", ", labels));
    }
    speakDelayed(spoken.toString());
  }

  /* The spoken name of the catalog action [value], or the value itself when unknown. */
  private String catalogLabel(String value) {
    if (catalogEntries == null) {
      catalogEntries = VoiceCommandCatalog.entries(service);
    }
    for (VoiceCommandMatcher.Entry entry : catalogEntries) {
      if (entry.getValue().equals(value)) {
        return entry.getPhrase();
      }
    }
    return value;
  }

  /* Runs when no answer arrives in time: the waiting command is cancelled, and says so. */
  private void onConfirmationTimeout() {
    if (pendingConfirmationAction != null) {
      pendingConfirmationAction = null;
      speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
    }
  }

  /** Drops a command waiting for confirmation without a word, e.g. after a recognition error. */
  public void cancelPendingConfirmation() {
    pendingConfirmationAction = null;
    mainHandler.removeCallbacks(confirmationTimeout);
    clearPhraseDialogue();
    dialStage = DIAL_STAGE_NONE;
    mainHandler.removeCallbacks(dialStageTimeout);
    invalidateAiFallback();
  }

  /* Applies one spoken percentage to the volume or the speech rate it names. */
  private void applyPercent(VoiceCommandParameters.Percent percent) {
    switch (percent.getKind()) {
      case SPEECH_VOLUME -> {
        int applied =
            SpeechVolumeControl.set(
                service, SharedPreferencesUtils.getSharedPreferences(service), percent.getValue());
        speakDelayed(service.getString(R.string.selector_speech_volume_value, applied));
      }
      case MEDIA_VOLUME -> {
        VoiceCommandActions.setMediaVolumePercent(service, percent.getValue());
        speakDelayed(
            service.getString(R.string.voice_param_media_volume_value, percent.getValue()));
      }
      case SPEECH_RATE -> {
        int applied = speechRateAndPitchActor.setSpeechRatePercent(percent.getValue());
        speakDelayed(service.getString(R.string.template_speech_rate_change, applied));
      }
    }
  }

  /* Applies a spoken rise or fall to the current value of the volume or rate it names. */
  private void applyRelativePercent(VoiceCommandParameters.RelativePercent relative) {
    int current;
    switch (relative.getKind()) {
      case SPEECH_VOLUME ->
          current =
              SpeechVolumeControl.get(
                  service, SharedPreferencesUtils.getSharedPreferences(service));
      case MEDIA_VOLUME -> current = VoiceCommandActions.getMediaVolumePercent(service);
      case SPEECH_RATE -> current = speechRateAndPitchActor.getSpeechRatePercentage();
      default -> current = 0;
    }
    int target = current + relative.getDelta();
    if (relative.getKind() != VoiceCommandParameters.PercentKind.SPEECH_RATE) {
      target = Math.max(0, Math.min(100, target));
    }
    applyPercent(new VoiceCommandParameters.Percent(relative.getKind(), target));
  }

  /*
   * Moves accessibility focus to the [index]-th item that can take it on the current
   * screen, counting in reading order from 1. Returns false when there is no window to
   * count in; a screen with fewer items is answered with spoken feedback instead.
   */
  private boolean goToItemNumber(int index, EventId eventId) {
    AccessibilityNodeInfoCompat root =
        AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    if (root == null) {
      return false;
    }
    OrderedTraversalStrategy strategy = new OrderedTraversalStrategy(root);
    AccessibilityNodeInfoCompat node =
        TraversalStrategyUtils.findFirstFocusInNodeTree(
            strategy,
            root,
            TraversalStrategy.SEARCH_FOCUS_FORWARD,
            AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS);
    for (int i = 1; i < index && node != null; i++) {
      node =
          TraversalStrategyUtils.searchFocus(
              strategy,
              node,
              TraversalStrategy.SEARCH_FOCUS_FORWARD,
              AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS);
    }
    if (node == null) {
      speakDelayed(service.getString(R.string.voice_item_not_found, index));
      return true;
    }
    pipeline.returnFeedback(
        eventId,
        Feedback.nodeAction(node, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS));
    handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ITEM_NUMBER);
    return true;
  }

  private VoiceCommandPhoneNumbers.Keywords phoneKeywords() {
    // Read fresh every time, so phrasings the user replaced in Settings apply at once.
    return VoiceCommandPhoneNumbers.Keywords.from(service);
  }

  /* Asks for the number of a call command said with none, and listens for it. */
  private void askDialNumber() {
    dialStage = DIAL_STAGE_NUMBER;
    mainHandler.removeCallbacks(dialStageTimeout);
    mainHandler.postDelayed(dialStageTimeout, CONFIRMATION_TIMEOUT_MS);
    speakDelayed(service.getString(R.string.voice_call_ask_number));
    if (confirmationSession != null) {
      confirmationSession.requestListenAgain();
    }
  }

  /* Runs when the asked-for number never arrives: the call is abandoned. */
  private void onDialStageTimeout() {
    if (dialStage != DIAL_STAGE_NONE) {
      dialStage = DIAL_STAGE_NONE;
      speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
    }
  }

  /* Takes the spoken answer to the call command's question for a number. */
  private void handleDialAnswer(String command) {
    dialStage = DIAL_STAGE_NONE;
    if (VoiceCommandPhrases.isCancel(command, phraseKeywords())) {
      speakDelayed(service.getString(R.string.voice_commands_confirm_cancelled));
      return;
    }
    String number = VoiceCommandPhoneNumbers.parseNumber(command);
    if (number == null) {
      speakDelayed(service.getString(R.string.voice_call_number_not_understood));
      noteCommandRan();
      return;
    }
    showCallConfirmation(number);
  }

  /*
   * Shows the confirmation dialog for a spoken number; the call itself is placed only
   * from the dialog's call button. Without the call permission the dialog opens the
   * dialler with the number filled in instead, and that is said aloud here.
   */
  private void showCallConfirmation(String number) {
    VoiceCommandCallDialog.show(
        service,
        number,
        () -> {
          speakDelayed(service.getString(R.string.voice_call_no_permission));
          return kotlin.Unit.INSTANCE;
        });
    handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_DIAL);
  }

  /* Runs a command carrying a spoken percentage or a countdown duration, if this is one. */
  private boolean handleParameterCommand(String command, EventId eventId) {
    VoiceCommandPhoneNumbers.Request call =
        VoiceCommandPhoneNumbers.parseCommand(command, phoneKeywords());
    if (call != null) {
      if (call instanceof VoiceCommandPhoneNumbers.Request.Number) {
        showCallConfirmation(((VoiceCommandPhoneNumbers.Request.Number) call).getNumber());
      } else if (call instanceof VoiceCommandPhoneNumbers.Request.AskNumber) {
        askDialNumber();
      } else {
        // Words followed the call phrases but form no number: say so; nothing is shown.
        speakDelayed(service.getString(R.string.voice_call_number_not_understood));
        noteCommandRan();
      }
      return true;
    }
    VoiceCommandParameters.Keywords keywords = parameterKeywords();
    VoiceCommandParameters.Percent percent =
        VoiceCommandParameters.parsePercent(command, keywords);
    if (percent != null) {
      applyPercent(percent);
      noteCommandRan();
      return true;
    }
    VoiceCommandParameters.RelativePercent relative =
        VoiceCommandParameters.parseRelativePercent(command, keywords);
    if (relative != null) {
      applyRelativePercent(relative);
      noteCommandRan();
      return true;
    }
    Long alarmMillis = VoiceCommandParameters.parseDurationMillis(command, keywords);
    if (alarmMillis != null) {
      VoiceCommandActions.startCountdownAlarm(service, alarmMillis);
      speakDelayed(
          service.getString(
              R.string.countdown_alarm_added, Stopwatch.formatElapsed(service, alarmMillis)));
      noteCommandRan();
      return true;
    }
    Long clockMillis =
        VoiceCommandParameters.parseClockAlarmMillis(
            command, keywords, System.currentTimeMillis());
    if (clockMillis != null) {
      VoiceCommandActions.startCountdownAlarm(service, clockMillis);
      speakDelayed(
          service.getString(
              R.string.countdown_alarm_added, Stopwatch.formatElapsed(service, clockMillis)));
      noteCommandRan();
      return true;
    }
    Integer itemNumber = VoiceCommandParameters.parseItemNumber(command, keywords);
    if (itemNumber != null) {
      return goToItemNumber(itemNumber, eventId);
    }
    return false;
  }

  /* Runs the catalog action whose name the recognized text matches, if any. */
  private boolean handleCatalogCommand(String command, EventId eventId) {
    if (catalogEntries == null) {
      catalogEntries = VoiceCommandCatalog.entries(service);
    }
    // An action name can carry a repeat count: "<name> <count> <times word>".
    VoiceCommandParameters.Repeat repeat =
        VoiceCommandParameters.parseRepeat(command, parameterKeywords());
    String text = repeat != null ? repeat.getText() : command;
    // Taught phrases are matched before the catalog, so they win over action names.
    VoiceCommandMatcher.Match match = null;
    boolean fromPhrase = false;
    List<VoiceCommandPhrases.Phrase> phrases = VoiceCommandPhrases.load(service);
    if (!phrases.isEmpty()) {
      match = VoiceCommandMatcher.INSTANCE.match(text, VoiceCommandPhrases.entries(phrases));
      fromPhrase = match != null;
    }
    if (match == null) {
      match = VoiceCommandMatcher.INSTANCE.match(text, catalogEntries);
    }
    if (match == null) {
      return false;
    }
    if (fromPhrase) {
      runPhraseValues(VoiceCommandPhrases.splitValues(match.getValue()), repeat, eventId);
    } else {
      runCatalogValue(match.getValue(), match.getPhrase(), repeat, eventId);
    }
    return true;
  }

  /*
   * Runs what a taught phrase stands for. A single action behaves like the action said
   * by name, confirmation included. Several actions form a macro and run in order with
   * no confirmation at all, even for a step that cannot be undone: the user composed
   * the steps and confirmed them when saving the phrase. A spoken repeat count does
   * not apply to a macro.
   */
  private void runPhraseValues(
      List<String> values, VoiceCommandParameters.Repeat repeat, EventId eventId) {
    if (values.size() == 1) {
      runCatalogValue(values.get(0), catalogLabel(values.get(0)), repeat, eventId);
      return;
    }
    handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ACTION);
    for (int i = 0; i < values.size(); i++) {
      final String value = values.get(i);
      if (i == 0) {
        service.performCatalogAction(value, eventId);
      } else {
        mainHandler.postDelayed(
            () -> service.performCatalogAction(value, eventId), MACRO_STEP_INTERVAL_MS * i);
      }
    }
  }

  /* Runs one catalog action by value, asking first when the action cannot be undone. */
  private void runCatalogValue(
      String value, String label, VoiceCommandParameters.Repeat repeat, EventId eventId) {
    // Sending composed text cannot be undone, so it runs only after a spoken confirmation.
    if (value.equals(service.getString(R.string.shortcut_value_submit_text))) {
      requestConfirmation(
          label,
          () -> {
            service.performCatalogAction(value, eventId);
            handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ACTION);
          });
      return;
    }
    performCatalogValue(value, repeat, eventId);
  }

  /* Runs one catalog action by value at once, with its spoken repeats. */
  private void performCatalogValue(
      String value, VoiceCommandParameters.Repeat repeat, EventId eventId) {
    service.performCatalogAction(value, eventId);
    handleVoiceCommandRecognized(VOICE_COMMAND_TYPE_ACTION);
    if (repeat != null) {
      for (int i = 1; i < repeat.getCount(); i++) {
        mainHandler.postDelayed(
            () -> service.performCatalogAction(value, eventId), REPEAT_INTERVAL_MS * i);
      }
    }
  }

  private List<String> getCommandList(int[] formattedCommandArray, int[] parameterArrays) {
    List<String> commandList = new ArrayList<>();
    for (int i = 0; i < formattedCommandArray.length; i++) {
      for (String format : phrasesFor(formattedCommandArray[i])) {
        for (int j = 0; j < parameterArrays.length; j++) {
          commandList.add(String.format(format, service.getString(parameterArrays[j])));
        }
      }
    }

    return commandList;
  }

  private void handleVoiceCommandRecognized(@VoiceCommandTypeId int type) {
    analytics.onVoiceCommandEvent(VOICE_COMMAND_RECOGNIZED);
    analytics.onVoiceCommandType(type);
    noteCommandRan();
  }

  private boolean containsAll(String command, int stringResId1, int stringResId2) {
    return containsWord(command, stringResId1) && containsWord(command, stringResId2);
  }

  private int contains(String command, int[] stringResIdArray) {
    if (command == null || stringResIdArray == null) {
      return -1;
    }
    for (int i = 0; i < stringResIdArray.length; i++) {
      if (containsWord(command, stringResIdArray[i])) {
        return i;
      }
    }
    return -1;
  }

  private boolean containsWord(String command, int stringResId) {
    String[] commandSplit = command.split("\\s|\\p{Punct}");
    if (equals((command), stringResId)) {
      return true;
    }
    for (int i = 0; i < commandSplit.length; i++) {
      if (equals(commandSplit[i], stringResId)) {
        return true;
      }
    }
    if (!fuzzyLegacyPass) {
      return false;
    }
    // A command word said without its diacritics still names the command.
    for (String phrase : phrasesFor(stringResId)) {
      String word = VoiceCommandPhrases.identity(phrase);
      if (word.isEmpty()) {
        continue;
      }
      for (int i = 0; i < commandSplit.length; i++) {
        if (VoiceCommandPhrases.identity(commandSplit[i]).equals(word)) {
          return true;
        }
      }
    }
    return false;
  }

  private @Nullable String equals(String command, List<String> stringList) {
    for (int i = 0; i < stringList.size(); i++) {
      if (command.equals(stringList.get(i).toLowerCase())) {
        return command;
      }
    }
    if (!fuzzyLegacyPass) {
      return null;
    }
    List<VoiceCommandMatcher.Entry> entries = new ArrayList<>();
    for (int i = 0; i < stringList.size(); i++) {
      entries.add(new VoiceCommandMatcher.Entry(stringList.get(i), Integer.toString(i)));
    }
    VoiceCommandMatcher.Match match = VoiceCommandMatcher.INSTANCE.match(command, entries);
    return match == null
        ? null
        : stringList.get(Integer.parseInt(match.getValue())).toLowerCase();
  }

  private boolean equals(String command, int stringResId) {
    List<String> phrases = phrasesFor(stringResId);
    for (String phrase : phrases) {
      if (command.equals(phrase.toLowerCase())) {
        return true;
      }
    }
    if (!fuzzyLegacyPass) {
      return false;
    }
    List<VoiceCommandMatcher.Entry> entries = new ArrayList<>();
    for (String phrase : phrases) {
      entries.add(new VoiceCommandMatcher.Entry(phrase, ""));
    }
    return VoiceCommandMatcher.INSTANCE.match(command, entries) != null;
  }

  private boolean startsWith(String command, int stringResId) {
    for (String phrase : phrasesFor(stringResId)) {
      if (command.startsWith(phrase.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  private int startsWith(String command, int[] stringResIdArray) {
    if (command == null || stringResIdArray == null) {
      return -1;
    }
    for (int i = 0; i < stringResIdArray.length; i++) {
      for (String phrase : phrasesFor(stringResIdArray[i])) {
        if (command.startsWith(phrase.toLowerCase())) {
          return stringResIdArray[i];
        }
      }
    }
    return -1;
  }

  private int equals(String command, int[] stringResIdArray) {
    if (command == null || stringResIdArray == null) {
      return -1;
    }
    for (int i = 0; i < stringResIdArray.length; i++) {
      for (String phrase : phrasesFor(stringResIdArray[i])) {
        if (command.equals(phrase.toLowerCase())) {
          return stringResIdArray[i];
        }
      }
    }
    if (!fuzzyLegacyPass) {
      return -1;
    }
    List<VoiceCommandMatcher.Entry> entries = new ArrayList<>();
    for (int i = 0; i < stringResIdArray.length; i++) {
      for (String phrase : phrasesFor(stringResIdArray[i])) {
        entries.add(
            new VoiceCommandMatcher.Entry(phrase, Integer.toString(stringResIdArray[i])));
      }
    }
    VoiceCommandMatcher.Match match = VoiceCommandMatcher.INSTANCE.match(command, entries);
    return match == null ? -1 : Integer.parseInt(match.getValue());
  }

  /* Returns the tail of command-string after the stringResId prefix. */
  private CharSequence remainder(String command, int stringResId) {
    String best = null;
    for (String phrase : phrasesFor(stringResId)) {
      String prefix = phrase.toLowerCase();
      if (command.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
        best = prefix;
      }
    }
    if (best != null) {
      return command.substring(best.length());
    }
    CharSequence prefix = service.getString(stringResId).toLowerCase();
    return command.substring(prefix.length());
  }

  /**
   * The phrasings a string resource is matched by: the ones the user set for it in
   * Settings when they replaced them, or the resource's own text otherwise.
   */
  private List<String> phrasesFor(int resId) {
    List<String> override = VoiceCommandSpeechOverrides.forResource(service, resId);
    if (override != null) {
      return override;
    }
    return Collections.singletonList(service.getString(resId));
  }

  private @Nullable AccessibilityNodeInfoCompat getEditTextFocus() {
    // TODO: Apply the input focus node if the accessibility focus is on IME window.
    @Nullable AccessibilityNodeInfoCompat node =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true);
    if (Role.getRole(node) == Role.ROLE_EDIT_TEXT) {
      return node;
    } else {
      speakDelayed(service.getString(R.string.not_editable));
      return null;
    }
  }

  /* Whether [command] asks for continuous listening in this session. */
  private boolean isContinuousStartCommand(String command) {
    List<String> phrases =
        VoiceCommandSpeechOverrides.forResource(service, R.string.voice_continuous_start_commands);
    if (phrases == null) {
      phrases = VoiceCommandBilingual.list(service, R.string.voice_continuous_start_commands);
    }
    Set<String> words = new HashSet<>();
    for (String word : phrases) {
      words.add(VoiceCommandPhrases.identity(word));
    }
    return words.contains(VoiceCommandPhrases.identity(command));
  }

  private VoiceCommandChain.Keywords chainKeywords() {
    if (chainKeywords == null) {
      chainKeywords = VoiceCommandChain.Keywords.from(service);
    }
    return chainKeywords;
  }

  /*
   * Runs a sentence as a chain of commands joined by a connector word, in order. A part
   * nothing recognizes is skipped and the rest still run. Returns false when the sentence
   * is not a chain.
   */
  private boolean tryChainedCommands(String command, EventId eventId) {
    List<String> parts = VoiceCommandChain.split(command, chainKeywords());
    if (parts.size() < 2) {
      return false;
    }
    final int chainId = ++chainGeneration;
    for (int i = 0; i < parts.size(); i++) {
      final String part = parts.get(i);
      if (i == 0) {
        handleChainedPart(chainId, part, eventId);
      } else {
        mainHandler.postDelayed(
            () -> handleChainedPart(chainId, part, eventId), REPEAT_INTERVAL_MS * i);
      }
    }
    return true;
  }

  /* Runs one part of a chain, unless an earlier part took the session over. */
  private void handleChainedPart(int chainId, String part, EventId eventId) {
    if (chainId != chainGeneration) {
      return;
    }
    if (pendingConfirmationAction != null || phraseStage != PHRASE_STAGE_NONE
        || dialStage != DIAL_STAGE_NONE) {
      // A part asked a question: the chain stops there and the rest is dropped.
      chainGeneration++;
      return;
    }
    handleSpeechCommand(part, /* isPartialResult= */ false, /* chainedPart= */ true);
  }

  /* Tells the listening session that a command just ran. */
  private void noteCommandRan() {
    if (confirmationSession != null) {
      confirmationSession.noteCommandRan();
    }
  }

  /* Speaks the feedback for a command nothing recognized. */
  private void speakUnrecognized(String command) {
    if (echoNotRecognizedTextEnabled) {
      speakDelayed(
          service.getString(R.string.voice_commands_echo_feedback_not_recognized, command));
    } else {
      speakDelayed(
          service.getString(
              R.string.voice_commands_partial_result, service.getString(R.string.title_pref_help)));
    }
  }

  /* Drops an AI answer still on its way, without a word. */
  private void invalidateAiFallback() {
    aiRequestId++;
    aiCommand = null;
    mainHandler.removeCallbacks(aiFallbackTimeout);
  }

  /*
   * Hands an unmatched sentence to the AI fallback, when the user turned it on. Returns
   * true when the fallback took the sentence over; its outcome is spoken when it lands.
   */
  private boolean startAiFallback(String command, EventId eventId) {
    if (!VoiceCommandAi.isEnabled(service)) {
      return false;
    }
    if (catalogEntries == null) {
      catalogEntries = VoiceCommandCatalog.entries(service);
    }
    List<VoiceCommandMatcher.Entry> entries = new ArrayList<>();
    entries.addAll(VoiceCommandPhrases.entries(VoiceCommandPhrases.load(service)));
    entries.addAll(catalogEntries);
    final int requestId = ++aiRequestId;
    aiCommand = command;
    mainHandler.postDelayed(aiFallbackTimeout, AI_FALLBACK_TIMEOUT_MS);
    VoiceCommandAi.interpret(
        service,
        command,
        entries,
        interpretation -> {
          mainHandler.post(() -> onAiInterpretation(requestId, interpretation, eventId));
          return kotlin.Unit.INSTANCE;
        });
    return true;
  }

  /* Runs what the AI made of one sentence, or calls it unrecognized after all. */
  private void onAiInterpretation(
      int requestId, VoiceCommandAi.Interpretation interpretation, EventId eventId) {
    if (requestId != aiRequestId) {
      // A newer command, or the timeout, already settled this sentence.
      return;
    }
    mainHandler.removeCallbacks(aiFallbackTimeout);
    String command = aiCommand;
    aiCommand = null;
    aiRequestId++;
    if (interpretation instanceof VoiceCommandAi.Interpretation.Action) {
      VoiceCommandAi.Interpretation.Action action =
          (VoiceCommandAi.Interpretation.Action) interpretation;
      VoiceCommandMatcher.Entry entry = action.getEntry();
      // The pick runs at once, with no confirmation, and its name is spoken first, so the
      // user hears what their sentence was understood as.
      speakDelayed(entry.getPhrase());
      VoiceCommandParameters.Repeat repeat =
          action.getRepeat() > 1
              ? new VoiceCommandParameters.Repeat(entry.getPhrase(), action.getRepeat())
              : null;
      performCatalogValue(entry.getValue(), repeat, eventId);
    } else if (interpretation instanceof VoiceCommandAi.Interpretation.Percent) {
      applyPercent(((VoiceCommandAi.Interpretation.Percent) interpretation).getPercent());
    } else if (interpretation instanceof VoiceCommandAi.Interpretation.Alarm) {
      long durationMillis =
          ((VoiceCommandAi.Interpretation.Alarm) interpretation).getDurationMillis();
      VoiceCommandActions.startCountdownAlarm(service, durationMillis);
      speakDelayed(
          service.getString(
              R.string.countdown_alarm_added, Stopwatch.formatElapsed(service, durationMillis)));
    } else {
      analytics.onVoiceCommandEvent(VOICE_COMMAND_UNRECOGNIZED);
      speakUnrecognized(command);
    }
  }

  /* Runs when the AI answer never arrives: the sentence is called unrecognized after all. */
  private void onAiFallbackTimeout() {
    if (aiCommand == null) {
      return;
    }
    String command = aiCommand;
    aiCommand = null;
    aiRequestId++;
    analytics.onVoiceCommandEvent(VOICE_COMMAND_UNRECOGNIZED);
    speakUnrecognized(command);
  }

  /** Delay to announce message to inform user. */
  private void speakDelayed(String text) {
    SpeakOptions speakOptions =
        SpeakOptions.create()
            .setFlags(
                FeedbackItem.FLAG_NO_HISTORY
                    | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL);
    pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.speech(text, speakOptions).setDelayMs(RECOGNITION_SPEECH_DELAY_MS));
  }

  private boolean sendInterpretation(Interpretation.VoiceCommand.Action action, EventId eventId) {
    return sendInterpretation(action, /* node= */ null, /* text= */ null, eventId);
  }

  private boolean sendInterpretation(
      Interpretation.VoiceCommand.Action action,
      AccessibilityNodeInfoCompat node,
      EventId eventId) {
    return sendInterpretation(action, node, /* text= */ null, eventId);
  }

  private boolean sendInterpretation(
      Interpretation.VoiceCommand.Action action, CharSequence text, EventId eventId) {
    return sendInterpretation(action, /* node= */ null, text, eventId);
  }

  private boolean sendInterpretation(
      Interpretation.VoiceCommand.Action action,
      AccessibilityNodeInfoCompat node,
      CharSequence text,
      EventId eventId) {
    return interpretationReceiver.input(
        eventId,
        /* event= */ null,
        Interpretation.VoiceCommand.create(action, node, /* granularity= */ null, text));
  }

  private boolean sendInterpretation(
      Interpretation.VoiceCommand.Action action, CursorGranularity granularity, EventId eventId) {
    return interpretationReceiver.input(
        eventId,
        /* event= */ null,
        Interpretation.VoiceCommand.create(
            action, /* targetNode= */ null, granularity, /* text= */ null));
  }
}
