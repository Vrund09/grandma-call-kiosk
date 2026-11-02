package com.family.grandmacall

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The home screen: one big photo tile per family member. */
class MainActivity : Activity() {

    companion object {
        private const val TAP_LOCKOUT_MS = 5_000L
        private const val BACK_PRESSES_TO_EXIT = 5
        private const val BACK_PRESS_WINDOW_MS = 3_000L
        private const val ADMIN_HOLD_MS = 5_000L
        private const val ADMIN_CORNER_DP = 80
        private const val DIM_AFTER_MS = 60_000L
        private const val BRIGHT = 180
        private const val DIM = 8
        private const val SHIFT_EVERY_MS = 180_000L
        private const val SHIFT_DP = 3

        /** True while the photo screen is in front; used to spot a call screen stuck behind it. */
        @Volatile var visible = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var config: Config
    private val tiles = mutableListOf<View>()
    private var lastTap = 0L
    private lateinit var root: View

    private val dim = Runnable { setBrightness(DIM) }

    /** The J2 has an AMOLED screen; nudging the picture a few pixels now and then prevents burn-in. */
    private val shift = object : Runnable {
        override fun run() {
            val max = dp(SHIFT_DP)
            root.translationX = (-max..max).random().toFloat()
            root.translationY = (-max..max).random().toFloat()
            handler.postDelayed(this, SHIFT_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        config = Config.get(this)
        Kiosk.apply(this)
        startService(Intent(this, KeepOnService::class.java))
        root = buildLayout()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        KeepOnService.onPowerChanged = { applyPowerMode() }
        applyPowerMode()
        hideSystemBars()
        if (config.blockStatusBar) StatusBarBlocker.show(this)
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_RING, audio.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
        tiles.forEach { setHighlighted(it, false) }
        wake()
        handler.removeCallbacks(shift)
        handler.postDelayed(shift, SHIFT_EVERY_MS)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(dim)
        handler.removeCallbacks(shift)
        super.onPause()
    }

    /** Full brightness now, dim again after a minute without touches. */
    private fun wake() {
        setBrightness(BRIGHT)
        handler.removeCallbacks(dim)
        handler.postDelayed(dim, DIM_AFTER_MS)
    }

    /** Samsung ignores per-window brightness, so set the system brightness (0–255) instead. */
    private fun setBrightness(level: Int) {
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        } else if (!Kiosk.guardsOff()) {
            // Something popped up over the photos while we're still on screen: almost always
            // the long-press power menu (with Airplane mode). Close it at once.
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    /** Recents or any other screen covered the photos: come back unless a call is on. */
    private val bounceBack = object : Runnable {
        override fun run() {
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (!Kiosk.guardsOff() && telephony.callState == TelephonyManager.CALL_STATE_IDLE) {
                Kiosk.goHome(this@MainActivity)
            }
            // Android delays app launches for a few seconds after Home is pressed, so keep
            // trying until we're back on screen (onStart cancels this).
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.postDelayed(bounceBack, 1_000)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(bounceBack)
    }

    /** Volume keys do nothing here; ring and call volume stay at maximum. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> true
        else -> super.onKeyDown(keyCode, event)
    }

    /** On charger power: screen always on. On battery (power cut): let it sleep to save battery. */
    private fun applyPowerMode() {
        if (KeepOnService.onBattery) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val backPresses = ArrayDeque<Long>()

    /**
     * A single Back does nothing. Back pressed 5 times within 3 seconds opens the normal
     * phone for 10 minutes, so a visitor can use it (for example for an emergency call).
     */
    @Deprecated("Back is handled here")
    override fun onBackPressed() {
        val now = SystemClock.elapsedRealtime()
        backPresses.addLast(now)
        while (backPresses.isNotEmpty() && now - backPresses.first() > BACK_PRESS_WINDOW_MS) backPresses.removeFirst()
        if (backPresses.size >= BACK_PRESSES_TO_EXIT) {
            backPresses.clear()
            Kiosk.openNormalPhone(this)
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // --- layout ---------------------------------------------------------------------------

    /** Up to 3 people: one per row. 4 or more: a two-column grid, so each photo stays tall. */
    private fun buildLayout(): View {
        val columns = if (config.contacts.size >= 4) 2 else 1
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        for (rowContacts in config.contacts.chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (contact in rowContacts) {
                row.addView(tapArea(contact), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            // Keep a lone last tile the same size as the others.
            repeat(columns - rowContacts.size) { row.addView(View(this), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return grid
    }

    private fun contactTile(contact: Contact): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.DKGRAY)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val photo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadPhoto(contact.photo)?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.GRAY)
        }
        tile.addView(photo, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        tile.addView(TextView(this).apply {
            text = contact.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        tiles += tile
        return tile
    }

    /**
     * One tap always calls. The touch area includes the black gap around the photo, and the
     * call goes to the photo where the finger came down, however much it slides or how long
     * it's held. Extra fingers are ignored. After a call starts, touches do nothing for a few
     * seconds and that photo stays highlighted, so a shaky second tap can't place two calls.
     */
    private fun tapArea(contact: Contact): View {
        val tile = contactTile(contact)
        val area = FrameLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(tile, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        area.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (canTap()) setHighlighted(tile, true)
                MotionEvent.ACTION_UP -> if (canTap()) {
                    lastTap = SystemClock.elapsedRealtime()
                    setHighlighted(tile, true)
                    CallService.call(this, contact.number)
                }
                // Only the hidden family-menu gesture cancels a touch.
                MotionEvent.ACTION_CANCEL -> if (canTap()) setHighlighted(tile, false)
            }
            true
        }
        return area
    }

    private fun canTap() = SystemClock.elapsedRealtime() - lastTap > TAP_LOCKOUT_MS

    private fun setHighlighted(v: View, on: Boolean) {
        v.animate().scaleX(if (on) 0.95f else 1f).scaleY(if (on) 0.95f else 1f).setDuration(120).start()
        (v.background as? GradientDrawable)?.setStroke(if (on) dp(8) else dp(2), if (on) Color.YELLOW else Color.WHITE)
    }

    // --- hidden family exit: hold the top-left corner for 5 seconds -----------------------

    private var cornerDown = false
    private var swallowGesture = false
    private val openAdmin = Runnable {
        swallowGesture = true
        startActivity(Intent(this, AdminActivity::class.java))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) wake()
        val corner = dp(ADMIN_CORNER_DP)
        // The status-bar blocker eats touches at the very top, so the hotspot starts just below it.
        val inCorner = ev.x < corner && ev.y < corner + dp(StatusBarBlocker.HEIGHT_DP)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowGesture = false
                cornerDown = inCorner
                if (inCorner) handler.postDelayed(openAdmin, ADMIN_HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (cornerDown && !inCorner) {
                cornerDown = false; handler.removeCallbacks(openAdmin)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cornerDown = false; handler.removeCallbacks(openAdmin)
            }
        }
        if (swallowGesture) {
            // Make sure the tile under the corner doesn't also place a call.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // --- helpers --------------------------------------------------------------------------

    private fun loadPhoto(path: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (_: Exception) {
        null
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), Color.WHITE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
