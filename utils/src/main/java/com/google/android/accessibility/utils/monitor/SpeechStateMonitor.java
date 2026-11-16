/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.google.android.accessibility.utils.monitor;

import static android.view.accessibility.AccessibilityEvent.SPEECH_STATE_LISTENING_END;
import static android.view.accessibility.AccessibilityEvent.SPEECH_STATE_LISTENING_START;
import static android.view.accessibility.AccessibilityEvent.SPEECH_STATE_SPEAKING_END;
import static android.view.accessibility.AccessibilityEvent.SPEECH_STATE_SPEAKING_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE;

import android.annotation.TargetApi;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Monitors the event TYPE_SPEECH_STATE_CHANGE from the other applications to start or stop Talkback
 * speaking or listening.
 */
@TargetApi(33)
public class SpeechStateMonitor {
  /** The possible states from the other applications which SpeechStateMonitor keeps. */
  @IntDef(
      value = {SPEECH_STATE_SPEAKING_START, SPEECH_STATE_LISTENING_START},
      flag = true)
  @Retention(RetentionPolicy.SOURCE)
  public @interface ReadSpeechState {}

  // The max time of valid speech state. After that, the speech state will be regarded as invalid.
  private static final int MAX_CHECK_STATE_MILLISEC = 300000;

  private final HashMap<String, StateTimeStamp> packageToLastState = new HashMap<>();

  // The last up time of speech state from the same package name.
  private long lastSpeechUptimeMillisec = 0;

  /** Read-only interface for reading speech state. */
  // public final State state = new State();
  public int getEventTypes() {
    return TYPE_SPEECH_STATE_CHANGE;
  }

  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != TYPE_SPEECH_STATE_CHANGE) {
      return;
    }
    CharSequence packageName = event.getPackageName();
    if (packageName == null) {
      return;
    }
    String senderPackage = packageName.toString();
    if (TextUtils.isEmpty(senderPackage)) {
      return;
    }
    int changeTypes = event.getSpeechStateChangeTypes();

    @Nullable StateTimeStamp oldState = packageToLastState.get(senderPackage);
    int oldStateValue = oldState != null ? oldState.getState() : 0;
    int state = oldStateValue;

    if ((changeTypes & SPEECH_STATE_SPEAKING_END) != 0) {
      state &= ~SPEECH_STATE_SPEAKING_START;
    }
    if ((changeTypes & SPEECH_STATE_LISTENING_END) != 0) {
      state &= ~SPEECH_STATE_LISTENING_START;
    }
    int startTypes = changeTypes & (SPEECH_STATE_SPEAKING_START | SPEECH_STATE_LISTENING_START);
    state |= startTypes;

    if (state == 0) {
      packageToLastState.remove(senderPackage);
    } else if (startTypes != 0 || state != oldStateValue) {
      long timestamp = oldState != null ? oldState.getTimestamp() : 0L;
      if (startTypes != 0) {
        timestamp = SystemClock.uptimeMillis();
        lastSpeechUptimeMillisec = timestamp;
      }
      packageToLastState.put(senderPackage, new StateTimeStamp(state, timestamp));
    }
  }

  public boolean isSpeaking() {
    return isStateValid(SPEECH_STATE_SPEAKING_START);
  }

  public boolean isListening() {
    return isStateValid(SPEECH_STATE_LISTENING_START);
  }

  private boolean isAnyStateValid() {
    long currentSpeechMillisec;
    for (Map.Entry<String, StateTimeStamp> s : packageToLastState.entrySet()) {
      currentSpeechMillisec = SystemClock.uptimeMillis() - s.getValue().getTimestamp();
      if ((currentSpeechMillisec < MAX_CHECK_STATE_MILLISEC)) {
        return true;
      }
    }
    return false;
  }

  private boolean isStateValid(@ReadSpeechState int state) {
    long currentTimeGapMillisec;
    for (Map.Entry<String, StateTimeStamp> s : packageToLastState.entrySet()) {
      currentTimeGapMillisec = SystemClock.uptimeMillis() - s.getValue().getTimestamp();
      if ((currentTimeGapMillisec < MAX_CHECK_STATE_MILLISEC)
          && ((s.getValue().getState() & state) != 0)) {
        return true;
      }
    }

    if (!packageToLastState.isEmpty() && !isAnyStateValid()) {
      packageToLastState.clear();
    }

    return false;
  }

  private static class StateTimeStamp {

    @ReadSpeechState private final int state;
    private final long uptimeMillisec;

    StateTimeStamp(@ReadSpeechState int state, long uptimeMillisec) {
      this.state = state;
      this.uptimeMillisec = uptimeMillisec;
    }

    int getState() {
      return state;
    }

    long getTimestamp() {
      return uptimeMillisec;
    }
  }
}
