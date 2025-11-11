package io.github.sdpiter.livetranslate

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import io.github.sdpiter.livetranslate.overlay.OverlayService

@OptIn(ExperimentalPermissionsApi::class)
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun Landing(askNotifPermission: (() -> Unit)?) {
    val ctx = LocalContext.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("LiveTranslate α", style = MaterialTheme.typography.headlineSmall)
            Text(
                "1) Разрешите микрофон и overlay\n2) Нажмите «Запустить оверлей»\n3) Выберите режим",
                style = MaterialTheme.typography.bodySmall
            )

            Button(onClick = { micPermission.launchPermissionRequest() }) {
                Text(if (micPermission.status.isGranted) "Микрофон: разрешён" else "Разрешить микрофон")
            }

            Button(onClick = {
                if (!Settings.canDrawOverlays(ctx)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    ctx.startActivity(intent)
                } else {
                    overlayGranted = true
                }
            }) {
                Text(if (overlayGranted) "Overlay: разрешён" else "Разрешить «поверх окон»")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = micPermission.status.isGranted && Settings.canDrawOverlays(ctx),
                    onClick = {
                        askNotifPermission?.invoke()
                        val i = Intent(ctx, OverlayService::class.java)
                        i.action = OverlayService.ACTION_START
                        ctx.startService(i)
                    }
                ) { Text("Запустить оверлей") }

                OutlinedButton(onClick = {
                    val i = Intent(ctx, OverlayService::class.java)
                    i.action = OverlayService.ACTION_STOP
                    ctx.startService(i)
                }) { Text("Остановить") }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Советы:\n— Скачайте офлайн‑голоса TTS (EN/RU) в настройках телефона\n— На Xiaomi включите Плавающие окна, Автозапуск и «Без ограничений» для батареи",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // Обновляем флаг после возвращения из настроек оверлея
    LaunchedEffect(Unit) {
        // Небольшой триггер для перерисовки после возвращения в активность
        overlayGranted = Settings.canDrawOverlays(ctx)
    }
}
