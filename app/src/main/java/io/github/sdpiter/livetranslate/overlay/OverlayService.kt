package io.github.sdpiter.livetranslate.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import io.github.sdpiter.livetranslate.MainActivity
import io.github.sdpiter.livetranslate.R
import io.github.sdpiter.livetranslate.asr.SpeechRecognizerEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "io.github.sdpiter.livetranslate.START"
        const val ACTION_STOP  = "io.github.sdpiter.livetranslate.STOP"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "lt_channel"
    }

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var debugView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private lateinit var asr: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"
    private var targetLang = "ru"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotifChannel()

        val note = buildNotification("Запуск…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIF_ID, note)
        } catch (e: Exception) {
            Toast.makeText(this, "FGS не запустился: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            updateNotification("FGS не запустился")
        }

        if (!Settings.canDrawOverlays(this)) {
            updateNotification("Нет права overlay")
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            Toast.makeText(this, "Дайте «Появление поверх других приложений»", Toast.LENGTH_LONG).show()
            retryShowOverlay()
        } else {
            tryShowOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun noteIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LiveTranslate")
            .setContentText(text)
            .setContentIntent(noteIntent())
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "LiveTranslate", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun retryShowOverlay() {
        scope.launch {
            repeat(12) {
                delay(1500)
                if (Settings.canDrawOverlays(this@OverlayService)) {
                    tryShowOverlay()
                    return@launch
                }
            }
            updateNotification("Overlay не выдан")
        }
    }

    private fun safeLp(): WindowManager.LayoutParams {
        // Минимальный набор флагов, чтобы исключить проблемы с фокусом/положением
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = 40
        return lp
    }

    private fun addDebugBar() {
        if (debugView != null) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCCFF1744.toInt()) // алый
            val tv = TextView(this@OverlayService).apply {
                text = "DEBUG OVERLAY (должен быть виден)"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(24, 16, 24, 16)
            }
            addView(tv)
        }
        val lp = safeLp().apply { y = 0 } // шапка сверху
        try {
            wm.addView(layout, lp)
            debugView = layout
            Toast.makeText(this, "Debug‑полоса добавлена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка debug‑оверлея: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryShowOverlay() {
        try {
            // 1) Добавим debug‑полосу обычным View
            addDebugBar()
            // 2) Основная панель на Compose
            showOverlay()
            updateNotification("Оверлей активен")
        } catch (e: Exception) {
            updateNotification("Ошибка overlay: ${e.javaClass.simpleName}")
            Toast.makeText(this, "Ошибка overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) throw SecurityException("Overlay permission missing")
        if (overlayView != null) return

        val lp = safeLp()
        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    OverlayUI(
                        onClose = { stopSelf() },
                        onSwap = {
                            val wasRu = listenLang.startsWith("ru", true)
                            listenLang = if (wasRu) "en-US" else "ru-RU"
                            targetLang = if (wasRu) "ru" else "en"
                            updateNotification("Направление: $listenLang → $targetLang")
                        }
                    )
                }
            }
        }
        wm.addView(view, lp)
        overlayView = view
        Toast.makeText(this, "Основная панель добавлена", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun OverlayUI(onClose: () -> Unit, onSwap: () -> Unit) {
        var lastOriginal by remember { mutableStateOf("") }
        var lastTranslated by remember { mutableStateOf("") }
        var isDialog by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            asr = SpeechRecognizerEngine(this@OverlayService)
            translator = MlKitTranslator()
            tts = TtsEngine(this@OverlayService)
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
                .padding(horizontal = 8.dp, vertical = 64.dp) // отступ, чтобы не прятаться под статусбар
                .background(CColor(0xAA000000)),
            color = CColor(0xCC222222)
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
                    Text("LiveTranslate", color = CColor.White)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        Text(if (listening) "●" else "○",
                            color = if (listening) CColor(0xFF58E6D9)) else CColor.LightGray)
                        Text("Swap", color = CColor.White,
                            modifier = androidx.compose.ui.Modifier.clickable { onSwap() })
                        Text("✕", color = CColor.White,
                            modifier = androidx.compose.ui.Modifier.clickable { onClose() })
                    }
                }
                if (lastOriginal.isNotBlank()) Text(lastOriginal, color = CColor(0xFFB0BEC5))
                if (lastTranslated.isNotBlank()) Text(lastTranslated, color = CColor.White)

                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { isDialog = false; startListening(); listening = true }) { Text("Субтитры") }
                    Button(onClick = { isDialog = true;  startListening(); listening = true }) { Text("Диалог") }
                    OutlinedButton(onClick = {
                        if (listening) { asr.stop(); listening = false; updateNotification("Пауза") }
                        else { startListening(); listening = true; updateNotification("Старт") }
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
        try { overlayView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { debugView?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        debugView = null
        if (this::asr.isInitialized) asr.stop()
        if (this::tts.isInitialized) tts.shutdown()
        scope.cancel()
    }
}
