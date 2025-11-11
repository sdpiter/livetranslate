package io.github.sdpiter.livetranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sdpiter.livetranslate.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private var askNotifPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        askNotifPermission = {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                Landing(askNotifPermission = askNotifPermission)
            }
        }
    }
}

@Composable
fun Landing(askNotifPermission: (() -> Unit)?) {
    val ctx = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("LiveTranslate α", style = MaterialTheme.typography.headlineSmall)
            Text("1) Разрешите микрофон и overlay\n2) «Запустить оверлей»\n3) Выберите режим", style = MaterialTheme.typography.bodySmall)

            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(if (micGranted) "Микрофон: разрешён" else "Разрешить микрофон")
            }

            Button(onClick = {
                if (!Settings.canDrawOverlays(ctx)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    ctx.startActivity(intent)
                } else overlayGranted = true
            }) { Text(if (overlayGranted) "Overlay: разрешён" else "Разрешить «поверх окон»") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = micGranted && Settings.canDrawOverlays(ctx),
                    onClick = {
                        askNotifPermission?.invoke()
                        val i = Intent(ctx, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_START
                        }
                        // Важно: foreground-start
                        androidx.core.content.ContextCompat.startForegroundService(ctx, i)
                    }
                ) { Text("Запустить оверлей") }

                OutlinedButton(onClick = {
                    val i = Intent(ctx, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_STOP
                    }
                    ctx.startService(i)
                }) { Text("Остановить") }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Советы:\n— Скачайте офлайн‑голоса TTS (EN/RU)\n— На Xiaomi включите Плавающие окна, Автозапуск и «Без ограничений»",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    LaunchedEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(ctx)
    }
}
