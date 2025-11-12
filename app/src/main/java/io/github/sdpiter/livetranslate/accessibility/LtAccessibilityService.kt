package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.sdpiter.livetranslate.asr.SpeechRecognizerEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LtAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE = "io.github.sdpiter.livetranslate.accessibility.HIDE"
        const val ACTION_TOGGLE = "io.github.sdpiter.livetranslate.accessibility.TOGGLE"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null

    // Core
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var asr: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"
    private var targetLang = "ru"
    private var dialogMode = false
    private var listening = false
    private var collectJob: Job? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW   -> showPanel()
                ACTION_HIDE   -> hidePanel()
                ACTION_TOGGLE -> if (panel == null) showPanel() else hidePanel()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
            addAction(ACTION_TOGGLE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Receiver error: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        // Инициализация движков
        asr = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)

        Toast.makeText(this, "LiveTranslate (Accessibility) готово", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun params(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }
    }

    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        // Направление (создаём до header, чтобы ссылаться в onClick)
        val dir = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f
            text = "Направление: EN → RU"
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 16f; text = "LiveTranslate (Accessibility)"
            }
            val btnSwap = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 16f; text = "  Swap"
                setOnClickListener {
                    val wasRu = listenLang.startsWith("ru", true)
                    listenLang = if (wasRu) "en-US" else "ru-RU"
                    targetLang = if (wasRu) "ru" else "en"
                    dir.text = "Направление: ${if (listenLang.startsWith("ru")) "RU" else "EN"} → ${targetLang.uppercase()}"
                }
            }
            val btnClose = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 18f; text = "   ✕"
                setOnClickListener { hidePanel() }
            }
            addView(title); addView(btnSwap); addView(btnClose)
        }

        val tvOriginal = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f
            text = ""
        }
        val tvTranslated = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            text = ""
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val btnSubs = Button(this@LtAccessibilityService).apply {
                text = "Субтитры"
                setOnClickListener { dialogMode = false; startListening(tvOriginal, tvTranslated) }
            }
            val btnDialog = Button(this@LtAccessibilityService).apply {
                text = "Диалог"
                setOnClickListener { dialogMode = true; startListening(tvOriginal, tvTranslated) }
            }
            val btnToggle = Button(this@LtAccessibilityService).apply {
                text = "Пауза/Старт"
                setOnClickListener {
                    if (listening) stopListening() else startListening(tvOriginal, tvTranslated)
                }
            }
            val btnClear = Button(this@LtAccessibilityService).apply {
                text = "Очистить"
                setOnClickListener { tvOriginal.text = ""; tvTranslated.text = "" }
            }
            addView(btnSubs); addView(btnDialog); addView(btnToggle); addView(btnClear)
        }

        root.addView(header)
        root.addView(dir)
        root.addView(tvOriginal)
        root.addView(tvTranslated)
        root.addView(controls)

        try {
            wm.addView(root, params())
            panel = root
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay(panel): ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null
        stopListening()
    }

    private fun startListening(tvOriginal: TextView, tvTranslated: TextView) {
        stopListening()
        listening = true

        collectJob = scope.launch {
            // Подготовим переводчик (скачает пакет при необходимости)
            try { translator.ensure(listenLang, targetLang) } catch (_: Exception) {}

            // Собираем текст из ASR
            launch {
                asr.results.collectLatest { text ->
                    tvOriginal.text = text
                    if (text.isNotBlank()) {
                        val tr = try { translator.translate(text) } catch (_: Exception) { "" }
                        tvTranslated.text = tr
                        if (dialogMode && tr.isNotBlank()) tts.speak(tr, targetLang)
                    }
                }
            }
            asr.start(listenLang)
        }
    }

    private fun stopListening() {
        listening = false
        try { asr.stop() } catch (_: Exception) {}
        collectJob?.cancel()
        collectJob = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        hidePanel()
        if (this::tts.isInitialized) tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
