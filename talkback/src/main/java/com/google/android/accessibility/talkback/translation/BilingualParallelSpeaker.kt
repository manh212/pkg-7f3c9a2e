/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.translation

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationVoice
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import com.google.android.accessibility.utils.output.VoiceProfiles
import java.util.Locale

/**
 * The original-text voice of a parallel bilingual reading. The speech controller speaks the
 * translation through its queue and hands the original to this speaker, which reads it on its
 * own text-to-speech engine at the same time, the way the timer announcements are read.
 */
class BilingualParallelSpeaker(private val context: Context) :
  SpeechControllerImpl.ParallelBilingualVoice {

  private var voice: NotificationVoice? = null
  private var voiceKey: String? = null

  override fun speak(
    text: CharSequence,
    profileId: String,
    audioStream: String,
    originalStarted: Runnable,
    originalFailed: Runnable,
  ) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val profile =
      profileId
        .takeIf { it.isNotEmpty() && it in VoiceProfiles.ids(prefs) }
        ?.let { VoiceProfiles.read(prefs, it) }
        ?: VoiceProfiles.readActive(prefs)
        ?: VoiceProfiles.VoiceProfile(
          "",
          "",
          FailoverTextToSpeech.getSelectedEngine(context).orEmpty(),
          "",
          "",
          SharedPreferencesUtils.getIntFromStringPref(
            prefs, context.resources, R.string.pref_speech_volume_key, R.string.pref_speech_volume_default,
          ),
          SharedPreferencesUtils.getFloatFromStringPref(
            prefs, context.resources, R.string.pref_speech_rate_key, R.string.pref_speech_rate_default,
          ),
          SharedPreferencesUtils.getFloatFromStringPref(
            prefs, context.resources, R.string.pref_speech_pitch_key, R.string.pref_speech_pitch_default,
          ),
          false,
        )
    val stream =
      audioStream
        .takeIf { it.isNotEmpty() }
        ?.let { FailoverTextToSpeech.audioStreamForValue(it) }
        ?: FailoverTextToSpeech.getSpeechAudioStream(context)
    val key = "${profile.id}:$stream"
    if (voice == null || voiceKey != key) {
      voice?.shutdown()
      voice = NotificationVoice(context, stream, parallelSpeech = true)
      voiceKey = key
    }
    val selected = voice ?: return
    if (selected.currentProfile != profile) selected.use(profile)
    val locale =
      profile.language()
        .takeIf { it.isNotEmpty() }
        ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
    selected.speak(text, true, locale, object : NotificationVoice.Listener {
      private var started = false

      override fun onStart() {
        started = true
        originalStarted.run()
      }

      override fun onDone(spoken: Boolean) {
        if (!started) originalFailed.run()
      }

      override fun onUnavailable() = originalFailed.run()
    })
  }

  override fun stop() {
    voice?.stop()
  }

  fun shutdown() {
    voice?.shutdown()
    voice = null
    voiceKey = null
  }
}
