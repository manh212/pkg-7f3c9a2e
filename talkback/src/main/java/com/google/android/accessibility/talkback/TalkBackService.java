Warning: truncated output (original token count: 64977)
Total output lines: 6320

/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.google.android.accessibility.talkback;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.RENEW_ENSURE_FOCUS;
import static com.google.android.accessibility.talkback.Feedback.PassThroughMode.Action.DISABLE_PASSTHROUGH;
import static com.google.android.accessibility.talkback.Feedback.Speech.Action.INVALIDATE_FREQUENT_CONTENT_CHANGE_CACHE;
import static com.google.android.accessibility.talkback.TalkBackExitController.TalkBackMistriggeringRecoveryType.TYPE_ACCESSIBILITY_SHORTCUT;
import static com.google.android.accessibility.talkback.TalkBackExitController.TalkBackMistriggeringRecoveryType.TYPE_TALKBACK_EXIT_BANNER;
import static com.google.android.accessibility.talkback.TalkBackExitController.TalkBackMistriggeringRecoveryType.TYPE_TALKBACK_EXIT_WATERMARK;
import static com.google.android.accessibility.talkback.TalkBackServiceDumpHelperKt.getConditionDefault;
import static com.google.android.accessibility.talkback.TalkBackServiceDumpHelperKt.getConditionDefaultVerboseOnly;
import static com.google.android.accessibility.talkback.TalkBackServiceDumpHelperKt.getConditionGivenArgs;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_NAME_ROLE_STATE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_NAME_STATE_ROLE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_ROLE_STATE_NAME_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_STATE_ROLE_NAME_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_ROLE_NAME_STATE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_STATE_NAME_ROLE_POSITION;
import static com.google.android.accessibility.talkback.dynamicfeature.ModuleDownloadPrompter.Requester.ONBOARDING;
import static com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType.ICON_LABEL;
import static com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType.IMAGE_DESCRIPTION;
import static com.google.android.accessibility.talkback.ipc.IpcService.EXTRA_IS_ANY_GESTURE_CHANGED;
import static com.google.android.accessibility.talkback.ipc.IpcService.EXTRA_IS_ICON_DETECTION_UNAVAILABLE;
import static com.google.android.accessibility.talkback.ipc.IpcService.EXTRA_IS_IMAGE_DESCRIPTION_UNAVAILABLE;
import static com.google.android.accessibility.talkback.logging.EventLatencyLogger.EVENTS_TO_LOG_ATTRIBUTES;
import static com.google.android.accessibility.talkback.permission.PermissionRequestActivity.PERMISSIONS;
import static com.google.android.accessibility.talkback.speech.SpeechCacheController.DEFAULT_CACHE_SUPPORT_TTS_ENGINE;
import static com.google.android.accessibility.talkback.speech.SpeechCacheController.DEFAULT_MAX_CACHED_WINDOWS_SIZE;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageId.PAGE_ID_FINISHED;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageId.PAGE_ID_UNKNOWN;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageId.PAGE_ID_UPDATE_WELCOME;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageId.PAGE_ID_WELCOME_TO_TALKBACK;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.UNKNOWN_PAGE_INDEX;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static com.google.android.accessibility.utils.output.SpeechControllerImpl.CAPITAL_LETTERS_TYPE_SPEAK_CAP;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;

import android.Manifest.permission;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.FingerprintGestureController;
import android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.os.UserManagerCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.Braille.BrailleImeInputPolicy;
import com.google.android.accessibility.material.flags.MaterialFlags;
import com.google.android.accessibility.talkback.sandflyai.SandflyAi;
import com.google.android.accessibility.talkback.Feedback.DeviceInfo.Action;
import com.google.android.accessibility.talkback.PrimesController.TimerAction;
import com.google.android.accessibility.talkback.TalkBackExitController.TrainingState;
import com.google.android.accessibility.talkback.actor.AutoScrollActor;
import com.google.android.accessibility.talkback.backup.SettingsProfiles;
import com.google.android.accessibility.talkback.actor.DimScreenActor;
import com.google.android.accessibility.talkback.actor.DimScreenActor.DimScreenNotifier;
import com.google.android.accessibility.talkback.actor.DirectionNavigationActor;
import com.google.android.accessibility.talkback.actor.FocusActor;
import com.google.android.accessibility.talkback.actor.FocusActorForScreenStateChange;
import com.google.android.accessibility.talkback.actor.FocusActorForTapAndTouchExploration;
import com.google.android.accessibility.talkback.actor.FullScreenReadActor;
import com.google.android.accessibility.talkback.actor.GestureReporter;
import com.google.android.accessibility.talkback.actor.ImageCaptioner;
import com.google.android.accessibility.talkback.actor.KeyboardActor;
import com.google.android.accessibility.talkback.actor.LanguageActor;
import com.google.android.accessibility.talkback.actor.NodeActionPerformer;
import com.google.android.accessibility.talkback.actor.NumberAdjustor;
import com.google.android.accessibility.talkback.actor.PassThroughModeActor;
import com.google.android.accessibility.talkback.actor.SpeechRateAndPitchActor;
import com.google.android.accessibility.talkback.actor.SystemActionPerformer;
import com.google.android.accessibility.talkback.actor.TalkBackUIActor;
import com.google.android.accessibility.talkback.actor.TextEditActor;
import com.google.android.accessibility.talkback.actor.TypoNavigator;
import com.google.android.accessibility.talkback.actor.VolumeAdjustor;
import com.google.android.accessibility.talkback.actor.gemini.AiCoreEndpoint;
import com.google.android.accessibility.talkback.actor.gemini.ArateaEndpoint;
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor;
import com.google.android.accessibility.talkback.actor.gemini.GeminiConfiguration;
import com.google.android.accessibility.talkback.actor.gemini.GeminiFunctionUtils;
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestEndpoint;
import com.google.android.accessibility.talkback.actor.gemini.LocalGemmaRequestPerformer;
import com.google.android.accessibility.talkback.actor.search.UniversalSearchActor;
import com.google.android.accessibility.talkback.actor.search.UniversalSearchManager;
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor;
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandProcessor;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.PeriodicDataProvider;
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter;
import com.google.android.accessibility.talkback.collapse.MenuAutomation;
import com.google.android.accessibility.talkback.compositor.Compositor;
import com.google.android.accessibility.talkback.compositor.ClickFeedback;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.talkback.compositor.EarlyFocusSpeech;
import com.google.android.accessibility.talkback.compositor.EventFilter;
import com.google.android.accessibility.talkback.compositor.GlobalVariables;
import com.google.android.accessibility.talkback.compositor.PreparedFocusSpeech;
import com.google.android.accessibility.talkback.compositor.ProgressBarAnnouncer;
import com.google.android.accessibility.talkback.compositor.UsageHintMode;
import com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DescriptionOrder;
import com.google.android.accessibility.talkback.contextmenu.ListMenuManager;
import com.google.android.accessibility.talkback.controller.TelevisionNavigationController;
import com.google.android.accessibility.talkback.controlsounds.ControlSoundsSettings;
import com.google.android.accessibility.talkback.directtouch.DirectTouchController;
import com.google.android.accessibility.talkback.directtouch.DirectTouchSettings;
import com.google.android.accessibility.talkback.eventprocessor.AccessibilityEventProcessor;
import com.google.android.accessibility.talkback.eventprocessor.AccessibilityEventProcessor.TalkBackListener;
import com.google.android.accessibility.talkback.eventprocessor.PhoneticLetterMode;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorEmojiTraversal;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorEventQueue;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorMagnification;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorPhoneticLetters;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorVolumeStream;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorVolumeStream.TouchInteractingIndicator;
import com.google.android.accessibility.talkback.feedbackpolicy.ScreenFeedbackManager;
import com.google.android.accessibility.talkback.feedbackpolicy.WindowNamesSetting;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.flags.Flags;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.FocusFeedbackMapper;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForLogicalNavigation;
import com.google.android.accessibility.talkback.focusmanagement.ScrollAhead;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForScreenStateChange;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration;
import com.google.android.accessibility.talkback.focusmanagement.FocusTextInsideItems;
import com.google.android.accessibility.talkback.focusmanagement.SingleTapTypingController;
import com.google.android.accessibility.talkback.focusmanagement.HomeScreenPages;
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache;
import com.google.android.accessibility.talkback.focusmanagement.AsyncTraversalOrder;
import com.google.android.accessibility.talkback.eventprocessor.BackgroundEventGate;
import com.google.android.accessibility.talkback.eventprocessor.EventProcessingStats;
import com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor;
import com.google.android.accessibility.talkback.focusmanagement.WindowNodePrefetcher;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.TouchExplorationInterpreter;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.gesture.BackTapCommands;
import com.google.android.accessibility.talkback.gesture.EditBoxDoubleTap;
import com.google.android.accessibility.talkback.gesture.GestureController;
import com.google.android.accessibility.talkback.gesture.GestureHistory;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationSettings;
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionStorage;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType;
import com.google.android.accessibility.talkback.imagecaption.ImageContents;
import com.google.android.accessibility.talkback.individualfeedback.IndividualFeedbackSettings;
import com.google.android.accessibility.talkback.individualfeedback.SoundVibrations;
import com.google.android.accessibility.talkback.interpreters.AccessibilityEventIdleInterpreter;
import com.google.android.accessibility.talkback.interpreters.AccessibilityFocusInterpreter;
import com.google.android.accessibility.talkback.interpreters.AutoScrollInterpreter;
import com.google.android.accessibility.talkback.interpreters.DirectionNavigationInterpreter;
import com.google.android.accessibility.talkback.interpreters.FullScreenReadInterpreter;
import com.google.android.accessibility.talkback.interpreters.HintEventInterpreter;
import com.google.android.accessibility.talkback.interpreters.InputFocusInterpreter;
import com.google.android.accessibility.talkback.interpreters.ManualScrollInterpreter;
import com.google.android.accessibility.talkback.interpreters.PassThroughModeInterpreter;
import com.google.android.accessibility.talkback.interpreters.ScrollPositionInterpreter;
import com.google.android.accessibility.talkback.interpreters.StateChangeEventInterpreter;
import com.google.android.accessibility.talkback.interpreters.SubtreeChangeEventInterpreter;
import com.google.android.accessibility.talkback.interpreters.UiChangeEventInterpreter;
import com.google.android.accessibility.talkback.ipc.IpcService;
import com.google.android.accessibility.talkback.ipc.IpcService.IpcClientCallback;
import com.google.android.accessibility.talkback.ipc.IpcService.ServerOnDestroyListener;
import com.google.android.accessibility.talkback.keyboard.KeyComboManager;
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings;
import com.google.android.accessibility.talkback.labeling.CustomLabelManager;
import com.google.android.accessibility.talkback.labeling.StoragelessLabelManager;
import com.google.android.accessibility.talkback.labeling.TalkBackLabelManager;
import com.google.android.accessibility.talkback.logging.EventLatencyLogger;
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator;
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleProcessor;
import com.google.android.accessibility.talkback.monitor.BatteryMonitor;
import com.google.android.accessibility.talkback.monitor.CallStateMonitor;
import com.google.android.accessibility.talkback.monitor.DeviceConfigurationMonitor;
import com.google.android.accessibility.talkback.monitor.FullScreenMonitor;
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor;
import com.google.android.accessibility.talkback.monitor.KeyboardLockMonitor;
import com.google.android.accessibility.talkback.monitor.ProximitySensorMonitor;
import com.google.android.accessibility.talkback.monitor.RingerModeAndScreenMonitor;
import com.google.android.accessibility.talkback.monitor.VolumeMonitor;
import com.google.android.accessibility.talkback.notificationlist.NotificationListService;
import com.google.android.accessibility.talkback.notifications.NotificationReader;
import com.google.android.accessibility.talkback.calls.CallAnnouncements;
import com.google.android.accessibility.talkback.calls.CallLimitController;
import com.google.android.accessibility.talkback.volume.TouchExplorationResumePolicy;
import com.google.android.accessibility.talkback.volume.VolumeKeyManager;
import com.google.android.accessibility.talkback.volume.VolumeKeyVolume;
import com.google.android.accessibility.talkback.notifications.NotificationSettings;
import com.google.android.accessibility.talkback.speechdictionary.SpeechDictionary;
import com.google.android.accessibility.talkback.gesture.AppGestures;
import com.google.android.accessibility.talkback.gesture.GestureProfiles;
import com.google.android.accessibility.talkback.dictation.SandflyDictation;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.pause.PauseController;
import com.google.android.accessibility.talkback.report.VerboseDiagnosticsLogger;
import com.google.android.accessibility.talkback.quicksettings.SystemPanelClicker;
import com.google.android.accessibility.talkback.recognition.LiveRecognition;
import com.google.android.accessibility.talkback.scripting.ScriptHost;
import com.google.android.accessibility.talkback.scripting.ScriptHostFactory;
import com.google.android.accessibility.talkback.scripting.ScriptServices;
import com.google.android.accessibility.talkback.scripting.Scripts;
import com.google.android.accessibility.talkback.selector.SelectorController;
import com.google.android.accessibility.talkback.selector.SelectorController.SelectorEventNotifier;
import com.google.android.accessibility.talkback.screenelements.ScreenElements;
import com.google.android.accessibility.talkback.shizuku.ShizukuClipboard;
import com.google.android.accessibility.talkback.shizuku.ShizukuGrants;
import com.google.android.accessibility.talkback.soundthemes.SoundThemes;
import com.google.android.accessibility.talkback.soundthemes.ThemeFeedback;
import com.google.android.accessibility.talkback.speech.SpeechCacheController;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.acce…54977 tokens truncated…ve()
                || (!overMedia && isAudioPlaybackActive()),
        this::isTouchInteracting);
  }

  private void startScripts() {
    if (pipeline != null && speechController != null) {
      startNotificationReader();
      CallLimitController.attach(this, () -> {
        CallAnnouncements.stopSpeech();
        pipeline.getFeedbackReturner().returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.speech(
                getString(R.string.call_limit_one_minute_warning),
                SpeakOptions.create()
                    .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
                    .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
                        | FeedbackItem.FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP)));
      });
      CallAnnouncements.attach(
          this,
          (text, completion) -> {
            boolean accepted = pipeline.getFeedbackReturner().returnFeedback(
                EVENT_ID_UNTRACKED,
                Feedback.speech(
                    text,
                    SpeechController.SpeakOptions.create()
                        .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
                        .setFlags(
                            FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_PHONE_CALL_ACTIVE
                                | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE)
                        .setCompletedAction(completion)));
            if (!accepted) completion.run(SpeechController.STATUS_ERROR);
            return kotlin.Unit.INSTANCE;
          },
          completion -> {
            speechController.stopItem(completion);
            return kotlin.Unit.INSTANCE;
          },
          () -> !speechController.isMute()
              && !speechController.getShouldSilentSpeech()
              && !PauseController.isPaused()
              && !isMicrophoneActiveAndHeadphoneOff()
              && !isSsbActive(),
          () -> speechController.isSpeaking());
      NotificationListService.onSandFlyStarted(this);
    }
    if (scriptHost != null || pipeline == null || !UserManagerCompat.isUserUnlocked(this)) {
      return;
    }
    scriptHost =
        ScriptHostFactory.create(
            this,
            pipeline.getFeedbackReturner(),
            () -> {
              if (pauseController != null) {
                pauseController.resume();
              }
            },
            new ScriptServicesImpl());
    Scripts.setHost(scriptHost);
    if (scriptHost != null) {
      scriptHost.onPausedChanged(PauseController.isPaused());
    }
  }

  /** What scripts reach in SandFly beyond feedback. */
  private class ScriptServicesImpl implements ScriptServices {
    @Override
    public boolean runCommand(String value) {
      if (gestureController == null) {
        return false;
      }
      gestureController.performAction(value, EVENT_ID_UNTRACKED);
      return true;
    }

    @Override
    public void stopSpeech() {
      if (speechController != null) {
        speechController.interrupt(/* stopTtsSpeechCompletely= */ true);
      }
    }

    @Override
    public boolean isSpeaking() {
      return speechController != null && speechController.isSpeaking();
    }

    @Override
    public boolean isPassThroughActive() {
      return pipeline != null
          && pipeline.getActorState().getPassThroughModeState().isPassThroughModeActive();
    }

    @Override
    public @Nullable String lastSpeech() {
      FeedbackItem last = speechController == null ? null : speechController.getLastUtterance();
      if (last == null) {
        return null;
      }
      CharSequence text =
          EmojiSpeech.restoreOriginalText(SpannableUtils.getCopyableText(last.getAggregateText()));
      return text == null ? null : text.toString().trim();
    }

  }

  public void onShutDown() {
    if (shouldLogAliveTime()) {
      analytics.sendAliveTimeLog();
    }
    if (talkBackExitController != null) {
      talkBackExitController.onShutDown();
    }
  }

  private boolean shouldLogAliveTime() {
    // Mis-trigger log also record the alive time as well. This method is for only log alive time
    // individually so ignore the case mis-trigger has logged.
    return ipcClientCallback != null
        && !ipcClientCallback.isTrainingRecentActive()
        && !ipcClientCallback.hasRequestDisableTalkBackFromMistrigger;
  }

  @Override
  public void uncaughtException(Thread thread, Throwable ex) {
    try {
      if (dimScreenController != null) {
        dimScreenController.shutdown();
      }

      if (menuManager != null && menuManager.isMenuShowing()) {
        menuManager.dismissAll();
      }
    } catch (Exception e) {
      // Do nothing.
    } finally {
      if (systemUeh != null
          && getServiceState() != ServiceStateListener.SERVICE_STATE_SHUTTING_DOWN) {
        systemUeh.uncaughtException(thread, ex);
      } else {
        // There is either no default exception handler available, or the service is in the middle
        // of shutting down.
        // If the service is in the middle of shutting down exceptions would cause the service to
        // crash.
        LogUtils.e(TAG, "Received exception while shutting down.", ex);
      }
    }
  }

  public void setTestingListener(TalkBackListener testingListener) {
    accessibilityEventProcessor.setTestingListener(testingListener);
  }

  public InputModeTracker getInputModeTracker() {
    return inputModeTracker;
  }

  public void registerTalkBackExitEventListener() {
    if (talkBackExitController != null) {
      addEventListener(talkBackExitController);
    }
  }

  public void unregisterTalkBackExitEventListener() {
    if (talkBackExitController != null) {
      postRemoveEventListener(talkBackExitController);
    }
  }

  /** Returns the collection state. */
  public CollectionState getCollectionState() {
    return collectionState;
  }

  /** Runnable to run after announcing "TalkBack off". */
  private static final class DisableTalkBackCompleteAction implements UtteranceCompleteRunnable {
    boolean isDone = false;

    @Override
    public void run(int status) {
      synchronized (DisableTalkBackCompleteAction.this) {
        isDone = true;
        DisableTalkBackCompleteAction.this.notifyAll();
      }
    }
  }

  /** Notifier notifies when TalkBack state changed. */
  public static class TalkbackServiceStateNotifier {
    private final Set<TalkBackServiceStateChangeListener> serviceStateChangeListeners;
    private static TalkbackServiceStateNotifier serviceStateChangeNotifier;

    public static TalkbackServiceStateNotifier getInstance() {
      if (serviceStateChangeNotifier == null) {
        serviceStateChangeNotifier = new TalkbackServiceStateNotifier();
      }
      return serviceStateChangeNotifier;
    }

    private TalkbackServiceStateNotifier() {
      serviceStateChangeListeners = ConcurrentHashMap.newKeySet();
    }

    private void notifyTalkBackServiceStateChanged(boolean enabled) {
      for (TalkBackServiceStateChangeListener serviceStateChangeListener :
          serviceStateChangeListeners) {
        serviceStateChangeListener.onServiceStateChange(enabled);
      }
    }

    public void registerTalkBackServiceStateChangeListener(
        TalkBackServiceStateChangeListener listener) {
      serviceStateChangeListeners.add(listener);
    }

    public void unregisterTalkBackServiceStateChangeListener(
        TalkBackServiceStateChangeListener listener) {
      serviceStateChangeListeners.remove(listener);
    }

    /** Notifies TalkBackService state changed events. */
    public interface TalkBackServiceStateChangeListener {
      /** Callbacks when TalkBackService state changed. */
      void onServiceStateChange(boolean isServiceActive);
    }
  }

  private void resetTouchExplorePassThrough() {
    if (pipeline != null && FeatureSupport.supportPassthrough()) {
      pipeline
          .getFeedbackReturner()
          .returnFeedback(
              Performance.EVENT_ID_UNTRACKED, Feedback.passThroughMode(DISABLE_PASSTHROUGH));
    }
  }

  protected Pipeline.@Nullable FeedbackReturner getFeedbackReturner() {
    if (pipeline != null) {
      return pipeline.getFeedbackReturner();
    }
    return null;
  }

  public @Nullable Statistics getPerformanceStatisticsByLabelAndStageId(
      String label, @StageId int stageId) {
    return Performance.getInstance().getStatisticsByLabelAndStageId(label, stageId);
  }

  final DumpComponent componentNodeHierarchy =
      new DumpComponent(
          "node_hierarchy",
          "dump the node hierarchy of the current layout as TalkBack perceives them (DEBUG mode"
              + " only)",
          getConditionDefaultVerboseOnly(),
          dumpLogger -> {
            if (!IS_DEBUG_BUILD) {
              dumpLogger.log("Can not dump information for node_hierarchy in a non-debug type.");
              return Unit.INSTANCE;
            }
            dumpLogger.log("Current Node Hierarchy:");
            TreeDebug.logNodeTreesOnAllDisplays(this, dumpLogger);
            dumpLogger.log("");
            return Unit.INSTANCE;
          });
  final DumpComponent componentBasicInfo =
      new DumpComponent(
          "basic_info",
          "dump the basic information of the TalkBackService",
          dumpLogger -> {
            dumpBasicInfo(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentDevInfo =
      new DumpComponent(
          "dev_info",
          "dump the developer information of the TalkBackService",
          dumpLogger -> {
            dumpDevInfo(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentGestureMapping =
      new DumpComponent(
          "gesture_mapping",
          "dump the gesture mapping in the TalkBackService settings",
          dumpLogger -> {
            dumpGestureMapping(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentCompositorState =
      new DumpComponent(
          "compositor_state",
          "dump the compositor state relevant to the feedback and its control logic of the"
              + " TalkBackService",
          dumpLogger -> {
            dumpCompositorState(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentSpeechController =
      new DumpComponent(
          "speech_controller",
          "dump the supported languages in the speech controller",
          dumpLogger -> {
            dumpSpeechController(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentPerfMetrics =
      new DumpComponent(
          "perf_metrics",
          "dump the metrics from the performance recorder",
          getConditionGivenArgs(),
          dumpLogger -> {
            Performance.getInstance().dump(dumpLogger);
            return Unit.INSTANCE;
          });
  final DumpComponent componentPerfMetricsClear =
      new DumpComponent(
          "clear_perf_metrics",
          "clear the metrics in the performance recorder",
          getConditionGivenArgs(),
          dumpLogger -> {
            Performance.getInstance().clearAllStatsAndRecords(dumpLogger);
            TraversalTreeCache.resetOrderCacheStats();
            EventProcessingStats.reset();
            return Unit.INSTANCE;
          });
  final DumpComponent componentSpeechCache =
      new DumpComponent(
          "speech_cache",
          "dump the window title of the cached speech",
          getConditionDefault(),
          dumpLogger -> {
            if (speechCacheController != null) {
              speechCacheController.dump(dumpLogger);
            }
            return Unit.INSTANCE;
          });
  final DumpComponent componentHelp =
      new DumpComponent(
          "help",
          "show the help message",
          getConditionGivenArgs(),
          dumpLogger -> {
            dumpLogger.log("adb shell dumpsys activity service TalkBackService [components...]");
            dumpLogger.log("");
            dumpLogger.log(
                "usage1: `adb shell dumpsys activity service TalkBackService` - no parameter"
                    + " request would dump all DEFAULT components");
            dumpLogger.log(
                "usage2: `adb shell dumpsys activity service TalkBackService help` - show the help"
                    + " message");
            dumpLogger.log(
                "usage3: `adb shell dumpsys activity service TalkBackService help basic_info` -"
                    + " show the help message and the basic information of the TalkBack");
            dumpLogger.log("");
            for (DumpComponent component : this.dumpComponents) {
              dumpLogger.log(
                  "%s: %s\n  %s",
                  component.component1(), component.conditionToString(), component.component2());
            }
            return Unit.INSTANCE;
          });
  private ImmutableList<DumpComponent> dumpComponents = null;

  protected void inflateDumpComponents(List<DumpComponent> outDumpComponentList) {
    dumpComponents =
        ImmutableList.sortedCopyOf(
            Comparator.comparing(DumpComponent::component1),
            Stream.of(
                    componentBasicInfo,
                    componentCompositorState,
                    componentDevInfo,
                    componentGestureMapping,
                    componentHelp,
                    componentNodeHierarchy,
                    componentPerfMetrics,
                    componentPerfMetricsClear,
                    componentSpeechController,
                    componentSpeechCache)
                .collect(toCollection(() -> outDumpComponentList)));
  }

  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    super.dump(fd, writer, args);
    if (dumpComponents == null) {
      writer.println("TalkBackService hasn't been initialized due to null dumpComponents.");
      return;
    }
    writer.println(
        "============ Talkback Service Dump: args=" + TextUtils.join(",", args) + " ============");

    Set<String> argsSet = new HashSet<>(Arrays.asList(args));
    Logger dumpLogger = (format, formatArgs) -> writer.println(String.format(format, formatArgs));

    TalkBackServiceDumpHelper.dump(dumpLogger, argsSet, dumpComponents);
  }

  private void dumpGestureMapping(Logger dumpLogger) {
    if (gestureShortcutMapping != null) {
      gestureShortcutMapping.dump(dumpLogger);
    }
  }

  private void dumpCompositorState(Logger dumpLogger) {
    if (globalVariables != null) {
      globalVariables.dump(dumpLogger);
    }
  }

  private void dumpSpeechController(Logger dumpLogger) {
    if (speechController != null) {
      Set<Locale> talkbackLanguages = speechController.state.getLanguages("TALKBACK");
      Set<Locale> selectToSpeakLanguages =
          (talkbackLanguages != null)
              ? null
              : speechController.state.getLanguages("SELECT_TO_SPEAK");
      if (talkbackLanguages == null && selectToSpeakLanguages == null) {
        talkbackLanguages = speechController.state.getLanguages();
      }
      speechController.dump(dumpLogger, talkbackLanguages);
    }
  }

  private void dumpBasicInfo(Logger dumpLogger) {
    dumpLogger.log("TalkBackService basic information: ");
    dumpLogger.log("  versionName=" + PackageManagerUtils.getVersionName(this));
    dumpLogger.log("  versionCode=" + PackageManagerUtils.getVersionCode(this));
    dumpLogger.log("  Debug log=" + LogUtils.isEnabled());
    dumpLogger.log("  Build.VERSION.SDK_INT=" + VERSION.SDK_INT);
    dumpLogger.log(
        "  BuildConfig.DEBUG=" + com.google.android.accessibility.utils.BuildConfig.DEBUG);
    dumpLogger.log("  Flags.BUILD_TYPE=" + Flags.BUILD_TYPE);
    dumpLogger.log("  MaterialFlags.BUILD_TYPE=" + MaterialFlags.BUILD_TYPE);
    dumpLogger.log("");
  }

  private void dumpDevInfo(Logger dumpLogger) {
    dumpLogger.log("TalkBackService developer information: ");
    SharedPreferences sharedPreferences = prefs;
    if (sharedPreferences == null) {
      dumpLogger.log(
          "  TalkBackService hasn't been connected and SharedPreferences hasn't been initialized.");
      return;
    }
    Resources res = getResources();

    dumpLogger.log(
        String.format(
            "  %s=%b",
            res.getString(R.string.title_pref_tts_overlay),
            SharedPreferencesUtils.getBooleanPref(
                prefs, res, R.string.pref_tts_overlay_key, R.bool.pref_tts_overlay_default)));
    dumpLogger.log(
        String.format(
            "  %s=%b",
            res.getString(R.string.title_pref_multiple_gesture_set),
            SharedPreferencesUtils.getBooleanPref(
                prefs,
                res,
                R.string.pref_multiple_gesture_set_key,
                R.bool.pref_multiple_gesture_set_default)));
    dumpLogger.log(
        String.format(
            "  %s=%b",
            res.getString(R.string.title_pref_echo_recognized_text_speech),
            SharedPreferencesUtils.getBooleanPref(
                prefs,
                res,
                R.string.pref_echo_recognized_text_speech_key,
                R.bool.pref_echo_recognized_text_default)));

    dumpLogger.log(
        String.format(
            "  %s=%b",
            res.getString(R.string.title_pref_explore_by_touch),
            SharedPreferencesUtils.getBooleanPref(
                prefs,
                res,
                R.string.pref_explore_by_touch_reflect_key,
                R.bool.pref_explore_by_touch_default)));
    dumpLogger.log(
        String.format(
            "  %s=%b",
            res.getString(R.string.title_pref_debug_log),
            SharedPreferencesUtils.getBooleanPref(
                prefs, res, R.string.pref_debug_log_key, R.bool.pref_debug_log_default)));

    dumpLogger.log("");
  }

  /**
   * Whether the active window is System UI's, such as the notification shade, quick settings or the
   * lock screen. These are system windows, which don't change the app in front, as closing them
   * sends no event for the app under them.
   */
  private boolean isSystemUiActive() {
    for (AccessibilityWindowInfo window : AccessibilityServiceCompatUtils.getWindows(this)) {
      if (!window.isActive()) {
        continue;
      }
      @Nullable AccessibilityNodeInfo root = window.getRoot();
      return root != null
          && AppGestures.SYSTEM_UI.contentEquals(String.valueOf(root.getPackageName()));
    }
    return false;
  }

  /**
   * Whether a window of [windowType] from [packageName] counts as an app's window, or one of
   * Meridian's own overlays, rather than the keyboard or a system window. When its window can't
   * be told, only the system UI's windows count as not an app's.
   */
  private static boolean isAppWindowType(int windowType, @Nullable CharSequence packageName) {
    switch (windowType) {
      case AccessibilityWindowInfo.TYPE_APPLICATION:
      case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
        return true;
      case AccessibilityWindowInfoUtils.WINDOW_TYPE_NONE:
        return !"com.android.systemui".contentEquals(String.valueOf(packageName));
      default:
        return false;
    }
  }

  private void updateAccessibilityEventSubscriptions(int eventTypes) {
    AccessibilityServiceInfo info = getServiceInfo();
    if (info != null && info.eventTypes != eventTypes) {
      info.eventTypes = eventTypes;
      setServiceInfo(info);
    }
  }

  private void updateRideAppAccessibility(@Nullable CharSequence packageName) {
    AccessibilityServiceInfo info = getServiceInfo();
    if (info == null) return;
    int flags = com.google.android.accessibility.utils.RideAppAccessibility.serviceFlags(
        info.flags, packageName);
    if (flags == info.flags) return;
    info.flags = flags;
    setServiceInfo(info);
    TraversalTreeCache.clear("ride app accessibility views changed");
    PreparedFocusSpeech.clear();
  }
}
