package com.google.android.accessibility.talkback.selector;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;

public final class SpeechVolumeControl {
  private static final int STEP = 10;

  private SpeechVolumeControl() {}

  public static int change(Context context, SharedPreferences prefs, boolean increase) {
    int current = current(context, prefs);
    int next = clamp(context, current + (increase ? STEP : -STEP));
    write(context, prefs, next);
    return next;
  }

  /** The current speech volume percentage, clamped to the allowed range. */
  public static int get(Context context, SharedPreferences prefs) {
    return current(context, prefs);
  }

  /** Sets the speech volume to the given percentage, clamped to the allowed range. */
  public static int set(Context context, SharedPreferences prefs, int percent) {
    int next = clamp(context, percent);
    write(context, prefs, next);
    return next;
  }

  private static int current(Context context, SharedPreferences prefs) {
    String profileId = VoiceProfiles.activeId(prefs);
    int current =
        profileId.isEmpty()
            ? SharedPreferencesUtils.getIntFromStringPref(
                prefs,
                context.getResources(),
                R.string.pref_speech_volume_key,
                R.string.pref_speech_volume_default)
            : VoiceProfiles.read(prefs, profileId).volume();
    return clamp(context, current);
  }

  private static int clamp(Context context, int value) {
    int minimum = context.getResources().getInteger(R.integer.pref_speech_volume_min);
    return Math.max(minimum, Math.min(VoiceProfiles.MAX_VOLUME, value));
  }

  private static void write(Context context, SharedPreferences prefs, int value) {
    String profileId = VoiceProfiles.activeId(prefs);
    String key =
        profileId.isEmpty()
            ? context.getString(R.string.pref_speech_volume_key)
            : VoiceProfiles.key(profileId, VoiceProfiles.VOLUME);
    prefs.edit().putString(key, Integer.toString(value)).apply();
  }
}
