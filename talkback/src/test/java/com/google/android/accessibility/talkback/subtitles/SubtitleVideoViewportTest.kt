package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleVideoViewportTest {
  @Test fun detectsYouTubeVideoViewportFromAccessibilityPlayerNode() {
    val player = AccessibilityNodeInfo.obtain()
    try {
      player.viewIdResourceName = "com.google.android.youtube:id/watch_player"
      player.setBoundsInScreen(Rect(20, 100, 1000, 650))
      player.isVisibleToUser = true
      val found = SubtitleVideoViewport.findPlayer(player, Rect(0, 0, 1080, 1920))
      assertEquals(Rect(20, 100, 1000, 650), found?.bounds)
      found?.node?.recycle()
    } finally { player.recycle() }
  }

  @Test fun detectsJwPlayerVideoViewport() {
    val player = AccessibilityNodeInfo.obtain()
    try {
      player.viewIdResourceName = "jw-player-episode"
      player.setBoundsInScreen(Rect(20, 100, 1000, 650))
      player.isVisibleToUser = true
      val found = SubtitleVideoViewport.findPlayer(player, Rect(0, 0, 1080, 1920))
      assertEquals(Rect(20, 100, 1000, 650), found?.bounds)
      found?.node?.recycle()
    } finally { player.recycle() }
  }

  @Test fun ignoresTextAndControlsThatAreNotVideoPlayers() {
    val label = AccessibilityNodeInfo.obtain()
    try {
      label.viewIdResourceName = "com.google.android.youtube:id/video_title"
      label.setBoundsInScreen(Rect(10, 100, 1000, 650))
      label.isVisibleToUser = true
      assertNull(SubtitleVideoViewport.findPlayer(label, Rect(0, 0, 1080, 1920)))
    } finally { label.recycle() }
  }
}
