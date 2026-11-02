package com.family.grandmacall

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Android 5.1 has no API to stop the notification shade being pulled down without
 * device-owner mode. This places an invisible touch-eating strip over the status bar.
 */
object StatusBarBlocker {
    const val HEIGHT_DP = 25

    private var view: View? = null

    fun show(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val height = (HEIGHT_DP * app.resources.displayMetrics.density).toInt()
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP }
        val v = View(app)
        try {
            wm.addView(v, params)
            view = v
        } catch (_: Exception) {
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        view = null
    }
}
