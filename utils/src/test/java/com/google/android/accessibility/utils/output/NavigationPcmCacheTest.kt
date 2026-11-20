package com.google.android.accessibility.utils.output

import com.google.android.accessibility.utils.Performance.PreparedSpeechWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  /**
   * Q11: pins the exact PreparedSpeechWork names the EventFilter / AsyncTraversalOrder /
   * TraversalTreeCache hooks count (see notes/q11-hooks.md). Only the enum class is
   * touched here — never Performance itself — so this stays plain JUnit with no Android
   * framework. Renaming any of these breaks the coordinator's call sites.
   */
  @Test
  fun q11CounterNamesExist() {
    val names = listOf(
      "STALE_NODE_NOT_HELD",
      "STALE_NODE_GRACED",
      "STALE_NODE_REFRESH_FAILED",
      "NO_TARGET_WEB_PIVOT",
      "NO_TARGET_UNPREDICTABLE",
      "NO_TARGET_SEARCH_EMPTY",
      "NO_TARGET_TARGET_REJECTED",
      "PREPARE_DROP_DEDUP",
      "PREPARE_DROP_STALE_GENERATION",
      "PREPARE_DROP_EPOCH",
      "PREPARE_DROP_TEXT_TOO_LONG",
      "PREPARE_DROP_PCM_GATE",
      "INVALIDATED_ENTRIES",
      "TOUCH_DEFERRED_DIED",
      "IDLE_DEFERRED_DIED",
      "ORDER_BUILD_READY",
      "ORDER_BUILD_STALE",
      "ORDER_BUILD_CANCELLED",
      "ORDER_BUILD_FAILED",
      "NULL_PREDICTION_CACHED",
      "FRAGMENTS_PREPARED",
      "ITEMS_PREPARED",
      "FRAGMENTS_SPOKEN",
      "ITEMS_SPOKEN",
    )
    assertEquals(24, names.size)
    for (name in names) {
      // valueOf throws IllegalArgumentException if the name does not exist.
      assertEquals(name, PreparedSpeechWork.valueOf(name).name)
    }
  }

  /**
   * Q11: the twelve pre-existing values keep their names and their ordinals — the new
   * values are appended only, so dumps from older builds keep their meaning.
   */
  @Test
  fun existingCounterValuesKeepTheirOrder() {
    val existing = listOf(
      "WAITING_FOR_TOUCH",
      "WAITING_FOR_IDLE",
      "MISSING_ORDER",
      "SUPPRESSED_WINDOW",
      "NO_TARGET",
      "STALE_NODE",
      "INELIGIBLE_NODE",
      "EMPTY_SPEECH",
      "PREPARED",
      "INVALIDATED",
      "MISSING_ENTRY",
      "TOO_NEW",
    )
    existing.forEachIndexed { index, name ->
      assertEquals(name, PreparedSpeechWork.valueOf(name).name)
      assertEquals(index, PreparedSpeechWork.valueOf(name).ordinal)
    }
    for (name in listOf("STALE_NODE_NOT_HELD", "FRAGMENTS_SPOKEN", "ITEMS_SPOKEN")) {
      assertTrue(PreparedSpeechWork.valueOf(name).ordinal >= existing.size)
    }
  }
}
