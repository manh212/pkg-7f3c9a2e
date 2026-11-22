package com.google.android.accessibility.talkback.recognition

import org.junit.Assert.*
import org.junit.Test

class CameraTextTrackerTest {
  @Test fun liveTextReadsTheFirstNonEmptyFrameWithoutWaitingForAMatch() {
    val tracker = CameraTextTracker(requireConfirmation = false)
    assertEquals("Văn bản tiếng Việt", tracker.observe("Văn bản tiếng Việt", 1000))
    assertEquals("Nội dung tiếp theo", tracker.observe("Nội dung tiếp theo", 2000))
    assertNull(tracker.observe("", 3000))
    assertEquals("Một dòng khác", tracker.observe("Một dòng khác", 4000))
  }

  @Test fun oneFrameAndChangingFragmentsAreNeverSpoken() {
    val tracker = CameraTextTracker()
    assertNull(tracker.observe("Em", 1000))
    assertNull(tracker.observe("Giá dịch vụ", 2000))
    assertNull(tracker.observe("Đây là một đoạn văn bản rõ ràng", 3000))
    assertEquals("Đây là một đoạn văn bản rõ ràng", tracker.observe("Đây là một đoạn văn bản rõ ràng", 4000))
  }

  @Test fun numbersMustAgreeEvenWhenTheRestOfTheTextIsAlmostIdentical() {
    assertFalse(CameraTextTracker.similar("vé số kiến thiết tiền giang ngày 22 11 2026 số 482910",
      "vé số kiến thiết tiền giang ngày 22 11 2026 số 482911"))
    assertFalse(CameraTextTracker.similar("thông tin trên tài liệu ngày 12 3 2026",
      "thông tin trên tài liệu ngày 1 23 2026"))
    assertTrue(CameraTextTracker.similar("đây là một tài liệu có nội dung rõ ràng và ổn định",
      "đây là một tài liệu có nội dung rõ ràng và rất ổn định"))
  }

  @Test fun emptyFramesAndLongGapsInvalidateConfirmation() {
    val tracker = CameraTextTracker()
    assertNull(tracker.observe("Tài liệu", 1000))
    assertNull(tracker.observe("", 2000))
    assertNull(tracker.observe("Tài liệu", 3000))
    assertNull(tracker.observe("Tài liệu", 65000))
    assertEquals("Tài liệu", tracker.observe("Tài liệu", 66000))
    tracker.reset()
    assertNull(tracker.observe("Tài liệu", 67000))
  }
}
