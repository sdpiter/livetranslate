package io.github.sdpiter.livetranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifierpackage io.github.sdpiter.livetranslate.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.core.app.NotificationCompat
import io.github.sdpiter.livetranslate.R
import io.github.sdpiter.livetranslate.asr.SpeechRecognizerEngine
import io.github.sdpiter.livetranslate.mt.MlKitTranslator
import io.github.sdpiter.livetranslate.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "io.github.sdpiter.livetranslate.START"
        const val ACTION_STOP = "io.github.sdpiter.livetranslate.STOP"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "lt_channel"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private lateinit var asr: SpeechRecognizerEngine
    private lateinit var translator: MlKitTranslator
    private lateinit var tts: TtsEngine

    private var listenLang = "en-US"   // вход
    private var targetLang = "ru"      // перевод

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotification("Working"))

        asr = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)

        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            // ACTION_START — уже запущено в onCreate
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LiveTranslate")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "LiveTranslate", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP

        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    OverlayUI(
                        onClose = { stopSelf() },
                        onSwap = {
                            // swap вход/выход
                            val wasListenRu = listenLang.startsWith("ru", ignoreCase = true)
                            listenLang = if (wasListenRu) "en-US" else "ru-RU"
                            targetLang = if (wasListenRu) "ru" else "en"
                        }
                    )
                }
            }
        }

        overlayView = view
        try {
            windowManager.addView(view, lp)
        } catch (_: Exception) { }
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

        // Подписка на результаты ASR
        LaunchedEffect(Unit) {
            scope.launch {
                asr.results.collectLatest { text ->
                    lastOriginal = text
                    if (text.isNotBlank()) {
                        // перевод
                        scope.launch {
                            translator.ensure(listenLang, targetLang)
                            val tr = translator.translate(text)
                            lastTranslated = tr
                            if (isDialog) {
                                tts.speak(tr, targetLang)
                            }
                        }
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
                        Text(
                            if (listening) "●" else "○",
                            color = if (listening) Color(0xFF58E6D9) else Color.LightGray
                        )
                        Text("Swap", color = Color.White, modifier = Modifier.clickable { onSwap() })
                        Text("✕", color = Color.White, modifier = Modifier.clickable { onClose() })
                    }
                }

                if (lastOriginal.isNotBlank()) {
                    Text(lastOriginal, color = Color(0xFFB0BEC5))
                }
                if (lastTranslated.isNotBlank()) {
                    Text(lastTranslated, color = Color.White)
                }

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
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        asr.stop()
        tts.shutdown()
        scope.cancel()
    }
}
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Landing()
            }
        }
    }
}

@Composable
fun Landing() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("LiveTranslate α", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Скелет приложения готов. Далее добавим оверлей, распознавание и перевод.")
        }
    }
}
