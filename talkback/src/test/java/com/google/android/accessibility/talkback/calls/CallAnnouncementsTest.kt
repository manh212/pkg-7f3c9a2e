package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.app.Notification
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.telephony.TelephonyManager
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
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

  private fun prepareBoost(step: Int = 8, min: Int = 0, max: Int = 20,
    stream: Int = AudioManager.STREAM_ACCESSIBILITY): AudioManager {
    val audio = context.getSystemService(AudioManager::class.java)!!
    shadowOf(audio).setStreamMinVolume(min)
    shadowOf(audio).setStreamMaxVolume(max)
    audio.setStreamVolume(AudioManager.STREAM_RING, max, 0)
    audio.setStreamVolume(stream, step, 0)
    prefs.edit().putBoolean(CallSettings.AUTO_BOOST_VOLUME, true).commit()
    return audio
  }

  private fun phoneState(state: String) {
    shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    context.sendBroadcast(Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
      .putExtra(TelephonyManager.EXTRA_STATE, state))
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  @Config(sdk = [26, 34])
  fun restoresExactFortyPercentEvenWhenTemporaryVolumeHasChanged() {
    val audio = prepareBoost()
    CallAnnouncements.onCallNotification("phone", incoming())
    assertEquals(20, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 17, 0)
    CallAnnouncements.onCallNotificationRemoved("phone")
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    assertEquals(8, prefs.getInt(context.getString(R.string.pref_a11y_volume_key), -1))
  }

  @Test fun restoresOriginalStepWithoutPercentageRoundingForNonzeroMinimum() {
    val audio = prepareBoost(step = 6, min = 2, max = 13)
    CallAnnouncements.onCallNotification("phone", incoming())
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 11, 0)
    CallAnnouncements.onCallNotificationRemoved("phone")
    assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun overlappingCallsKeepTheFirstBaselineUntilEveryRingingCallEnds() {
    val audio = prepareBoost()
    CallAnnouncements.onCallNotification("first", incoming())
    CallAnnouncements.onCallNotification("second", incoming())
    CallAnnouncements.onCallNotificationRemoved("first")
    assertEquals(20, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 16, 0)
    CallAnnouncements.onCallNotificationRemoved("second")
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun notificationToPhoneHandoffDoesNotReplaceTheOriginalBaseline() {
    val audio = prepareBoost()
    CallAnnouncements.onCallNotification("phone", incoming())
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 17, 0)
    phoneState(TelephonyManager.EXTRA_STATE_RINGING)
    phoneState(TelephonyManager.EXTRA_STATE_OFFHOOK)
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 16, 0)
    phoneState(TelephonyManager.EXTRA_STATE_IDLE)
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun keepsBaselineThroughVoipCallAndRestoresAgainAfterAudioRouteSettles() {
    val audio = prepareBoost()
    CallAnnouncements.onCallNotification("voip", incoming())
    val ongoing = incoming().apply { extras.putInt("android.callType", 2) }
    CallAnnouncements.onCallNotification("voip", ongoing)
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 15, 0)
    CallAnnouncements.onCallNotificationRemoved("voip")
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 18, 0)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 19, 0)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 9, 0)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(9, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun newIncomingCallCancelsOldRestoreCallbacksAndKeepsOriginalBaseline() {
    val audio = prepareBoost()
    CallAnnouncements.onCallNotification("first", incoming())
    CallAnnouncements.onCallNotificationRemoved("first")
    CallAnnouncements.onCallNotification("second", incoming())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(20, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
    CallAnnouncements.onCallNotificationRemoved("second")
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
  }

  @Test fun settingOffAndServiceDetachRestoreTheSavedStreamAndStep() {
    prefs.edit().putString(FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY,
      FailoverTextToSpeech.STREAM_VALUE_MEDIA).commit()
    val audio = prepareBoost(step = 8, stream = AudioManager.STREAM_MUSIC)
    CallAnnouncements.onCallNotification("phone", incoming())
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 17, 0)
    prefs.edit().putBoolean(CallSettings.AUTO_BOOST_VOLUME, false).commit()
    assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    CallAnnouncements.onCallNotificationRemoved("phone")
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
    prefs.edit().putBoolean(CallSettings.AUTO_BOOST_VOLUME, true).commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 16, 0)
    CallAnnouncements.detach()
    assertEquals(7, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  }
}
