package com.family.grandmacall

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log

/**
 * Device-owner powers: pin Grandma Call as the home app and hide unneeded apps.
 * Does nothing unless the app has been made device owner.
 */
object Kiosk {
    private const val FAMILY_MODE_MS = 10 * 60_000L
    @Volatile private var familyModeUntil = 0L

    /** While the family menu is in use, the photo screen stops pulling itself back to the front. */
    fun familyMode() = SystemClock.elapsedRealtime() < familyModeUntil
    fun startFamilyMode() { familyModeUntil = SystemClock.elapsedRealtime() + FAMILY_MODE_MS }
    fun endFamilyMode() { familyModeUntil = 0L }

    private const val CALL_GRACE_MS = 30_000L
    @Volatile private var callGraceUntil = 0L

    /**
     * Starting a call briefly takes focus away from the photos (Android's call handler opens
     * first, then the call screen). The escape guards must not react to that, or they
     * cancel the call before it's placed.
     */
    fun callStarting() { callGraceUntil = SystemClock.elapsedRealtime() + CALL_GRACE_MS }
    fun callEnded() { callGraceUntil = 0L }

    /** True while the escape guards should leave the screen alone. */
    fun guardsOff() = familyMode() || SystemClock.elapsedRealtime() < callGraceUntil

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun apply(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))
        for (pkg in Config.get(context).hideApps) {
            try { dpm.setApplicationHidden(admin, pkg, true) } catch (e: Exception) { Log.w("GrandmaCall", "hide $pkg", e) }
        }
    }

    /**
     * Brings the photos to the front. A direct launch is what closes Recents; the Home intent
     * is what gets past other apps' screens. Doing both covers every case seen on the J2.
     */
    fun goHome(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Lets someone else use the phone for a while: opens the normal Samsung home screen
     * (with its dialler) and relaxes the guards for 10 minutes. After that the photos
     * come back on their own.
     */
    fun openNormalPhone(context: Context) {
        startFamilyMode()
        StatusBarBlocker.hide(context)
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage("com.sec.android.app.launcher")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Turns the phone back into a normal phone: restores hidden apps and releases the home screen. */
    fun release(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        for (pkg in Config.get(context).hideApps) {
            try { dpm.setApplicationHidden(admin, pkg, false) } catch (_: Exception) {}
        }
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
    }
}
