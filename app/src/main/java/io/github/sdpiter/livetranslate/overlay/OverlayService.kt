package io.github.sdpiter.livetranslate.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import io.github.sdpiter.livetranslate.MainActivity
import io.github.sdpiter.livetranslate.R
import io.github.sdpiter.livetranslate.asr.SpeechRecognizerEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "io.github.sdpiter.livetranslate.START"
        const val ACTION_STOP  = "io.github.sdpiter.livetranslate.STOP"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "lt_channel"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private lateinit var asr: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"
    private var targetLang = "ru"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        if (!Settings.canDrawOverlays(this)) {
            updateNotification("Нет разрешения overlay — откройте настройки")
            // Откроем страницу разрешения
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Разрешите «поверх других приложений»", Toast.LENGTH_LONG).show()
            // продолжим работу — если дадут право, окно появится при повторном запуске
        }

        asr = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)

        try {
            showOverlay()
            updateNotification("Оверлей активен")
        } catch (e: Exception) {
            updateNotification("Ошибка overlay: ${e.javaClass.simpleName}")
            Toast.makeText(this, "Ошибка overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LiveTranslate")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "LiveTranslate", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) throw SecurityException("Overlay permission missing")
        if (overlayView != null) return

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.y = 40

        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    OverlayUI(
                        onClose = { stopSelf() },
                        onSwap = {
                            val wasListenRu = listenLang.startsWith("ru", true)
                            listenLang = if (wasListenRu) "en-US" else "ru-RU"
                            targetLang = if (wasListenRu) "ru" else "en"
                            updateNotification("Направление: $listenLang → $targetLang")
                        }
                    )
                }
            }
        }

        windowManager.addView(view, lp)
        overlayView = view
    }

    @Composable
    private fun OverlayUI(
        onClose: () -> Unit,
        onSwap: () -> Unit
    ) {
        var lastOriginal by remember { mutableStateOf("") }
        var lastTranslated by remember { mutableStateOf("") }
        var isDialog by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }

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
                .padding(8.dp)
                .background(Color(0xAA000000)),
            color = Color(0xCC222222)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LiveTranslate", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (listening) "●" else "○", color = if (listening) Color(0xFF58E6D9) else Color.LightGray)
                        Text("Swap", color = Color.White, modifier = Modifier.clickable { onSwap() })
                        Text("✕", color = Color.White, modifier = Modifier.clickable { onClose() })
                    }
                }
                if (lastOriginal.isNotBlank()) Text(lastOriginal, color = Color(0xFFB0BEC5))
                if (lastTranslated.isNotBlank()) Text(lastTranslated, color = Color.White)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        asr.stop()
        tts.shutdown()
        scope.cancel()
    }
}
