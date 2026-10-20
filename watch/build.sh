#!/usr/bin/env bash
# Builds WristMic.apk with the plain Android SDK tools (no Gradle needed).
#   ANDROID_JAR   path to platforms/android-35/android.jar
#   BUILD_TOOLS   path to build-tools/35.x (aapt2, d8, zipalign, apksigner)
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-35/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/35* 2>/dev/null | sort -V | tail -1)}"
KEYSTORE="${KEYSTORE:-wristmic.keystore}"
KS_PASS="${KS_PASS:-wristmic}"

rm -rf build
mkdir -p build/res build/gen build/classes build/dex

[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -alias wristmic -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=WristMic" -storepass "$KS_PASS" -keypass "$KS_PASS" >/dev/null 2>&1

"$BUILD_TOOLS/aapt2" compile --dir res -o build/res/
"$BUILD_TOOLS/aapt2" link -o build/unsigned.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --java build/gen build/res/*.flat

javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" -Xlint:-options -Xlint:deprecation -encoding UTF-8 \
    -d build/classes $(find src build/gen -name '*.java')

"$BUILD_TOOLS/d8" --release --min-api 30 --lib "$ANDROID_JAR" --output build/dex \
    $(find build/classes -name '*.class')

cp build/unsigned.apk build/with-dex.apk
(cd build/dex && zip -q -j ../with-dex.apk classes.dex)
"$BUILD_TOOLS/zipalign" -p -f 4 build/with-dex.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out WristMic.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify WristMic.apk
echo "Built $(pwd)/WristMic.apk ($(stat -c %s WristMic.apk) bytes)"
