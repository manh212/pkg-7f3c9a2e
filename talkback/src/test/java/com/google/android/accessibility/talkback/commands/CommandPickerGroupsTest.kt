package com.google.android.accessibility.talkback.commands

import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class CommandPickerGroupsTest {
  @Test
  fun edgePickerSeparatesAllFourFingerCountsAndPreservesEveryGesture() {
    val context = RuntimeEnvironment.getApplication()
    val groups = UnifiedCommandBindings.groupEdgeGestures(context)
    assertEquals(4, groups.size)
    assertEquals(EdgeGesture.values().toSet(), groups.flatMap { it.items }.toSet())
    assertEquals(EdgeGesture.values().size, groups.sumOf { it.items.size })
    for ((index, group) in groups.withIndex()) {
      assertTrue(group.items.isNotEmpty())
      assertTrue(group.items.all { it.fingers == index + 1 })
      assertEquals(group.items.toSet(), group.groups.flatMap { it.items }.toSet())
      for (side in group.groups) {
        assertTrue(side.items.isNotEmpty())
        assertEquals(1, side.items.map { it.edge }.toSet().size)
        assertEquals(1, side.items.map { it.isTap }.toSet().size)
      }
    }
    assertEquals(9, groups[3].items.size)
    assertEquals(3, groups[3].groups.size)
  }
}
