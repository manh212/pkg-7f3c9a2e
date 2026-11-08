package com.oai.geminilivetranslate.core

import android.util.Log

/** Android logger adapter for audio/network classes imported from Gemini Live Translate. */
class SessionLogger {
    fun log(level: Int, tag: String, message: String, throwable: Throwable? = null) {
        val name = "MeridianLive/$tag"
        when (level) {
            0 -> Log.e(name, message, throwable)
            1 -> Log.w(name, message, throwable)
            2 -> Log.i(name, message, throwable)
            else -> Log.d(name, message, throwable)
        }
    }
}
