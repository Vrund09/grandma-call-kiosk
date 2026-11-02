package com.family.grandmacall

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Keeps the screen on for good while the charger has power: if someone presses the power
 * button, the screen comes straight back on with the photos. Leaves the screen alone during
 * a call, when the proximity sensor may switch it off.
 *
 * Outage mode: when the charger stops supplying power, the screen is allowed to sleep after
 * 2 minutes so the battery lasts days instead of hours. Calls still ring, and Home shows the
 * photos. The family gets SMS alerts for the power cut, a low battery and power coming back.
 */
class KeepOnService : Service() {

    companion object {
        private const val TAG = "GrandmaCall"
        private const val ON_BATTERY_SCREEN_TIMEOUT_MS = 120_000
        private const val PLUGGED_SCREEN_TIMEOUT_MS = 1_800_000
        private const val LOW_BATTERY_PERCENT = 15

        /** True while the charger has no power. Read by MainActivity to let the screen sleep. */
        @Volatile var onBattery = false
            private set
        var onPowerChanged: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var power: PowerManager
    private lateinit var telephony: TelephonyManager
    private lateinit var config: Config
    private val prefs by lazy { getSharedPreferences("power", MODE_PRIVATE) }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.postDelayed({ wakeUp() }, 800)
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            onBatteryState(plugged, if (level >= 0) level * 100 / scale else -1)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(POWER_SERVICE) as PowerManager
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        config = Config.get(this)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // Sticky: delivers the current state straight away, then every change.
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        @Suppress("DEPRECATION")
        val notification = Notification.Builder(this)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Grandma Call")
            .setPriority(Notification.PRIORITY_MIN)
            .build()
        startForeground(2, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        unregisterReceiver(battery)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun wakeUp() {
        if (onBattery || telephony.callState != TelephonyManager.CALL_STATE_IDLE || power.isScreenOn) return
        val lock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "GrandmaCall:wake",
        )
        lock.acquire(3_000)
        Kiosk.goHome(this)
    }

    // --- outage mode ------------------------------------------------------------------------

    private var lastLevel = -1

    private fun onBatteryState(plugged: Boolean, level: Int) {
        lastLevel = level
        if (!plugged && !onBattery) {
            Log.i(TAG, "power lost, battery $level%")
            onBattery = true
            setScreenTimeout(ON_BATTERY_SCREEN_TIMEOUT_MS)
            // Short flickers are common; only text the family if the cut lasts.
            handler.postDelayed(powerCutAlert, config.powerCutAlertSeconds * 1000L)
            onPowerChanged?.invoke()
        } else if (plugged && onBattery) {
            Log.i(TAG, "power back, battery $level%")
            onBattery = false
            handler.removeCallbacks(powerCutAlert)
            setScreenTimeout(PLUGGED_SCREEN_TIMEOUT_MS)
            if (prefs.getBoolean("cutAlertSent", false)) sms("Grandma Call: power is back. Battery $level%.")
            prefs.edit().putBoolean("cutAlertSent", false).putBoolean("lowAlertSent", false).apply()
            onPowerChanged?.invoke()
            wakeUp()
        }
        // The low-battery text only follows a power-cut text, never comes before it.
        if (onBattery && level in 0..LOW_BATTERY_PERCENT && prefs.getBoolean("cutAlertSent", false) &&
            !prefs.getBoolean("lowAlertSent", false)
        ) {
            prefs.edit().putBoolean("lowAlertSent", true).apply()
            sms("Grandma Call: battery low ($level%) and no power. The phone may switch off soon.")
        }
    }

    private val powerCutAlert = Runnable {
        if (!onBattery) return@Runnable
        val low = lastLevel in 0..LOW_BATTERY_PERCENT
        prefs.edit().putBoolean("cutAlertSent", true).putBoolean("lowAlertSent", low).apply()
        sms(
            "Grandma Call: power cut. Running on battery ($lastLevel%)" +
                (if (low) " - battery LOW, the phone may switch off soon." else ". Screen will sleep to save battery; calls still work.")
        )
    }

    private fun setScreenTimeout(ms: Int) {
        try { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) } catch (_: Exception) {}
    }

    private fun sms(text: String) {
        val to = config.alertNumber ?: return
        Log.i(TAG, "alert SMS to $to: $text")
        if (config.dryRun) return
        try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(to, null, text, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
        }
    }
}
