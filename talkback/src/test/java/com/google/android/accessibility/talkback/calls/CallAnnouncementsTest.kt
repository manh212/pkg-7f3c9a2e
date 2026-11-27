package com.google.android.accessibility.talkback.calls

import android.app.Notification
import android.media.AudioManager
import android.os.Looper
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.SpeechController
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CallAnnouncementsTest {
  private val context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val spoken = mutableListOf<String>()
  private val stopped = mutableListOf<SpeechController.UtteranceCompleteRunnable>()
  private val completions = mutableListOf<SpeechController.UtteranceCompleteRunnable>()

  @Before fun setUp() {
    prefs.edit().clear().commit()
    CallAnnouncements.attach(context, { text, completion ->
      spoken += text.toString()
      completions += completion
    }, { stopped += it }, { true }, { false })
  }

  @After fun tearDown() {
    CallAnnouncements.detach()
    prefs.edit().clear().commit()
  }

  private fun incoming() = Notification.Builder(context, "calls")
    .setCategory(Notification.CATEGORY_CALL).setContentTitle("Người gọi").build()

  private fun waitForReading() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

  @Test fun duplicateEventsFromListenerAndAccessibilityAreReadOnce() {
    val notification = incoming()
    CallAnnouncements.onCallNotification("phone", notification)
    CallAnnouncements.onCallNotification("phone", notification)
    waitForReading()
    assertEquals(1, spoken.size)
    assertTrue(spoken.single().contains("Người gọi"))
  }

  @Test fun configuredRepeatsWaitForCompletionAndStopAtTheChosenCount() {
    prefs.edit().putString(CallSettings.REPEATS, "2").commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    repeat(3) { index ->
      assertEquals(index + 1, spoken.size)
      completions.last().run(SpeechController.STATUS_SPOKEN)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
    }
    assertEquals(3, spoken.size)
  }

  @Test fun removingCallCancelsCurrentReadingAndFurtherRepeats() {
    prefs.edit().putString(CallSettings.REPEATS, "3").commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    CallAnnouncements.onCallNotificationRemoved("phone")
    assertEquals(completions, stopped)
    completions.last().run(SpeechController.STATUS_SPOKEN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
    assertEquals(1, spoken.size)
  }

  @Test fun quietHoursAndDisabledCallAnnouncementsAreIndependentOfNotifications() {
    prefs.edit().putBoolean(CallSettings.QUIET, true)
      .putInt(CallSettings.START, 0).putInt(CallSettings.END, 0).commit()
    CallAnnouncements.onCallNotification("quiet", incoming())
    waitForReading()
    assertTrue(spoken.isEmpty())
    prefs.edit().putBoolean(CallSettings.QUIET, false)
      .putBoolean(CallSettings.ANNOUNCE, false).commit()
    CallAnnouncements.onCallNotification("disabled", incoming())
    waitForReading()
    assertTrue(spoken.isEmpty())
    prefs.edit().putBoolean(CallSettings.ANNOUNCE, true)
      .putBoolean("pref_speak_notifications", false).commit()
    CallAnnouncements.onCallNotification("enabled", incoming())
    waitForReading()
    assertEquals(1, spoken.size)
  }

  @Test fun switchingOffAnnouncementsCancelsSpeechAlreadyQueued() {
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    prefs.edit().putBoolean(CallSettings.ANNOUNCE, false).commit()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(completions, stopped)
  }

  @Test fun ongoingCallNotificationStopsRinging() {
    CallAnnouncements.onCallNotification("phone", incoming())
    val ongoing = incoming().apply { extras.putInt("android.callType", 2) }
    CallAnnouncements.onCallNotification("phone", ongoing)
    waitForReading()
    assertTrue(spoken.isEmpty())
  }

  @Test fun ongoingFlagNotificationIsNotAnnouncedAsIncoming() {
    // Zalo's outgoing-call notification carries FLAG_ONGOING_EVENT but no CallStyle
    // callType extra: it must not start ringing on its own.
    val ongoing = incoming().apply { flags = flags or Notification.FLAG_ONGOING_EVENT }
    CallAnnouncements.onCallNotification("zalo", ongoing)
    waitForReading()
    assertTrue(spoken.isEmpty())
    // And it stops ringing already started for the same app, like an ongoing
    // CallStyle notification does.
    CallAnnouncements.onCallNotification("zalo", incoming())
    CallAnnouncements.onCallNotification("zalo", ongoing)
    waitForReading()
    assertTrue(spoken.isEmpty())
  }

  @Test fun boostedSpeechPercentAddsTwentyAndCapsAtOneHundred() {
    assertEquals(20, CallAnnouncements.boostedSpeechPercent(0))
    assertEquals(90, CallAnnouncements.boostedSpeechPercent(70))
    assertEquals(100, CallAnnouncements.boostedSpeechPercent(80))
    assertEquals(100, CallAnnouncements.boostedSpeechPercent(90))
    assertEquals(100, CallAnnouncements.boostedSpeechPercent(100))
  }

  @Test fun incomingCallAutoBoostsSpeechVolumeAndRestoresAfterRingingEnds() {
    val audio = context.getSystemService(AudioManager::class.java)!!
    val ringMax = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
    val a11yMax = audio.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
    if (ringMax <= 0 || a11yMax <= 0) return

    val ring70 = CallAnnouncements.percentToStep(70, 0, ringMax)
    val a11y30 = CallAnnouncements.percentToStep(30, 0, a11yMax)
    val expectedBoost = CallAnnouncements.percentToStep(
      CallAnnouncements.boostedSpeechPercent(CallAnnouncements.stepToPercent(ring70, 0, ringMax)),
      0,
      a11yMax,
    )

    audio.setStreamVolume(AudioManager.STREAM_RING, ring70, 0)
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, a11y30, 0)

    // Default is OFF: incoming call must not change speech volume.
    CallAnnouncements.onCallNotification("phone", incoming())
    assertEquals(a11y30, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    CallAnnouncements.onCallNotificationRemoved("phone")

    // When enabled: incoming call boosts speech volume to ringtone + 20% and restores on removal.
    prefs.edit().putBoolean(CallSettings.AUTO_BOOST_VOLUME, true).commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    assertEquals(expectedBoost, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    CallAnnouncements.onCallNotificationRemoved("phone")
    assertEquals(a11y30, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }
}
