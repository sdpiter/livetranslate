package io.github.sdpiter.livetranslate.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class SpeechRecognizerEngine(private val context: Context) {

    val results = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var currentLang = "en-US"
    private var restarting = false

    fun start(languageTag: String) {
        currentLang = languageTag
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { scheduleRestart() }
                override fun onResults(resultsBundle: Bundle) {
                    val text = resultsBundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    emit(text)
                    scheduleRestart()
                }
                override fun onPartialResults(partialResults: Bundle) {
                    val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) emit(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang) // "en-US" | "ru-RU"
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // офлайн, если доступно
        }
        recognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        if (restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            try {
                recognizer?.stopListening()
                recognizer?.cancel()
            } catch (_: Exception) { }
            startListening()
        }, 200L)
    }

    private fun emit(text: String) {
        scope.launch { results.emit(text) }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        restarting = false
    }
}
