package com.family.grandmacall

import android.app.admin.DeviceAdminReceiver

/** Lets Grandma Call be the device owner (set once with `adb shell dpm set-device-owner`). */
class AdminReceiver : DeviceAdminReceiver()
