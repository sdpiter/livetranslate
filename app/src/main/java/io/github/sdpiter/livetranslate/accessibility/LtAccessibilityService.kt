package io.github.sdpiter.livetranslate.accessibility

import android.accessibilityservice.AccessibilityService
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

    private lateinit var wm: WindowManager
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        Toast.makeText(this, "Accessibility overlay включён", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun params(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Тип окна службы доступности
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCCFF1744.toInt()) // алый фон
            val title = TextView(this@LtAccessibilityService).apply {
                text = "LiveTranslate Overlay (Accessibility)"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(24, 18, 24, 18)
            }
            val close = TextView(this@LtAccessibilityService).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(24, 18, 24, 18)
                setOnClickListener { removeOverlay() }
            }
            addView(title)
            addView(close)
        }
        try {
            wm.addView(bar, params())
            overlay = bar
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка overlay: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeOverlay() {
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) { }
        overlay = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
