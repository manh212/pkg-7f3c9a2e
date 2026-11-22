package com.google.android.accessibility.talkback.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedCommandBindingsTest {

  @Test
  fun stripsGestureSetNumericSuffixOnly() {
    assertEquals(
      "pref_shortcut_right_and_down",
      UnifiedCommandBindings.stripGestureSetSuffix("pref_shortcut_right_and_down-1"),
    )
    assertEquals(
      "pref_shortcut_right_and_down",
      UnifiedCommandBindings.stripGestureSetSuffix("pref_shortcut_right_and_down"),
    )
    assertEquals(
      "pref_shortcut_custom-name",
      UnifiedCommandBindings.stripGestureSetSuffix("pref_shortcut_custom-name"),
    )
  }

  @Test
  fun exposesAllTenTargetKindsAndSevenResetScopes() {
    assertEquals(10, UnifiedCommandBindings.TargetKind.values().size)
    assertEquals(7, UnifiedCommandBindings.ResetScope.values().size)
    assertTrue(
      UnifiedCommandBindings.TargetKind.values().containsAll(
        listOf(
          UnifiedCommandBindings.TargetKind.TOUCH_GESTURE,
          UnifiedCommandBindings.TargetKind.FINGERPRINT_GESTURE,
          UnifiedCommandBindings.TargetKind.EDGE_GESTURE,
          UnifiedCommandBindings.TargetKind.BACK_TAP,
          UnifiedCommandBindings.TargetKind.KEYBOARD_SHORTCUT,
          UnifiedCommandBindings.TargetKind.VOLUME_KEY,
          UnifiedCommandBindings.TargetKind.MERIDIAN_MENU,
          UnifiedCommandBindings.TargetKind.CUSTOM_MENU,
          UnifiedCommandBindings.TargetKind.GESTURE_PROFILE,
          UnifiedCommandBindings.TargetKind.APP_GESTURE,
        )
      )
    )
  }
}
