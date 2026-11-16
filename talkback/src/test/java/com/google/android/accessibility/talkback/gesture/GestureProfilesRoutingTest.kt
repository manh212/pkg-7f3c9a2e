package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-ldltr")
class GestureProfilesRoutingTest {
  private val context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private lateinit var mapping: GestureShortcutMapping

  @Before fun start() {
    FormFactorUtils.initialize(context)
    prefs.edit().clear().commit()
    GestureProfiles.reset(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
    mapping = GestureShortcutMapping(context)
  }

  @After fun stop() {
    mapping.onUnbind()
    prefs.edit().clear().commit()
    GestureProfiles.load(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
  }

  private fun select(gestures: Map<String, String>) {
    GestureProfiles.write(prefs, listOf(GestureProfiles.Profile("one", "Một", gestures)))
    GestureProfiles.select(context, prefs, "one")
  }

  @Test fun applicationAssignmentOverridesProfileAndProfileOverridesGlobal() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putString(key, "GLOBAL").commit()
    select(mapOf(key to "PROFILE"))
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    AppGestures.write(prefs, mapOf("example.app" to AppGestures.App("App", mapOf(key to "APP"))))
    AppGestures.load(prefs)
    AppGestures.onWindowShown("example.app")
    assertEquals("APP", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    AppGestures.onWindowShown("another.app")
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
  }

  @Test fun reversedDirectionsApplyToProfileAndItsHint() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putBoolean(ReverseSwipeSettings.KEY, true).commit()
    select(mapOf(key to "PROFILE"))
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_LEFT))
    assertEquals(
      GestureShortcutMapping.getGestureString(context, AccessibilityService.GESTURE_SWIPE_LEFT),
      mapping.getGestureFromActionKey("PROFILE"),
    )
  }

  @Test fun extendedGesturesAndRotationHaveAssignableProfileKeys() {
    val extended = context.getString(R.string.pref_extended_gesture_up_down_key)
    val rotation = context.getString(R.string.pref_shortcut_2finger_rotate_clockwise_key)
    select(mapOf(extended to "EXTENDED", rotation to "ROTATION"))
    assertEquals("EXTENDED", mapping.getActionKeyFromGestureId(10001))
    assertEquals("ROTATION", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ROTATE_CLOCKWISE))
    assertTrue(mapping.hasGestureOverride(GestureShortcutMapping.ROTATE_CLOCKWISE))
  }

  @Test fun pinchAndAnchoredGesturesHaveAssignableProfileKeys() {
    val pinchIn = context.getString(R.string.pref_shortcut_2finger_pinch_key)
    val pinchOut = context.getString(R.string.pref_shortcut_2finger_spread_key)
    val anchoredUp = context.getString(R.string.pref_shortcut_2finger_anchored_swipe_up_key)
    val anchoredDown = context.getString(R.string.pref_shortcut_2finger_anchored_swipe_down_key)
    val anchoredLeft = context.getString(R.string.pref_shortcut_2finger_anchored_swipe_left_key)
    val anchoredRight = context.getString(R.string.pref_shortcut_2finger_anchored_swipe_right_key)
    val anchoredTap = context.getString(R.string.pref_shortcut_2finger_anchored_tap_key)
    select(mapOf(pinchIn to "PINCH_IN", pinchOut to "PINCH_OUT", anchoredUp to "ANCHORED_UP",
        anchoredDown to "ANCHORED_DOWN", anchoredLeft to "ANCHORED_LEFT",
        anchoredRight to "ANCHORED_RIGHT", anchoredTap to "ANCHORED_TAP"))
    assertEquals("PINCH_IN", mapping.getActionKeyFromGestureId(GestureShortcutMapping.PINCH_IN))
    assertEquals("PINCH_OUT", mapping.getActionKeyFromGestureId(GestureShortcutMapping.PINCH_OUT))
    assertEquals("ANCHORED_UP", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ANCHORED_SWIPE_UP))
    assertEquals("ANCHORED_DOWN", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ANCHORED_SWIPE_DOWN))
    assertEquals("ANCHORED_LEFT", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ANCHORED_SWIPE_LEFT))
    assertEquals("ANCHORED_RIGHT", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ANCHORED_SWIPE_RIGHT))
    assertEquals("ANCHORED_TAP", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ANCHORED_TAP))
    assertTrue(mapping.hasGestureOverride(GestureShortcutMapping.ANCHORED_TAP))
    assertEquals("Chụm hai ngón", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.PINCH_IN))
    assertEquals("Xòe hai ngón", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.PINCH_OUT))
    assertEquals("Giữ một ngón rồi vuốt lên", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.ANCHORED_SWIPE_UP))
    assertEquals("Giữ một ngón rồi vuốt xuống", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.ANCHORED_SWIPE_DOWN))
    assertEquals("Giữ một ngón rồi vuốt sang trái", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.ANCHORED_SWIPE_LEFT))
    assertEquals("Giữ một ngón rồi vuốt sang phải", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.ANCHORED_SWIPE_RIGHT))
    assertEquals("Giữ một ngón rồi chạm bằng ngón kia", GestureShortcutMapping.getGestureString(context, GestureShortcutMapping.ANCHORED_TAP))
  }

  @Test fun unassignedProfileIsDistinctFromInheritedGlobalAssignment() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putString(key, "GLOBAL").commit()
    select(mapOf(key to context.getString(R.string.shortcut_value_unassigned)))
    assertEquals(context.getString(R.string.shortcut_value_unassigned), mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    GestureProfiles.setRowValue(prefs, GestureProfiles.rowKey("one", key), GestureProfiles.DEFAULT_VALUE)
    assertEquals("GLOBAL", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
  }
}
