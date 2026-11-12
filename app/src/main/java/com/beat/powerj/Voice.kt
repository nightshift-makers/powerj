package com.beat.powerj

import android.content.Context
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/** Pleos TTS (ON_DEVICE). Falls back to Android TTS when the Pleos speech service is missing. */
class Voice(private val context: Context, private val onLog: (String) -> Unit) {

    private var pleosTts: PleosTts? = null
    private var pleosReady = false
    private var androidTts: android.speech.tts.TextToSpeech? = null

    fun start() {
        runCatching {
            pleosTts = PleosTts(context, ai.pleos.playground.tts.constant.Mode.ON_DEVICE).apply {
                initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                    override fun onSuccess() { pleosReady = true; setLocale(Locale.KOREAN); onLog("Pleos TTS ready") }
                    override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) { onLog("Pleos TTS init failed: $e → Android TTS"); fallback() }
                })
            }
        }.onFailure { onLog("Pleos TTS unavailable: ${it.message} → Android TTS"); fallback() }
    }

    private fun fallback() {
        if (androidTts != null) return
        androidTts = android.speech.tts.TextToSpeech(context) { status ->
            androidTts?.language = Locale.KOREAN
        }
    }

    fun speak(text: String) {
        if (pleosReady) runCatching { pleosTts?.speak(text, false) }.onSuccess { return }
        androidTts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "exit")
    }

    fun release() {
        runCatching { pleosTts?.release() }
        androidTts?.shutdown()
    }
}
