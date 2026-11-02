package com.family.grandmacall

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Places a call, keeps the loudspeaker on while it lasts, and brings the photo
 * screen back when it ends.
 */
@Suppress("DEPRECATION")
class CallService : Service() {

    companion object {
        private const val TAG = "GrandmaCall"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_START_WITH_SPEAKER = "android.telecom.extra.START_CALL_WITH_SPEAKERPHONE"

        fun call(context: Context, number: String) {
            context.startService(Intent(context, CallService::class.java).putExtra(EXTRA_NUMBER, number))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephony: TelephonyManager
    private lateinit var audio: AudioManager
    private lateinit var config: Config
    private var inCall = false
    private var wentOffhook = false

    private val listener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> onOffhook()
                // Android reports the current state (IDLE) as soon as we start listening, which
                // arrives after dialling has begun. Only an IDLE after OFFHOOK is a real hang-up.
                TelephonyManager.CALL_STATE_IDLE -> if (inCall && wentOffhook) onCallEnded()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        config = Config.get(this)
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        val notification = Notification.Builder(this)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Grandma Call")
            .setPriority(Notification.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER)
        if (number != null && config.dryRun) {
            Log.i(TAG, "would call $number (dry run)")
            stopSelf()
        } else if (number != null && !inCall) {
            dial(number)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dial(number: String) {
        Log.i(TAG, "dialling $number")
        inCall = true
        wentOffhook = false
        Kiosk.callStarting()
        for (stream in intArrayOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_RING)) {
            audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0)
        }
        try {
            startActivity(
                Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Asks Android's own call system to start on speaker. More reliable than
                    // switching it from outside, which Samsung's call screen can override.
                    .putExtra(EXTRA_START_WITH_SPEAKER, config.speakerphone)
            )
        } catch (e: Exception) {
            Log.e(TAG, "call failed", e)
        }
        // If the phone never goes off-hook (no SIM, no signal), don't wait forever.
        handler.postDelayed(dialWatchdog, 15_000)
    }

    private val dialWatchdog = Runnable {
        if (inCall && telephony.callState == TelephonyManager.CALL_STATE_IDLE) onCallEnded()
    }

    private fun onOffhook() {
        if (!inCall) return
        wentOffhook = true
        handler.removeCallbacks(dialWatchdog)
        if (config.speakerphone) handler.post(speakerKeeper)
        // Sometimes the call screen opens behind the photos and only a small banner shows,
        // leaving no hang-up button. Pull the call screen to the front if that happens.
        handler.postDelayed(showCallScreen, 1500)
        handler.postDelayed(showCallScreen, 4000)
    }

    private val showCallScreen = Runnable {
        if (inCall && MainActivity.visible) {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).showInCallScreen(false)
        }
    }

    /** Samsung's call screen can switch the speaker back off, so keep re-asserting it. */
    private val speakerKeeper = object : Runnable {
        override fun run() {
            if (!inCall) return
            // Set it every time: isSpeakerphoneOn can report true while the call is on the earpiece.
            audio.isSpeakerphoneOn = true
            handler.postDelayed(this, 1500)
        }
    }

    private fun onCallEnded() {
        inCall = false
        Kiosk.callEnded()
        handler.removeCallbacksAndMessages(null)
        audio.isSpeakerphoneOn = false
        Kiosk.goHome(this)
        stopSelf()
    }
}
