package com.google.android.accessibility.utils.output

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPcmCacheTest {
  @Test
  fun emptyTextUsesBaseTimeout() {
    assertEquals(2_000L, NavigationPcmCache.synthesisTimeoutMs(0))
  }

  @Test
  fun shortTextScalesLinearlyFromBase() {
    assertEquals(2_200L, NavigationPcmCache.synthesisTimeoutMs(100))
  }

  @Test
  fun mediumTextTakesThreeSeconds() {
    assertEquals(3_000L, NavigationPcmCache.synthesisTimeoutMs(500))
  }

  @Test
  fun timeoutReachesCapAtOneThousandChars() {
    assertEquals(4_000L, NavigationPcmCache.synthesisTimeoutMs(1000))
  }

  @Test
  fun justBelowCapIsNotRoundedUp() {
    assertEquals(3_998L, NavigationPcmCache.synthesisTimeoutMs(999))
  }

  @Test
  fun longTextIsCappedAtFourSeconds() {
    assertEquals(4_000L, NavigationPcmCache.synthesisTimeoutMs(1200))
    assertEquals(4_000L, NavigationPcmCache.synthesisTimeoutMs(5000))
  }

  @Test
  fun negativeLengthFallsBackToBaseTimeout() {
    assertEquals(2_000L, NavigationPcmCache.synthesisTimeoutMs(-5))
  }
}
