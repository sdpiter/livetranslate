package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LtAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW   = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE   = "io.github.sdpiter.livetranslate.accessibility.HIDE"
        const val ACTION_TOGGLE = "io.github.sdpiter.livetranslate.accessibility.TOGGLE"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null

    private var dirView: TextView? = null
    private var tvOriginalView: TextView? = null
    private var tvTranslatedView: TextView? = null
    private var btnStartPause: Button? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val errHandler = CoroutineExceptionHandler { _, e ->
        logE("CEH", e)
        mainHandler.post {
            Toast.makeText(this@LtAccessibilityService, "Ошибка: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            updateStateUi()
        }
    }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errHandler)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errHandler)

    private var vosk: VoskEngine? = null
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"
    private var targetLang = "ru"
    private var dialogMode = false
    private var listening = false
    private var collectJob: Job? = null

    private val engineMutex = Mutex()
    private var isToggling = false
    private var lastTapTs = 0L

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
            if (Build.VERSION.SDK_INT >= 34) registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(controlReceiver, filter)
        } catch (e: Exception) { logE("registerReceiver", e) }

        vosk = VoskEngine(
            this,
            onError = { logE("vosk", it) },
            onInfo  = { logI(it) }
        )
        translator = MlKitTranslator()
        tts = TtsEngine(this)
        logI("service.connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lpWindow() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }

    private fun row3(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; weightSum = 3f
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                val m = dp(6); setMargins(m, m, m, m)
            }
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
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

        val header = row3().apply {
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 16f; text = "LiveTranslate (Accessibility)"
            }
            val close = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); textSize = 18f; text = "   ✕"
                setOnClickListener { hidePanel() }
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        dirView = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f; text = "Направление: EN → RU"
        }
        tvOriginalView = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); textSize = 14f }
        tvTranslatedView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }

        val row1 = row3().apply {
            addView(btn("Субтитры") { dialogMode = false; startSafe() })
            addView(btn("Диалог") { dialogMode = true; startSafe() })
            btnStartPause = btn(if (listening) "Пауза" else "Старт") {
                if (listening) stopSafe() else startSafe()
            }
            addView(btnStartPause)
        }

        val row2 = row3().apply {
            addView(btn("Swap EN↔RU") { swap(restart = listening) })
            addView(btn("Очистить") { tvOriginalView?.text = ""; tvTranslatedView?.text = "" })
            addView(btn("Скрыть") { hidePanel() })
        }

        root.addView(header)
        root.addView(dirView)
        root.addView(tvOriginalView)
        root.addView(tvTranslatedView)
        root.addView(row1)
        root.addView(row2)

        try { wm.addView(root, lpWindow()); panel = root } catch (e: Exception) {
            logE("addView", e); Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
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
        btnStartPause?.text = if (listening) "Пауза" else "Старт"
    }

    private fun startSafe() {
        if (isToggling) return
        isToggling = true
        workScope.launch {
            engineMutex.withLock {
                try {
                    logI("startSafe.begin")
                    stopInternal()
                    try { translator.ensure(listenLang, targetLang) } catch (e: Exception) { logE("translator.ensure", e) }
                    listening = true
                    uiScope.launch { updateStateUi() }

                    val v = vosk ?: return@withLock
                    collectJob = launch(errHandler) {
                        launch(errHandler) {
                            v.results.collectLatest { text ->
                                uiScope.launch { tvOriginalView?.text = text }
                                if (text.isNotBlank()) {
                                    val tr = translator.translateSafe(text)
                                    uiScope.launch {
                                        tvTranslatedView?.text = tr
                                        if (dialogMode && tr.isNotBlank()) tts.speak(tr, targetLang)
                                    }
                                }
                            }
                        }
                        v.start(listenLang)
                    }
                    logI("startSafe.ok")
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
                    logI("stopSafe.begin")
                    stopInternal()
                    uiScope.launch { updateStateUi(); onStopped?.invoke() }
                    logI("stopSafe.ok")
                } catch (e: Exception) { logE("stopSafe", e) }
                finally { isToggling = false }
            }
        }
    }

    private suspend fun stopInternal() {
        if (!listening && collectJob == null) return
        listening = false
        collectJob?.cancel(); collectJob = null
        try { vosk?.stopAndJoin() } catch (e: Exception) { logE("vosk.stopAndJoin", e) }
        delay(120)
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        try { vosk?.stop() } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        if (this::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    // Лог
    private fun logE(tag: String, e: Throwable) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(filesDir, "lt.log").appendText("$ts [ERR][$tag] ${e.javaClass.simpleName}: ${e.message}\n")
        } catch (_: Exception) {}
    }
    private fun logI(msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(filesDir, "lt.log").appendText("$ts [INF] $msg\n")
        } catch (_: Exception) {}
    }
}
