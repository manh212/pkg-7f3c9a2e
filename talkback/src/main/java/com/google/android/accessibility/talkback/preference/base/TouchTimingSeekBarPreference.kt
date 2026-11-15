package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.util.AttributeSet
import com.google.android.accessibility.talkback.vietnameseime.MillisecondsSeekBarPreference
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.SharedPreferencesUtils

class TouchTimingSeekBarPreference(context: Context, attrs: AttributeSet) :
  MillisecondsSeekBarPreference(context, attrs) {

  private val doubleTap = key == LatencyExperiments.DOUBLE_TAP_CLICK_DURATION_KEY

  init {
    if (doubleTap) {
      max = LatencyExperiments.maximumDoubleTapClickDurationMs()
      min = LatencyExperiments.minimumDoubleTapClickDurationMs()
      setDefaultValue(LatencyExperiments.defaultDoubleTapClickDurationMs())
    } else {
      max = LatencyExperiments.maximumStationaryTouchDelayMs()
      min = LatencyExperiments.minimumStationaryTouchDelayMs()
      setDefaultValue(LatencyExperiments.defaultStationaryTouchDelayMs())
    }
  }

  override fun onSetInitialValue(defaultValue: Any?) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    value =
      if (doubleTap) LatencyExperiments.doubleTapClickDurationMs(prefs)
      else LatencyExperiments.stationaryTouchDelayMs(prefs)
  }
}
