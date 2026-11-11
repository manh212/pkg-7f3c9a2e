/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.accessibility.talkback.actor.voicecommands;

import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
import static android.speech.SpeechRecognizer.ERROR_NO_MATCH;
import static android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
import static android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
import static com.google.android.accessibility.talkback.Feedback.Speech.Action.SAVE_LAST;
import static com.google.android.accessibility.talkback.Feedback.Speech.Action.SILENCE;
import static com.google.android.accessibility.talkback.Feedback.Speech.Action.UNSILENCE;
import static com.google.android.accessibility.talkback.Feedback.VoiceRecognition.Action.START_LISTENING;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_ATTEMPT;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_ENGINE_ERROR;
import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.VOICE_COMMAND_TIMEOUT;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Feedback.ShowToast;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.gesture.GestureHints;
import com.google.android.accessibility.talkback.training.VoiceCommandHelpInitiator;
import com.google.android.accessibility.talkback.utils.SpeechRecognizerPerformer;
import com.google.android.accessibility.talkback.utils.SpeechRecognizerPerformer.SpeechRecognizerRequester;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.monitor.ScreenMonitor;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/**
 * Starts and ends the voice recognition for Talkback. For more information on implementation check
 * REFERTO
 */
public class VoiceCommandActor implements SpeechRecognizerRequester {

  private static final String TAG = "VoiceCommandActor";
  static final int RECOGNITION_SPEECH_DELAY_MS = 100;
  public static final int TURN_OFF_RECOGNITION_DELAY_MS = 10000;
  // Listening again for a confirmation answer waits until the spoken question has finished,
  // because starting to listen silences the speech that is still playing.
  private static final int CONFIRMATION_RELISTEN_DELAY_MS = 2000;
  // Continuous listening uses the same wait, so a command's own speech is not cut at once.
  private static final int CONTINUOUS_RELISTEN_DELAY_MS = 2000;
  private static final int CONTINUOUS_ERROR_RELISTEN_DELAY_MS = 800;
  // Continuous listening ends after this long with no command run.
  private static final long CONTINUOUS_IDLE_LIMIT_MS = 30000;

  /** The settings switch that makes every voice command session listen continuously. */
  public static final String PREF_CONTINUOUS = "pref_voice_commands_continuous";

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean continuousActive;
  private boolean awaitingAnswer;
  private boolean relistenRequested;
  private long lastCommandRanAtMs;
  private VoiceCommandDialog voiceCommandDialog;
  private final Context talkbackContext;
  private final TalkBackAnalytics analytics;
  private Pipeline.FeedbackReturner pipeline;

  private final VoiceCommandProcessor voiceCommandProcessor;
  private final SpeechRecognizerPerformer speechRecognizerPerformer;

  @Override
  public boolean onResult(String command, boolean isPartialResult) {
    LogUtils.v(
        TAG, "handleSpeechCommand with command: \"%s\", isPartial:%b", command, isPartialResult);
    String text = commandText(command);
    if (isPartialResult) {
      return voiceCommandProcessor.handleSpeechCommand(text, /* isPartialResult= */ true);
    }
    boolean wasAwaitingAnswer = awaitingAnswer;
    awaitingAnswer = false;
    relistenRequested = false;
    if (continuousActive && !wasAwaitingAnswer && isContinuousStopWord(text)) {
      // A stop word ends continuous listening without reaching the processor, unless the
      // result is the answer to a question the processor asked.
      continuousActive = false;
      return true;
    }
    boolean handled = voiceCommandProcessor.handleSpeechCommand(text, /* isPartialResult= */ false);
    if (continuousActive && handled && !relistenRequested) {
      // The processor did not ask a question of its own: listen for the next command.
      scheduleContinuousRelisten(CONTINUOUS_RELISTEN_DELAY_MS);
    }
    return handled;
  }

  /* Whether [text] is one of the words that end continuous listening. */
  private boolean isContinuousStopWord(String text) {
    String identity = VoiceCommandPhrases.identity(text);
    for (String word : talkbackContext.getString(R.string.voice_continuous_stop_words).split(",")) {
      if (VoiceCommandPhrases.identity(word).equals(identity)) {
        return true;
      }
    }
    return false;
  }

  private boolean continuousIdleExpired() {
    return SystemClock.uptimeMillis() - lastCommandRanAtMs > CONTINUOUS_IDLE_LIMIT_MS;
  }

  /* Listens once more for continuous listening, unless the idle limit has passed. */
  private void scheduleContinuousRelisten(int delayMs) {
    if (continuousIdleExpired()) {
      continuousActive = false;
      return;
    }
    mainHandler.postDelayed(speechRecognizerPerformer::doListening, delayMs);
  }

  @VisibleForTesting
  static String commandText(String speech) {
    return speech.toLowerCase().replaceAll("^[\\p{P}\\s]+|[\\p{P}\\s]+$", "");
  }

  @Override
  public void onFeedbackEvent(@FeedbackEvent int eventId) {
    LogUtils.v(TAG, "eventToFeedback id = %d", eventId);
    switch (eventId) {
      case EVENT_PLATFORM_NOT_SUPPORT ->
          speak(talkbackContext.getString(R.string.voice_commands_no_action));
      case EVENT_OPERATION_FORBIDDEN_DURING_SUW ->
          speak(talkbackContext.getString(R.string.voice_commands_during_setup_hint));
      case EVENT_DIALOG_CONFIRM ->
          pipeline.returnFeedback(
              EVENT_ID_UNTRACKED,
              Feedback.voiceRecognition(START_LISTENING, /* checkDialog= */ false));
      case EVENT_MIC_PERMISSION_NOT_GRANTED ->
          // If not accepted, show a toast for the user.
          pipeline.returnFeedback(
              EVENT_ID_UNTRACKED,
              Feedback.showToast(
                  ShowToast.Action.SHOW,
                  talkbackContext.getString(R.string.voice_commands_no_mic_permissions),
                  true));
      case EVENT_START_LISTENING -> {
        pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(SILENCE));
        analytics.onVoiceCommandEvent(VOICE_COMMAND_ATTEMPT);
      }
      case EVENT_STOP_LISTENING ->
          pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(UNSILENCE));
      case EVENT_FEATURE_UNAVAILABLE ->
          pipeline.returnFeedback(
              EVENT_ID_UNTRACKED,
              Feedback.showToast(
                  ShowToast.Action.SHOW,
                  talkbackContext.getString(R.string.voice_commands_no_voice_recognition_ability),
                  false));
      default -> LogUtils.e(TAG, "Unrecognized event caught.");
    }
  }

  @Override
  public void onError(int error) {
    LogUtils.d(TAG, "errorCallback with error: %d", error);
    if (continuousActive
        && !awaitingAnswer
        && (error == ERROR_NO_MATCH || error == ERROR_SPEECH_TIMEOUT)) {
      // In continuous listening, hearing nothing or nothing understood is not an event
      // worth a spoken error each round: listen again until the idle limit ends the
      // session. No confirmation can be pending here; one would be awaiting its answer.
      scheduleContinuousRelisten(CONTINUOUS_ERROR_RELISTEN_DELAY_MS);
      return;
    }
    // The error message below already tells the user; a command waiting for its confirmation
    // is dropped with the failed answer round.
    voiceCommandProcessor.cancelPendingConfirmation();
    switch (error) {
      case ERROR_INSUFFICIENT_PERMISSIONS ->
          speakDelayed(talkbackContext, R.string.voice_commands_no_mic_permissions);
      case ERROR_RECOGNIZER_BUSY ->
          speakDelayed(talkbackContext, R.string.voice_commands_many_requests);
      case ERROR_NO_MATCH ->
          speakDelayed(
              talkbackContext.getString(
                  R.string.voice_commands_partial_result,
                  talkbackContext.getString(R.string.title_pref_help)));
      case ERROR_SPEECH_TIMEOUT ->
          speakDelayed(
              talkbackContext.getString(
                  R.string.voice_commands_timeout,
                  talkbackContext.getString(R.string.title_pref_help)));
      default -> speakDelayed(talkbackContext, R.string.voice_commands_error);
    }
    analytics.onVoiceCommandEvent(
        error == ERROR_SPEECH_TIMEOUT ? VOICE_COMMAND_TIMEOUT : VOICE_COMMAND_ENGINE_ERROR);
    analytics.onVoiceCommandError(error);
  }

  /** Constructor to initialize variables needed from GestureController. */
  public VoiceCommandActor(
      Context context,
      VoiceCommandProcessor voiceCommandProcessor,
      TalkBackAnalytics analytics,
      SpeechRecognizerPerformer speechRecognizerPerformer) {
    talkbackContext = context;
    this.voiceCommandProcessor = voiceCommandProcessor;
    this.analytics = analytics;
    this.speechRecognizerPerformer = speechRecognizerPerformer;
    speechRecognizerPerformer.setListener(this);
    voiceCommandProcessor.setConfirmationSession(
        new VoiceCommandProcessor.ConfirmationSession() {
          @Override
          public void requestListenAgain() {
            relistenRequested = true;
            awaitingAnswer = true;
            mainHandler.postDelayed(
                speechRecognizerPerformer::doListening, CONFIRMATION_RELISTEN_DELAY_MS);
          }

          @Override
          public void enableContinuousListening() {
            continuousActive = true;
            lastCommandRanAtMs = SystemClock.uptimeMillis();
          }

          @Override
          public void noteCommandRan() {
            lastCommandRanAtMs = SystemClock.uptimeMillis();
          }
        });
  }

  public void setPipeline(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
    voiceCommandDialog = new VoiceCommandDialog(talkbackContext, pipeline);
  }

  /**
   * Looks to see if the appropriate mic permissions are given for voice commands and start to
   * listen if has mic permission.
   *
   * @param checkDialog If the dialog is dismissed and call startListening() again, it needs to
   *     ignore checking dialog display preference, or the dialog would show again.
   */
  public void getSpeechPermissionAndListen(boolean checkDialog) {
    // A new session listens continuously only when the settings switch says so; the
    // spoken command can still switch it on for this session alone.
    continuousActive =
        SharedPreferencesUtils.getSharedPreferences(talkbackContext)
            .getBoolean(PREF_CONTINUOUS, false);
    lastCommandRanAtMs = SystemClock.uptimeMillis();
    if (checkDialog && voiceCommandDialog.getShouldShowDialogPref()) {
      voiceCommandDialog.showDialog();
      return;
    }
    LogUtils.v(TAG, "getSpeechPermissionAndListen with checkDialog: %b", checkDialog);
    speechRecognizerPerformer.doListening();
  }

  public void startListeningIfScreenNotLocked(boolean checkDialog, String nodeMenuShortcut) {
    if (ScreenMonitor.isDeviceLocked(talkbackContext)) {
      // Names where to assign a gesture when none opens the Backtalk menu.
      String menuGesture =
          TextUtils.isEmpty(nodeMenuShortcut)
              ? GestureHints.gestureSettingsPath(talkbackContext)
              : GestureHints.lowerCaseFirst(talkbackContext, nodeMenuShortcut);
      speak(talkbackContext.getString(R.string.voice_command_screen_locked_hint, menuGesture));
    } else {
      pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(SAVE_LAST));
      getSpeechPermissionAndListen(checkDialog);
    }
  }

  /** Stops speech recognition. */
  public void stopListening() {
    speechRecognizerPerformer.stopListening();
  }

  /** Returns first run tutorial dialog for voice commands */
  @VisibleForTesting
  public VoiceCommandDialog getVoiceCommandDialog() {
    return voiceCommandDialog;
  }

  /** Starts the activity of voice command help pages. */
  public void showCommandsHelpPage() {
    talkbackContext.startActivity(
        VoiceCommandHelpInitiator.createVoiceCommandHelpIntent(talkbackContext));
  }

  // TODO: Remove this once the bug is resolved.
  /** Speak into the voice-commands speech queue. Used internally and by GestureController. */
  public void speakDelayed(Context context, int stringResourceId) {
    speakDelayed(context.getString(stringResourceId));
  }

  public void speakDelayed(String text) {
    SpeakOptions speakOptions =
        SpeakOptions.create()
            .setFlags(
                FeedbackItem.FLAG_NO_HISTORY
                    | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL);
    pipeline.returnFeedback(
        // TODO: Add performance EventId support for speech commands.
        EVENT_ID_UNTRACKED,
        Feedback.speech(text, speakOptions).setDelayMs(RECOGNITION_SPEECH_DELAY_MS));
  }

  private void speak(String text) {
    SpeakOptions speakOptions =
        SpeakOptions.create()
            .setFlags(
                FeedbackItem.FLAG_NO_HISTORY
                    | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL);
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, speakOptions));
  }
}
