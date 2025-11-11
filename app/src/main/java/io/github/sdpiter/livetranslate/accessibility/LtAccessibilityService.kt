package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.github.sdpiter.livetranslate.asr.SpeechRecognizerEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LtAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    // Ядро
    private lateinit var asr: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"
    private var targetLang = "ru"

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        asr = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)
        showOverlay()
        Toast.makeText(this, "Accessibility overlay включён", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun lp(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Тип окна службы доступности — не требует FGS
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme { OverlayUI() }
            }
        }
        try {
            wm.addView(view, lp())
            overlay = view
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeOverlay() {
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlay = null
    }

    @Composable
    private fun OverlayUI() {
        var lastOriginal by remember { mutableStateOf("") }
        var lastTranslated by remember { mutableStateOf("") }
        var isDialog by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }

        // Подписка на текст из ASR
        LaunchedEffect(Unit) {
            scope.launch {
                asr.results.collectLatest { text ->
                    lastOriginal = text
                    if (text.isNotBlank()) {
                        translator.ensure(listenLang, targetLang)
                        val tr = translator.translate(text)
                        lastTranslated = tr
                        if (isDialog) tts.speak(tr, targetLang)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .background(Color(0xAA000000)),
            color = Color(0xCC222222)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LiveTranslate (Accessibility)", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (listening) "●" else "○", color = if (listening) Color(0xFF58E6D9) else Color.LightGray)
                        Text("Swap", color = Color.White, modifier = Modifier.clickable {
                            val wasRu = listenLang.startsWith("ru", true)
                            listenLang = if (wasRu) "en-US" else "ru-RU"
                            targetLang = if (wasRu) "ru" else "en"
                        })
                        Text("✕", color = Color.White, modifier = Modifier.clickable {
                            asr.stop()
                            listening = false
                            removeOverlay()
                        })
                    }
                }

                if (lastOriginal.isNotBlank()) Text(lastOriginal, color = Color(0xFFB0BEC5))
                if (lastTranslated.isNotBlank()) Text(lastTranslated, color = Color.White)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        isDialog = false
                        startListening()
                        listening = true
                    }) { Text("Субтитры") }

                    Button(onClick = {
                        isDialog = true
                        startListening()
                        listening = true
                    }) { Text("Диалог") }

                    OutlinedButton(onClick = {
                        if (listening) {
                            asr.stop(); listening = false
                        } else {
                            startListening(); listening = true
                        }
                    }) { Text(if (listening) "Пауза" else "Старт") }
                }
            }
        }
    }

    private fun startListening() {
        scope.launch {
            translator.ensure(listenLang, targetLang)
            asr.start(listenLang)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        if (this::asr.isInitialized) asr.stop()
        if (this::tts.isInitialized) tts.shutdown()
        scope.cancel()
    }
}
