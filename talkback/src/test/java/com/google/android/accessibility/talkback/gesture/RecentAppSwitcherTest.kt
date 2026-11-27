package com.google.android.accessibility.talkback.gesture

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.UserManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.talkback.TalkBackService
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RecentAppSwitcherTest {
  class TargetService : TalkBackService() {
    override fun onCreate() {}
  }

  private class FakeBackend : RecentAppSwitcher.Backend {
    var current: RecentAppSwitcher.Window? = null
    val installed = mutableSetOf("app.a", "app.b", "app.c")
    val launched = mutableListOf<Intent>()
    var rejectLaunch = false

    override fun foregroundWindow() = current
    override fun isHome(packageName: String) = packageName == "launcher"
    override fun launchIntent(packageName: String): Intent? =
      if (packageName in installed) Intent(Intent.ACTION_MAIN).setPackage(packageName) else null
    override fun launch(intent: Intent) {
      if (rejectLaunch) throw SecurityException()
      launched.add(intent)
    }
  }

  private lateinit var service: TargetService
  private lateinit var backend: FakeBackend
  private lateinit var switcher: RecentAppSwitcher
  private var failures = 0

  @Before
  fun setUp() {
    service = Robolectric.buildService(TargetService::class.java).get()
    shadowOf(service.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    shadowOf(service.getSystemService(KeyguardManager::class.java)).setIsKeyguardLocked(false)
    service.getSharedPreferences("recent_app_pair", Context.MODE_PRIVATE).edit().clear().commit()
    backend = FakeBackend()
    switcher = RecentAppSwitcher(service, backend) { failures++ }
  }

  @After
  fun tearDown() {
    switcher.close()
  }

  private fun shown(name: String, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION) {
    backend.current = RecentAppSwitcher.Window(name, type)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    try {
      switcher.onAccessibilityEvent(event)
    } finally {
      event.recycle()
    }
  }

  private fun lastLaunched() = backend.launched.last().`package`

  @Test
  fun alternatesTwoAppsOnlyAfterForegroundConfirmation() {
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, backend.launched.last().flags)
    assertTrue(switcher.switchToPrevious())
    assertEquals(1, backend.launched.size)
    shown("app.a")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.b", lastLaunched())
    shown("app.b")
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(0, failures)
  }

  @Test
  fun thirdAppReplacesTheOlderApp() {
    shown("app.a")
    shown("app.b")
    shown("app.c")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.b", lastLaunched())
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.c", lastLaunched())
  }

  @Test
  fun systemWindowsAndNonlaunchableAppsDoNotReplaceThePair() {
    shown("app.a")
    shown("app.b")
    shown("keyboard", AccessibilityWindowInfo.TYPE_INPUT_METHOD)
    shown("overlay", AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    shown("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM)
    shown("android")
    shown("nonlaunchable")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun homeOpensTheMostRecentAppWithoutEnteringThePair() {
    shown("app.a")
    shown("app.b")
    shown("launcher")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.b", lastLaunched())
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun pairSurvivesRestartWithoutFreshWindowEvents() {
    shown("app.a")
    shown("app.b")
    switcher.close()
    backend.current = null
    switcher = RecentAppSwitcher(service, backend) { failures++ }
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun requiresTwoObservedAppsAndDropsAnUninstalledTarget() {
    shown("app.a")
    assertFalse(switcher.switchToPrevious())
    shown("app.b")
    backend.installed.remove("app.a")
    assertFalse(switcher.switchToPrevious())
    backend.installed.add("app.a")
    assertFalse(switcher.switchToPrevious())
    shown("app.a")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.b", lastLaunched())
  }

  @Test
  fun failedLaunchDoesNotReorderHistoryAndCanBeRetried() {
    shown("app.a")
    shown("app.b")
    backend.rejectLaunch = true
    assertFalse(switcher.switchToPrevious())
    backend.rejectLaunch = false
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun unconfirmedLaunchSignalsFailureOnceAndAllowsRetry() {
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(1, failures)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(1, failures)
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun differentAppTakingForegroundCancelsThePendingFailure() {
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    shown("app.c")
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(0, failures)
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.b", lastLaunched())
  }

  @Test
  fun lockedDeviceDoesNotRecordOrLaunchAppsOrPlayPendingFailure() {
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    val keyguard = shadowOf(service.getSystemService(KeyguardManager::class.java))
    keyguard.setIsKeyguardLocked(true)
    shown("app.c")
    assertFalse(switcher.switchToPrevious())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(0, failures)
    keyguard.setIsKeyguardLocked(false)
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun closingCancelsPendingWork() {
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    switcher.close()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertFalse(switcher.switchToPrevious())
    assertEquals(0, failures)
  }

  @Test
  fun unavailableCredentialStorageDoesNotReadOrWriteHistory() {
    shadowOf(service.getSystemService(UserManager::class.java)).setUserUnlocked(false)
    shown("app.a")
    shown("app.b")
    assertFalse(switcher.switchToPrevious())
    shadowOf(service.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    shown("app.b")
    assertFalse(switcher.switchToPrevious())
  }

  @Test
  fun malformedSavedHistoryDoesNotPreventLearningANewPair() {
    service.getSharedPreferences("recent_app_pair", Context.MODE_PRIVATE).edit()
      .putString("packages", "broken").commit()
    shown("app.a")
    shown("app.b")
    assertTrue(switcher.switchToPrevious())
    assertEquals("app.a", lastLaunched())
  }

  @Test
  fun androidBackendUsesFocusedWindowAndStartsTheOtherInstalledApp() {
    switcher.close()
    switcher = RecentAppSwitcher(service, onFailure = { failures++ })
    for (name in listOf("app.a", "app.b")) {
      val activity = ActivityInfo().apply {
        packageName = name
        this.name = "$name.Main"
        applicationInfo = ApplicationInfo().apply { packageName = name }
      }
      shadowOf(service.packageManager).addOrUpdateActivity(activity)
      shadowOf(service.packageManager).addResolveInfoForIntent(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name),
        ResolveInfo().apply { activityInfo = activity },
      )
    }
    fun window(name: String, focused: Boolean): AccessibilityWindowInfo {
      val root = AccessibilityNodeInfo.obtain().apply { packageName = name }
      return AccessibilityWindowInfo.obtain().also {
        shadowOf(it).apply {
          setType(AccessibilityWindowInfo.TYPE_APPLICATION)
          setFocused(focused)
          setActive(focused)
          setRoot(root)
        }
      }
    }
    shadowOf(service).setWindows(listOf(window("app.a", true), window("app.b", false)))
    switcher.refresh()
    shadowOf(service).setWindows(listOf(window("app.a", false), window("app.b", true)))
    switcher.refresh()
    assertTrue(switcher.switchToPrevious())
    val launched = shadowOf(service).nextStartedActivity
    assertEquals("app.a", launched.component?.packageName)
    assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertEquals(0, launched.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or
      Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
  }
}
