#!/bin/bash
# Build Grandma Call, install it on the phone connected by USB, and apply the phone settings.
set -euo pipefail
cd "$(dirname "$0")/.."

# On macOS, pick JDK 17 automatically; elsewhere set JAVA_HOME yourself.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
PKG=com.family.grandmacall

./gradlew -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Always-on kiosk settings.
adb shell settings put global stay_on_while_plugged_in 7
adb shell settings put system screen_off_timeout 1800000
adb shell settings put secure screensaver_enabled 0
adb shell settings put secure screensaver_activate_on_dock 0
adb shell settings put secure screensaver_activate_on_sleep 0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
# Pop-up (heads-up) notifications such as SMS quick-reply cover the photos and swallow taps.
adb shell settings put global heads_up_notifications_enabled 0

adb shell am start -n $PKG/.MainActivity >/dev/null
echo "Installed."
echo "First time on this phone? Use scripts/setup-new-phone.sh instead (it also sets up the kiosk lock)."
