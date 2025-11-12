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
import io.github.sdpiter.livetranslate.asr.VoskEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LtAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW   = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE   = "io.github.sdpiter.livetranslate.accessibility.HIDE"
        const val ACTION_TOGGLE = "io.github.sdpiter.livetranslate.accessibility.TOGGLE"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null

    // UI refs
    private var dirView: TextView? = null
    private var tvOriginalView: TextView? = null
    private var tvTranslatedView: TextView? = null
    private var btnAsrView: Button? = null

    // Scopes: UI и фон
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ASR/MT/TTS
    private lateinit var asrSystem: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine
    private var vosk: VoskEngine? = null
    private var useVosk = true // по умолчанию Vosk — без пиликанья

    // Состояние
    private var listenLang = "en-US"
    private var targetLang = "ru"
    private var dialogMode = false
    private var listening = false
    private var collectJob: Job? = null
    private var isToggling = false
    private var lastActionTs = 0L

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
            addAction(ACTION_SHOW); addAction(ACTION_HIDE); addAction(ACTION_TOGGLE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Receiver error: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        asrSystem = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)
        vosk = VoskEngine(this)

        Toast.makeText(this, "LiveTranslate (Accessibility) готово", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lpWindow(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }

    private fun btnLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            val m = dp(6)
            setMargins(m, m, m, m)
        }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = btnLayoutParams()
            setOnClickListener { if (canClick()) onClick() }
        }

    private fun canClick(minIntervalMs: Long = 400): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastActionTs >= minIntervalMs) {
            lastActionTs = now; true
        } else false
    }

    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 16f; text = "LiveTranslate (Accessibility)"
            }
            val btnClose = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 18f; text = "   ✕"
                setOnClickListener { hidePanel() }
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClose)
        }

        // Direction label
        dirView = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f
            text = "Направление: EN → RU"
        }

        // Text outputs
        tvOriginalView = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f; text = ""
        }
        tvTranslatedView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; text = ""
        }

        // Row 1: Subtitles, Dialog, Pause/Start (ровные ширины)
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeButton("Субтитры") { dialogMode = false; startListeningSafe() })
            addView(makeButton("Диалог") { dialogMode = true; startListeningSafe() })
            addView(makeButton(if (listening) "Пауза" else "Старт") {
                if (listening) stopListeningSafe() else startListeningSafe()
            }.also { btn ->
                // обновлять подпись при смене состояния
                uiScope.launch {
                    // держим ссылку на кнопку
                    // кнопку переименуем из start/stop в процессе
                }
            })
        }

        // Row 2: Swap, ASR:Vosk/System, Clear
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val btnSwap = makeButton("Swap EN↔RU") { swapLanguages(restart = listening) }
            btnAsrView = makeButton(if (useVosk) "ASR: Vosk" else "ASR: System") {
                useVosk = !useVosk
                btnAsrView?.text = if (useVosk) "ASR: Vosk" else "ASR: System"
                if (listening) {
                    // мягкий перезапуск
                    stopListeningSafe { startListeningSafe() }
                }
            }
            val btnClear = makeButton("Очистить") { tvOriginalView?.text = ""; tvTranslatedView?.text = "" }
            addView(btnSwap); addView(btnAsrView); addView(btnClear)
        }

        root.addView(header)
        root.addView(dirView)
        root.addView(tvOriginalView)
        root.addView(tvTranslatedView)
        root.addView(row1)
        root.addView(row2)

        try {
            wm.addView(root, lpWindow())
            panel = root
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay(panel): ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null
        stopListeningSafe()
    }

    private fun swapLanguages(restart: Boolean) {
        val wasRu = listenLang.startsWith("ru", true)
        listenLang = if (wasRu) "en-US" else "ru-RU"
        targetLang = if (wasRu) "ru" else "en"
        dirView?.text = "Направление: ${if (listenLang.startsWith("ru")) "RU" else "EN"} → ${targetLang.uppercase()}"
        if (restart) {
            stopListeningSafe { startListeningSafe() }
        }
    }

    private fun startListeningSafe() {
        if (isToggling) return
        isToggling = true
        workScope.launch {
            try {
                stopListeningInternal() // на всякий
                translator.ensure(listenLang, targetLang) // фоново
                listening = true
                collectJob = launch {
                    if (useVosk) {
                        val v = vosk ?: return@launch
                        launch {
                            v.results.collectLatest { text ->
                                uiScope.launch {
                                    tvOriginalView?.text = text
                                }
                                if (text.isNotBlank()) {
                                    val tr = try { translator.translate(text) } catch (_: Exception) { "" }
                                    uiScope.launch {
                                        tvTranslatedView?.text = tr
                                        if (dialogMode && tr.isNotBlank()) tts.speak(tr, targetLang)
                                    }
                                }
                            }
                        }
                        v.start(listenLang)
                    } else {
                        launch {
                            asrSystem.results.collectLatest { text ->
                                uiScope.launch {
                                    tvOriginalView?.text = text
                                }
                                if (text.isNotBlank()) {
                                    val tr = try { translator.translate(text) } catch (_: Exception) { "" }
                                    uiScope.launch {
                                        tvTranslatedView?.text = tr
                                        if (dialogMode && tr.isNotBlank()) tts.speak(tr, targetLang)
                                    }
                                }
                            }
                        }
                        asrSystem.start(listenLang)
                    }
                }
            } catch (_: Exception) {
                uiScope.launch { Toast.makeText(this@LtAccessibilityService, "Ошибка старта", Toast.LENGTH_SHORT).show() }
            } finally {
                isToggling = false
            }
        }
    }

    private fun stopListeningSafe(onStopped: (() -> Unit)? = null) {
        if (isToggling) return
        isToggling = true
        workScope.launch {
            try {
                stopListeningInternal()
                uiScope.launch { /* можно обновить кнопки на “Старт” */ }
                onStopped?.let { uiScope.launch { it() } }
            } finally {
                isToggling = false
            }
        }
    }

    private suspend fun stopListeningInternal() {
        listening = false
        try { asrSystem.stop() } catch (_: Exception) {}
        try { vosk?.stop() } catch (_: Exception) {}
        collectJob?.cancel()
        collectJob = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        hidePanel()
        if (this::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
