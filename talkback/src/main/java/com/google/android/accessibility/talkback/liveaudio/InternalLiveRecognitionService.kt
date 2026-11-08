package com.google.android.accessibility.talkback.liveaudio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.oai.geminilivetranslate.audio.AudioSource
import com.oai.geminilivetranslate.audio.InternalAudioSource
import com.oai.geminilivetranslate.audio.StreamingPcmPlayer
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.network.GeminiLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal integration of Gemini Live Translate's INTERNAL source, input sender, Gemini Live
 * callback path and translated PCM player. No microphone, file reader, webview or source app UI.
 */
class InternalLiveRecognitionService : Service() {
    private val logger = SessionLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var source: InternalAudioSource? = null
    private var projection: MediaProjection? = null
    private var client: GeminiLiveClient? = null
    private var player: StreamingPcmPlayer? = null
    private var sourceJob: Job? = null
    private var sendJob: Job? = null
    private var queue: LinkedBlockingDeque<ByteArray>? = null
    private val droppedInputChunks = AtomicLong(0)
    private var mode = MODE_TRANSLATE
    private var voice = VOICE_GEMINI
    @Volatile private var active = false
    private var connectionGeneration = 0
    private var lastRawTranscript = ""
    private var committedTranscript = ""
    private var reconnectAttempts = 0
    private var resumeHandle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY

        mode = intent.getStringExtra(EXTRA_MODE).let { if (it == MODE_TRANSCRIBE) MODE_TRANSCRIBE else MODE_TRANSLATE }
        voice = intent.getStringExtra(EXTRA_VOICE).let { if (it == VOICE_READER) VOICE_READER else VOICE_GEMINI }
        val code = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        val prefs = SharedPreferencesUtils.getSharedPreferences(this)
        val key = GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY)
        if (key.isEmpty()) {
            announce("Chưa có khóa Gemini API. Hãy nhập trong cài đặt Nhận dạng.")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification("Đang khởi động dịch âm thanh nội bộ"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification("Đang khởi động dịch âm thanh nội bộ"))
        }
        try {
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(code, data)
        } catch (e: RuntimeException) {
            logger.log(0, "MediaProjection", "Không khởi tạo được phiên thu", e)
            stopSelf()
            return START_NOT_STICKY
        }
        active = true
        running = true
        currentTranscript = ""
        if (mode == MODE_TRANSLATE && voice == VOICE_GEMINI) {
            player = StreamingPcmPlayer(
                sampleRate = 24_000,
                bufferBytes = 48_000,
                queueCapacity = 50,
                initialJitterChunks = 1,
                logger = logger,
                diagnosticName = "TranslatedPlayer-accessibility",
            ).also { it.start() }
        }
        connectGemini(key)
        return START_NOT_STICKY
    }

    private fun connectGemini(key: String) {
        if (!active) return
        val generation = ++connectionGeneration
        val model = if (mode == MODE_TRANSCRIBE) TRANSCRIBE_MODEL else TRANSLATE_MODEL
        val operation = if (mode == MODE_TRANSCRIBE)
            GeminiLiveClient.OperationMode.TRANSCRIBE else GeminiLiveClient.OperationMode.TRANSLATE
        val liveClient = GeminiLiveClient(
            apiKey = key,
            model = model,
            targetLanguage = "vi",
            echoTargetLanguage = false,
            logger = logger,
            resumeHandle = resumeHandle,
            operationMode = operation,
            listener = object : GeminiLiveClient.Listener {
                @SuppressLint("NewApi")
                override fun onSetupComplete() {
                    if (!active || generation != connectionGeneration) return
                    reconnectAttempts = 0
                    notifyStatus(if (mode == MODE_TRANSCRIBE)
                        "Đang nhận dạng lời nói từ âm thanh nội bộ" else "Đang dịch âm thanh nội bộ")
                    if (source == null) launchSource() else source?.resume()
                }

                override fun onText(text: String) {
                    if (!active || generation != connectionGeneration) return
                    if (mode == MODE_TRANSLATE) appendTranslation(text)
                    else appendTranscriptionSegment(text)
                }

                override fun onAudio(pcm24kMono: ByteArray) {
                    if (!active || generation != connectionGeneration || mode != MODE_TRANSLATE) return
                    if (voice == VOICE_GEMINI) player?.enqueue(pcm24kMono)
                }

                override fun onInputTranscript(text: String) {
                    if (!active || generation != connectionGeneration) return
                    if (mode == MODE_TRANSCRIBE) appendTranscriptionSegment(text)
                }

                override fun onInterimTranscript(text: String) {
                    // The source app displays interim words but does not commit them as final.
                    if (!active || generation != connectionGeneration) return
                    logger.log(3, "TranscribeLive", "Interim transcript chars=${text.length}")
                }

                override fun onInterrupted() {
                    if (generation != connectionGeneration) return
                    player?.flush()
                    lastRawTranscript = ""
                }

                override fun onTurnComplete() {
                    if (generation != connectionGeneration) return
                    lastRawTranscript = ""
                }

                override fun onSessionResumptionUpdate(resumable: Boolean, newHandle: String?) {
                    if (generation == connectionGeneration && resumable && !newHandle.isNullOrBlank()) {
                        resumeHandle = newHandle
                    }
                }

                override fun onGoAway(timeLeft: String?) {
                    if (generation == connectionGeneration) reconnect(key)
                }

                override fun onError(error: Throwable) {
                    if (generation != connectionGeneration) return
                    logger.log(0, "Gemini", "Phiên Gemini Live gặp lỗi", error)
                    reconnect(key)
                }

                override fun onClosed(reason: String) {
                    if (generation == connectionGeneration && active) {
                        logger.log(1, "Gemini", "Mất kết nối: $reason")
                        reconnect(key)
                    }
                }
            },
        )
        client = liveClient
        liveClient.connect()
    }

    // API 29+ is enforced at the service entry and in the capture-request Activity.
    @SuppressLint("NewApi")
    private fun launchSource() {
        val projection = projection ?: return
        val capture = InternalAudioSource(this, projection, logger)
        source = capture
        queue = LinkedBlockingDeque(10)
        // Original TranslationService: queue with DROP_OLDEST and bounded retries
        sendJob?.cancel()
        sendJob = scope.launch {
            while (isActive && active) {
                val packet = queue?.pollFirst(250, TimeUnit.MILLISECONDS) ?: continue
                var delivered = false
                while (isActive && active && !delivered) {
                    when (client?.sendAudio(packet) ?: GeminiLiveClient.SendResult.NOT_READY) {
                        GeminiLiveClient.SendResult.SENT -> delivered = true
                        GeminiLiveClient.SendResult.BACKPRESSURED -> delay(12)
                        GeminiLiveClient.SendResult.NOT_READY,
                        GeminiLiveClient.SendResult.CLOSED -> delay(30)
                        GeminiLiveClient.SendResult.FAILED -> delay(50)
                    }
                }
            }
        }
        sourceJob = scope.launch {
            capture.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    if (!active) return
                    val q = queue ?: return
                    val packet = data.copyOf()
                    if (q.offerLast(packet)) return
                    if (q.pollFirst() != null) droppedInputChunks.incrementAndGet()
                    q.offerLast(packet)
                }

                override fun onError(error: Throwable) {
                    if (!active) return
                    logger.log(0, "InternalAudio", "Nguồn âm thanh nội bộ đã dừng", error)
                    announce("Không thu được âm thanh nội bộ: ${error.message.orEmpty()}")
                    stopSelf()
                }
            })
        }
    }

    private fun appendTranslation(raw: String) {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return
        val previous = lastRawTranscript
        val delta = when {
            previous.isBlank() -> cleaned
            cleaned.startsWith(previous) -> cleaned.removePrefix(previous).trim()
            previous.startsWith(cleaned) -> ""
            else -> cleaned
        }
        lastRawTranscript = cleaned
        if (delta.isNotBlank()) {
            currentTranscript = (currentTranscript + " " + delta).trim().takeLast(12_000)
            if (voice == VOICE_READER) speakWithReader(delta)
        }
    }

    private fun appendTranscriptionSegment(raw: String) {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return
        val base = committedTranscript
        val updated = mergeLiveTranscript(base, cleaned)
        if (updated == base) return
        committedTranscript = updated
        currentTranscript = updated.takeLast(12_000)
        val delta = updated.removePrefix(base).trim()
        if (delta.isNotBlank()) speakWithReader(delta)
    }

    /** Adapted from TranslationService.mergeLiveTranscript in Gemini Live Translate. */
    private fun mergeLiveTranscript(baseRaw: String, candidateRaw: String): String {
        val base = baseRaw.trim()
        val candidate = candidateRaw.trim()
        if (base.isBlank()) return candidate
        if (candidate.isBlank()) return base
        if (candidate == base || base.endsWith(candidate)) return base
        if (candidate.startsWith(base)) return candidate
        if (base.startsWith(candidate)) return base
        val maxOverlap = minOf(base.length, candidate.length)
        for (length in maxOverlap downTo 1) {
            if (base.regionMatches(base.length - length, candidate, 0, length, ignoreCase = false)) {
                val suffix = candidate.substring(length).trimStart()
                return if (suffix.isBlank()) base else "$base $suffix"
            }
        }
        return "$base $candidate"
    }

    private fun speakWithReader(text: String) {
        if (text.isBlank()) return
        val speech = TalkBackService.getInstance()?.speechController ?: return
        val options = SpeechController.SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
            .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES)
            .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE)
        speech.speak(text, Performance.EVENT_ID_UNTRACKED, options)
    }

    @SuppressLint("NewApi")
    private fun reconnect(key: String) {
        if (!active) return
        reconnectAttempts++
        if (reconnectAttempts > 5) {
            announce("Không thể tiếp tục phiên Gemini Live.")
            stopSelf()
            return
        }
        ++connectionGeneration
        client?.close(false)
        client = null
        queue?.clear()
        source?.pause()
        scope.launch {
            delay(minOf(1_000L * reconnectAttempts, 5_000L))
            if (!active) return@launch
            // Match the original source: resume capture only after Gemini setup completes.
            connectGemini(key)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nhận dạng âm thanh nội bộ", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, InternalLiveRecognitionActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, InternalLiveRecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (mode == MODE_TRANSCRIBE) "Chép lời âm thanh nội bộ" else "Dịch âm thanh nội bộ")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Dừng", stop)
            .build()
    }

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun announce(text: String) = speakWithReader(text)

    @SuppressLint("NewApi")
    override fun onDestroy() {
        active = false
        running = false
        connectionGeneration++
        source?.stop()
        source = null
        sourceJob?.cancel()
        sendJob?.cancel()
        queue?.clear()
        queue = null
        client?.close(false)
        client = null
        player?.stop()
        player = null
        runCatching { projection?.stop() }
        projection = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.google.android.accessibility.talkback.liveaudio.START"
        const val ACTION_STOP = "com.google.android.accessibility.talkback.liveaudio.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PROJECTION_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val MODE_TRANSLATE = "translate"
        const val MODE_TRANSCRIBE = "transcribe"
        const val VOICE_GEMINI = "gemini"
        const val VOICE_READER = "reader"
        private const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
        private const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
        private const val CHANNEL_ID = "meridian_live_audio"
        private const val NOTIFICATION_ID = 8134
        @Volatile var running = false
            private set
        @Volatile var currentTranscript = ""
            private set
    }
}
