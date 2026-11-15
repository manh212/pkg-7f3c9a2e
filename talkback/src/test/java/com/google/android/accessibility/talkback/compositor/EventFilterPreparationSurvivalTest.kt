package com.google.android.accessibility.talkback.compositor

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.utils.LatencyExperiments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventFilterPreparationSurvivalTest {
  private fun node(): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo()
    info.packageName = "com.example"
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  @After
  fun reset() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)
  }

  @Test
  fun fixOn_focusMovesOntoPredictedTarget_keepsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)
    val anchor = node()
    val pivot = node()

    assertTrue(EventFilter.shouldKeepPassOnFocusChange(anchor, pivot, pivot))
  }

  @Test
  fun fixOn_focusMovesBackToAnchor_keepsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)
    val anchor = node()
    val pivot = node()

    assertTrue(EventFilter.shouldKeepPassOnFocusChange(anchor, pivot, anchor))
  }

  @Test
  fun fixOn_focusMovesElsewhere_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)
    val anchor = node()
    val pivot = node()

    assertFalse(EventFilter.shouldKeepPassOnFocusChange(anchor, pivot, node()))
  }

  @Test
  fun fixOff_focusMovesOntoPredictedTarget_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(false)
    val anchor = node()
    val pivot = node()

    assertFalse(EventFilter.shouldKeepPassOnFocusChange(anchor, pivot, pivot))
  }

  @Test
  fun fixOff_focusMovesBackToAnchor_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(false)
    val anchor = node()
    val pivot = node()

    assertFalse(EventFilter.shouldKeepPassOnFocusChange(anchor, pivot, anchor))
  }

  @Test
  fun fixOn_granularityChange_keepsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)

    assertTrue(
        EventFilter.shouldKeepPassOnGranularityChange(
            /* latencyExtrasEnabled= */ true, /* anyPreparationEnabled= */ true))
  }

  @Test
  fun fixOff_granularityChange_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(false)

    assertFalse(
        EventFilter.shouldKeepPassOnGranularityChange(
            /* latencyExtrasEnabled= */ true, /* anyPreparationEnabled= */ true))
  }

  @Test
  fun fixOn_granularityChangeWithoutLatencyExtras_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)

    assertFalse(
        EventFilter.shouldKeepPassOnGranularityChange(
            /* latencyExtrasEnabled= */ false, /* anyPreparationEnabled= */ true))
  }

  @Test
  fun fixOn_granularityChangeWithoutPreparation_dropsPass() {
    LatencyExperiments.setKeepPreparationOnFocusChange(true)

    assertFalse(
        EventFilter.shouldKeepPassOnGranularityChange(
            /* latencyExtrasEnabled= */ true, /* anyPreparationEnabled= */ false))
  }

  @Test
  fun reanchor_resetsWalkAndUpdatesControlTargetType() {
    val pass =
        EventFilter.PreparationPass(
            node(), /* maximumDistance= */ 2, NavigationTarget.TARGET_HEADING)
    pass.targetTypeIndex = 1
    pass.directionIndex = 1
    pass.distance = 2
    pass.pivot = node()
    pass.fingerDownDeferrals = 2

    pass.reanchor(NavigationTarget.TARGET_CONTROL)

    assertEquals(NavigationTarget.TARGET_CONTROL, pass.controlTargetType)
    assertEquals(0, pass.targetTypeIndex)
    assertEquals(0, pass.directionIndex)
    assertEquals(1, pass.distance)
    assertNull(pass.pivot)
    assertEquals(0, pass.fingerDownDeferrals)
  }
}
