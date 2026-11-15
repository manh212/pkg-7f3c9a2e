package com.google.android.accessibility.talkback.commands

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.utils.FeatureSupport
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/**
 * Two-finger swipes must be assignable in gesture profiles: the gesture lists
 * CommandBindings builds filtered them out, so a profile could never give them a
 * function while the default profile scrolled with them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandBindingsTest {
  private lateinit var controller: ActivityController<FragmentActivity>
  private lateinit var activity: FragmentActivity

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(FragmentActivity::class.java)
    activity = controller.get()
    activity.setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(activity)
    val prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    prefs.edit().clear().commit()
    GestureProfiles.reset(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
    controller.setup()
  }

  @After
  fun tearDown() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    prefs.edit().clear().commit()
    GestureProfiles.load(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
    controller.pause().stop().destroy()
  }

  @Test
  fun twoFingerSwipesAreListedForGestureProfiles() {
    // The old filter only hid the swipes while multi-finger gestures work; without
    // this the test would pass vacuously.
    assertTrue(FeatureSupport.isMultiFingerGestureSupported())
    val fragment = GesturePreferencesFragment()
    activity.supportFragmentManager.beginTransaction()
      .replace(android.R.id.content, fragment).commitNow()

    val categories = CommandBindings.gestureCategories(activity, fragment.preferenceScreen)
    val twoFinger = categories.firstOrNull {
      it.key == activity.getString(R.string.pref_category_2finger_shortcuts_key)
    }
    assertNotNull("Two-finger category is missing", twoFinger)
    val keys = twoFinger!!.gestures.map { it.key }.toSet()
    for (swipeKey in listOf(
      R.string.pref_shortcut_2finger_swipe_up_key,
      R.string.pref_shortcut_2finger_swipe_down_key,
      R.string.pref_shortcut_2finger_swipe_left_key,
      R.string.pref_shortcut_2finger_swipe_right_key,
    )) {
      assertTrue(activity.getString(swipeKey), keys.contains(activity.getString(swipeKey)))
    }
  }

  @Test
  fun twoFingerSwipeAssignmentInAProfileReachesDispatch() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    GestureProfiles.write(
      prefs,
      listOf(
        GestureProfiles.Profile(
          "one",
          "Một",
          mapOf(activity.getString(R.string.pref_shortcut_2finger_swipe_up_key) to "PROFILE"),
        )
      ),
    )
    GestureProfiles.select(activity, prefs, "one")
    val mapping = GestureShortcutMapping(activity)
    try {
      assertEquals(
        "PROFILE",
        mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_2_FINGER_SWIPE_UP),
      )
    } finally {
      mapping.onUnbind()
    }
  }
}

/**
 * Host for inflating the real gesture preferences in tests. Must be a named public
 * class: FragmentManager refuses anonymous fragments ("must be a public static class").
 */
class GesturePreferencesFragment : PreferenceFragmentCompat() {
  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.gesture_preferences)
  }
}
