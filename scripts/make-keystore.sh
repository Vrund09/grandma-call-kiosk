#!/bin/bash
# Create the signing key for your phone, once. Keep keystore/ backed up (it is git-ignored):
# a device-owner app can only be updated with the key it was first installed with.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f keystore/release.keystore ]; then echo "keystore/release.keystore already exists."; exit 0; fi
mkdir -p keystore
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
keytool -genkeypair -keystore keystore/release.keystore -alias grandmacall \
  -storepass grandmacall -keypass grandmacall -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=Grandma Call"
echo "Created keystore/release.keystore. Back it up somewhere safe."
