#!/bin/bash
# One-time setup of a freshly factory-reset phone (no accounts, USB debugging on):
# installs Grandma Call, makes it device owner (kiosk lock), applies settings, reboots.
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=com.family.grandmacall

adb get-state >/dev/null || { echo "No phone found. Plug it in and allow USB debugging."; exit 1; }

accounts=$(adb shell dumpsys account | tr -d '\r' | sed -n 's/.*Accounts: \([0-9]*\).*/\1/p' | head -1)
if [ "${accounts:-0}" != "0" ]; then
  echo "The phone has $accounts account(s) signed in. Device owner needs a phone with none."
  echo "Factory reset it and skip the Google/Samsung sign-in during setup."
  exit 1
fi

[ -f keystore/release.keystore ] || ./scripts/make-keystore.sh
[ -f config.json ] || echo "Note: no config.json yet, so the example contacts are used (dry run, no real calls)."

./scripts/install.sh
adb shell dpm set-device-owner $PKG/.AdminReceiver
echo "Rebooting. The phone should come up on the photo screen."
adb reboot
