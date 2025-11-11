package io.github.sdpiter.livetranslate

import android.Manifest
import android.app.NotificationManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sdpiter.livetranslate.debug.FgTestService
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

        setContent { MaterialTheme { Landing(askNotifPermission) } }
    }
}

@Composable
fun Landing(askNotifPermission: (() -> Unit)?) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Утилиты статуса
    fun notifEnabled(): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 24) nm.areNotificationsEnabled() else true
    }
    fun isAccEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val svc = "${ctx.packageName}/io.github.sdpiter.livetranslate.accessibility.LtAccessibilityService"
        return enabled?.split(':')?.contains(svc) == true
    }

    // Микрофон
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    // Overlay / Уведомления / Спец. возможности
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var notificationsGranted by remember { mutableStateOf(notifEnabled()) }
    var accEnabled by remember { mutableStateOf(isAccEnabled()) }

    // Обновляем статусы при возврате из настроек
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(ctx)
                notificationsGranted = notifEnabled()
                accEnabled = isAccEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("LiveTranslate α", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Статус: Микрофон=$micGranted • Overlay=$overlayGranted • Уведомл.=$notificationsGranted • Доступность=$accEnabled",
                style = MaterialTheme.typography.bodySmall
            )

            // Разрешение микрофона
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(if (micGranted) "Микрофон: разрешён" else "Разрешить микрофон")
            }

            // Настройки overlay
            Button(onClick = {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                ctx.startActivity(i)
            }) { Text("Открыть настройки «поверх окон»") }

            // Настройки уведомлений
            Button(onClick = {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                ctx.startActivity(i)
            }) { Text("Открыть настройки уведомлений") }

            // Специальные возможности (Accessibility overlay)
            Button(onClick = {
                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                ctx.startActivity(i)
            }) { Text(if (accEnabled) "Спец. возможности: включено" else "Открыть Спец. возможности") }

            // Управление текущим OverlayService (может быть неактивен на One UI — используйте Accessibility)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = micGranted,
                    onClick = {
                        askNotifPermission?.invoke()
                        val i = Intent(ctx, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_START)
                        ContextCompat.startForegroundService(ctx, i)
                    }
                ) { Text("Запустить оверлей") }

                OutlinedButton(onClick = {
                    ctx.stopService(Intent(ctx, OverlayService::class.java))
                }) { Text("Остановить") }
            }

            // Тестовый FGS (уведомление на 20 секунд)
            OutlinedButton(onClick = {
                askNotifPermission?.invoke()
                val i = Intent(ctx, FgTestService::class.java)
                ContextCompat.startForegroundService(ctx, i)
            }) { Text("Тест уведомления (FGS)") }

            Spacer(Modifier.height(12.dp))
            Text(
                "Подсказки:\n— Включите уведомления для LiveTranslate (Android 13+)\n" +
                        "— Разрешите «Появление поверх» и включите службу в «Спец. возможности»\n" +
                        "— На Samsung отключите агрессивную экономию энергии для приложения",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
