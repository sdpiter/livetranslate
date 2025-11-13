package io.github.sdpiter.livetranslate.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object SettingsStorage {
    const val ACTION_CHANGED = "io.github.sdpiter.livetranslate.SETTINGS_CHANGED"

    private const val PREFS = "lt_settings"
    private const val K_AUTO_DETECT = "auto_detect"
    private const val K_PRELOAD_ON_START = "preload_on_start"
    private const val K_FONT_SCALE = "font_scale"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun autoDetect() = prefs.getBoolean(K_AUTO_DETECT, true)
    fun preloadOnStart() = prefs.getBoolean(K_PRELOAD_ON_START, true)
    fun fontScale() = prefs.getFloat(K_FONT_SCALE, 1.0f).coerceIn(0.8f, 1.4f)

    fun setAutoDetect(ctx: Context, v: Boolean) {
        prefs.edit().putBoolean(K_AUTO_DETECT, v).apply()
        notifyChanged(ctx)
    }

    fun setPreloadOnStart(ctx: Context, v: Boolean) {
        prefs.edit().putBoolean(K_PRELOAD_ON_START, v).apply()
        notifyChanged(ctx)
    }

    fun setFontScale(ctx: Context, v: Float) {
        prefs.edit().putFloat(K_FONT_SCALE, v.coerceIn(0.8f, 1.4f)).apply()
        notifyChanged(ctx)
    }

    private fun notifyChanged(ctx: Context) {
        val i = Intent(ACTION_CHANGED).setPackage(ctx.packageName)
        ctx.sendBroadcast(i)
    }
}
