package io.github.sdpiter.livetranslate.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "io.github.sdpiter.livetranslate.START"
        const val ACTION_STOP  = "io.github.sdpiter.livetranslate.STOP"
        private const val NOTIF_ID = 3001
        private const val CHANNEL_ID = "lt_fg"
    }

    private lateinit var wm: WindowManager
    private var view: View? = null

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
        createChannel()
        val note = buildNote("LiveTranslate работает")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, note)

        asr = SpeechRecognizerEngine(this)
        translator = MlKitTranslator()
        tts = TtsEngine(this)

        showOverlay()
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "LiveTranslate", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNote(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LiveTranslate")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()

    private fun showOverlay() {
        if (view != null) return
        // простые флаги, чтобы окно точно отрисовалось
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE, // warning ок
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }

        view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    OverlayUI()
                }
            }
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun OverlayUI() {
        var lastOriginal by remember { mutableStateOf("") }
        var lastTranslated by remember { mutableStateOf("") }
        var listening by remember { mutableStateOf(false) }
        var dialogMode by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            scope.launch {
                asr.results.collectLatest { text ->
                    lastOriginal = text
                    if (text.isNotBlank()) {
                        // Важно: используем translateSafe вместо translate
                        try {
                            translator.ensure(listenLang, targetLang)
                            val tr = translator.translateSafe(text)
                            lastTranslated = tr
                            if (dialogMode && tr.isNotBlank()) {
                                tts.speak(tr, targetLang)
                            }
                        } catch (_: Exception) {
                            // тихо игнорируем — OverlayService для компиляции/резерва
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
                Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LiveTranslate (FGS)", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (listening) "●" else "○",
                            color = if (listening) Color(0xFF58E6D9) else Color.LightGray)
                        Text("Swap", color = Color.White, modifier = Modifier.clickable {
                            val wasRu = listenLang.startsWith("ru", true)
                            listenLang = if (wasRu) "en-US" else "ru-RU"
                            targetLang = if (wasRu) "ru" else "en"
                        })
                        Text("✕", color = Color.White, modifier = Modifier.clickable { stopSelf() })
                    }
                }

                if (lastOriginal.isNotBlank()) Text(lastOriginal, color = Color(0xFFB0BEC5))
                if (lastTranslated.isNotBlank()) Text(lastTranslated, color = Color.White)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialogMode = false; startListening(); listening = true }) { Text("Субтитры") }
                    Button(onClick = { dialogMode = true;  startListening(); listening = true }) { Text("Диалог") }
                    OutlinedButton(onClick = {
                        if (listening) { asr.stop(); listening = false }
                        else { startListening(); listening = true }
                    }) { Text(if (listening) "Пауза" else "Старт") }
                }
            }
        }
    }

    private fun startListening() {
        if (!Settings.canDrawOverlays(this)) return
        scope.launch {
            try { translator.ensure(listenLang, targetLang) } catch (_: Exception) {}
            asr.start(listenLang)
        }
    }

    override fun onDestroy() {
        try { view?.let { wm.removeView(it) } } catch (_: Exception) {}
        asr.stop()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
