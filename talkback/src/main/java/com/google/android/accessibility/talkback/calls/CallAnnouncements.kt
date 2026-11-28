package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.notifications.NotificationVoice
import com.google.android.accessibility.talkback.permission.PermissionRequestActivity
import com.google.android.accessibility.talkback.speakerphone.SpeakerphoneInCallService
import com.google.android.accessibility.talkback.timer.Stopwatch
import com.google.android.accessibility.utils.SettingsUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.VoiceProfiles
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object CallAnnouncements {
  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private var context: Context? = null
  private var prefs: SharedPreferences? = null
  private var fallback: ((CharSequence, SpeechController.UtteranceCompleteRunnable) -> Unit)? = null
  private var stopFallback: ((SpeechController.UtteranceCompleteRunnable) -> Unit)? = null
  private var canSpeak: () -> Boolean = { false }
  private var isSpeaking: () -> Boolean = { false }
  private var voice: NotificationVoice? = null
  private val voiceRequests = mutableMapOf<NotificationVoice.Listener, () -> Boolean>()
  private var phoneState = TelephonyManager.CALL_STATE_IDLE
  private var phoneStartedAt = 0L
  private var telecomOwnsPhone = false
  private var epoch = 0
  private var speechEpoch = 0
  private val sessions = mutableMapOf<Any, Session>()
  private val utterances = mutableMapOf<SpeechController.UtteranceCompleteRunnable, () -> Boolean>()
  private const val PHONE = "phone"
  internal const val RING_VOLUME_BOOST_PERCENT = 20
  private data class SpeechVolumeSnapshot(val stream: Int, val step: Int)
  private var speechVolumeSnapshot: SpeechVolumeSnapshot? = null
  private var speechVolumeRaised = false
  private var volumeRestoreEpoch = 0
  private var pendingVolumeRestore: Runnable? = null

  private class Session(val key: Any, var caller: String = "") {
    var ringing = false
    var active = false
    var ongoing = false
    var readCount = 0
    var connectedAt = 0L
    var durationSaid = false
    var runnable: Runnable? = null
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == PermissionRequestActivity.ACTION_DONE) {
        TalkBackService.getInstance()
          ?.refreshCallStateMonitoring()
        return
      }
      @Suppress("DEPRECATION")
      val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
      when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
        TelephonyManager.EXTRA_STATE_RINGING -> phoneState(TelephonyManager.CALL_STATE_RINGING, number)
        TelephonyManager.EXTRA_STATE_OFFHOOK -> phoneState(TelephonyManager.CALL_STATE_OFFHOOK, number)
        TelephonyManager.EXTRA_STATE_IDLE -> phoneState(TelephonyManager.CALL_STATE_IDLE, number)
      }
    }
  }

  private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    sessions.values.forEach { session ->
      if (session.ringing && !settings().announce) cancel(session)
      if (key == CallSettings.INTERVAL && session.active) scheduleDuration(session)
    }
    if (key == CallSettings.AUTO_BOOST_VOLUME && !settings().autoBoostVolume) {
      restoreBoostedSpeechVolume()
    }
    stopInvalidSpeech()
    if (!allowed()) stopSpeech()
  }

  @JvmStatic
  fun attach(
    context: Context,
    fallback: (CharSequence, SpeechController.UtteranceCompleteRunnable) -> Unit,
    stopFallback: (SpeechController.UtteranceCompleteRunnable) -> Unit,
    canSpeak: () -> Boolean,
    isSpeaking: () -> Boolean,
  ) {
    detach()
    this.context = context
    this.prefs = SharedPreferencesUtils.getSharedPreferences(context)
    this.fallback = fallback
    this.stopFallback = stopFallback
    this.canSpeak = canSpeak
    this.isSpeaking = isSpeaking
    prefs?.registerOnSharedPreferenceChangeListener(settingsListener)
    val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
      addAction(PermissionRequestActivity.ACTION_DONE)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      SpeakerphoneInCallService.refreshAnnouncements()
    }
  }

  @JvmStatic
  fun detach() {
    context?.let { runCatching { it.unregisterReceiver(receiver) } }
    prefs?.unregisterOnSharedPreferenceChangeListener(settingsListener)
    epoch++
    sessions.values.forEach(::cancel)
    sessions.clear()
    restoreBoostedSpeechVolume(settle = false)
    stopSpeech()
    voice?.shutdown()
    voice = null
    voiceRequests.clear()
    context = null
    prefs = null
    fallback = null
    stopFallback = null
    canSpeak = { false }
    isSpeaking = { false }
    phoneState = TelephonyManager.CALL_STATE_IDLE
    phoneStartedAt = 0L
    telecomOwnsPhone = false
  }

  private fun settings(): CallSettings = prefs?.let { CallSettings.read(it) } ?: CallSettings()

  private fun allowed(): Boolean {
    val context = context ?: return false
    val settings = settings()
    return canSpeak() && !settings.isQuietNow() &&
      (settings.duringDnd || SettingsUtils.getDoNotDisturbState(context) == 0)
  }

  private fun phoneState(state: Int, number: String) {
    val context = context ?: return
    if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) return
    if (state == phoneState) {
      if (number.isNotBlank()) sessions[PHONE]?.let { resolveCaller(it, number) }
      return
    }
    val previous = phoneState
    phoneState = state
    if (state != TelephonyManager.CALL_STATE_IDLE) {
      sessions.keys.filter { it is String && it.startsWith("notification:") }.toList().forEach {
        sessions.remove(it)?.let(::cancel)
      }
    }
    if (state != TelephonyManager.CALL_STATE_IDLE && phoneStartedAt == 0L) {
      phoneStartedAt = System.currentTimeMillis()
    }
    if (telecomOwnsPhone || sessions.keys.any { it is Call }) {
      if (state == TelephonyManager.CALL_STATE_IDLE) {
        telecomOwnsPhone = false
        phoneStartedAt = 0L
      }
      stopInvalidSpeech()
      return
    }
    when (state) {
      TelephonyManager.CALL_STATE_RINGING -> {
        val session = Session(PHONE)
        sessions[PHONE] = session
        resolveCaller(session, number)
        startRinging(session)
      }
      TelephonyManager.CALL_STATE_OFFHOOK -> sessions.remove(PHONE)?.let(::cancel)
      TelephonyManager.CALL_STATE_IDLE -> {
        sessions.remove(PHONE)?.let(::cancel)
        if (previous == TelephonyManager.CALL_STATE_OFFHOOK && settings().duration) {
          readLoggedDuration(context, phoneStartedAt, epoch, 0)
        }
        phoneStartedAt = 0L
      }
    }
    stopInvalidSpeech()
  }

  @JvmStatic
  fun onTelecomCall(call: Call, state: Int) {
    if (context == null) return
    telecomOwnsPhone = true
    val previousPhone = sessions.remove(PHONE)
    previousPhone?.let(::cancel)
    var previousReadCount = previousPhone?.readCount ?: 0
    sessions.keys.filter { it is String && it.startsWith("notification:") }.toList().forEach {
      sessions.remove(it)?.let {
        previousReadCount = maxOf(previousReadCount, it.readCount)
        cancel(it)
      }
    }
    val session = sessions.getOrPut(call) { Session(call) }
    session.active = state == Call.STATE_ACTIVE
    session.ongoing = state != Call.STATE_DISCONNECTED
    if (session.caller.isBlank()) resolveCaller(session, call.details.handle?.schemeSpecificPart.orEmpty())
    if (state == Call.STATE_RINGING) {
      if (!session.ringing) {
        session.readCount = maxOf(session.readCount, previousReadCount)
        startRinging(session)
      }
    } else {
      if (session.ringing) cancel(session)
      if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
        if (session.connectedAt == 0L) {
          val connectedWall = call.details.connectTimeMillis
          val elapsed = if (connectedWall > 0) {
            (System.currentTimeMillis() - connectedWall).coerceAtLeast(0)
          } else 0L
          session.connectedAt = SystemClock.elapsedRealtime() - elapsed
        }
        if (state == Call.STATE_ACTIVE) {
          if (session.runnable == null) scheduleDuration(session)
        } else cancel(session)
      } else if (state == Call.STATE_DISCONNECTED) {
        announceDuration(session)
        cancel(session)
      }
    }
    stopInvalidSpeech()
  }

  @JvmStatic
  fun onTelecomRemoved(call: Call) {
    sessions.remove(call)?.let { session ->
      announceDuration(session)
      cancel(session)
    }
    if (sessions.keys.none { it is Call } && phoneState == TelephonyManager.CALL_STATE_IDLE) {
      telecomOwnsPhone = false
    }
    stopInvalidSpeech()
  }

  @JvmStatic
  fun onTelecomDisconnected() {
    sessions.keys.filterIsInstance<Call>().toList().forEach {
      sessions.remove(it)?.let(::cancel)
    }
    telecomOwnsPhone = false
    stopInvalidSpeech()
  }

  private fun announceDuration(session: Session) {
    if (session.durationSaid || session.connectedAt == 0L) return
    session.durationSaid = true
    if (settings().duration) {
      sayDuration(((SystemClock.elapsedRealtime() - session.connectedAt) / 1000).coerceAtLeast(0))
    }
  }

  private fun scheduleDuration(session: Session) {
    session.runnable?.let(main::removeCallbacks)
    session.runnable = null
    val interval = settings().intervalSeconds
    if (interval <= 0) return
    val runnable = Runnable {
      session.runnable = null
      if (sessions[session.key] !== session || !session.active) return@Runnable
      if (settings().intervalSeconds > 0) {
        val context = context ?: return@Runnable
        val seconds = ((SystemClock.elapsedRealtime() - session.connectedAt) / 1000).coerceAtLeast(0)
        val generation = epoch
        speak(context.getString(R.string.call_duration_announcement, Stopwatch.formatElapsed(context, seconds * 1000L)),
          { epoch == generation && sessions[session.key] === session && session.active &&
            settings().intervalSeconds > 0 }) {}
        scheduleDuration(session)
      }
    }
    session.runnable = runnable
    main.postDelayed(runnable, interval * 1000L)
  }

  private fun sayDuration(seconds: Long) {
    val context = context ?: return
    val generation = epoch
    speak(context.getString(R.string.call_duration_announcement, Stopwatch.formatElapsed(context, seconds * 1000L)),
      { epoch == generation && settings().duration }) {}
  }

  private fun startRinging(session: Session) {
    session.ringing = true
    boostSpeechVolumeForIncomingCall()
    scheduleRing(session, 400L)
  }

  internal fun boostedSpeechPercent(ringPercent: Int): Int =
    (ringPercent.coerceIn(0, 100) + RING_VOLUME_BOOST_PERCENT).coerceAtMost(100)

  internal fun stepToPercent(step: Int, minStep: Int, maxStep: Int): Int {
    if (maxStep <= minStep) return 0
    return (((step - minStep) * 100f) / (maxStep - minStep)).roundToInt().coerceIn(0, 100)
  }

  internal fun percentToStep(percent: Int, minStep: Int, maxStep: Int): Int {
    if (maxStep <= minStep) return minStep
    return (minStep + ((maxStep - minStep) * percent.coerceIn(0, 100) / 100f).roundToInt())
      .coerceIn(minStep, maxStep)
  }

  private fun safeMinVolume(audio: AudioManager, stream: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        audio.getStreamMinVolume(stream)
      } catch (_: IllegalArgumentException) {
        0
      }
    } else {
      0
    }

  private fun boostSpeechVolumeForIncomingCall() {
    val ctx = context ?: return
    if (!settings().autoBoostVolume) return
    cancelPendingVolumeRestore()
    if (speechVolumeRaised) return
    val audio = ctx.getSystemService(AudioManager::class.java) ?: return
    val speechStream = FailoverTextToSpeech.getSpeechAudioStream(ctx)
    if (speechStream == AudioManager.STREAM_RING) return
    runCatching {
      val ringMin = safeMinVolume(audio, AudioManager.STREAM_RING)
      val ringMax = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
      val ringVol = audio.getStreamVolume(AudioManager.STREAM_RING)
      val ringPercent = stepToPercent(ringVol, ringMin, ringMax)
      val targetPercent = boostedSpeechPercent(ringPercent)
      val speechMin = safeMinVolume(audio, speechStream)
      val speechMax = audio.getStreamMaxVolume(speechStream)
      val currentSpeech = audio.getStreamVolume(speechStream)
      val targetStep = percentToStep(targetPercent, speechMin, speechMax)
      if (targetStep > currentSpeech) {
        if (speechVolumeSnapshot == null) speechVolumeSnapshot = SpeechVolumeSnapshot(speechStream, currentSpeech)
        if (speechVolumeSnapshot?.stream != speechStream) return@runCatching
        TalkBackService.getInstance()?.ignoreSelfVolumeChange(speechStream)
        speechVolumeRaised = true
        audio.setStreamVolume(speechStream, targetStep, 0)
      }
    }
  }

  private fun cancelPendingVolumeRestore() {
    volumeRestoreEpoch++
    pendingVolumeRestore?.let(main::removeCallbacks)
    pendingVolumeRestore = null
  }

  private fun restoreBoostedSpeechVolume(keepBaseline: Boolean = false, settle: Boolean = true) {
    val snapshot = speechVolumeSnapshot ?: return
    val ctx = context?.applicationContext ?: return
    val audio = ctx.getSystemService(AudioManager::class.java) ?: return
    cancelPendingVolumeRestore()
    if (keepBaseline && !speechVolumeRaised) return
    val token = volumeRestoreEpoch
    fun restore(attempt: Int) {
      if (volumeRestoreEpoch != token || speechVolumeSnapshot !== snapshot) return
      pendingVolumeRestore = null
      val restored = runCatching {
        if (audio.getStreamVolume(snapshot.stream) != snapshot.step) {
          TalkBackService.getInstance()?.ignoreSelfVolumeChange(snapshot.stream)
          audio.setStreamVolume(snapshot.stream, snapshot.step, 0)
        }
        audio.getStreamVolume(snapshot.stream) == snapshot.step
      }.getOrDefault(false)
      if (restored) speechVolumeRaised = false
      if (keepBaseline && restored) return
      if (attempt < 2 && (settle || !restored)) {
        val next = Runnable { restore(attempt + 1) }
        pendingVolumeRestore = next
        main.postDelayed(next, if (attempt == 0) 250L else 750L)
      } else if (restored) {
        if (!keepBaseline) {
          speechVolumeSnapshot = null
          if (snapshot.stream == AudioManager.STREAM_ACCESSIBILITY) {
            SharedPreferencesUtils.putIntPref(SharedPreferencesUtils.getSharedPreferences(ctx),
              ctx.resources, R.string.pref_a11y_volume_key, snapshot.step)
          }
        }
      } else {
        LogUtils.w("CallAnnouncements", "Speech volume restoration was not accepted")
      }
    }
    restore(0)
  }

  private fun scheduleRing(session: Session, delay: Long) {
    session.runnable?.let(main::removeCallbacks)
    val runnable = Runnable {
      if (!ringValid(session) || session.readCount > settings().repeats) return@Runnable
      val context = context ?: return@Runnable
      val text = if (session.caller.isBlank()) context.getString(R.string.call_incoming_unknown)
        else context.getString(R.string.call_incoming, session.caller)
      session.readCount++
      speak(text, { ringValid(session) }) {
        if (ringValid(session) && session.readCount <= settings().repeats) scheduleRing(session, 3000L)
      }
    }
    session.runnable = runnable
    main.postDelayed(runnable, delay)
  }

  private fun ringValid(session: Session): Boolean =
    sessions[session.key] === session && session.ringing && settings().announce

  private fun cancel(session: Session) {
    session.ringing = false
    session.runnable?.let(main::removeCallbacks)
    session.runnable = null
  }

  private fun resolveCaller(session: Session, number: String) {
    if (number.isBlank()) return
    session.caller = number
    val context = context ?: return
    if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return
    worker.execute {
      val name = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
          ?.use { if (it.moveToFirst()) it.getString(0) else null }
      }.getOrNull()
      main.post {
        if (sessions[session.key] === session && session.caller == number && !name.isNullOrBlank()) {
          session.caller = name
        }
      }
    }
  }

  private fun readLoggedDuration(context: Context, startedAt: Long, generation: Int, attempt: Int) {
    if (startedAt <= 0 || !hasPermission(context, Manifest.permission.READ_CALL_LOG)) return
    worker.execute {
      val seconds = runCatching {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
          arrayOf(CallLog.Calls.DURATION, CallLog.Calls.TYPE),
          "${CallLog.Calls.DATE} >= ?", arrayOf((startedAt - 2000).toString()),
          "${CallLog.Calls.DATE} DESC")?.use {
          if (it.moveToFirst() && it.getInt(1) in setOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE)) {
            it.getLong(0)
          } else null
        }
      }.getOrNull()
      main.post {
        if (epoch != generation || phoneState != TelephonyManager.CALL_STATE_IDLE) return@post
        if (seconds != null) {
          if (settings().duration) sayDuration(seconds)
        } else if (attempt < 3) {
          main.postDelayed({ readLoggedDuration(context, startedAt, generation, attempt + 1) }, 500L)
        }
      }
    }
  }

  private fun speak(text: CharSequence, valid: () -> Boolean, done: () -> Unit) {
    val context = context ?: return
    if (!allowed() || !valid()) return
    if (isSpeaking() || voiceRequests.isNotEmpty() || utterances.isNotEmpty()) {
      val generation = epoch
      val speechGeneration = speechEpoch
      main.postDelayed({
        if (epoch == generation && speechEpoch == speechGeneration) speak(text, valid, done)
      }, 200L)
      return
    }
    val profileId = settings().voiceProfile
    val prefs = prefs ?: return
    if (profileId.isEmpty() || !VoiceProfiles.names(prefs, "") { "" }.containsKey(profileId)) {
      speakFallback(text, valid, done)
      return
    }
    val selectedVoice = voice ?: NotificationVoice(context).also { voice = it }
    val profile = VoiceProfiles.read(prefs, profileId)
    if (selectedVoice.currentProfile != profile) selectedVoice.use(profile)
    val listener = object : NotificationVoice.Listener {
      override fun onStart() {
        if (!valid() || !allowed()) selectedVoice.stop(this)
      }
      override fun onDone(spoken: Boolean) {
        voiceRequests.remove(this)
        if (spoken && valid()) done()
      }
      override fun onUnavailable() {
        voiceRequests.remove(this)
        if (valid() && allowed()) speakFallback(text, valid, done)
      }
    }
    voiceRequests[listener] = valid
    selectedVoice.speak(text, false, null, listener)
  }

  private fun speakFallback(text: CharSequence, valid: () -> Boolean, done: () -> Unit) {
    if (!valid() || !allowed()) return
    lateinit var completion: SpeechController.UtteranceCompleteRunnable
    completion = SpeechController.UtteranceCompleteRunnable { status ->
      utterances.remove(completion)
      if (status == SpeechController.STATUS_SPOKEN && valid()) done()
    }
    utterances[completion] = valid
    fallback?.invoke(text, completion)
  }

  private fun stopInvalidSpeech() {
    if (sessions.values.none { it.ringing }) {
      val ongoing = phoneState != TelephonyManager.CALL_STATE_IDLE || sessions.values.any { it.ongoing }
      restoreBoostedSpeechVolume(keepBaseline = ongoing && settings().autoBoostVolume)
    }
    utterances.toMap().forEach { (completion, valid) ->
      if (!valid()) {
        utterances.remove(completion)
        stopFallback?.invoke(completion)
      }
    }
    voiceRequests.toMap().forEach { (listener, valid) ->
      if (!valid()) {
        voiceRequests.remove(listener)
        voice?.stop(listener)
      }
    }
  }

  @JvmStatic
  fun stopSpeech() {
    speechEpoch++
    utterances.keys.toList().forEach { stopFallback?.invoke(it) }
    utterances.clear()
    voiceRequests.clear()
    voice?.stop()
  }

  @JvmStatic
  fun onCallNotification(packageName: String, notification: Notification) {
    if (context == null) return
    if (notification.category != Notification.CATEGORY_CALL) return
    val key = "notification:$packageName"
    val callType = notification.extras?.getInt("android.callType", 0) ?: 0
    val isOngoing = callType == 2 ||
      (callType == 0 && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && notification.fullScreenIntent == null)
    if (isOngoing) {
      sessions[key]?.let { session ->
        session.ongoing = true
        cancel(session)
      }
      stopInvalidSpeech()
      return
    }
    if (phoneState != TelephonyManager.CALL_STATE_IDLE || sessions.keys.any { it is Call }) return
    if (!allowed()) return
    if (sessions[key]?.ringing == true) return
    val caller = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val session = Session(key, caller)
    sessions[key] = session
    startRinging(session)
  }

  @JvmStatic
  fun onCallNotificationRemoved(packageName: String) {
    sessions.remove("notification:$packageName")?.let(::cancel)
    stopInvalidSpeech()
  }

  private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
