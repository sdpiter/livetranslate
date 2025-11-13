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
        const val ACTION_SHOW = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE = "io.github.sdpiter.livetranslate.accessibility.HIDE"
        const val ACTION_TOGGLE = "io.github.sdpiter.livetranslate.accessibility.TOGGLE"
    }

    // --- (Остальной код остается без изменений до функции showPanel) ---

    private fun showPanel() {
        if (panel != null) return
        // ... (предыдущий код без изменений)

        val row2 = row3().apply {
            addView(btn("Swap EN↔RU") { this@LtAccessibilityService.swapLanguages() }) // ← Исправлено!
            addView(btn("Очистить") { clearAll() })
            addView(btn("Скрыть") { hidePanel() })
        }

        // ... (остальной код без изменений)
    }

    // Новая функция для смены языков (замена swap)
    private fun swapLanguages() {
        val wasRu = listenLang.startsWith("ru", true)
        listenLang = if (wasRu) "en-US" else "ru-RU"
        targetLang = if (wasRu) "ru" else "en"
        dirView?.text = "Направление: ${if (listenLang.startsWith("ru")) "RU" else "EN"} → ${targetLang.uppercase()}"
        if (listening) {
            stopSafe { startSafe() }
        }
    }

    // --- (Остальной код остается без изменений) ---
}
