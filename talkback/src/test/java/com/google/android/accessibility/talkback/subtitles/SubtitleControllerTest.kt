package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.imagecaption.installOfflineOcrTestModels
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleControllerTest {
  class TestService : AccessibilityService() {
    var foregroundPackage = "video.app"
    var foregroundBounds = Rect(0, 0, 1000, 1000)
    override fun getRootInActiveWindow(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
      packageName = foregroundPackage
      setBoundsInScreen(foregroundBounds)
      ReflectionHelpers.callInstanceMethod<Any>(
        this, "setSealed", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
  }
  private class Speaker : SubtitleSpeaker {
    var busy = true
    val spoken = mutableListOf<Pair<String, Boolean>>()
    var stops = 0
    override fun isBusy() = busy
    override fun speak(text: String, interrupt: Boolean) { spoken += text to interrupt }
    override fun stop() { stops++ }
  }
  private lateinit var service: TestService
  private lateinit var controller: SubtitleController
  private lateinit var speaker: Speaker
  private val announcements = mutableListOf<String>()

  @Before fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    installOfflineOcrTestModels(service)
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear()
      .putString(SubtitleSettings.ENGINE, SubtitleSettings.OFFLINE_ENGINE).putString(SubtitleSettings.REGION, "full").commit()
    speaker = Speaker()
    controller = SubtitleController(service, { announcements += it }, speaker) { true }
    controller.toggle()
    assertTrue(get("active") as Boolean)
    set("quietUntil", 0L)
    handlerRemoveCaptures()
  }

  @After fun tearDown() { controller.shutdown() }

  @Test fun changingAppsStopsCompletelyAndComingBackDoesNotRestart() {
    service.foregroundPackage = "other.app"
    controller.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
    assertFalse(get("active") as Boolean)
    assertEquals(1, speaker.stops)
    service.foregroundPackage = "video.app"
    controller.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
    assertFalse(get("active") as Boolean)
  }

  @Test fun aLateOcrResultCannotSpeakAfterStopOrAfterRestart() {
    val request = get("generation") as Int
    controller.stop(false)
    controller.toggle()
    set("quietUntil", 0L)
    frame(request, "Kết quả cũ không được đọc")
    frame(request, "Kết quả cũ không được đọc")
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(speaker.spoken.isEmpty())
  }

  @Test fun finishingModeKeepsOnlyTheNewestPendingSubtitle() {
    val request = get("generation") as Int
    repeat(2) { frame(request, "Phụ đề đầu tiên đang đợi") }
    repeat(2) { frame(request, "Nội dung mới thay thế câu cũ") }
    speaker.busy = false
    handlerRemoveCaptures()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110))
    assertEquals(listOf("Nội dung mới thay thế câu cũ" to false), speaker.spoken)
  }

  @Test fun interruptModePassesTheSelectedPolicyAndTouchStopsOnlySubtitleSpeech() {
    SharedPreferencesUtils.getSharedPreferences(service).edit().putString(SubtitleSettings.SPEECH, "interrupt").commit()
    val request = get("generation") as Int
    repeat(2) { frame(request, "Câu mới cần đọc ngay") }
    assertEquals(listOf("Câu mới cần đọc ngay" to true), speaker.spoken)
    controller.onUserInput()
    assertEquals(1, speaker.stops)
    assertTrue(get("active") as Boolean)
  }

  @Test fun emptyFramesStaySilentWithoutBeingTreatedAsCaptureErrors() {
    // Ensure the idle-no-text threshold is satisfied even with Robolectric's small uptime.
    set("lastDeliveredAt", -10_000L)
    val request = get("generation") as Int
    repeat(8) { frameEmpty(request) }
    assertTrue(get("active") as Boolean)
    assertEquals(0, get("failures") as Int)
    assertEquals(0, announcements.count { it.contains("Chưa tìm thấy phụ đề") })
    repeat(8) { frameEmpty(request) }
    assertEquals(0, announcements.count { it.contains("Chưa tìm thấy phụ đề") })
  }

  @Test fun unrelatedOcrTextIsNotACaptionAndStaysSilent() {
    set("lastDeliveredAt", -10_000L)
    val request = get("generation") as Int
    val unrelated = SubtitleLine("Thanh điều khiển lớn", Rect(0, 0, 1000, 900))
    repeat(8) { frameLines(request, listOf(unrelated)) }
    assertEquals(0, get("failures") as Int)
    assertEquals(0, announcements.count { it.contains("Chưa tìm thấy phụ đề") })
    assertTrue(get("active") as Boolean)
  }

  @Test fun lateOcrResultDoesNotResetNewCaptureAfterTimeout() {
    val oldGeneration = get("generation") as Int
    set("capturePending", true)
    (get("captureTimeout") as Runnable).run()
    assertTrue((get("generation") as Int) > oldGeneration)
    set("capturePending", true)
    frame(oldGeneration, "Kết quả cũ không được đọc")
    assertTrue(get("capturePending") as Boolean)
  }

  @Test fun screenOffStopsTheSession() {
    service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(get("active") as Boolean)
  }

  @Test fun automaticReadsUpperCaptionFromChangedContainerAndFollowsItsReplacement() {
    service.foregroundBounds = Rect(0, 0, 1000, 560)
    restartWithRegion("auto")
    val first = "Hãy công nhận quyền của robot!"
    val second = "Kiên quyết phản đối!"
    val source = captionContainer(first)
    assertFalse(readAccessible(source))
    assertTrue(readAccessible(captionContainer(first), fromWindow = true))
    assertTrue(readAccessible(captionContainer(second)))
    assertTrue(readAccessible(captionContainer(second), fromWindow = true))
    assertEquals(listOf(first to true, second to true), speaker.spoken)
  }

  @Test fun aNarrowCustomRegionReadsTextAtFullCaptionHeightAndFallsBackToOcr() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putInt(SubtitleSettings.TOP, 0).putInt(SubtitleSettings.BOTTOM, 15).commit()
    restartWithRegion("custom")
    val caption = "Một dòng phụ đề cỡ lớn"
    assertTrue(readAccessible(captionContainer(caption), fromWindow = true))
    val request = get("generation") as Int
    frameLines(request, listOf(SubtitleLine("OCR trùng lúc có văn bản", Rect(100, 40, 900, 90))))
    assertEquals(listOf(caption to true), speaker.spoken)

    assertFalse(readAccessible(captionContainer(null), fromWindow = true))
    val fallback = "Phụ đề tiếp theo nhận dạng bằng OCR"
    frameLines(request, listOf(SubtitleLine(fallback, Rect(100, 40, 900, 90))))
    assertEquals(listOf(caption to true, fallback to true), speaker.spoken)
  }

  private fun restartWithRegion(region: String) {
    controller.stop(false)
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putString(SubtitleSettings.REGION, region).putString(SubtitleSettings.SPEECH, "interrupt").commit()
    controller.toggle()
    set("quietUntil", 0L)
    handlerRemoveCaptures()
  }

  private fun captionContainer(caption: String?): AccessibilityNodeInfo {
    val control = AccessibilityNodeInfo.obtain().apply {
      text = "Cài đặt Phụ đề"
      isVisibleToUser = true
      isClickable = true
      setBoundsInScreen(Rect(500, 10, 650, 70))
    }
    return AccessibilityNodeInfo.obtain().apply {
      isVisibleToUser = true
      setBoundsInScreen(service.foregroundBounds)
      shadowOf(this).addChild(control)
      if (caption != null) {
        val line = AccessibilityNodeInfo.obtain().apply {
          text = caption
          isVisibleToUser = true
          setBoundsInScreen(Rect(100, 10, 900, 90))
        }
        shadowOf(this).addChild(line)
      }
      ReflectionHelpers.callInstanceMethod<Any>(
        this, "setSealed", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
  }

  private fun readAccessible(source: AccessibilityNodeInfo, fromWindow: Boolean = false): Boolean {
    val method = SubtitleController::class.java.getDeclaredMethod(
      "readAccessibleSubtitle", AccessibilityNodeInfo::class.java, Boolean::class.javaPrimitiveType)
    method.isAccessible = true
    return try {
      method.invoke(controller, source, fromWindow) as Boolean
    } finally {
      source.recycle()
    }
  }

  private fun frame(request: Int, text: String) {
    val method = SubtitleController::class.java.getDeclaredMethod("complete", Int::class.javaPrimitiveType, List::class.java)
    method.isAccessible = true
    method.invoke(controller, request, listOf(SubtitleLine(text, Rect(100, 800, 900, 850))))
  }
  private fun frameEmpty(request: Int) = frameLines(request, emptyList())
  private fun frameLines(request: Int, lines: List<SubtitleLine>) {
    val method = SubtitleController::class.java.getDeclaredMethod("complete", Int::class.javaPrimitiveType, List::class.java)
    method.isAccessible = true
    method.invoke(controller, request, lines)
  }
  private fun get(name: String): Any? = SubtitleController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
  private fun set(name: String, value: Any) { SubtitleController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
  private fun handlerRemoveCaptures() {
    val handler = get("handler") as android.os.Handler
    handler.removeCallbacks(get("capture") as Runnable)
  }
}
