package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.sdpiter.livetranslate.asr.VoskEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File   // <-- добавлен импорт

class LtAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW   = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE   = "io.github.sdpiter.livetranslate.accessibility.HIDE"
        const val ACTION_TOGGLE = "io.github.sdpiter.livetranslate.accessibility.TOGGLE"
    }

    // Window
    private lateinit var wm: WindowManager
    private var panel: View? = null

    // UI refs
    private var dirView: TextView? = null
    private var tvOriginalView: TextView? = null
    private var tvTranslatedView: TextView? = null
    private var btnStartPause: Button? = null

    // Error handler без ссылок на uiScope (во избежание рекурсии типов)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val errHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        logE("CEH", e)
        mainHandler.post {
            Toast.makeText(this@LtAccessibilityService, "Ошибка: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            updateStateUi()
        }
    }

    // Scopes
    private val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errHandler)
    private val workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errHandler)

    // Engines (Vosk‑only)
    private var vosk: VoskEngine? = null
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    // State
    private var listenLang: String = "en-US"
    private var targetLang: String = "ru"
    private var dialogMode: Boolean = false
    private var listening: Boolean = false
    private var collectJob: Job? = null

    // Защита от гонок старт/стоп
    private val engineMutex: Mutex = Mutex()
    private var isToggling: Boolean = false
    private var lastTapTs: Long = 0L

    private val controlReceiver: BroadcastReceiver = object : BroadcastReceiver() {
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
            logE("registerReceiver", e)
        }

        vosk = VoskEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)

        Toast.makeText(this, "LiveTranslate (A11y) готово", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lpWindow(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }

    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                val m = dp(6); setMargins(m, m, m, m)
            }
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (!isToggling && now - lastTapTs >= 350) { lastTapTs = now; onClick() }
            }
        }

    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val header = row().apply {
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 16f; text = "LiveTranslate (Accessibility)"
            }
            val close = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 18f; text = "   ✕"
                setOnClickListener { hidePanel() }
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }

        dirView = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f; text = "Направление: EN → RU"
        }
        tvOriginalView = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); textSize = 14f }
        tvTranslatedView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }

        val row1 = row().apply {
            addView(btn("СУБТИТРЫ") { dialogMode = false; startSafe() })
            addView(btn("ДИАЛОГ") { dialogMode = true; startSafe() })
            btnStartPause = btn(if (listening) "ПАУЗА" else "СТАРТ") {
                if (listening) stopSafe() else startSafe()
            }
            addView(btnStartPause)
        }

        val row2 = row().apply {
            addView(btn("SWAP EN ↔ RU") { swap(restart = listening) })
            addView(btn("ОЧИСТИТЬ") { tvOriginalView?.text = ""; tvTranslatedView?.text = "" })
            addView(btn("СКРЫТЬ") { hidePanel() })
        }

        root.addView(header)
        root.addView(dirView)
        root.addView(tvOriginalView)
        root.addView(tvTranslatedView)
        root.addView(row1)
        root.addView(row2)

        try { wm.addView(root, lpWindow()); panel = root } catch (e: Exception) {
            logE("addView", e)
            Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null
        stopSafe()
    }

    private fun swap(restart: Boolean) {
        val wasRu = listenLang.startsWith("ru", true)
        listenLang = if (wasRu) "en-US" else "ru-RU"
        targetLang = if (wasRu) "ru" else "en"
        dirView?.text = "Направление: ${if (listenLang.startsWith("ru")) "RU" else "EN"} → ${targetLang.uppercase()}"
        if (restart) stopSafe { startSafe() }
    }

    private fun updateStateUi() {
        btnStartPause?.text = if (listening) "ПАУЗА" else "СТАРТ"
    }

    private fun startSafe() {
        if (isToggling) return
        isToggling = true
        workScope.launch {
            engineMutex.withLock {
                try {
                    stopInternal() // страховка
                    try { translator.ensure(listenLang, targetLang) } catch (e: Exception) { logE("translator.ensure", e) }
                    listening = true
                    uiScope.launch { updateStateUi() }

                    val v = vosk ?: return@withLock
                    collectJob = launch(errHandler) {
                        launch(errHandler) {
                            v.results.collectLatest { text ->
                                uiScope.launch { tvOriginalView?.text = text }
                                if (text.isNotBlank()) {
                                    val tr = try { translator.translate(text) } catch (e: Exception) { logE("translate", e); "" }
                                    uiScope.launch {
                                        tvTranslatedView?.text = tr
                                        if (dialogMode && tr.isNotBlank()) tts.speak(tr, targetLang)
                                    }
                                }
                            }
                        }
                        v.start(listenLang)
                    }
                } catch (e: Exception) {
                    logE("startSafe", e)
                    uiScope.launch { Toast.makeText(this@LtAccessibilityService, "Ошибка старта: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
                } finally { isToggling = false }
            }
        }
    }

    private fun stopSafe(onStopped: (() -> Unit)? = null) {
        if (isToggling) return
        isToggling = true
        workScope.launch {
            engineMutex.withLock {
                try {
                    stopInternal()
                    uiScope.launch { updateStateUi(); onStopped?.invoke() }
                } catch (e: Exception) {
                    logE("stopSafe", e)
                } finally { isToggling = false }
            }
        }
    }

    private suspend fun stopInternal() {
        if (!listening && collectJob == null) return
        listening = false
        collectJob?.cancel()
        collectJob = null
        try { vosk?.stopAndJoin() } catch (e: Exception) { logE("vosk.stopAndJoin", e) }
        delay(120) // отпускаем микрофон
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        try { vosk?.stop() } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        if (this::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    // лог в app/files/lt.log
    private fun logE(tag: String, e: Throwable) {
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val line = "$ts [$tag] ${e.javaClass.simpleName}: ${e.message}\n"
            File(filesDir, "lt.log").appendText(line)
        } catch (_: Exception) {}
    }
}
