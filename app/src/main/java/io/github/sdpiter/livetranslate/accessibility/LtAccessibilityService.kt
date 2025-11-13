package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.sdpiter.livetranslate.asr.VoskEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.settings.SettingsStorage
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

    // Window
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var dock: View? = null

    // UI refs
    private var dirView: TextView? = null
    private var tvOriginalView: TextView? = null
    private var tvTranslatedView: TextView? = null
    private var btnStartPause: Button? = null

    // История
    private var historyScroll: ScrollView? = null
    private var historyList: LinearLayout? = null
    private val history: ArrayDeque<Pair<String, String>> = ArrayDeque()
    private val maxHistory: Int = 20
    private var lastPushedOriginal: String = ""

    // Handlers / scopes
    private val mainHandler = Handler(Looper.getMainLooper())
    private val errHandler = CoroutineExceptionHandler { _, e ->
        logE("CEH", e)
        mainHandler.post {
            Toast.makeText(this@LtAccessibilityService, "Ошибка: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            updateStateUi()
        }
    }
    private val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errHandler)
    private val workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errHandler)

    // Engines
    private var vosk: VoskEngine? = null
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    // State
    private var listenLang: String = "en-US"
    private var targetLang: String = "ru"
    private var dialogMode: Boolean = false
    private var listening: Boolean = false
    private var collectJob: Job? = null

    // Автодетект
    private var autoDetect: Boolean = true
    private var lastDetect: String = "en" // "en"/"ru"

    // Шрифт панели (масштаб)
    private var fontScale: Float = 1.0f

    // гонки
    private val engineMutex = Mutex()
    private var isToggling: Boolean = false
    private var lastTapTs: Long = 0L

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW   -> { hideDock(); showPanel() }
                ACTION_HIDE   -> hidePanel()
                ACTION_TOGGLE -> if (panel == null) { hideDock(); showPanel() } else hidePanel()
                SettingsStorage.ACTION_CHANGED -> applySettings()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Инициализируем настройки
        SettingsStorage.init(this)
        applySettings() // установить autoDetect+fontScale

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW); addAction(ACTION_HIDE); addAction(ACTION_TOGGLE)
            addAction(SettingsStorage.ACTION_CHANGED)
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

        // Предзагрузка EN↔RU (если включено)
        if (SettingsStorage.preloadOnStart()) {
            workScope.launch(errHandler) {
                logI("preload.start")
                try {
                    translator.ensure("en-US", "ru-RU")
                    translator.ensure("ru-RU", "en-US")
                    logI("preload.done")
                } catch (e: Exception) { logE("preload", e) }
            }
        }

        // Док-бар
        showDock()
        logI("service.connected")
    }

    private fun applySettings() {
        autoDetect = SettingsStorage.autoDetect()
        fontScale = SettingsStorage.fontScale()
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

    private fun sp(base: Float): Float = base * fontScale

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
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

    // Док
    private fun showDock() {
        if (dock != null) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC673AB7.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
                text = "LiveTranslate — развернуть"
                setOnClickListener { hideDock(); showPanel() }
            }
            val close = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f)); text = "  ✕"
                setOnClickListener { hideDock() }
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        try { wm.addView(bar, lpWindow().apply { y = 0 }); dock = bar } catch (e: Exception) { logE("dock.add", e) }
    }
    private fun hideDock() {
        try { dock?.let { wm.removeView(it) } } catch (_: Exception) {}
        dock = null
    }

    // Панель
    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val header = row3().apply {
            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f)); text = "LiveTranslate (Accessibility)"
            }
            val close = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(18f)); text = "   ✕"
                setOnClickListener { hidePanel() }
            }
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        dirView = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
            text = "Направление: EN → RU"
        }
        tvOriginalView = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f)) }
        tvTranslatedView = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f)) }

        // История
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyScroll = ScrollView(this).apply { addView(historyList) }

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
            addView(btn("Очистить") { clearAll() })
            addView(btn("Скрыть") { hidePanel() })
        }

        root.addView(header)
        root.addView(dirView)
        root.addView(tvOriginalView)
        root.addView(tvTranslatedView)
        root.addView(historyScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
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
        showDock()
    }

    // История
    private fun clearAll() {
        tvOriginalView?.text = ""
        tvTranslatedView?.text = ""
        history.clear()
        historyList?.removeAllViews()
        lastPushedOriginal = ""
    }
    private fun pushHistory(orig: String, tr: String) {
        val o = orig.trim()
        if (o.isEmpty()) return
        if (o == lastPushedOriginal) return
        lastPushedOriginal = o
        if (history.size >= maxHistory) history.removeFirst()
        history.addLast(o to tr)
        historyList?.let { list ->
            list.removeAllViews()
            history.forEach { (oo, tt) ->
                val line = TextView(this).apply {
                    setTextColor(0xFFB0BEC5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                    text = if (tt.isNotBlank()) "• $oo  →  $tt" else "• $oo"
                }
                list.addView(line)
            }
            historyScroll?.post { historyScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateStateUi() {
        btnStartPause?.text = if (listening) "Пауза" else "Старт"
    }

    // Автодетект (простая эвристика)
    private fun detectLang(text: String): String {
        // если есть кириллица — ru, иначе en
        return if (CYR_REGEX.containsMatchIn(text)) "ru" else "en"
    }

    private fun maybeAutoSwitchLanguage(text: String) {
        if (!autoDetect) return
        // переключаемся, только если фраза «существенная»
        val significant = text.length >= 10 || text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
        if (!significant) return
        val detected = detectLang(text)
        if (detected != lastDetect) {
            lastDetect = detected
            val newListen = if (detected == "ru") "ru-RU" else "en-US"
            if (newListen != listenLang) {
                listenLang = newListen
                targetLang = if (detected == "ru") "en" else "ru"
                dirView?.text = "Направление: ${if (listenLang.startsWith("ru")) "RU" else "EN"} → ${targetLang.uppercase()}"
                if (listening) stopSafe { startSafe() }
            }
        }
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
                    lastDetect = if (listenLang.startsWith("ru")) "ru" else "en"
                    uiScope.launch { updateStateUi() }

                    val v = vosk ?: return@withLock
                    collectJob = launch(errHandler) {
                        launch(errHandler) {
                            v.results.collectLatest { text ->
                                uiScope.launch { tvOriginalView?.text = text }
                                if (text.isNotBlank()) {
                                    maybeAutoSwitchLanguage(text)
                                    val tr = translator.translateSafe(text)
                                    uiScope.launch { tvTranslatedView?.text = tr }
                                    if (text.length >= 10 || text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) {
                                        uiScope.launch { pushHistory(text, tr) }
                                        lastPushedOriginal = ""
                                    }
                                    if (dialogMode && tr.isNotBlank()) {
                                        uiScope.launch { tts.speak(tr, targetLang) }
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
        try { dock?.let { wm.removeView(it) } } catch (_: Exception) {}
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

    private val CYR_REGEX = Regex("[\\u0400-\\u04FF]")
}
