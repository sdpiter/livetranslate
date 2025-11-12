package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class LtAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW = "io.github.sdpiter.livetranslate.accessibility.SHOW"
        const val ACTION_HIDE = "io.github.sdpiter.livetranslate.accessibility.HIDE"
    }

    private lateinit var wm: WindowManager
    private var bar: View? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW -> showBar()
                ACTION_HIDE -> removeBar()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Регистрируем управление из приложения
        val f = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        registerReceiver(controlReceiver, f)
        showBar()
        Toast.makeText(this, "LiveTranslate overlay (Accessibility) включён", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun params(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // TYPE_ACCESSIBILITY_OVERLAY — не требует FGS
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
    }

    private fun showBar() {
        if (bar != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC673AB7.toInt()) // фиолетовый
            setPadding(dp(12), dp(10), dp(12), dp(10))

            val title = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                text = "LiveTranslate — Accessibility overlay активен"
            }
            val close = TextView(this@LtAccessibilityService).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                text = "   ✕"
                setOnClickListener { removeBar() }
            }
            addView(title)
            addView(close)
        }
        try {
            wm.addView(row, params())
            bar = row
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeBar() {
        try { bar?.let { wm.removeView(it) } } catch (_: Exception) {}
        bar = null
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        removeBar()
        super.onDestroy()
    }
}
