package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.res.XmlResourceParser
import android.util.Xml
import androidx.annotation.XmlRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The navigation section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun navigationScreen_isSevenSubScreensInOrder() {
    val rows = rowsOf(R.xml.settings_group_navigation)
    assertEquals(
      listOf(
        "pref_navigation_movement",
        "pref_touch_activation",
        "pref_smart_context_settings",
        "pref_direct_touch",
        "pref_category_manage_gestures",
        "pref_category_manage_keyboard_shortcut_key",
        "pref_volume_key_settings_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        "NavigationPrefFragment",
        "TouchActivationFragment",
        "SmartContextGestureSettingsFragment",
        "DirectTouchFragment",
        "TalkBackGestureShortcutPreferenceFragment",
        "TalkBackKeyboardShortcutPreferenceFragment",
        "VolumeKeySettingsFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun navigationScreen_hasNoFeedbackRowAndNoTouchStopsSwitch() {
    val rows = rowsOf(R.xml.settings_group_navigation)
    assertFalse(rows.any { it.first == "pref_navigation_feedback_screen" })
    assertFalse(rows.any { it.second?.endsWith("NavigationFeedbackFragment") == true })
    assertFalse(rows.any { it.first == "pref_touch_stops_speech_key" })
  }

  @Test
  fun gestureScreen_startsWithTwoFingerSwitchesAndHasNoMultiTapTimeout() {
    val rows = rowsOf(R.xml.gesture_preferences)
    assertEquals(
      listOf("pref_extended_two_finger_gestures", "pref_two_finger_rotation"),
      rows.take(2).map { it.first },
    )
    assertFalse(rows.any { it.first == "pref_multi_tap_timeout" })
  }

  @Test
  fun touchActivationScreen_holdsTouchStopsSwitchAndNoExtendedSwitch() {
    val rows = rowsOf(R.xml.touch_activation_preferences)
    assertEquals(
      listOf(
        "pref_lift_to_activate_key",
        "pref_single_tap",
        "pref_fix_stuck_touch_exploration",
        "pref_speak_nav_bar_buttons",
        "pref_touch_stops_speech_key",
      ),
      rows.map { it.first },
    )
    assertFalse(rows.any { it.first == "pref_extended_two_finger_gestures" })
  }

  @Test
  fun movementScreen_hasNoRotateSwitch() {
    assertFalse(
      rowsOf(R.xml.navigation_preferences).any { it.first == "pref_two_finger_rotation" })
  }

  @Test
  fun speakVolumeChangesMovedToSoundAndVibration() {
    assertTrue(
      rowsOf(R.xml.sound_and_vibration_preferences)
        .any { it.first == "pref_speak_volume_changes" && it.second == null })
    assertFalse(
      rowsOf(R.xml.volume_key_preferences).any { it.first == "pref_speak_volume_changes" })
  }

  private fun rowsOf(@XmlRes resId: Int): List<Pair<String, String?>> {
    val parser = context.resources.getXml(resId)
    val rows = mutableListOf<Pair<String, String?>>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser)
        if (key != null) {
          rows.add(key to parser.getAttributeValue(NAMESPACE, "fragment"))
        }
      }
    }
    return rows
  }

  /**
   * Robolectric's getAttributeValue does not resolve @string keys, so read the resource id
   * directly from the parser and resolve it instead.
   */
  private fun keyOf(parser: XmlResourceParser): String? {
    val resId = parser.getAttributeResourceValue(NAMESPACE, "key", 0)
    return if (resId != 0) context.getString(resId) else parser.getAttributeValue(NAMESPACE, "key")
  }

  companion object {
    private const val NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
