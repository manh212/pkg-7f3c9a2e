/*
 * Copyright (C) 2024 Google Inc.
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

package com.google.android.accessibility.talkback.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.monitor.RingerModeAndScreenMonitor;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Clock;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utility class to get the current time and date. */
public final class DateTimeUtils {
  private static final String TAG = "DateTimeUtils";

  /** Ids of time feedback formats in advanced settings. */
  @IntDef({
    TIME_FEEDBACK_FORMAT_UNDEFINED,
    TIME_FEEDBACK_FORMAT_12_HOURS,
    TIME_FEEDBACK_FORMAT_24_HOURS
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface TimeFeedbackFormat {}

  public static final int TIME_FEEDBACK_FORMAT_UNDEFINED = 0;
  public static final int TIME_FEEDBACK_FORMAT_12_HOURS = 1;
  public static final int TIME_FEEDBACK_FORMAT_24_HOURS = 2;

  private static final Pattern VI_AM_PATTERN =
      Pattern.compile("[\\s\\u00A0\\u202F]+SA(?=$|[\\s,.;:!?])");
  private static final Pattern VI_PM_PATTERN =
      Pattern.compile("[\\s\\u00A0\\u202F]+CH(?=$|[\\s,.;:!?])");

  private static Clock clock = Clock.systemUTC();

  /** Returns the current time in text using the local format. */
  public static String getCurrentTime(Context context, @TimeFeedbackFormat int timeFormat) {
    int timeFlags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_CAP_NOON_MIDNIGHT;

    return formatCurrentTime(context, clock.millis(), timeFlags, timeFormat);
  }

  /** Returns the current time in text using the time format in advanced settings. */
  public static String getCurrentTime(Context context) {
    return getCurrentTime(context, getTimeFeedbackFormat(context));
  }

  /** Formats an arbitrary timestamp using Meridian's configured 12h/24h time format for speech. */
  public static String formatTime(Context context, long millis) {
    int timeFlags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_CAP_NOON_MIDNIGHT;
    return formatCurrentTime(context, millis, timeFlags, getTimeFeedbackFormat(context));
  }

  /** Returns whether Meridian's effective time format is 24-hour. */
  public static boolean is24HourMode(Context context) {
    return resolve24Hour(context, getTimeFeedbackFormat(context));
  }

  /** Replaces Vietnamese ICU SA/CH abbreviations with spoken "sáng"/"chiều" for TTS clarity. */
  public static String normalizeVietnameseAmPm(Context context, String text) {
    if (text == null || text.isEmpty() || !isVietnameseLocale(context)) {
      return text;
    }
    String result = VI_AM_PATTERN.matcher(text).replaceAll(" sáng");
    result = VI_PM_PATTERN.matcher(result).replaceAll(" chiều");
    return result;
  }

  /** Returns the current time and date in text using the local format. */
  public static String getCurrentTimeAndDate(Context context) {
    int timeFlags =
        DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_SHOW_DATE
            | DateUtils.FORMAT_SHOW_WEEKDAY
            | DateUtils.FORMAT_SHOW_YEAR
            | DateUtils.FORMAT_CAP_NOON_MIDNIGHT;

    return formatCurrentDateTime(context, timeFlags, getTimeFeedbackFormat(context));
  }

  /** Returns the time format chosen in advanced settings. */
  private static @TimeFeedbackFormat int getTimeFeedbackFormat(Context context) {
    Resources resources = context.getResources();
    final SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    final String timeFeedbackFormat =
        SharedPreferencesUtils.getStringPref(
            prefs,
            resources,
            R.string.pref_time_feedback_format_key,
            R.string.pref_time_feedback_format_default);

    return RingerModeAndScreenMonitor.prefValueToTimeFeedbackFormat(resources, timeFeedbackFormat);
  }

  // Formats the output in a way that the time comes first and the date comes second.
  private static String formatCurrentDateTime(
      Context context, int timeFlags, @TimeFeedbackFormat int timeFormat) {

    long millis = clock.millis();
    String timeString = formatCurrentTime(context, millis, timeFlags, timeFormat);
    String dateString = formatCurrentDate(context, millis, timeFlags);

    if (timeString.isEmpty()) {
      return dateString;
    }
    if (dateString.isEmpty()) {
      return timeString;
    }
    return BidiFormatter.getInstance()
        .unicodeWrap(
            String.format("%s %s", timeString, dateString), TextDirectionHeuristics.LOCALE);
  }

  private static boolean resolve24Hour(Context context, @TimeFeedbackFormat int timeFormat) {
    return switch (timeFormat) {
      case DateTimeUtils.TIME_FEEDBACK_FORMAT_12_HOURS -> false;
      case DateTimeUtils.TIME_FEEDBACK_FORMAT_24_HOURS -> true;
      default -> DateFormat.is24HourFormat(context);
    };
  }

  private static boolean isVietnameseLocale(Context context) {
    Locale locale = null;
    if (context != null && context.getResources() != null) {
      locale = context.getResources().getConfiguration().getLocales().get(0);
    }
    if (locale == null) {
      locale = Locale.getDefault();
    }
    return locale != null && "vi".equalsIgnoreCase(locale.getLanguage());
  }

  private static String formatVietnameseSpokenTime(long millis, boolean use24Hour) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(millis);
    int hour24 = cal.get(Calendar.HOUR_OF_DAY);
    int minute = cal.get(Calendar.MINUTE);
    if (use24Hour) {
      return minute == 0
          ? String.format(Locale.ROOT, "%d giờ", hour24)
          : String.format(Locale.ROOT, "%d giờ %d phút", hour24, minute);
    }
    int hour12 = hour24 % 12;
    if (hour12 == 0) {
      hour12 = 12;
    }
    final String period;
    if (hour24 == 0) {
      period = "đêm";
    } else if (hour24 < 12) {
      period = "sáng";
    } else if (hour24 == 12) {
      period = "trưa";
    } else if (hour24 < 18) {
      period = "chiều";
    } else {
      period = "tối";
    }
    return minute == 0
        ? String.format(Locale.ROOT, "%d giờ %s", hour12, period)
        : String.format(Locale.ROOT, "%d giờ %d phút %s", hour12, minute, period);
  }

  private static String formatCurrentTime(
      Context context, long millis, int timeFlags, @TimeFeedbackFormat int timeFormat) {
    int currentTimeFlags = timeFlags & DateUtils.FORMAT_SHOW_TIME;
    String timeString = "";
    if (currentTimeFlags != 0) {
      boolean use24Hour = resolve24Hour(context, timeFormat);
      if (isVietnameseLocale(context)) {
        return formatVietnameseSpokenTime(millis, use24Hour);
      }
      currentTimeFlags |= use24Hour ? DateUtils.FORMAT_24HOUR : DateUtils.FORMAT_12HOUR;
      timeString = DateUtils.formatDateTime(context, millis, currentTimeFlags);
    }
    return timeString;
  }

  private static String formatCurrentDate(Context context, long millis, int timeFlags) {
    int dateFlags = timeFlags & ~DateUtils.FORMAT_SHOW_TIME;
    String dateString = "";
    if (dateFlags != 0) {
      dateString = DateUtils.formatDateTime(context, millis, dateFlags);
    }
    return dateString;
  }

  /** Injects a customized clock for testing. */
  @VisibleForTesting
  public static void setMockClock(Clock clock) {
    DateTimeUtils.clock = clock;
  }

  @VisibleForTesting
  public static void resetMockClock() {
    DateTimeUtils.clock = Clock.systemUTC();
  }

  private DateTimeUtils() {}
}
