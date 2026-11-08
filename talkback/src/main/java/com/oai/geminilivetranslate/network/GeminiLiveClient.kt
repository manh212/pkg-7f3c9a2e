package com.oai.geminilivetranslate.network

import com.oai.geminilivetranslate.core.SessionLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini API-only adapter of the original GeminiLiveClient; excludes the AI Studio WebView path. */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val targetLanguage: String,
    private val echoTargetLanguage: Boolean,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val resumeHandle: String? = null,
    private val maxQueuedWireBytes: Long = 512L * 1024L,
    private val operationMode: OperationMode = OperationMode.TRANSLATE,
) {
    enum class OperationMode { TRANSLATE, TRANSCRIBE }

    interface Listener {
        fun onOpen() = Unit
        fun onSetupComplete()
        fun onText(text: String)
        fun onAudio(pcm24kMono: ByteArray)
        fun onInputTranscript(text: String) = Unit
        fun onInterimTranscript(text: String) = Unit
        fun onTurnComplete() = Unit
        fun onInterrupted() = Unit
        fun onSessionResumptionUpdate(resumable: Boolean, newHandle: String?) = Unit
        fun onGoAway(timeLeft: String?) = Unit
        fun onError(error: Throwable)
        fun onClosed(reason: String)
    }

    enum class SendResult {
        SENT,
        NOT_READY,
        BACKPRESSURED,
        CLOSED,
        FAILED,
    }

    data class BackpressureStats(
        val queuedWireBytes: Long,
        val maxObservedWireBytes: Long,
        val backpressureEvents: Long,
    )

    data class ResponseStats(
        val serverContentEvents: Long,
        val inputTranscriptEvents: Long,
        val outputTranscriptionObjects: Long,
        val outputTextEvents: Long,
        val modelTextEvents: Long,
        val textDispatchEvents: Long,
        val audioChunks: Long,
        val audioBytes: Long,
        val turnCompleteEvents: Long,
    )


    private val closed = AtomicBoolean(false)
    @Volatile private var apiBackend: GeminiApiLiveClient? = null

    fun connect() {
        if (closed.get()) return
        if (apiKey.isBlank()) {
            listener.onError(IllegalStateException("GEMINI_API_KEY_REQUIRED"))
            return
        }
        val backend = GeminiApiLiveClient(
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            echoTargetLanguage = echoTargetLanguage,
            logger = logger,
            listener = listener,
            resumeHandle = resumeHandle,
            maxQueuedWireBytes = maxQueuedWireBytes,
            operationMode = operationMode,
        )
        apiBackend = backend
        backend.connect()
    }

    fun sendAudio(pcm16kMono: ByteArray): SendResult =
        apiBackend?.sendAudio(pcm16kMono) ?: SendResult.NOT_READY

    fun sendAudioStreamEnd(): SendResult =
        apiBackend?.sendAudioStreamEnd() ?: SendResult.NOT_READY

    fun backpressureStats(): BackpressureStats =
        apiBackend?.backpressureStats() ?: BackpressureStats(0L, 0L, 0L)

    fun responseStats(): ResponseStats =
        apiBackend?.responseStats() ?: ResponseStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

    fun close(graceful: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        apiBackend?.close(graceful)
        apiBackend = null
    }

    companion object {
        internal fun createSetupMessage(
            model: String,
            targetLanguage: String,
            echoTargetLanguage: Boolean,
            resumeHandle: String? = null,
            operationMode: OperationMode = OperationMode.TRANSLATE,
        ): String {
            val setup = JSONObject()
                .put("model", "models/${model.trim().removePrefix("models/")}")
            if (operationMode == OperationMode.TRANSCRIBE) {
                setup.put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT")),
                )
                setup.put(
                    "inputAudioTranscription",
                    JSONObject().put("languageCodes", JSONArray()),
                )
            } else {
                val translationConfig = JSONObject()
                    .put("targetLanguageCode", targetLanguage)
                    .put("echoTargetLanguage", echoTargetLanguage)
                setup.put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("translationConfig", translationConfig),
                )
                // As in the source's AI Studio R17 setup, also request the translated text
                // so Meridian's screen reader voice option has words to speak.
                setup.put("outputAudioTranscription", JSONObject())
                setup.put(
                    "contextWindowCompression",
                    JSONObject().put("slidingWindow", JSONObject()),
                )
                resumeHandle?.trim()?.takeIf(String::isNotBlank)?.let { handle ->
                    setup.put("sessionResumption", JSONObject().put("handle", handle))
                }
            }
            return JSONObject().put("setup", setup).toString()
        }


    }
}
