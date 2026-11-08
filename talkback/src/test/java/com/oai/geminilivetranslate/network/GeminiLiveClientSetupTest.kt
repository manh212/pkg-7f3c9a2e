package com.oai.geminilivetranslate.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from Gemini Live Translate's original setup test for screen-reader output. */
class GeminiLiveClientSetupTest {
    @Test fun translateModelRequestsAudioAndTranslatedTextForMeridian() {
        val root = JSONObject(GeminiLiveClient.createSetupMessage(
            model = "gemini-3.5-live-translate-preview",
            targetLanguage = "vi",
            echoTargetLanguage = false,
        ))
        val setup = root.getJSONObject("setup")
        val config = setup.getJSONObject("generationConfig")
        val translate = config.getJSONObject("translationConfig")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals("vi", translate.getString("targetLanguageCode"))
        assertFalse(translate.getBoolean("echoTargetLanguage"))
        // Restore the original standalone setup first, instead of changing it
        // merely because the gesture routes through SubtitleController.
        assertFalse(config.has("outputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
    }

    @Test fun canUseDocumentedTranscriptionLayoutIfOldLayoutIsRejected() {
        val setup = JSONObject(GeminiLiveClient.createSetupMessage(
            model = "gemini-3.5-live-translate-preview",
            targetLanguage = "vi",
            echoTargetLanguage = false,
            transcriptPlacement = GeminiLiveClient.TranscriptPlacement.GENERATION_CONFIG,
        )).getJSONObject("setup")
        assertTrue(setup.getJSONObject("generationConfig").has("outputAudioTranscription"))
        assertFalse(setup.has("outputAudioTranscription"))
    }
}
