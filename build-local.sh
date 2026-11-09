#!/bin/bash
# Build JS Tether v3 without Gradle: aapt2 + javac + d8 (or dx) + apksigner/jarsigner.
# Mac:  ANDROID_HOME=~/Library/Android/sdk ./build-local.sh
#       (needs build-tools and platforms;android-33 from the Android SDK, and a JDK)
# Signs with release.keystore in this folder (the same key as v1/v2, so v3 installs over v2).
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
B="${BUILD_DIR:-$P/build}"
VERSION_CODE=29; VERSION_NAME=4.7.0
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
AJAR="${ANDROID_JAR:-$SDK/platforms/android-33/android.jar}"
AAPT2="${AAPT2:-$BT/aapt2}"
KS="${KEYSTORE:-$P/release.keystore}"
KS_PASS="${KS_PASS:-jstether}"
[ -x "$AAPT2" ] || { echo "aapt2 not found (set ANDROID_HOME or AAPT2). Looked in: $BT"; exit 1; }
[ -f "$AJAR" ] || { echo "android.jar not found: $AJAR (install platforms;android-33)"; exit 1; }
if [ ! -f "$KS" ]; then
  echo "release.keystore not found at $KS"
  echo "Copy it from the Mac project folder (same key as v2), or set KEYSTORE=... Without it v3 cannot install over v2."
  exit 1
fi

rm -rf "$B"; mkdir -p "$B/classes" "$B/gen" "$B/dex"
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.johorstudio.tether\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\">\n    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"29\" />#" \
  "$P/app/src/main/AndroidManifest.xml" > "$B/AndroidManifest.xml"
"$AAPT2" compile --dir "$P/app/src/main/res" -o "$B/res.zip"
"$AAPT2" link -o "$B/unsigned.apk" -I "$AJAR" --manifest "$B/AndroidManifest.xml" "$B/res.zip" --java "$B/gen"
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJAR" -d "$B/classes" \
  $(find "$P/app/src" -name '*.java') $(find "$B/gen" -name '*.java')
if [ -x "$BT/d8" ]; then
  "$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
  cp "$B/dex/classes.dex" "$B/classes.dex"
elif [ -n "${DX_CP:-}" ]; then
  java -cp "$DX_CP" com.android.dx.command.Main --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"
else
  echo "d8 not found in $BT (or set DX_CP to the old dx build)"; exit 1
fi
(cd "$B" && zip -q unsigned.apk classes.dex)
OUT="$B/JSTether-v$VERSION_NAME.apk"
if [ -x "$BT/apksigner" ]; then
  "$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$OUT" "$B/aligned.apk"
  "$BT/apksigner" verify "$OUT" && echo "signed (v1+v2)"
else
  cp "$B/unsigned.apk" "$OUT"
  jarsigner -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -sigalg SHA256withRSA -digestalg SHA-256 "$OUT" jstether >/dev/null
  jarsigner -verify "$OUT" | tail -1
fi
ls -la "$OUT"
echo "Copy to releases/:  cp '$OUT' '$P/releases/'"
