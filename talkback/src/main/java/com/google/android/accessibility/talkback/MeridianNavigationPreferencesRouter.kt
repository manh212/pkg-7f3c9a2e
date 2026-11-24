package com.google.android.accessibility.talkback

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.google.android.accessibility.talkback.compositor.EventFilter
import com.google.android.accessibility.talkback.feedbackpolicy.ScreenFeedbackManager
import com.google.android.accessibility.talkback.feedbackpolicy.WindowNamesSetting
import com.google.android.accessibility.talkback.focusmanagement.FocusFeedbackMapper
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForLogicalNavigation
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForScreenStateChange
import com.google.android.accessibility.talkback.focusmanagement.FocusTextInsideItems
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.talkback.speechdictionary.SpeechDictionary
import com.google.android.accessibility.talkback.translation.AutomaticTranslation
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.UniversalAccessibilityHelper
import com.google.android.accessibility.utils.input.WindowEventInterpreter

/**
 * Centralizes Meridian navigation, window-transition, gesture, and focus-filter preference
 * synchronization so {@link TalkBackService#reloadPreferences} stays compact and easy to audit.
 */
object MeridianNavigationPreferencesRouter {

  /** Callback invoked when animation scale should be updated for window delay preferences. */
  fun interface AnimationToggle {
    fun setAnimationEnabled(enabled: Boolean)
  }

  /**
   * Reads navigation, window-transition, speech-dictionary, gesture, and focus-filter preferences
   * and pushes them to their target subsystems in a single deterministic pass.
   */
  @JvmStatic
  fun apply(
    context: Context,
    prefs: SharedPreferences,
    res: Resources,
    animationToggle: AnimationToggle?,
  ): String {
    // Preference to turn off animations, so that windows are announced sooner.
    val reduceDelayPref =
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_reduce_window_delay_key,
        R.bool.pref_reduce_window_delay_default,
      )
    animationToggle?.setAnimationEnabled(!reduceDelayPref)
    WindowEventInterpreter.setReduceWindowDelay(reduceDelayPref)

    val windowNames = WindowNamesSetting.read(prefs, res)
    ScreenFeedbackManager.setWindowNames(windowNames)

    AutomaticTranslation.update(context)
    FocusProcessorForLogicalNavigation.setWrapAround(
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_wrap_around_key,
        R.bool.pref_wrap_around_default,
      )
    )
    FocusProcessorForLogicalNavigation.setAutoSwitchPage(
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_auto_switch_page_key,
        R.bool.pref_auto_switch_page_default,
      )
    )
    SpeechDictionary.load(context, prefs)
    AppGestures.load(prefs)
    GestureProfiles.load(prefs)
    FocusFeedbackMapper.setTouchStopsSpeech(
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_touch_stops_speech_key,
        R.bool.pref_touch_stops_speech_default,
      )
    )
    // TV navigation relies on focus following input focus, so the switch is hidden there.
    FocusProcessorForScreenStateChange.setFocusNewWindows(
      FormFactorUtils.isAndroidTv() ||
        SharedPreferencesUtils.getBooleanPref(
          prefs,
          res,
          R.string.pref_focus_new_windows_key,
          R.bool.pref_focus_new_windows_default,
        )
    )
    EventFilter.setSpeakItemsBeforeScroll(
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_speak_items_before_scroll_key,
        R.bool.pref_speak_items_before_scroll_default,
      )
    )
    AccessibilityNodeInfoUtils.setFocusTextInsideItems(FocusTextInsideItems.isOn(context))
    UniversalAccessibilityHelper.init(context)
    val focusContentFilterEnabled =
      SharedPreferencesUtils.getBooleanPref(
        prefs,
        res,
        R.string.pref_focus_content_filter_key,
        R.bool.pref_focus_content_filter_default,
      )
    if (UniversalAccessibilityHelper.isFocusFilterEnabled() != focusContentFilterEnabled) {
      UniversalAccessibilityHelper.setFocusFilterEnabled(focusContentFilterEnabled)
      TraversalTreeCache.clear("focus_content_filter_changed")
    } else {
      UniversalAccessibilityHelper.setFocusFilterEnabled(focusContentFilterEnabled)
    }
    return windowNames
  }
}
