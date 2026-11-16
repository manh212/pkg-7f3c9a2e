/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.android.accessibility.talkback.logging;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import com.google.android.accessibility.talkback.PrimesController;
import com.google.android.accessibility.talkback.PrimesController.TimerAction;
import com.google.android.accessibility.utils.LatencyTracker;
import com.google.android.accessibility.utils.Performance.EventData;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.FailoverTtsListener;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.UtteranceInfoCombo;
import com.google.android.libraries.accessibility.utils.concurrent.HandlerExecutor;
import java.util.concurrent.Executor;

/** Logs the event-based latency via {@link PrimesController}. */
public class EventLatencyLogger implements LatencyTracker, FailoverTtsListener {

  static final boolean LATENCY_DEBUG = true;

  /**
   * Bitmask representing the set of view events for which we want to log additional
   * accessibility-related information.
   */
  public static final int EVENTS_TO_LOG_ATTRIBUTES =
      TYPE_VIEW_ACCESSIBILITY_FOCUSED
          | TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
          | TYPE_VIEW_TEXT_SELECTION_CHANGED
          | TYPE_VIEW_TEXT_CHANGED;

  private final Executor executor;
  private final PrimesController primesController;

  public EventLatencyLogger(
      PrimesController primesController, Context context, SharedPreferences prefs) {
    this.primesController = primesController;
    executor = new HandlerExecutor(new Handler(context.getMainLooper()));
  }

  public void destroy() {}

  @Override
  public void onFeedbackOutput(EventData eventData) {
    if (eventData == null) {
      return;
    }
    long startMs = eventData.timeReceivedAtTalkback;
    long endMs = eventData.getTimeFeedbackOutput();
    if (startMs <= 0 || endMs <= startMs) {
      return;
    }
    primesController.recordDuration(TimerAction.EVENT_BASED_HEARING_FEEDBACK, startMs, endMs);
  }

  @Override
  public void onAccessibilityActionPerformed(EventData eventData) {
    if (eventData == null || eventData.getActionDetails() == null) {
      return;
    }
    long processingTime = eventData.getActionDetails().getProcessingTime();
    if (processingTime < 0) {
      return;
    }
    primesController.recordDuration(TimerAction.EVENT_BASED_PERFORMING_ACTION, processingTime);
  }

  @Override
  public void onUtteranceStarted(String utteranceId, long delay) {
    if (delay < 0) {
      return;
    }
    primesController.recordDuration(TimerAction.TTS_DELAY, delay);
  }

  @Override
  public Executor getExecutor() {
    return executor;
  }

  @Override
  public void onTtsInitialized(boolean wasSwitchingEngines, String enginePackageName) {}

  @Override
  public void onBeforeUtteranceRequested(
      String utteranceId, UtteranceInfoCombo utteranceInfoCombo) {}

  @Override
  public void onUtteranceStarted(String utteranceId) {}

  @Override
  public void onUtteranceRangeStarted(String utteranceId, int start, int end) {}

  @Override
  public void onUtteranceCompleted(String utteranceId, boolean success) {}
}
