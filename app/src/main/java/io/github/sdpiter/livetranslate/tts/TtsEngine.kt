package io.github.sdpiter.livetranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsEngine(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var ready = false
    private var rate = 1.0f
    private var pitch = 1.0f

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    fun setRate(value: Float) { rate = value.coerceIn(0.5f, 2.0f) }
    fun setPitch(value: Float) { pitch = value.coerceIn(0.5f, 2.0f) }

    fun speak(text: String, langTag: String) {
        if (!ready || text.isBlank()) return
        val locale = when (langTag.lowercase()) {
            "ru", "ru-ru" -> Locale("ru", "RU")
            else -> Locale.US
        }
        tts.language = locale
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lt-${System.currentTimeMillis()}")
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
