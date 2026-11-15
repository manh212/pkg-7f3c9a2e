package com.google.android.accessibility.talkback.compositor

import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressReadingTest {
  @Test
  fun readsRangesWithANonzeroMinimum() {
    val node = AccessibilityNodeInfo.obtain().apply {
      rangeInfo = RangeInfo(RangeInfo.RANGE_TYPE_FLOAT, 20f, 220f, 70f)
      contentDescription = "Cài đặt"
    }
    assertEquals(ProgressReading(25, "Cài đặt"), ProgressReading.fromNode(node, null))
  }

  @Test
  fun readsExplicitPercentInTheStateDescription() {
    val node = AccessibilityNodeInfo.obtain().apply {
      stateDescription = "Đã xử lý ٤٥٪"
      contentDescription = "Tải xuống"
    }
    assertEquals(ProgressReading(45, "Tải xuống"), ProgressReading.fromNode(node, null))
  }

  @Test
  fun readsLegacyViewSelectedEventValues() {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SELECTED).apply {
      itemCount = 200
      currentItemIndex = 90
    }
    assertEquals(ProgressReading(45, ""), ProgressReading.fromEvent(event))
    event.currentItemIndex = -1
    assertNull(ProgressReading.fromEvent(event))
  }

  @Test
  fun readsNotificationProgressAndKeepsIndeterminateValuesUnknown() {
    val builder = Notification.Builder(RuntimeEnvironment.getApplication(), "download")
      .setContentTitle("Tải tệp")
    assertNull(ProgressReading.fromNotification(builder.build()))
    val running = builder.setProgress(400, 300, false).build()
    assertEquals(ProgressReading(75, "Tải tệp"), ProgressReading.fromNotification(running))
    val indeterminate = builder.setProgress(0, 0, true).build()
    assertEquals(ProgressReading(null, "Tải tệp"), ProgressReading.fromNotification(indeterminate))
  }
}
