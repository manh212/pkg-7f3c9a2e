package com.google.android.accessibility.talkback.volume

import android.app.KeyguardManager
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class VolumeKeyManagerTest {
  class RecordingService : TalkBackService() {
    val actions = mutableListOf<String>()
    var callActive = false
    var reading = false
    var touchingScreen = false
    override fun onCreate() {}
    override fun isPhoneCallActive() = callActive
    override fun isTouchInteracting() = touchingScreen
    override fun shouldAdjustSpeechVolumeWhileSpeaking() = reading
    override fun adjustSpeechVolumeWhileSpeaking(increase: Boolean) {
      actions += if (increase) "speechUp" else "speechDown"
    }
    override fun resolveVolumeKeyUse(triggerModifierPressed: Boolean) = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    override fun performCatalogAction(action: String) { actions += action }
    override fun adjustContextualVolume(increase: Boolean, touching: Boolean) {
      actions += if (increase) "up" else "down"
    }
    override fun adjustUnmanagedVolumeKey(increase: Boolean, touching: Boolean) {
      if (touching) adjustContextualVolume(increase, true)
      else actions += if (increase) "systemUp" else "systemDown"
    }
  }

  private lateinit var service: RecordingService
  private val up = KeyEvent.KEYCODE_VOLUME_UP
  private val down = KeyEvent.KEYCODE_VOLUME_DOWN

  @Before fun setUp() {
    VolumeKeyManager.reset()
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear().commit()
  }

  @After fun tearDown() { VolumeKeyManager.reset() }

  private fun key(code: Int, action: Int): Boolean =
    VolumeKeyManager.onKeyEvent(service, KeyEvent(action, code), false)
  private fun tap(code: Int) {
    key(code, KeyEvent.ACTION_DOWN)
    key(code, KeyEvent.ACTION_UP)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
  }
  private fun waitForSequence() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
  private fun setLocked(locked: Boolean) {
    shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
  }
  private fun setScreenOff(screenOff: Boolean) {
    shadowOf(checkNotNull(service.getSystemService(PowerManager::class.java)))
      .setIsInteractive(!screenOff)
  }

  @Test fun defaultChordWorksInBothOrdersAndCancelsSinglePresses() {
    listOf(up to down, down to up).forEach { (first, second) ->
      key(first, KeyEvent.ACTION_DOWN)
      key(second, KeyEvent.ACTION_DOWN)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
      key(first, KeyEvent.ACTION_UP)
      key(second, KeyEvent.ACTION_UP)
    }
    assertEquals(listOf("TOGGLE_TOUCH_EXPLORATION", "TOGGLE_TOUCH_EXPLORATION"), service.actions)
  }

  @Test fun adjustsSpeechDuringReadingAndRetainsTouchAndIdleVolumeRoutes() {
    service.reading = true
    tap(up)
    tap(down)

    service.reading = false
    service.touchingScreen = true
    tap(up)

    service.touchingScreen = false
    tap(down)

    assertEquals(listOf("speechUp", "speechDown", "up", "systemDown"), service.actions)
  }

  @Test fun disabledMultiPressKeepsSinglePresses() {
    repeat(3) { tap(up) }
    waitForSequence()
    assertEquals(listOf("systemUp", "systemUp", "systemUp"), service.actions)
  }

  @Test fun unassignedMultiPressFallsBackToSinglePresses() {
    repeat(2) { tap(down) }
    waitForSequence()
    assertEquals(listOf("systemDown", "systemDown"), service.actions)
  }

  @Test fun twoThreeAndFourPressesRunOnlyTheMatchingAssignment() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    for (code in listOf(up, down)) {
      for (count in 2..4) {
        val action = "action_${code}_$count"
        prefs.edit().putString(VolumeKeyManager.multiKey(code == up, count), action).commit()
        repeat(count) { tap(code) }
        waitForSequence()
        assertEquals(action, service.actions.last())
      }
    }
    assertEquals(6, service.actions.size)
  }

  @Test fun resetDropsPendingSequenceAndHold() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    repeat(2) { tap(up) }
    VolumeKeyManager.reset()
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }

  @Test fun activeCallsLeaveKeysToSystemAndDropPendingActions() {
    tap(up)
    service.actions.clear()
    service.callActive = true
    assertFalse(key(down, KeyEvent.ACTION_DOWN))
    assertFalse(key(down, KeyEvent.ACTION_UP))
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }

  @Test fun managedHoldKeepsItsAssignmentAndDoesNotRunTheTap() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("HOME"), service.actions)
  }

  @Test fun shortHoldFiresAtOnePointFiveSeconds() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    assertEquals(emptyList<String>(), service.actions)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
    assertEquals(listOf("HOME"), service.actions)
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("HOME"), service.actions)
  }

  @Test fun longHoldFiresAtFourSecondsAndSuppressesShort() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME")
      .putString(VolumeKeyManager.KEY_UP_HOLD_LONG_ACTION, "BACK").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
    assertEquals(emptyList<String>(), service.actions)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
    assertEquals(listOf("BACK"), service.actions)
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("BACK"), service.actions)
  }

  @Test fun shortHoldFiresOnReleaseWhenLongHoldAssigned() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME")
      .putString(VolumeKeyManager.KEY_UP_HOLD_LONG_ACTION, "BACK").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
    assertEquals(emptyList<String>(), service.actions)
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("HOME"), service.actions)
  }

  @Test fun screenOffHoldUsesScreenOffKeys() {
    setLocked(true)
    setScreenOff(true)
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME")
      .putString(VolumeKeyManager.KEY_UP_HOLD_SCREEN_OFF_ACTION, "NEXT").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("NEXT"), service.actions)
  }

  @Test fun lockScreenHoldUpDefaultsToStopwatch() {
    setLocked(true)
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true).commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("TOGGLE_STOPWATCH"), service.actions)
  }

  @Test fun chordDropsAnEarlierMultiPressSequence() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    tap(up)
    key(up, KeyEvent.ACTION_DOWN)
    key(down, KeyEvent.ACTION_DOWN)
    key(up, KeyEvent.ACTION_UP)
    key(down, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("TOGGLE_TOUCH_EXPLORATION"), service.actions)
  }

  @Test fun clearingMultiPressAssignmentDropsThePendingAssignment() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit()
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    repeat(2) { tap(up) }
    prefs.edit().remove(VolumeKeyManager.multiKey(true, 2)).commit()
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }
}
