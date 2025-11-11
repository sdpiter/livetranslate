package io.github.sdpiter.livetranslate.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.sdpiter.livetranslate.R
import kotlinx.coroutines.*

class FgTestService : Service() {

    companion object {
        private const val CH = "lt_test"
        private const val ID = 9911
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CH, "LT Test", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val note: Notification = NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("LiveTranslate • Test")
                .setContentText("FGS запущен")
                .setOngoing(true)
                .build()

            startForeground(ID, note)
            Toast.makeText(this, "FGS: стартовал (должно быть уведомление)", Toast.LENGTH_SHORT).show()

            scope.launch {
                delay(20_000) // держим 20 сек
                stopSelf()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "FGS: ошибка ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
