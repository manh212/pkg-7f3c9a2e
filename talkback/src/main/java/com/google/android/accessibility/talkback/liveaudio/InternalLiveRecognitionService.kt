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
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.subtitles.SubtitleSettings
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsBuffer = StringBuilder()
    private val ttsFlush = Runnable { flushTtsBuffer() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var source: InternalAudioSource? = null
    private var projection: MediaProjection? = null
    private var client: GeminiLiveClient? = null
    private var player: StreamingPcmPlayer? = null
    private var sourceJob: Job? = null
    private var sendJob: Job? = null
    private var queue: LinkedBlockingDeque<ByteArray>? = null
    private val droppedInputChunks = AtomicLong(0)
    private var voice = VOICE_GEMINI
    @Volatile private var active = false
    private var connectionGeneration = 0
    private var lastRawTranscript = ""
    private var reconnectAttempts = 0
    private var resumeHandle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            clearPendingStart()
            TalkBackService.getInstance()?.speechController
                ?.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        // If the user cancelled through the subtitle gesture during consent, discard
        // a late start request rather than silently starting a new session.
        if (!hasPendingStart()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            clearPendingStart()
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        val code = intent.getIntExtra(EXTRA_PROJECTION_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (code == 0 || data == null) {
            clearPendingStart()
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = SharedPreferencesUtils.getSharedPreferences(this)
        val key = GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY)
        voice = SubtitleSettings.geminiVoice(prefs)
        if (key.isEmpty()) {
            announce("Chưa thiết lập khóa truy cập.")
            clearPendingStart()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification("Đang đọc phụ đề"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(code, data)
                ?: throw IllegalStateException("Projection result rejected")
            active = true
            running = true
            clearPendingStart()
            if (voice == VOICE_GEMINI) {
                player = StreamingPcmPlayer(
                    sampleRate = 24_000,
                    bufferBytes = 48_000,
                    queueCapacity = 50,
                    initialJitterChunks = 1,
                    usage = if (SubtitleSettings.geminiChannel(prefs) == SubtitleSettings.CHANNEL_MEDIA)
                        AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                    logger = logger,
                    diagnosticName = "SubtitleAudio",
                ).also { it.start() }
            }
            connectGemini(key)
        } catch (e: RuntimeException) {
            logger.log(0, "SubtitleSession", "Capture start failed", e)
            clearPendingStart()
            active = false
            running = false
            announce("Không bật được đọc phụ đề. Vui lòng kiểm tra quyền thu âm thanh.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun connectGemini(key: String) {
        if (!active) return
        val generation = ++connectionGeneration
        val model = TRANSLATE_MODEL
        val operation = GeminiLiveClient.OperationMode.TRANSLATE
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
                    notifyStatus("Đang đọc phụ đề")
                    if (source == null) launchSource() else source?.resume()
                }

                override fun onText(text: String) {
                    if (!active || generation != connectionGeneration) return
                    appendTranslation(text)
                }

                override fun onAudio(pcm24kMono: ByteArray) {
                    if (!active || generation != connectionGeneration) return
                    if (voice == VOICE_GEMINI) player?.enqueue(pcm24kMono)
                }

                override fun onInterrupted() {
                    if (generation != connectionGeneration) return
                    player?.flush()
                    mainHandler.post {
                        mainHandler.removeCallbacks(ttsFlush)
                        ttsBuffer.clear()
                        lastRawTranscript = ""
                    }
                }

                override fun onTurnComplete() {
                    if (generation != connectionGeneration) return
                    mainHandler.post {
                        lastRawTranscript = ""
                        flushTtsBuffer()
                    }
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

    /** Gemini emits text incrementally. Keep the source client's cumulative/delta handling,
     * then buffer partial words for Meridian's existing subtitle utterance group. */
    private fun appendTranslation(raw: String) {
        if (voice != VOICE_READER) return
        mainHandler.post {
            if (!active || voice != VOICE_READER) return@post
            val cleaned = raw.trim()
            if (cleaned.isBlank()) return@post
            val previous = lastRawTranscript
            val delta = when {
                previous.isBlank() -> cleaned
                cleaned.startsWith(previous) -> cleaned.removePrefix(previous).trim()
                previous.startsWith(cleaned) -> ""
                else -> cleaned
            }
            lastRawTranscript = cleaned
            if (delta.isNotBlank()) queueTts(delta)
        }
    }

    /** Same buffering principle and defaults as source TranslationService.queueTts. */
    private fun queueTts(text: String) {
        if (text.isBlank() || voice != VOICE_READER) return
        if (ttsBuffer.isNotEmpty()) ttsBuffer.append(' ')
        ttsBuffer.append(text)
        val words = ttsBuffer.toString().trim().split(Regex("\\s+")).size
        val flush = ttsBuffer.length >= 60 || words >= 8 ||
            text.lastOrNull() in listOf('.', '!', '?', '。', '！', '？')
        mainHandler.removeCallbacks(ttsFlush)
        if (flush) flushTtsBuffer() else mainHandler.postDelayed(ttsFlush, 2_200L)
    }

    private fun flushTtsBuffer() {
        mainHandler.removeCallbacks(ttsFlush)
        val text = ttsBuffer.toString().trim()
        ttsBuffer.clear()
        if (text.isNotBlank()) speakWithReader(text)
    }

    /** Use the very same speech group and flags as SubtitleController's OCR speaker. */
    private fun speakWithReader(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            val speech = TalkBackService.getInstance()?.speechController ?: return@post
            val flags = FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE or
                FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP
            val options = SpeechController.SpeakOptions.create()
                .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
                .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES)
                .setFlags(flags)
            speech.speak(text, Performance.EVENT_ID_UNTRACKED, options)
        }
    }

    @SuppressLint("NewApi")
    private fun reconnect(key: String) {
        if (!active) return
        reconnectAttempts++
        if (reconnectAttempts > 5) {
            announce("Không thể tiếp tục đọc phụ đề.")
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
            NotificationChannel(CHANNEL_ID, "Đọc phụ đề", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đọc phụ đề")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun announce(text: String) = speakWithReader(text)

    @SuppressLint("NewApi")
    override fun onDestroy() {
        active = false
        running = false
        clearPendingStart()
        mainHandler.removeCallbacks(ttsFlush)
        ttsBuffer.clear()
        // Do not erase a spoken startup/revocation error from the accessibility
        // queue. Explicit user STOP clears it in onStartCommand above.
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
        const val EXTRA_PROJECTION_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val VOICE_GEMINI = "gemini"
        const val VOICE_READER = "reader"
        private const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
        private const val CHANNEL_ID = "meridian_subtitle_reader"
        private const val NOTIFICATION_ID = 8134
        @Volatile var running = false
            private set
        @Volatile private var pendingStartAt = 0L

        fun markPendingStart() {
            pendingStartAt = SystemClock.elapsedRealtime().coerceAtLeast(1L)
        }

        fun clearPendingStart() {
            pendingStartAt = 0L
        }

        fun hasPendingStart(): Boolean {
            val started = pendingStartAt
            return started > 0L &&
                SystemClock.elapsedRealtime() - started in 0L..600_000L
        }
    }
}
